package enk.danwills.android.enkishadereditor.hardware;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;

/**
 * Produces a 512x2 RGBA texture matching ShaderToy's audio input layout:
 * row 0 (v near 0.25) is the FFT magnitude spectrum across frequency bins,
 * row 1 (v near 0.75) is the raw waveform, both encoded 0..255 in every
 * channel. Can capture from the microphone or, on API 29+, from whatever
 * the device is currently playing back via a {@link MediaProjection}.
 */
public class AudioSpectrumListener {
	private static final String TAG = "AudioSpectrumListener";

	public static final int TEXTURE_WIDTH = 512;
	public static final int TEXTURE_HEIGHT = 2;

	private static final int SAMPLE_RATE = 44100; // Hz
	private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
	private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	private static final int FFT_SIZE = TEXTURE_WIDTH * 2; // 1024 samples -> 512 usable bins
	private static final int BUFFER_SIZE = Math.max(
			AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
			FFT_SIZE * 2);
	private static final float SMOOTHING = 0.6f;

	private static final ByteBuffer EMPTY_PIXELS =
			ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);

	private final short[] window = new short[FFT_SIZE];
	private final float[] smoothedSpectrum = new float[TEXTURE_WIDTH];

	@Nullable
	private AudioRecord audioRecord;
	@Nullable
	private Thread recordingThread;
	@Nullable
	private MediaProjection mediaProjection;
	@Nullable
	private MediaProjection.Callback mediaProjectionCallback;
	private volatile boolean running = false;
	private volatile ByteBuffer spectrumPixels;

	public boolean registerMicrophone(@NonNull Context context) {
		if (running) {
			return true;
		}
		if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
				!= PackageManager.PERMISSION_GRANTED) {
			return false;
		}
		try {
			audioRecord = new AudioRecord(
					MediaRecorder.AudioSource.MIC,
					SAMPLE_RATE,
					CHANNEL_CONFIG,
					AUDIO_FORMAT,
					BUFFER_SIZE);
		} catch (IllegalArgumentException | SecurityException e) {
			Log.e(TAG, "registerMicrophone failed", e);
			audioRecord = null;
			return false;
		}
		return startIfInitialized();
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	public boolean registerPlayback(@NonNull MediaProjection mediaProjection) {
		if (running) {
			return true;
		}
		AudioPlaybackCaptureConfiguration captureConfig =
				new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
						.addMatchingUsage(AudioAttributes.USAGE_MEDIA)
						.addMatchingUsage(AudioAttributes.USAGE_GAME)
						.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
						.build();
		AudioFormat format = new AudioFormat.Builder()
				.setEncoding(AUDIO_FORMAT)
				.setSampleRate(SAMPLE_RATE)
				.setChannelMask(CHANNEL_CONFIG)
				.build();
		try {
			audioRecord = new AudioRecord.Builder()
					.setAudioFormat(format)
					.setBufferSizeInBytes(BUFFER_SIZE)
					.setAudioPlaybackCaptureConfig(captureConfig)
					.build();
		} catch (IllegalArgumentException | UnsupportedOperationException |
				SecurityException e) {
			Log.e(TAG, "registerPlayback failed to build AudioRecord", e);
			audioRecord = null;
			return false;
		}
		if (!startIfInitialized()) {
			Log.e(TAG, "registerPlayback: AudioRecord built but failed to initialize/start");
			return false;
		}
		Log.i(TAG, "registerPlayback: system audio capture started");
		this.mediaProjection = mediaProjection;
		mediaProjectionCallback = new MediaProjection.Callback() {
			@Override
			public void onStop() {
				// The user stopped the capture (e.g. via the system
				// "Stop sharing" control); let the recording thread
				// notice and clean itself up on its next iteration.
				Log.i(TAG, "MediaProjection.Callback.onStop: projection revoked");
				running = false;
			}
		};
		mediaProjection.registerCallback(mediaProjectionCallback, new Handler(Looper.getMainLooper()));
		return true;
	}

	public void unregister() {
		running = false;
		if (recordingThread != null) {
			try {
				recordingThread.join(500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			recordingThread = null;
		}
		if (mediaProjection != null && mediaProjectionCallback != null) {
			mediaProjection.unregisterCallback(mediaProjectionCallback);
		}
		mediaProjection = null;
		mediaProjectionCallback = null;
		audioRecord = null;
	}

	@NonNull
	public ByteBuffer getSpectrumPixels() {
		ByteBuffer pixels = spectrumPixels;
		if (pixels == null) {
			pixels = EMPTY_PIXELS;
		}
		pixels.rewind();
		return pixels;
	}

	private boolean startIfInitialized() {
		AudioRecord record = audioRecord;
		if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
			if (record != null) {
				record.release();
			}
			audioRecord = null;
			return false;
		}
		running = true;
		recordingThread = new Thread(this::processAudioData, "AudioSpectrumListener");
		recordingThread.start();
		return true;
	}

	private void processAudioData() {
		AudioRecord record = audioRecord;
		if (record == null) {
			return;
		}
		try {
			record.startRecording();
			short[] readBuffer = new short[Math.max(FFT_SIZE, BUFFER_SIZE / 2)];
			while (running) {
				int read = record.read(readBuffer, 0, readBuffer.length);
				if (read <= 0) {
					continue;
				}
				int shift = Math.min(read, FFT_SIZE);
				int keep = FFT_SIZE - shift;
				if (keep > 0) {
					System.arraycopy(window, shift, window, 0, keep);
				}
				System.arraycopy(readBuffer, read - shift, window, keep, shift);
				computeAndPublish();
			}
		} finally {
			try {
				record.stop();
			} catch (IllegalStateException ignored) {
				// Not recording; nothing to stop.
			}
			record.release();
		}
	}

	private void computeAndPublish() {
		double[] re = new double[FFT_SIZE];
		double[] im = new double[FFT_SIZE];
		for (int i = 0; i < FFT_SIZE; ++i) {
			double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1));
			re[i] = (window[i] / 32768.0) * hann;
		}
		fft(re, im);

		ByteBuffer pixels = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);

		// Row 0: FFT magnitude spectrum, bin 0 (low frequencies) to bin
		// TEXTURE_WIDTH-1 (high frequencies), approximating ShaderToy's
		// audio spectrum row. The exact scaling ShaderToy itself uses
		// isn't public, so this applies a square-root compression (for
		// a less spiky, more usable dynamic range) plus light temporal
		// smoothing, which looks visually similar in practice.
		for (int i = 0; i < TEXTURE_WIDTH; ++i) {
			double magnitude = Math.hypot(re[i], im[i]) / (FFT_SIZE / 4.0);
			double compressed = Math.sqrt(Math.min(magnitude, 1.0));
			smoothedSpectrum[i] += (float) ((compressed - smoothedSpectrum[i]) * SMOOTHING);
			putGray(pixels, toByte(smoothedSpectrum[i]));
		}

		// Row 1: waveform, the most recent TEXTURE_WIDTH samples,
		// centered at 128 the same way ShaderToy encodes it.
		int waveformStart = FFT_SIZE - TEXTURE_WIDTH;
		for (int i = 0; i < TEXTURE_WIDTH; ++i) {
			int sample = window[waveformStart + i];
			putGray(pixels, toByte((float) (sample / 32768.0 * 0.5 + 0.5)));
		}

		pixels.rewind();
		spectrumPixels = pixels;
	}

	private static void putGray(@NonNull ByteBuffer buffer, byte value) {
		buffer.put(value).put(value).put(value).put((byte) 255);
	}

	private static byte toByte(float value01) {
		float clamped = Math.max(0f, Math.min(1f, value01));
		return (byte) Math.round(clamped * 255);
	}

	// Standard iterative radix-2 Cooley-Tukey FFT, in place on `re`/`im`.
	// `re.length` must be a power of two.
	private static void fft(@NonNull double[] re, @NonNull double[] im) {
		int n = re.length;
		for (int i = 1, j = 0; i < n; ++i) {
			int bit = n >> 1;
			for (; (j & bit) != 0; bit >>= 1) {
				j ^= bit;
			}
			j ^= bit;
			if (i < j) {
				double tempRe = re[i];
				re[i] = re[j];
				re[j] = tempRe;
				double tempIm = im[i];
				im[i] = im[j];
				im[j] = tempIm;
			}
		}
		for (int len = 2; len <= n; len <<= 1) {
			double angle = -2 * Math.PI / len;
			double wRe = Math.cos(angle);
			double wIm = Math.sin(angle);
			for (int start = 0; start < n; start += len) {
				double curRe = 1;
				double curIm = 0;
				int half = len / 2;
				for (int k = 0; k < half; ++k) {
					int a = start + k;
					int b = a + half;
					double uRe = re[a];
					double uIm = im[a];
					double vRe = re[b] * curRe - im[b] * curIm;
					double vIm = re[b] * curIm + im[b] * curRe;
					re[a] = uRe + vRe;
					im[a] = uIm + vIm;
					re[b] = uRe - vRe;
					im[b] = uIm - vIm;
					double nextCurRe = curRe * wRe - curIm * wIm;
					double nextCurIm = curRe * wIm + curIm * wRe;
					curRe = nextCurRe;
					curIm = nextCurIm;
				}
			}
		}
	}
}
