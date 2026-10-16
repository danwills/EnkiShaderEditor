package enk.danwills.android.enkishadereditor.opengl;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class ShaderRenderer implements GLSurfaceView.Renderer {
	public interface OnRendererListener {
		void onInfoLog(@NonNull List<ShaderError> error);

		void onFramesPerSecond(int fps);

		// Called when a shader declares `audioSpectrum` and the "use
		// system playback audio" preference is on, but no MediaProjection
		// consent has been granted yet. Invoked on the GL thread.
		void onRequestAudioCapture();
	}

	public static final String UNIFORM_AUDIO_SPECTRUM = "audioSpectrum";
	public static final String UNIFORM_BACKBUFFER = "backbuffer";
	public static final String UNIFORM_BATTERY = "battery";
	public static final String UNIFORM_CAMERA_ADDENT = "cameraAddent";
	public static final String UNIFORM_CAMERA_BACK = "cameraBack";
	public static final String UNIFORM_CAMERA_FRONT = "cameraFront";
	public static final String UNIFORM_CAMERA_ORIENTATION = "cameraOrientation";
	public static final String UNIFORM_DATE = "date";
	public static final String UNIFORM_DAYTIME = "daytime";
	public static final String UNIFORM_FRAME_NUMBER = "frame";
	public static final String UNIFORM_FTIME = "ftime";
	public static final String UNIFORM_GRAVITY = "gravity";
	public static final String UNIFORM_GYROSCOPE = "gyroscope";
	public static final String UNIFORM_LAST_NOTIFICATION_TIME = "lastNotificationTime";
	public static final String UNIFORM_LIGHT = "light";
	public static final String UNIFORM_LINEAR = "linear";
	public static final String UNIFORM_MAGNETIC = "magnetic";
	public static final String UNIFORM_MOUSE = "mouse";
	public static final String UNIFORM_NIGHT_MODE = "nightMode";
	public static final String UNIFORM_NOTIFICATION_COUNT = "notificationCount";
	public static final String UNIFORM_OFFSET = "offset";
	public static final String UNIFORM_ORIENTATION = "orientation";
	public static final String UNIFORM_INCLINATION = "inclination";
	public static final String UNIFORM_INCLINATION_MATRIX = "inclinationMatrix";
	public static final String UNIFORM_POINTERS = "pointers";
	public static final String UNIFORM_POINTER_COUNT = "pointerCount";
	public static final String UNIFORM_POSITION = "position";
	public static final String UNIFORM_POWER_CONNECTED = "powerConnected";
	public static final String UNIFORM_PRESSURE = "pressure";
	public static final String UNIFORM_PROXIMITY = "proximity";
	public static final String UNIFORM_RESOLUTION = "resolution";
	public static final String UNIFORM_ROTATION_MATRIX = "rotationMatrix";
	public static final String UNIFORM_ROTATION_VECTOR = "rotationVector";
	public static final String UNIFORM_RUNTIME = "runtime";
	public static final String UNIFORM_SECOND = "second";
	public static final String UNIFORM_START_RANDOM = "startRandom";
	public static final String UNIFORM_SUB_SECOND = "subsecond";
	public static final String UNIFORM_TIME = "time";
	public static final String UNIFORM_MEDIA_VOLUME = "mediaVolume";
	public static final String UNIFORM_MIC_AMPLITUDE = "micAmplitude";
	public static final String UNIFORM_TOUCH = "touch";
	public static final String UNIFORM_TOUCH_START = "touchStart";

	private static final int MAX_TEXTURES = 32;
	private static final long FPS_UPDATE_FREQUENCY_NS = 200000000L;
	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_REFRESH_RATE = 60f;

	private final GlDevice device = new GlDevice(MAX_TEXTURES);
	private final RendererProgramManager programManager =
			new RendererProgramManager(MAX_TEXTURES);
	private final ShaderRenderPipeline renderPipeline =
			new ShaderRenderPipeline(device, Mesh.fullScreenQuad());
	private final BuiltinUniforms builtinUniforms;
	private final Context context;
	private final Object thumbnailLock = new Object();
	private final Object fullImageLock = new Object();

	@Nullable
	private OnRendererListener onRendererListener;
	@NonNull
	private BuiltinUniforms.SurfaceState surfaceState =
			BuiltinUniforms.SurfaceState.empty();
	private long lastRender;
	private byte[] thumbnail = new byte[1];
	private boolean captureThumbnail;
	@Nullable
	private byte[] fullImage;
	private boolean captureFullImage;
	private volatile long nextFpsUpdate;
	private volatile float sum;
	private volatile float samples;
	private volatile int lastFps;
	private volatile float refreshRate = DEFAULT_REFRESH_RATE;
	private volatile long lastCompileNanos = -1;
	private volatile boolean lastCompileSucceeded = false;
	private volatile long lastProgramBinaryBytes = -1;
	private int glVersion = 2;
	// 0 means "no backing DB row" - see ShaderProjectSession.getShaderId().
	// Caching is simply skipped for such sessions (nothing stable to key
	// the cache on).
	private volatile long shaderId = 0;
	@Nullable
	private ProgramBinaryCache programBinaryCache;

	public ShaderRenderer(Context context) {
		this.context = context;
		builtinUniforms = new BuiltinUniforms(context);
	}

	public void setVersion(int version) {
		glVersion = version;
		programManager.setVersion(version);
	}

	public void setFragmentShader(String source, float quality, long shaderId) {
		builtinUniforms.setRuntimeNanos(0);
		setQuality(quality);
		this.shaderId = shaderId;
		programManager.setFragmentShader(source);
		resetFps();
	}

	// Supplies the persisted compiled-program cache - see
	// ProgramBinaryCache. Not required; caching is simply skipped if
	// this is never called (or passed null).
	public void setProgramBinaryCache(@Nullable ProgramBinaryCache cache) {
		programBinaryCache = cache;
	}

	public long getRuntimeNanos() {
		return builtinUniforms.getRuntimeNanos();
	}

	public void setRuntimeNanos(long nanos) {
		builtinUniforms.setRuntimeNanos(nanos);
	}

	// Restarts every time-based uniform (`time`, `runtime`, `frame`,
	// `startRandom`), not just `runtime` - see BuiltinUniforms.restart() -
	// and clears the backbuffer ping-pong targets to black, so a
	// feedback shader's accumulated image is discarded too.
	public void restartTime() {
		builtinUniforms.restart(System.nanoTime());
		renderPipeline.clearTargets();
	}

	public void pauseClock() {
		builtinUniforms.pauseClock();
	}

	public void resumeClock() {
		builtinUniforms.resumeClock();
	}

	public void setQuality(float quality) {
		builtinUniforms.setQuality(quality);
	}

	public void setOnRendererListener(OnRendererListener listener) {
		onRendererListener = listener;
	}

	// Time taken by the most recent shader compile+link, or -1 if none
	// has happened yet.
	public long getLastCompileNanos() {
		return lastCompileNanos;
	}

	public boolean wasLastCompileSuccessful() {
		return lastCompileSucceeded;
	}

	// Size in bytes of the linked main program's GPU binary, queried
	// via GLES30's glGetProgramBinary machinery, or -1 if unavailable -
	// either because the context is GLES2-only, or (common on many
	// GPU drivers) the device reports zero supported binary formats,
	// meaning program binary retrieval simply isn't supported here.
	// The size itself is a vendor/driver-specific blob, not a portable
	// or comparable metric across devices.
	public long getLastProgramBinaryBytes() {
		return lastProgramBinaryBytes;
	}

	public int getLastFps() {
		return lastFps;
	}

	public void setMediaProjection(@Nullable MediaProjection mediaProjection) {
		builtinUniforms.setMediaProjection(mediaProjection);
	}

	// Clears the "already asked for system audio consent" guard. Safe to
	// call from any thread (see BuiltinAudioUniforms.resetConsentGuard).
	// Called ahead of refreshAudioCaptureSource(), separately and not
	// queued onto the GL thread, so that two preference changes made in
	// quick succession (e.g. toggling the preference off then back on
	// before the GL thread next runs) can't both reset the guard after
	// the first one already re-asked - only the first queued refresh
	// should actually trigger a new prompt.
	public void resetAudioCaptureConsent() {
		builtinUniforms.resetAudioCaptureConsent();
	}

	// Re-evaluates which audio source to use against the current
	// preferences, e.g. after "use system playback audio" changes,
	// without requiring a shader recompile to trigger it.
	public void refreshAudioCaptureSource() {
		builtinUniforms.refreshAudioCaptureSource();
		if (builtinUniforms.consumeNeedsAudioCaptureConsent() && onRendererListener != null) {
			onRendererListener.onRequestAudioCapture();
		}
	}

	// See BuiltinAudioUniforms.releaseMediaProjection().
	public void releaseMediaProjection() {
		builtinUniforms.releaseMediaProjection();
	}

	// See AudioSpectrumListener.isReceivingAudio().
	public boolean isReceivingAudio() {
		return builtinUniforms.isReceivingAudio();
	}

	public void setCropBorderPixel(boolean cropBorderPixel) {
		renderPipeline.setCropBorderPixel(cropBorderPixel);
	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		device.resetState();
		device.disable(GLES20.GL_CULL_FACE);
		device.disable(GLES20.GL_BLEND);
		device.disable(GLES20.GL_DEPTH_TEST);
		device.setClearColor(0f, 0f, 0f, 1f);

		discardContextResources();
		var thumbnailErrors = renderPipeline.createContextResources();
		if (!thumbnailErrors.isEmpty()) {
			submitErrors(thumbnailErrors);
		}

		if (programManager.hasPreparedShader()) {
			resetFps();

			ProgramBinary cachedBinary = null;
			String sourceHash = null;
			String driverSignature = null;
			if (shaderId > 0 && programBinaryCache != null && glVersion >= 3) {
				String sourceText = programManager.getSourceText();
				if (sourceText != null) {
					sourceHash = ProgramBinaryKey.sourceHash(sourceText, glVersion);
					driverSignature = ProgramBinaryKey.driverSignature();
					cachedBinary = programBinaryCache.get(shaderId, sourceHash, driverSignature);
				}
			}

			long compileStart = System.nanoTime();
			var reloadResult = programManager.reload(context, device, cachedBinary);
			lastCompileNanos = System.nanoTime() - compileStart;
			lastCompileSucceeded = reloadResult.succeeded();
			submitErrors(reloadResult.textureErrors());
			if (reloadResult.succeeded()) {
				submitErrors(Collections.emptyList());
				ProgramBinary freshBinary = reloadResult.freshBinary();
				if (freshBinary != null) {
					lastProgramBinaryBytes = freshBinary.bytes().length;
					if (programBinaryCache != null &&
							sourceHash != null && driverSignature != null) {
						programBinaryCache.put(shaderId, freshBinary, sourceHash, driverSignature,
								lastCompileNanos / 1_000_000);
					}
				} else {
					// Either this came from a cache hit (nothing fresh to
					// report/persist) or caching wasn't attempted above
					// (no shaderId/cache, e.g. an unsaved scratch session) -
					// either way, still worth querying directly for the
					// stats display.
					lastProgramBinaryBytes = queryProgramBinaryBytes(programManager.getMainProgram());
				}
				builtinUniforms.configure(
						device,
						programManager.getMainProgram(),
						programManager.getFTimeMax(),
						programManager.getTextureResources());
				if (builtinUniforms.consumeNeedsAudioCaptureConsent() &&
						onRendererListener != null) {
					onRendererListener.onRequestAudioCapture();
				}
			} else if (!reloadResult.programErrors().isEmpty()) {
				submitErrors(reloadResult.programErrors());
				lastProgramBinaryBytes = -1;
			}
		}
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
		long now = System.nanoTime();
		lastRender = now;
		refreshRate = getRefreshRate(context);
		surfaceState = builtinUniforms.updateSurface(width, height, now);
		if (surfaceState.renderTargetsChanged()) {
			renderPipeline.releaseTargets();
		}

		resetFps();
	}

	@Override
	public void onDrawFrame(GL10 gl) {
		var surfaceProgram = programManager.getSurfaceProgram();
		var mainProgram = programManager.getMainProgram();
		var surfaceBindings = programManager.getSurfaceBindings();
		if (surfaceProgram == null ||
				mainProgram == null ||
				surfaceBindings == null) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			cancelCaptureFullImage();
			return;
		}

		if (!renderPipeline.hasTargets()) {
			var targetErrors = renderPipeline.ensureTargets(
					context,
					surfaceState.renderWidth(),
					surfaceState.renderHeight(),
					programManager.getBackBufferParameters());
			if (!targetErrors.isEmpty()) {
				submitErrors(targetErrors);
			}
			if (!renderPipeline.hasTargets()) {
				cancelCaptureThumbnail();
				cancelCaptureFullImage();
				return;
			}
		}

		var frame = builtinUniforms.beginFrame(device, renderPipeline.getBackTexture());
		if (frame == null) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			cancelCaptureFullImage();
			return;
		}

		renderPipeline.renderMainPass(frame.bindings(), mainProgram);
		renderPipeline.renderSurfacePass(
				surfaceBindings,
				surfaceProgram,
				frame.surfaceWidth(),
				frame.surfaceHeight());
		captureFullImage(frame.surfaceWidth(), frame.surfaceHeight());
		renderPipeline.swapTargets();
		captureThumbnail();

		if (onRendererListener != null) {
			updateFps(frame.now());
		}

		builtinUniforms.endFrame();
	}

	public void unregisterListeners() {
		builtinUniforms.release();
	}

	public void touchAt(MotionEvent e) {
		builtinUniforms.updateTouch(e);
	}

	public void setOffset(float x, float y) {
		builtinUniforms.updateOffset(x, y);
	}

	public byte[] getThumbnail() {
		synchronized (thumbnailLock) {
			captureThumbnail = true;
			try {
				thumbnailLock.wait(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				captureThumbnail = false;
				return null;
			}
			return thumbnail;
		}
	}

	// Blocks (off the GL thread - callers should invoke this from a
	// background thread) until the next frame renders, then returns a
	// full-resolution PNG of exactly what's on screen - no toolbar,
	// editor, or other app UI, since it reads straight from the
	// GLSurfaceView's own framebuffer. Null on timeout/failure.
	@Nullable
	public byte[] captureFullScreenshot() {
		synchronized (fullImageLock) {
			captureFullImage = true;
			try {
				fullImageLock.wait(3000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				captureFullImage = false;
				return null;
			}
			return fullImage;
		}
	}

	private void resetFps() {
		sum = 0;
		samples = 0;
		lastFps = 0;
		nextFpsUpdate = 0;
	}

	private void submitErrors(@NonNull List<ShaderError> errors) {
		if (onRendererListener != null) {
			onRendererListener.onInfoLog(errors);
		}
	}

	private void discardContextResources() {
		builtinUniforms.clearConfiguration();
		renderPipeline.discardContextResources();
		programManager.discardContextResources();
	}

	// -1 if binary retrieval isn't supported here: needs an ES3 context
	// and a driver that actually reports at least one supported binary
	// format (plenty of GPUs report zero, meaning the entry points
	// exist but retrieval isn't really supported) - see
	// GlDevice.getProgramBinary().
	private long queryProgramBinaryBytes(@NonNull GlProgram program) {
		if (glVersion < 3) {
			return -1;
		}
		ProgramBinary binary = device.getProgramBinary(program);
		return binary != null ? binary.bytes().length : -1;
	}

	private void captureThumbnail() {
		synchronized (thumbnailLock) {
			var surfaceBindings = programManager.getSurfaceBindings();
			var surfaceProgram = programManager.getSurfaceProgram();
			if (captureThumbnail &&
					surfaceBindings != null &&
					surfaceProgram != null) {
				thumbnail = renderPipeline.captureThumbnail(
						surfaceBindings,
						surfaceProgram);
				captureThumbnail = false;
				thumbnailLock.notifyAll();
			}
		}
	}

	private void cancelCaptureThumbnail() {
		synchronized (thumbnailLock) {
			if (captureThumbnail) {
				captureThumbnail = false;
				thumbnailLock.notifyAll();
			}
		}
	}

	// width/height: the surface's actual current size, matching what
	// renderSurfacePass() just drew into the on-screen framebuffer -
	// still bound at this point, so the read captures that exact frame.
	private void captureFullImage(int width, int height) {
		synchronized (fullImageLock) {
			if (captureFullImage) {
				fullImage = renderPipeline.readPixelsAsPng(width, height);
				captureFullImage = false;
				fullImageLock.notifyAll();
			}
		}
	}

	private void cancelCaptureFullImage() {
		synchronized (fullImageLock) {
			if (captureFullImage) {
				captureFullImage = false;
				fullImageLock.notifyAll();
			}
		}
	}

	private void updateFps(long now) {
		long delta = now - lastRender;

		synchronized (this) {
			sum += Math.min(NS_PER_SECOND / delta, refreshRate);

			if (++samples > 0xffff) {
				sum = sum / samples;
				samples = 1;
			}
		}

		if (now > nextFpsUpdate) {
			int fps = Math.round(sum / samples);
			if (fps != lastFps && onRendererListener != null) {
				onRendererListener.onFramesPerSecond(fps);
				lastFps = fps;
			}
			nextFpsUpdate = now + FPS_UPDATE_FREQUENCY_NS;
		}

		lastRender = now;
	}

	private static float getRefreshRate(Context context) {
		float refreshRate = DEFAULT_REFRESH_RATE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Display display = getDisplay(context);
			if (display != null && display.getMode() != null) {
				refreshRate = display.getMode().getRefreshRate();
			}
		} else {
			WindowManager windowManager = (WindowManager) context
					.getSystemService(Context.WINDOW_SERVICE);
			if (windowManager != null) {
				refreshRate = windowManager.getDefaultDisplay()
						.getRefreshRate();
			}
		}
		return refreshRate > 0f ? refreshRate : DEFAULT_REFRESH_RATE;
	}

	@SuppressLint("UseRequiresApi")
	@TargetApi(Build.VERSION_CODES.R)
	private static Display getDisplay(Context context) {
		try {
			return context.getDisplay();
		} catch (UnsupportedOperationException e) {
			// Fall through. Thrown when the context isn't associated
			// with a display.
		}
		DisplayManager displayManager = context.getSystemService(
				DisplayManager.class);
		if (displayManager == null) {
			return null;
		}
		return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
	}
}
