package enk.danwills.android.enkishadereditor.io;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.List;

// Encodes a sequence of already-saved frame images into a video (an
// H.264 or HEVC MP4, or a VP9 or VP8 WebM - see VideoFormat), entirely
// on-device via Android's own MediaCodec (hardware-
// accelerated where the device offers it) + MediaMuxer - no FFmpeg or
// other native/third-party libraries. Frames are read back one at a
// time from their MediaStore URIs (not held all in memory at once,
// which a long high-res sequence easily couldn't afford) and written
// straight through to the output file via a MediaStore-backed file
// descriptor, so the assembled video is never fully resident in memory
// either. Runs synchronously (blocking, can take several seconds to
// minutes depending on frame count/resolution) - callers must invoke
// this off the UI thread.
//
// Frames are pushed to the encoder by rendering each one as a textured
// quad into a small, throwaway GL context whose surface *is* the
// encoder's own input surface - not by drawing into that surface with
// Surface.lockCanvas()/unlockCanvasAndPost() (a plain software Canvas),
// which looked like the simpler option but turned out not to reliably
// feed actual frame data into at least one real device's hardware
// encoder (c2.qti.avc.encoder): the muxer ended up with a track that
// was never actually started, meaning the encoder never saw real
// pixels at all. GL rendering onto the input surface is the standard,
// well-supported path (same technique used by Android's own CTS/Grafika
// sample code for bitmap/image-sequence-to-video encoding). This also
// lets each frame's presentation timestamp be set explicitly via
// eglPresentationTimeANDROID, so the output plays back at the right
// speed regardless of how long encoding itself takes - no artificial
// pacing delay needed between frames.
public final class VideoEncoder {
	private static final String TAG = "VideoEncoder";
	private static final String ALBUM = "Enki Shader Editor";

	private static final String VERTEX_SHADER = """
			attribute vec4 position;
			attribute vec2 texCoord;
			varying vec2 vTexCoord;
			void main() {
				gl_Position = position;
				vTexCoord = texCoord;
			}
			""";
	private static final String FRAGMENT_SHADER = """
			precision mediump float;
			varying vec2 vTexCoord;
			uniform sampler2D tex;
			void main() {
				gl_FragColor = texture2D(tex, vTexCoord);
			}
			""";
	// Two triangles as a strip: top-left, bottom-left, top-right,
	// bottom-right. v=0 at the top (not the GL-conventional bottom) so
	// a Bitmap's row 0 (its own top row) lands at the top of the frame
	// without needing a separate vertical flip step.
	private static final float[] QUAD_VERTICES = {
			-1f, 1f, 0f, 0f,
			-1f, -1f, 0f, 1f,
			1f, 1f, 1f, 0f,
			1f, -1f, 1f, 1f,
	};

	private VideoEncoder() {
	}

	public interface ProgressListener {
		// Return false to abort encoding after this frame - the output
		// file is discarded (same as any other encode failure, see the
		// finally block below) rather than left as a partial video.
		boolean onFrameEncoded(int frameIndex, int totalFrames);
	}

	public static boolean isSupported() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
	}

	// frameUris: already-saved frame images, in playback order. quality:
	// 0 (smallest file, roughest) to 100 (largest file, best) - linearly
	// scales the bitrate between a low and high bound, independent of
	// fps (see estimateBitrate()'s own doc for why). videoScale: an
	// integer multiplier (1 = no change) applied to each frame's own
	// width/height to get the *video's* actual encoded resolution - for
	// deliberately-tiny captures (e.g. a pixel-art-style shader rendered
	// at 64x64) where the raw frame size would be awkward to view/share
	// as a video. Every frame is always drawn with GL_NEAREST sampling
	// (not GL_LINEAR) specifically so this upscale stays crisp/blocky
	// ("pixelly") rather than smoothing the source pixels away - at
	// videoScale 1 this has no visible effect either way, since there's
	// no sub-pixel stretch for either filter mode to interpolate across.
	// filename: the desired output filename (including the format's
	// extension). Returns
	// the saved video's content:// URI, or null on any failure (missing
	// encoder support, an I/O error, etc) - callers should treat that as
	// "the image sequence is still fine, just the video step failed",
	// not something that should discard the frames already saved.
	@Nullable
	@RequiresApi(Build.VERSION_CODES.Q)
	public static Uri encode(@NonNull Context context, @NonNull List<Uri> frameUris,
			int width, int height, int videoScale, float fps, int quality,
			@NonNull VideoFormat videoFormat, @NonNull String filename,
			@Nullable ProgressListener progressListener) {
		// Null for GIF, which is GifEncoder's.
		String codecMimeType = videoFormat.codecMimeType;
		if (frameUris.isEmpty() || codecMimeType == null) {
			return null;
		}
		ContentResolver resolver = context.getContentResolver();
		Uri outputUri = insertPendingVideo(resolver, filename, videoFormat.mimeType);
		if (outputUri == null) {
			return null;
		}

		int videoWidth = width * videoScale;
		int videoHeight = height * videoScale;
		MediaCodec codec = null;
		MediaMuxer muxer = null;
		Surface inputSurface = null;
		EglSession egl = null;
		ParcelFileDescriptor outputPfd = null;
		int program = 0;
		int texture = 0;
		boolean succeeded = false;
		try {
			MediaFormat format = MediaFormat.createVideoFormat(
					codecMimeType, videoWidth, videoHeight);
			format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
					MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			format.setInteger(MediaFormat.KEY_BIT_RATE,
					estimateBitrate(videoWidth, videoHeight, fps, quality, videoFormat.bitrateScale));
			// This is only an encoder hint (bitrate/I-frame pacing) - it's
			// fine that it's rounded (and clamped to at least 1 for a
			// sub-1-fps sequence). The video's *actual* played-back speed
			// comes entirely from each frame's own presentation timestamp
			// below, computed from the real (unrounded) fps - so this
			// works correctly, low-fps sequences included, regardless of
			// what gets declared here.
			format.setInteger(MediaFormat.KEY_FRAME_RATE, Math.max(1, Math.round(fps)));
			format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

			// An encoder that takes this size too - see encoderFor().
			String codecName = videoFormat.encoderFor(videoWidth, videoHeight);
			if (codecName == null) {
				Log.e(TAG, "encode: no " + codecMimeType + " encoder for " +
						videoWidth + "x" + videoHeight);
				return null;
			}
			codec = MediaCodec.createByCodecName(codecName);
			codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			inputSurface = codec.createInputSurface();
			codec.start();

			egl = EglSession.create(inputSurface);
			if (egl == null) {
				return null;
			}
			program = compileProgram();
			if (program == 0) {
				return null;
			}
			int positionAttrib = GLES20.glGetAttribLocation(program, "position");
			int texCoordAttrib = GLES20.glGetAttribLocation(program, "texCoord");
			FloatBuffer vertexBuffer = ByteBuffer
					.allocateDirect(QUAD_VERTICES.length * 4)
					.order(ByteOrder.nativeOrder())
					.asFloatBuffer()
					.put(QUAD_VERTICES);
			vertexBuffer.position(0);

			outputPfd = resolver.openFileDescriptor(outputUri, "rw");
			if (outputPfd == null) {
				return null;
			}
			muxer = new MediaMuxer(outputPfd.getFileDescriptor(), videoFormat.muxerFormat);

			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
			long frameDurationNanos = Math.round(1_000_000_000.0 / fps);
			int[] trackIndex = {-1};
			boolean[] muxerStarted = {false};
			int[] textureHolder = new int[1];

			for (int i = 0; i < frameUris.size(); ++i) {
				Bitmap bitmap = decodeUri(resolver, frameUris.get(i));
				if (bitmap == null) {
					return null;
				}
				GLES20.glGenTextures(1, textureHolder, 0);
				texture = textureHolder[0];
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
				GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
						GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
				GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
						GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
				GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
						GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
				GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,
						GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
				GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
				bitmap.recycle();

				GLES20.glViewport(0, 0, videoWidth, videoHeight);
				GLES20.glClearColor(0f, 0f, 0f, 1f);
				GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
				GLES20.glUseProgram(program);
				vertexBuffer.position(0);
				GLES20.glEnableVertexAttribArray(positionAttrib);
				GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
				vertexBuffer.position(2);
				GLES20.glEnableVertexAttribArray(texCoordAttrib);
				GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer);
				GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
				GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
				GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "tex"), 0);
				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

				EGLExt.eglPresentationTimeANDROID(
						egl.display, egl.surface, i * frameDurationNanos);
				EGL14.eglSwapBuffers(egl.display, egl.surface);

				GLES20.glDeleteTextures(1, textureHolder, 0);
				texture = 0;

				drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted, false);
				if (progressListener != null && !progressListener.onFrameEncoded(i + 1, frameUris.size())) {
					return null;
				}
			}

			codec.signalEndOfInputStream();
			drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted, true);
			if (!muxerStarted[0]) {
				return null;
			}
			muxer.stop();
			succeeded = true;
			return outputUri;
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "encode: failed", e);
			return null;
		} finally {
			if (texture != 0) {
				GLES20.glDeleteTextures(1, new int[]{texture}, 0);
			}
			if (program != 0) {
				GLES20.glDeleteProgram(program);
			}
			if (egl != null) {
				egl.destroy();
			}
			if (inputSurface != null) {
				inputSurface.release();
			}
			if (codec != null) {
				try {
					codec.stop();
				} catch (RuntimeException ignored) {
					// Already stopped/never started cleanly - nothing more to do.
				}
				codec.release();
			}
			if (muxer != null) {
				muxer.release();
			}
			closeQuietly(outputPfd);
			if (succeeded) {
				clearPending(resolver, outputUri);
			} else {
				resolver.delete(outputUri, null, null);
			}
		}
	}

	// Pulls any output the encoder has ready and writes it to the muxer,
	// starting the muxer (and resolving the real track index) the first
	// time the encoder reports its output format. endOfStream: keep
	// polling past a momentary "no output yet" until the end-of-stream
	// buffer actually arrives, instead of giving up after one empty poll.
	private static void drainEncoder(@NonNull MediaCodec codec, @NonNull MediaMuxer muxer,
			@NonNull MediaCodec.BufferInfo bufferInfo, @NonNull int[] trackIndex,
			@NonNull boolean[] muxerStarted, boolean endOfStream) {
		while (true) {
			int outIndex = codec.dequeueOutputBuffer(bufferInfo, 10000);
			if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
				if (!endOfStream) {
					return;
				}
			} else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				trackIndex[0] = muxer.addTrack(codec.getOutputFormat());
				muxer.start();
				muxerStarted[0] = true;
			} else if (outIndex >= 0) {
				ByteBuffer encodedData = codec.getOutputBuffer(outIndex);
				if (encodedData != null && bufferInfo.size > 0 && muxerStarted[0]) {
					encodedData.position(bufferInfo.offset);
					encodedData.limit(bufferInfo.offset + bufferInfo.size);
					muxer.writeSampleData(trackIndex[0], encodedData, bufferInfo);
				}
				codec.releaseOutputBuffer(outIndex, false);
				if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
					return;
				}
			}
		}
	}

	@Nullable
	private static Bitmap decodeUri(@NonNull ContentResolver resolver, @NonNull Uri uri) {
		try (InputStream in = resolver.openInputStream(uri)) {
			return in == null ? null : BitmapFactory.decodeStream(in);
		} catch (IOException e) {
			return null;
		}
	}

	private static int compileProgram() {
		int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
		int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
		if (vertexShader == 0 || fragmentShader == 0) {
			return 0;
		}
		int program = GLES20.glCreateProgram();
		GLES20.glAttachShader(program, vertexShader);
		GLES20.glAttachShader(program, fragmentShader);
		GLES20.glLinkProgram(program);
		GLES20.glDeleteShader(vertexShader);
		GLES20.glDeleteShader(fragmentShader);
		int[] linkStatus = new int[1];
		GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
		if (linkStatus[0] != GLES20.GL_TRUE) {
			Log.e(TAG, "compileProgram: link failed: " + GLES20.glGetProgramInfoLog(program));
			GLES20.glDeleteProgram(program);
			return 0;
		}
		return program;
	}

	private static int compileShader(int type, @NonNull String source) {
		int shader = GLES20.glCreateShader(type);
		GLES20.glShaderSource(shader, source);
		GLES20.glCompileShader(shader);
		int[] status = new int[1];
		GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
		if (status[0] != GLES20.GL_TRUE) {
			Log.e(TAG, "compileShader: failed: " + GLES20.glGetShaderInfoLog(shader));
			GLES20.glDeleteShader(shader);
			return 0;
		}
		return shader;
	}

	// A simple, commonly-used rule of thumb (bits per pixel per second,
	// scaled linearly by quality) rather than anything content-aware -
	// good enough for a utility export, not meant to compete with a
	// dedicated video pipeline. fps is floored at 10 for this
	// calculation specifically (not for anything to do with actual
	// playback speed, which always uses the real fps - see the explicit
	// per-frame presentation timestamps above): a low-fps sequence still
	// wants each individual frame to look good, and scaling bitrate
	// straight down with fps would starve those infrequent frames of
	// detail precisely when each one's on screen the longest. scale: see
	// VideoFormat.bitrateScale.
	private static int estimateBitrate(int width, int height, float fps, int quality,
			double scale) {
		double minBitsPerPixel = 0.02;
		double maxBitsPerPixel = 0.5;
		double bitsPerPixel = minBitsPerPixel +
				(maxBitsPerPixel - minBitsPerPixel) * (Math.max(0, Math.min(100, quality)) / 100.0);
		float bitrateFps = Math.max(10f, fps);
		long bitrate = (long) (width * height * bitrateFps * bitsPerPixel * scale);
		return (int) Math.min(50_000_000, Math.max(500_000, bitrate));
	}

	@Nullable
	@RequiresApi(Build.VERSION_CODES.Q)
	private static Uri insertPendingVideo(@NonNull ContentResolver resolver, @NonNull String filename,
			@NonNull String mimeType) {
		ContentValues values = new ContentValues();
		values.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
		values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
		values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM);
		values.put(MediaStore.Video.Media.IS_PENDING, 1);
		return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private static void clearPending(@NonNull ContentResolver resolver, @NonNull Uri uri) {
		ContentValues values = new ContentValues();
		values.put(MediaStore.Video.Media.IS_PENDING, 0);
		resolver.update(uri, values, null, null);
	}

	private static void closeQuietly(@Nullable ParcelFileDescriptor pfd) {
		if (pfd != null) {
			try {
				pfd.close();
			} catch (IOException ignored) {
				// Nothing more to do with a close failure on our way out.
			}
		}
	}

	// A minimal, throwaway ES2 EGL context whose surface is the given
	// encoder input Surface directly - same EGL-session shape as
	// HeadlessShaderCompiler's own pbuffer-backed one, just bound to a
	// real window surface instead, since frames need to actually reach
	// the encoder rather than just exist in an offscreen buffer.
	private static final class EglSession {
		final EGLDisplay display;
		final EGLSurface surface;
		private final EGLContext context;

		private EglSession(@NonNull EGLDisplay display, @NonNull EGLSurface surface,
				@NonNull EGLContext context) {
			this.display = display;
			this.surface = surface;
			this.context = context;
		}

		@Nullable
		static EglSession create(@NonNull Surface inputSurface) {
			EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
			if (display == null || display == EGL14.EGL_NO_DISPLAY) {
				return null;
			}
			int[] versionOut = new int[2];
			if (!EGL14.eglInitialize(display, versionOut, 0, versionOut, 1)) {
				return null;
			}
			int[] configAttribs = {
					EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
					EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
					EGL14.EGL_RED_SIZE, 8,
					EGL14.EGL_GREEN_SIZE, 8,
					EGL14.EGL_BLUE_SIZE, 8,
					EGL14.EGL_ALPHA_SIZE, 8,
					EGL14.EGL_NONE
			};
			EGLConfig[] configs = new EGLConfig[1];
			int[] numConfigs = new int[1];
			if (!EGL14.eglChooseConfig(
					display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
					numConfigs[0] <= 0 || configs[0] == null) {
				EGL14.eglTerminate(display);
				return null;
			}
			EGLConfig config = configs[0];

			int[] contextAttribs = {
					EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
					EGL14.EGL_NONE
			};
			EGLContext context = EGL14.eglCreateContext(
					display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
			if (context == null || context == EGL14.EGL_NO_CONTEXT) {
				EGL14.eglTerminate(display);
				return null;
			}

			int[] surfaceAttribs = {EGL14.EGL_NONE};
			EGLSurface surface = EGL14.eglCreateWindowSurface(
					display, config, inputSurface, surfaceAttribs, 0);
			if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
				EGL14.eglDestroyContext(display, context);
				EGL14.eglTerminate(display);
				return null;
			}

			if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
				EGL14.eglDestroySurface(display, surface);
				EGL14.eglDestroyContext(display, context);
				EGL14.eglTerminate(display);
				return null;
			}

			return new EglSession(display, surface, context);
		}

		void destroy() {
			EGL14.eglMakeCurrent(display,
					EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
			EGL14.eglDestroySurface(display, surface);
			EGL14.eglDestroyContext(display, context);
			EGL14.eglTerminate(display);
		}
	}
}
