package enk.danwills.android.enkishadereditor.io;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioTrack;
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
// the voice; with both, the voice is mixed into both channels (and can go
// in a second, mono audio track as well, for editing). Frames and
// sound share one clock (System.nanoTime(), which the virtual display's
// frames carry), so they stay in step. The voice can be played back into
// headphones as it's recorded, so the speaker hears themselves. Runs on
// threads of its own.
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
	// Clearer voice (VoiceClarity), so a soft, bassy voice cuts through
	// the music: rumble and boom below LOW_CUT_HZ taken out, the mud
	// round MUD_HZ dipped by MUD_DB, the consonants ("s", "t", "f") that
	// make speech intelligible lifted by PRESENCE_DB above about
	// PRESENCE_HZ; then levelled by its peaks (which the voice mode's own
	// levelling already brings near full scale): over LEVEL_THRESHOLD_DB
	// they're turned down LEVEL_RATIO times, and LEVEL_MAKEUP_DB brings
	// everything back up so a full-scale peak comes out where it went in
	// - the loud syllables stay put and the soft ones come up, by as much
	// as the makeup - then softly limited above LIMIT.
	private static final double LOW_CUT_HZ = 90;
	private static final double MUD_HZ = 300;
	private static final double MUD_DB = -4;
	private static final double PRESENCE_HZ = 3000;
	private static final double PRESENCE_DB = 9;
	private static final double LEVEL_THRESHOLD_DB = -24;
	private static final double LEVEL_RATIO = 3;
	private static final double LEVEL_MAKEUP_DB = 14;
	private static final double LEVEL_ATTACK_S = 0.005;
	private static final double LEVEL_RELEASE_S = 0.15;
	private static final double LIMIT = 0.7;
	private static final int DUCK_HOLD = RATE * 3 / 10;
	private static final int DUCK_RELEASE = RATE / 2;
	private static final long TIMEOUT_US = 10_000;

	private final Context context;
	private final MediaProjection projection;
	private final boolean sound;
	// What's playing in the mix, 1 as it is.
	private final float soundLevel;
	private final boolean voice;
	@Nullable
	private final VoiceClarity clarity;
	// The voice in a track of its own too, after the mix (with both).
	private final boolean voiceTrack;
	// The voice in headphones as it's recorded, 1 as it is, 0 for not.
	private final float hearLevel;
	private final boolean duck;
	private final float duckLevel;
	private final Listener listener;

	private MediaCodec video;
	private MediaCodec audio;
	@Nullable
	private MediaCodec voiceAudio;
	private Surface inputSurface;
	private VirtualDisplay display;
	private AudioRecord playback;
	private AudioRecord mic;
	// Plays the voice back into headphones (hearLevel), if there are any.
	@Nullable
	private AudioTrack hear;
	private short[] hearChunk;
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
	// all), 1 as it is; voice: the microphone; clarity: the voice
	// cleaned up and levelled (VoiceClarity); voiceTrack: the voice in a
	// second audio track too, for editing (only with what's playing as well);
	// hearLevel: the voice played back into headphones, 0 for not;
	// duckLevel: what's playing while the voice talks, 1 for no ducking.
	public ScreenRecorder(@NonNull Context context, @NonNull MediaProjection projection,
			float soundLevel, boolean voice, boolean clarity, boolean voiceTrack,
			float hearLevel, float duckLevel, @NonNull Listener listener) {
		this.context = context.getApplicationContext();
		this.projection = projection;
		this.sound = soundLevel > 0;
		this.soundLevel = Math.min(1f, Math.max(0f, soundLevel));
		this.voice = voice;
		this.clarity = voice && clarity ? new VoiceClarity() : null;
		this.voiceTrack = voice && voiceTrack;
		this.hearLevel = voice ? Math.min(1f, Math.max(0f, hearLevel)) : 0f;
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
				if (mic != null && hearLevel > 0 && headphones()) {
					hear = hearTrack();
				}
			}
			if (channels > 0) {
				audio = audioEncoder(channels);
			}
			// (With the voice alone, the mix is the voice already.)
			if (voiceTrack && playback != null && mic != null) {
				voiceAudio = audioEncoder(1);
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
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4),
					1 + (audio != null ? 1 : 0) + (voiceAudio != null ? 1 : 0));

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
				if (voiceAudio != null) {
					voiceAudio.start();
				}
				if (playback != null) {
					playback.startRecording();
				}
				if (mic != null) {
					mic.startRecording();
					if (hear != null) {
						hear.play();
					}
					if (playback != null) {
						micThread = new Thread(this::listen, "record-voice");
						micThread.start();
					}
				}
				audioThread = new Thread(this::encodeAudio, "record-audio");
				audioThread.start();
			}
			Log.i(TAG, "recording " + size[0] + "x" + size[1] + (playback != null ? ", sound" : "") +
					(mic != null ? ", voice" : "") + (voiceAudio != null ? ", voice track" : "") +
					(hear != null ? ", heard" : ""));
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
		if (hear != null) {
			try {
				hear.stop();
			} catch (RuntimeException ignored) {
				// Never started.
			}
			hear.release();
			hear = null;
		}
		if (echoCanceler != null) {
			echoCanceler.release();
			echoCanceler = null;
		}
		for (MediaCodec codec : new MediaCodec[]{video, audio, voiceAudio}) {
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
		voiceAudio = null;
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

	@NonNull
	private static MediaCodec audioEncoder(int channels) throws IOException {
		MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
		MediaFormat format = MediaFormat.createAudioFormat(
				MediaFormat.MIMETYPE_AUDIO_AAC, RATE, channels);
		format.setInteger(MediaFormat.KEY_AAC_PROFILE,
				MediaCodecInfo.CodecProfileLevel.AACObjectLC);
		format.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE);
		format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * channels * 2 * 2);
		codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
		return codec;
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

	// Whether anything but the phone's own speaker is there to play into:
	// the voice played back through a speaker would howl.
	private boolean headphones() {
		AudioManager manager = context.getSystemService(AudioManager.class);
		if (manager == null) {
			return false;
		}
		for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
			switch (device.getType()) {
				case AudioDeviceInfo.TYPE_WIRED_HEADSET:
				case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
				case AudioDeviceInfo.TYPE_USB_HEADSET:
				case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
				case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
					return true;
				default:
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
							device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
						return true;
					}
			}
		}
		return false;
	}

	// Plays the voice back, as quickly as it can. As an assistant's speech:
	// the playback capture doesn't take that usage, so it isn't recorded
	// twice, and unlike voice-call usage it isn't what the voice mode's echo
	// canceller takes out of the microphone.
	@Nullable
	private AudioTrack hearTrack() {
		try {
			AudioTrack track = new AudioTrack.Builder()
					.setAudioAttributes(new AudioAttributes.Builder()
							.setUsage(AudioAttributes.USAGE_ASSISTANT)
							.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
							.build())
					.setAudioFormat(new AudioFormat.Builder()
							.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
							.setSampleRate(RATE)
							.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
							.build())
					.setBufferSizeInBytes(Math.max(CHUNK * 2 * 2, AudioTrack.getMinBufferSize(
							RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
					.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
					.setTransferMode(AudioTrack.MODE_STREAM)
					.build();
			if (track.getState() == AudioTrack.STATE_INITIALIZED) {
				hearChunk = new short[CHUNK];
				return track;
			}
			track.release();
		} catch (RuntimeException e) {
			Log.w(TAG, "can't play the voice back", e);
		}
		return null;
	}

	// The voice's latest chunk into the headphones, never waiting: what
	// doesn't fit is dropped rather than letting the delay grow.
	private void hear(@NonNull short[] chunk, int n) {
		if (hear == null) {
			return;
		}
		n = Math.min(n, hearChunk.length);
		for (int i = 0; i < n; ++i) {
			hearChunk[i] = (short) (chunk[i] * hearLevel);
		}
		hear.write(hearChunk, 0, n, AudioTrack.WRITE_NON_BLOCKING);
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
			if (clarity != null) {
				clarity.process(chunk, n);
			}
			hear(chunk, n);
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
		ByteBuffer voiceBytes = ByteBuffer.allocate(CHUNK * 2).order(ByteOrder.nativeOrder());
		int voiceFrames = 0;
		long samples = 0;
		long firstUs = -1;
		// What's playing's gain from ducking, and how long it's held.
		float duckGain = 1f;
		int hold = 0;
		MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
		MediaCodec.BufferInfo voiceInfo = new MediaCodec.BufferInfo();
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
				if (!stereo) {
					if (clarity != null) {
						clarity.process(chunk, n);
					}
					hear(chunk, n);
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
					voiceFrames = frames;
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
			long us = Math.max(0, firstUs) + samples * 1_000_000 / RATE;
			if (queue(audio, bytes, chunk, n, us, ended)) {
				samples += n / channels;
				if (voiceAudio != null) {
					// The voice as it went into the mix, at the same time.
					queue(voiceAudio, voiceBytes, voiceChunk, voiceFrames, us, false);
				}
			}
			if (ended && voiceAudio != null) {
				queue(voiceAudio, voiceBytes, voiceChunk, 0, us, true);
			}
			drain(audio, info, ended);
			if (voiceAudio != null) {
				drain(voiceAudio, voiceInfo, ended);
			}
		}
	}

	// Gives codec n samples of pcm (by way of bytes) at us, or its end
	// of stream; true if the samples went in.
	private static boolean queue(@NonNull MediaCodec codec, @NonNull ByteBuffer bytes,
			@NonNull short[] pcm, int n, long us, boolean ended) {
		int index = codec.dequeueInputBuffer(TIMEOUT_US * 10);
		if (index < 0) {
			return false;
		}
		ByteBuffer input = codec.getInputBuffer(index);
		if (ended || input == null) {
			codec.queueInputBuffer(index, 0, 0, us, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
			return false;
		}
		bytes.clear();
		bytes.asShortBuffer().put(pcm, 0, n);
		input.clear();
		input.put(bytes.array(), 0, Math.min(n * 2, input.remaining()));
		codec.queueInputBuffer(index, 0, n * 2, us, 0);
		return true;
	}

	private static short clip(int sample) {
		return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
	}

	// Clearer voice's chain (see LOW_CUT_HZ), mono, at RATE.
	private static final class VoiceClarity {
		private final Biquad lowCut = Biquad.highPass(LOW_CUT_HZ);
		private final Biquad mud = Biquad.peaking(MUD_HZ, 1, MUD_DB);
		private final Biquad presence = Biquad.highShelf(PRESENCE_HZ, PRESENCE_DB);
		private final double threshold = Math.pow(10, LEVEL_THRESHOLD_DB / 20);
		private final double makeup = Math.pow(10, LEVEL_MAKEUP_DB / 20);
		private final double attack = 1 - Math.exp(-1 / (LEVEL_ATTACK_S * RATE));
		private final double release = 1 - Math.exp(-1 / (LEVEL_RELEASE_S * RATE));
		// The level the leveller follows, full scale 1.
		private double envelope;

		// Processes the first n samples in place.
		void process(@NonNull short[] samples, int n) {
			for (int i = 0; i < n; ++i) {
				double x = samples[i] / 32768.0;
				x = presence.process(mud.process(lowCut.process(x)));
				double level = Math.abs(x);
				envelope += (level - envelope) * (level > envelope ? attack : release);
				double gain = makeup;
				if (envelope > threshold) {
					gain *= Math.pow(threshold / envelope, 1 - 1 / LEVEL_RATIO);
				}
				x *= gain;
				// Soft limit: straight up to LIMIT, then easing towards 1.
				double magnitude = Math.abs(x);
				if (magnitude > LIMIT) {
					x = Math.signum(x) * (LIMIT + (1 - LIMIT) *
							Math.tanh((magnitude - LIMIT) / (1 - LIMIT)));
				}
				samples[i] = clip((int) Math.round(x * 32767));
			}
		}
	}

	// An RBJ cookbook biquad, at RATE.
	private static final class Biquad {
		private final double b0;
		private final double b1;
		private final double b2;
		private final double a1;
		private final double a2;
		private double x1;
		private double x2;
		private double y1;
		private double y2;

		private Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
			this.b0 = b0 / a0;
			this.b1 = b1 / a0;
			this.b2 = b2 / a0;
			this.a1 = a1 / a0;
			this.a2 = a2 / a0;
		}

		// Butterworth (Q 0.707): nothing below hz.
		static Biquad highPass(double hz) {
			double w = 2 * Math.PI * hz / RATE;
			double cos = Math.cos(w);
			double alpha = Math.sin(w) / (2 * Math.sqrt(0.5));
			return new Biquad((1 + cos) / 2, -(1 + cos), (1 + cos) / 2,
					1 + alpha, -2 * cos, 1 - alpha);
		}

		// gainDb round hz, q wide.
		static Biquad peaking(double hz, double q, double gainDb) {
			double a = Math.pow(10, gainDb / 40);
			double w = 2 * Math.PI * hz / RATE;
			double cos = Math.cos(w);
			double alpha = Math.sin(w) / (2 * q);
			return new Biquad(1 + alpha * a, -2 * cos, 1 - alpha * a,
					1 + alpha / a, -2 * cos, 1 - alpha / a);
		}

		// gainDb above about hz (shelf slope 1).
		static Biquad highShelf(double hz, double gainDb) {
			double a = Math.pow(10, gainDb / 40);
			double w = 2 * Math.PI * hz / RATE;
			double cos = Math.cos(w);
			double root = 2 * Math.sqrt(a) * Math.sin(w) / 2 * Math.sqrt(2);
			return new Biquad(a * ((a + 1) + (a - 1) * cos + root),
					-2 * a * ((a - 1) + (a + 1) * cos),
					a * ((a + 1) + (a - 1) * cos - root),
					(a + 1) - (a - 1) * cos + root,
					2 * ((a - 1) - (a + 1) * cos),
					(a + 1) - (a - 1) * cos - root);
		}

		double process(double x) {
			double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
			x2 = x1;
			x1 = x;
			y2 = y1;
			y1 = y;
			return y;
		}
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
		int kind = isVideo ? Muxer.VIDEO : codec == audio ? Muxer.MIX : Muxer.VOICE;
		while (true) {
			int index;
			try {
				// (Not waiting when it's only what's ready: the audio thread
				// drains after every 20 ms chunk, two codecs with the voice
				// track, and waiting there made it fall behind and lose sound.)
				index = codec.dequeueOutputBuffer(info, toEnd ? TIMEOUT_US : 0);
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
				muxer.addTrack(kind, codec.getOutputFormat());
				// One thread drains both audio tracks: leave this one's
				// samples till later rather than wait in write() for the other's
				// format, which that wait would hold up.
				if (!toEnd && !muxer.isStarted()) {
					return;
				}
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
					muxer.write(kind, output, info);
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

	// A MediaMuxer started once every track's format is in, adding them
	// in a fixed order (video, mix, voice: players play the first audio
	// track); samples that come before that wait for it (briefly), and
	// ones that come later keep their order per track.
	private static final class Muxer {
		static final int VIDEO = 0;
		static final int MIX = 1;
		static final int VOICE = 2;

		private final MediaMuxer muxer;
		private final int tracks;
		private final MediaFormat[] formats = new MediaFormat[3];
		private final int[] track = {-1, -1, -1};
		private final long[] lastUs = {-1, -1, -1};
		private int added;
		private boolean started;
		private boolean wrote;

		Muxer(@NonNull MediaMuxer muxer, int tracks) {
			this.muxer = muxer;
			this.tracks = tracks;
		}

		synchronized void addTrack(int kind, @NonNull MediaFormat format) {
			if (formats[kind] != null) {
				return;
			}
			formats[kind] = format;
			if (++added == tracks) {
				for (int k = 0; k < formats.length; ++k) {
					if (formats[k] != null) {
						track[k] = muxer.addTrack(formats[k]);
					}
				}
				muxer.start();
				started = true;
				notifyAll();
			}
		}

		synchronized boolean isStarted() {
			return started;
		}

		synchronized void write(int kind, @NonNull ByteBuffer data,
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
			if (info.presentationTimeUs <= lastUs[kind]) {
				return;
			}
			lastUs[kind] = info.presentationTimeUs;
			muxer.writeSampleData(track[kind], data, info);
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
