package enk.danwills.android.enkishadereditor.io;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.projection.MediaProjection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Records the screen, with what's playing and/or the microphone, to an
// MP4 in Movies/Enki Shader Editor - a voice-over tutorial, say. The
// picture is a MediaProjection's virtual display mirroring the screen
// straight into a hardware H.264 encoder's input surface. The sound is a
// playback capture of what's playing (Enki's sound passes and music among
// it), in stereo, and the microphone through the VOICE_COMMUNICATION
// source, so the phone's echo canceller keeps the speaker's sound out of
// the voice; with both, the voice is mixed into both channels. Frames and
// sound share one clock (System.nanoTime(), which the virtual display's
// frames carry), so they stay in step. Runs on threads of its own.
// Needs API 29 (playback capture, MediaStore paths) and, for any sound,
// RECORD_AUDIO.
@RequiresApi(Build.VERSION_CODES.Q)
public final class ScreenRecorder {
	public interface Listener {
		// On a background thread, once it's stopped: where it was saved,
		// or null if nothing was.
		void onFinished(@Nullable Uri uri);
	}

	private static final String TAG = "ScreenRecorder";
	private static final String ALBUM = "Enki Shader Editor";
	private static final String VIDEO_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC;
	private static final int FPS = 30;
	private static final int RATE = 48000;
	// Samples a channel per audio chunk: 20 ms.
	private static final int CHUNK = RATE / 50;
	private static final int AUDIO_BITRATE = 192_000;
	// The voice, over what's playing (the voice mode's own levelling
	// already brings speech up to around -15 dB, peaking at full scale).
	private static final float VOICE_GAIN = 1f;
	// Ducking: while the voice is over DUCK_THRESHOLD (RMS of a chunk,
	// about -36 dB: the voice mode's noise suppression leaves near
	// silence between words), what's playing goes down to duckLevel
	// within a chunk, holds DUCK_HOLD after the voice stops,
	// so it doesn't pump between words, then comes back up over
	// DUCK_RELEASE.
	private static final double DUCK_THRESHOLD = 500;
	private static final int DUCK_HOLD = RATE * 3 / 10;
	private static final int DUCK_RELEASE = RATE / 2;
	private static final long TIMEOUT_US = 10_000;

	private final Context context;
	private final MediaProjection projection;
	private final boolean sound;
	// What's playing in the mix, 1 as it is.
	private final float soundLevel;
	private final boolean voice;
	private final boolean duck;
	private final float duckLevel;
	private final Listener listener;

	private MediaCodec video;
	private MediaCodec audio;
	private Surface inputSurface;
	private VirtualDisplay display;
	private AudioRecord playback;
	private AudioRecord mic;
	private AcousticEchoCanceler echoCanceler;
	private Muxer muxer;
	private Uri uri;
	private ParcelFileDescriptor fd;
	private HandlerThread callbackThread;
	@Nullable
	private MediaProjection.Callback projectionCallback;
	private Thread videoThread;
	private Thread audioThread;
	private Thread micThread;
	private volatile boolean stopping;
	private long startNanos;

	// The microphone's latest second, for mixing in.
	private final short[] voiceRing = new short[RATE];
	private int voiceHead;
	private int voiceCount;

	// soundLevel: what's playing, 0 for none (no playback capture at
	// all), 1 as it is; voice: the microphone; duckLevel: what's playing
	// while the voice talks, 1 for no ducking.
	public ScreenRecorder(@NonNull Context context, @NonNull MediaProjection projection,
			float soundLevel, boolean voice, float duckLevel, @NonNull Listener listener) {
		this.context = context.getApplicationContext();
		this.projection = projection;
		this.sound = soundLevel > 0;
		this.soundLevel = Math.min(1f, Math.max(0f, soundLevel));
		this.voice = voice;
		this.duckLevel = Math.min(1f, Math.max(0f, duckLevel));
		this.duck = this.duckLevel < 1f;
		this.listener = listener;
	}

	// Starts recording a width x height screen at dpi; false (with
	// everything let go) if it couldn't.
	// MissingPermission: the caller has RECORD_AUDIO before asking for
	// sound or voice.
	@SuppressLint("MissingPermission")
	public boolean start(int width, int height, int dpi) {
		try {
			video = MediaCodec.createEncoderByType(VIDEO_TYPE);
			int[] size = encoderSize(video, width, height);
			MediaFormat videoFormat = MediaFormat.createVideoFormat(VIDEO_TYPE, size[0], size[1]);
			videoFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT,
					MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
			videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, (int) Math.min(30_000_000L,
					Math.max(2_000_000L, (long) size[0] * size[1] * FPS / 10)));
			videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
			videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
			// A still screen sends no frames; keep the video going anyway.
			videoFormat.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000 / FPS);
			video.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			inputSurface = video.createInputSurface();

			int channels = 0;
			if (sound) {
				playback = playbackCapture();
				if (playback != null) {
					channels = 2;
				}
			}
			if (voice) {
				mic = micCapture();
				if (mic != null && channels == 0) {
					channels = 1;
				}
			}
			if (channels > 0) {
				audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
				MediaFormat audioFormat = MediaFormat.createAudioFormat(
						MediaFormat.MIMETYPE_AUDIO_AAC, RATE, channels);
				audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
						MediaCodecInfo.CodecProfileLevel.AACObjectLC);
				audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
				audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * channels * 2 * 2);
				audio.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
			}

			String name = "Enki screen recording " + new SimpleDateFormat(
					"yyyy-MM-dd HH.mm.ss", Locale.US).format(new Date()) + ".mp4";
			ContentResolver resolver = context.getContentResolver();
			uri = insertPendingVideo(resolver, name);
			fd = uri != null ? resolver.openFileDescriptor(uri, "w") : null;
			if (fd == null) {
				throw new IOException("no file for the recording");
			}
			muxer = new Muxer(new MediaMuxer(fd.getFileDescriptor(),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), audio != null ? 2 : 1);

			callbackThread = new HandlerThread("record-projection");
			callbackThread.start();
			Handler handler = new Handler(callbackThread.getLooper());
			// (Required before createVirtualDisplay() on API 34+.)
			projectionCallback = new MediaProjection.Callback() {
				@Override
				public void onStop() {
					stop();
				}
			};
			projection.registerCallback(projectionCallback, handler);

			startNanos = System.nanoTime();
			video.start();
			display = projection.createVirtualDisplay("Enki screen recording",
					size[0], size[1], dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
					inputSurface, null, handler);
			videoThread = new Thread(this::drainVideo, "record-video");
			videoThread.start();
			if (audio != null) {
				audio.start();
				if (playback != null) {
					playback.startRecording();
				}
				if (mic != null) {
					mic.startRecording();
					if (playback != null) {
						micThread = new Thread(this::listen, "record-voice");
						micThread.start();
					}
				}
				audioThread = new Thread(this::encodeAudio, "record-audio");
				audioThread.start();
			}
			Log.i(TAG, "recording " + size[0] + "x" + size[1] + (playback != null ? ", sound" : "") +
					(mic != null ? ", voice" : ""));
			return true;
		} catch (IOException | RuntimeException e) {
			Log.e(TAG, "couldn't start recording", e);
			stopping = true;
			release();
			discard();
			return false;
		}
	}

	// Stops it, finishing the file on its own threads, then tells the
	// listener. Safe to call more than once, from any thread.
	public void stop() {
		synchronized (this) {
			if (stopping) {
				return;
			}
			stopping = true;
		}
		new Thread(this::finish, "record-finish").start();
	}

	private void finish() {
		try {
			video.signalEndOfInputStream();
		} catch (RuntimeException e) {
			Log.w(TAG, "signalEndOfInputStream", e);
		}
		join(audioThread);
		join(micThread);
		join(videoThread);
		boolean saved = muxer != null && muxer.finish();
		release();
		if (saved) {
			ContentValues values = new ContentValues();
			values.put(MediaStore.Video.Media.IS_PENDING, 0);
			context.getContentResolver().update(uri, values, null, null);
			listener.onFinished(uri);
		} else {
			discard();
			listener.onFinished(null);
		}
	}

	private static void join(@Nullable Thread thread) {
		if (thread == null) {
			return;
		}
		try {
			thread.join(5000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private void release() {
		if (display != null) {
			display.release();
			display = null;
		}
		for (AudioRecord record : new AudioRecord[]{playback, mic}) {
			if (record != null) {
				try {
					record.stop();
				} catch (RuntimeException ignored) {
					// Never started.
				}
				record.release();
			}
		}
		playback = null;
		mic = null;
		if (echoCanceler != null) {
			echoCanceler.release();
			echoCanceler = null;
		}
		for (MediaCodec codec : new MediaCodec[]{video, audio}) {
			if (codec != null) {
				try {
					codec.stop();
				} catch (RuntimeException ignored) {
					// Never started.
				}
				codec.release();
			}
		}
		video = null;
		audio = null;
		if (inputSurface != null) {
			inputSurface.release();
			inputSurface = null;
		}
		if (muxer != null) {
			muxer.release();
		}
		if (fd != null) {
			try {
				fd.close();
			} catch (IOException ignored) {
				// Nothing more to do.
			}
			fd = null;
		}
		if (projectionCallback != null) {
			// (The projection can outlive the recording - see
			// SharedProjection.)
			projection.unregisterCallback(projectionCallback);
			projectionCallback = null;
		}
		if (callbackThread != null) {
			callbackThread.quitSafely();
			callbackThread = null;
		}
	}

	private void discard() {
		if (uri != null) {
			context.getContentResolver().delete(uri, null, null);
			uri = null;
		}
	}

	// The biggest size, down from the screen's own (16-pixel aligned),
	// the encoder takes at FPS - many top out around 4096x2176.
	@NonNull
	private static int[] encoderSize(@NonNull MediaCodec codec, int width, int height) {
		MediaCodecInfo.VideoCapabilities caps = codec.getCodecInfo()
				.getCapabilitiesForType(VIDEO_TYPE).getVideoCapabilities();
		for (float scale : new float[]{1f, 0.75f, 2f / 3f, 0.5f, 0.375f, 0.25f}) {
			int w = Math.max(16, (int) (width * scale) & ~15);
			int h = Math.max(16, (int) (height * scale) & ~15);
			if (caps.areSizeAndRateSupported(w, h, FPS)) {
				return new int[]{w, h};
			}
		}
		return new int[]{(width / 4) & ~15, (height / 4) & ~15};
	}

	@SuppressLint("MissingPermission")
	@Nullable
	private AudioRecord playbackCapture() {
		try {
			AudioPlaybackCaptureConfiguration config =
					new AudioPlaybackCaptureConfiguration.Builder(projection)
							.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
							.addMatchingUsage(AudioAttributes.USAGE_GAME)
							.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
							.build();
			AudioRecord record = new AudioRecord.Builder()
					.setAudioFormat(new AudioFormat.Builder()
							.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
							.setSampleRate(RATE)
							.setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
							.build())
					.setBufferSizeInBytes(Math.max(CHUNK * 2 * 2 * 8, AudioRecord.getMinBufferSize(
							RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)))
					.setAudioPlaybackCaptureConfig(config)
					.build();
			if (record.getState() == AudioRecord.STATE_INITIALIZED) {
				return record;
			}
			record.release();
		} catch (RuntimeException e) {
			Log.w(TAG, "no playback capture", e);
		}
		return null;
	}

	@SuppressLint("MissingPermission")
	@Nullable
	private AudioRecord micCapture() {
		try {
			AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
					RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
					Math.max(CHUNK * 2 * 8, AudioRecord.getMinBufferSize(RATE,
							AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)));
			if (record.getState() != AudioRecord.STATE_INITIALIZED) {
				record.release();
				return null;
			}
			if (AcousticEchoCanceler.isAvailable()) {
				echoCanceler = AcousticEchoCanceler.create(record.getAudioSessionId());
				if (echoCanceler != null) {
					echoCanceler.setEnabled(true);
				}
			}
			return record;
		} catch (RuntimeException e) {
			Log.w(TAG, "no microphone", e);
			return null;
		}
	}

	// With what's playing too: the microphone, kept for encodeAudio() to
	// mix in.
	private void listen() {
		short[] chunk = new short[CHUNK];
		while (!stopping) {
			int n = mic.read(chunk, 0, chunk.length);
			if (n <= 0) {
				continue;
			}
			synchronized (voiceRing) {
				for (int i = 0; i < n; ++i) {
					voiceRing[(voiceHead + voiceCount) % RATE] = chunk[i];
					if (voiceCount < RATE) {
						++voiceCount;
					} else {
						voiceHead = (voiceHead + 1) % RATE;
					}
				}
			}
		}
	}

	private void encodeAudio() {
		boolean stereo = playback != null;
		int channels = stereo ? 2 : 1;
		short[] chunk = new short[CHUNK * channels];
		short[] voiceChunk = new short[CHUNK];
		ByteBuffer bytes = ByteBuffer.allocate(chunk.length * 2).order(ByteOrder.nativeOrder());
		long samples = 0;
		long firstUs = -1;
		// What's playing's gain from ducking, and how long it's held.
		float duckGain = 1f;
		int hold = 0;
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		boolean ended = false;
		while (!ended) {
			int n;
			if (stopping) {
				n = 0;
				ended = true;
			} else {
				n = (stereo ? playback : mic).read(chunk, 0, chunk.length);
				if (n <= 0) {
					continue;
				}
				int frames = n / channels;
				if (stereo && micThread != null) {
					synchronized (voiceRing) {
						int got = Math.min(frames, voiceCount);
						for (int i = 0; i < got; ++i) {
							voiceChunk[i] = voiceRing[(voiceHead + i) % RATE];
						}
						voiceHead = (voiceHead + got) % RATE;
						voiceCount -= got;
						for (int i = got; i < frames; ++i) {
							voiceChunk[i] = 0;
						}
					}
					float from = duckGain;
					if (duck) {
						double sum = 0;
						for (int i = 0; i < frames; ++i) {
							sum += (double) voiceChunk[i] * voiceChunk[i];
						}
						if (Math.sqrt(sum / Math.max(1, frames)) > DUCK_THRESHOLD) {
							hold = DUCK_HOLD;
						} else {
							hold = Math.max(0, hold - frames);
						}
						duckGain = hold > 0
								? duckLevel
								: Math.min(1f, duckGain + (float) frames / DUCK_RELEASE);
					}
					// Ramped across the chunk, so the gain never steps.
					for (int i = 0; i < frames; ++i) {
						float gain = soundLevel * (from + (duckGain - from) * i / frames);
						int v = (int) (voiceChunk[i] * VOICE_GAIN);
						chunk[i * 2] = clip((int) (chunk[i * 2] * gain) + v);
						chunk[i * 2 + 1] = clip((int) (chunk[i * 2 + 1] * gain) + v);
					}
				} else if (stereo && soundLevel < 1f) {
					for (int i = 0; i < n; ++i) {
						chunk[i] = (short) (chunk[i] * soundLevel);
					}
				}
				if (firstUs < 0) {
					// When the chunk began, by the clock the frames use.
					firstUs = (System.nanoTime() - startNanos) / 1000 -
							(long) (n / channels) * 1_000_000 / RATE;
				}
			}
			int index = audio.dequeueInputBuffer(TIMEOUT_US * 10);
			if (index >= 0) {
				ByteBuffer input = audio.getInputBuffer(index);
				long us = Math.max(0, firstUs) + samples * 1_000_000 / RATE;
				if (ended || input == null) {
					audio.queueInputBuffer(index, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
				} else {
					bytes.clear();
					bytes.asShortBuffer().put(chunk, 0, n);
					input.clear();
					input.put(bytes.array(), 0, Math.min(n * 2, input.remaining()));
					audio.queueInputBuffer(index, 0, n * 2, us, 0);
					samples += n / channels;
				}
			}
			drain(audio, info, ended);
		}
	}

	private static short clip(int sample) {
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
	}

	private void drainVideo() {
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		drain(video, info, true);
	}

	// Moves codec's output to the muxer; toEnd: until its end of stream,
	// else only what's ready.
	private void drain(@NonNull MediaCodec codec, @NonNull MediaCodec.BufferInfo info,
			boolean toEnd) {
		boolean isVideo = codec == video;
		while (true) {
			int index;
			try {
				index = codec.dequeueOutputBuffer(info, TIMEOUT_US);
			} catch (IllegalStateException e) {
				return;
			}
			if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
				if (!toEnd) {
					return;
				}
				continue;
			}
			if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
				muxer.addTrack(isVideo, codec.getOutputFormat());
				continue;
			}
			if (index < 0) {
				continue;
			}
			ByteBuffer output = codec.getOutputBuffer(index);
			if (output != null && info.size > 0 &&
					(info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
				if (isVideo) {
					// The display's frames carry System.nanoTime().
					info.presentationTimeUs -= startNanos / 1000;
				}
				if (info.presentationTimeUs >= 0) {
					muxer.write(isVideo, output, info);
				}
			}
			codec.releaseOutputBuffer(index, false);
			if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
				return;
			}
		}
	}

	@Nullable
	private static Uri insertPendingVideo(@NonNull ContentResolver resolver, @NonNull String name) {
		ContentValues values = new ContentValues();
		values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
		values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
		values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ALBUM);
		values.put(MediaStore.Video.Media.IS_PENDING, 1);
		return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
	}

	// A MediaMuxer started once every track's format is in; samples that
	// come before that wait for it (briefly), and ones that come later
	// keep their order per track.
	private static final class Muxer {
		private final MediaMuxer muxer;
		private final int tracks;
		private int added;
		private int videoTrack = -1;
		private int audioTrack = -1;
		private boolean started;
		private boolean wrote;
		private long lastVideoUs = -1;
		private long lastAudioUs = -1;

		Muxer(@NonNull MediaMuxer muxer, int tracks) {
			this.muxer = muxer;
			this.tracks = tracks;
		}

		synchronized void addTrack(boolean isVideo, @NonNull MediaFormat format) {
			int track = muxer.addTrack(format);
			if (isVideo) {
				videoTrack = track;
			} else {
				audioTrack = track;
			}
			if (++added == tracks) {
				muxer.start();
				started = true;
				notifyAll();
			}
		}

		synchronized void write(boolean isVideo, @NonNull ByteBuffer data,
				@NonNull MediaCodec.BufferInfo info) {
			long deadline = System.currentTimeMillis() + 2000;
			while (!started) {
				long wait = deadline - System.currentTimeMillis();
				if (wait <= 0) {
					return;
				}
				try {
					wait(wait);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
			if (isVideo) {
				if (info.presentationTimeUs <= lastVideoUs) {
					return;
				}
				lastVideoUs = info.presentationTimeUs;
			} else {
				if (info.presentationTimeUs <= lastAudioUs) {
					return;
				}
				lastAudioUs = info.presentationTimeUs;
			}
			muxer.writeSampleData(isVideo ? videoTrack : audioTrack, data, info);
			wrote = true;
		}

		// Ends the file; false if nothing was written.
		synchronized boolean finish() {
			if (!started) {
				return false;
			}
			try {
				muxer.stop();
				started = false;
				return wrote;
			} catch (RuntimeException e) {
				Log.e(TAG, "couldn't finish the recording", e);
				return false;
			}
		}

		synchronized void release() {
			if (started) {
				try {
					muxer.stop();
				} catch (RuntimeException ignored) {
					// Nothing more to do.
				}
				started = false;
			}
			muxer.release();
		}
	}
}
