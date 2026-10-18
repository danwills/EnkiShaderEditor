package enk.danwills.android.enkishadereditor.hardware;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
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
 * channel. Can capture from the microphone, from whatever the device is
 * currently playing back via a {@link MediaProjection} (API 29+), or from
 * one specific audio session (e.g. this app's own in-app MP3 player) via
 * a {@link Visualizer} - the latter needs no system-wide capture consent,
 * just the same RECORD_AUDIO permission the microphone path already does.
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

	// Bounds for the "full interval" mode's variable-length window: below
	// the minimum, wait for more samples rather than computing a
	// near-meaningless tiny FFT; above the maximum, drop the oldest
	// samples so a stall (e.g. a backgrounded app) can't make this grow
	// without bound or spike CPU usage once foregrounded again.
	private static final int MIN_INTERVAL_SAMPLES = 256;
	private static final int MAX_INTERVAL_SAMPLES = 16384;

	private static final ByteBuffer EMPTY_PIXELS =
			ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);

	private final short[] window = new short[FFT_SIZE];
	private final float[] smoothedSpectrum = new float[TEXTURE_WIDTH];
	private final Object intervalLock = new Object();

	// Below this, two reads are considered "no meaningful change" - real
	// audio signal has some noise floor jitter even when ostensibly
	// silent, and this avoids false positives from quantization noise.
	private static final short CHANGE_EPSILON = 24;
	// How recently both a nonzero sample and a change from the previous
	// read need to have been seen for isReceivingAudio() to consider the
	// capture healthy.
	private static final long HEALTH_WINDOW_NANOS = 1_500_000_000L;

	private short[] intervalSamples = new short[4096];
	private int intervalLength = 0;
	private boolean fullInterval = false;
	private short[] lastHealthSnapshot = new short[0];
	private volatile long lastNonZeroNanos = Long.MIN_VALUE;
	private volatile long lastChangeNanos = Long.MIN_VALUE;
	@Nullable
	private AudioRecord audioRecord;
	@Nullable
	private Thread recordingThread;
	@Nullable
	private MediaProjection mediaProjection;
	@Nullable
	private MediaProjection.Callback mediaProjectionCallback;
	@Nullable
	private Visualizer visualizer;
	private volatile boolean running = false;
	private volatile ByteBuffer spectrumPixels;

	// Selects how the audioSpectrum texture's timing works. Must be
	// called before register*() - ShaderToy-compatible (false, the
	// default) recomputes continuously from a short, fixed-size window;
	// full-interval (true) instead computes once per call to
	// getSpectrumPixels(), from everything captured since the previous
	// call, resized down to the fixed texture width.
	public void setFullIntervalMode(boolean fullInterval) {
		this.fullInterval = fullInterval;
	}

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

	// Captures raw PCM from one specific audio session (this app's own
	// in-app MP3 player, typically - see MainActivity's local-music-
	// player dialog) via a Visualizer, instead of a system-wide capture -
	// no MediaProjection consent needed, just RECORD_AUDIO (same as
	// registerMicrophone() above, and checked by the same caller before
	// either is ever tried). Visualizer's own waveform capture is
	// periodic (not a continuous stream) and 8-bit unsigned, unlike
	// AudioRecord's 16-bit signed - converted in processVisualizerWaveform()
	// before feeding the same sliding window + FFT every other source
	// already uses, so the visual result looks consistent regardless of
	// which source produced it. Its own built-in FFT capture is
	// deliberately unused for that same reason.
	public boolean registerVisualizer(int audioSessionId) {
		if (running) {
			return true;
		}
		try {
			Visualizer v = new Visualizer(audioSessionId);
			int[] captureSizeRange = Visualizer.getCaptureSizeRange();
			v.setCaptureSize(captureSizeRange[1]);
			v.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
				@Override
				public void onWaveFormDataCapture(
						Visualizer visualizer, byte[] waveform, int samplingRate) {
					processVisualizerWaveform(waveform);
				}

				@Override
				public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
					// Unused - see this method's own doc comment above.
				}
			}, Visualizer.getMaxCaptureRate() / 2, true, false);
			v.setEnabled(true);
			visualizer = v;
		} catch (RuntimeException e) {
			Log.e(TAG, "registerVisualizer failed", e);
			visualizer = null;
			return false;
		}
		running = true;
		return true;
	}

	private void processVisualizerWaveform(@NonNull byte[] waveform) {
		int count = waveform.length;
		short[] samples = new short[count];
		for (int i = 0; i < count; ++i) {
			samples[i] = (short) (((waveform[i] & 0xFF) - 128) << 8);
		}
		updateHealthSignal(samples, count);
		if (fullInterval) {
			appendIntervalSamples(samples, count);
			return;
		}
		int shift = Math.min(count, FFT_SIZE);
		int keep = FFT_SIZE - shift;
		if (keep > 0) {
			System.arraycopy(window, shift, window, 0, keep);
		}
		System.arraycopy(samples, count - shift, window, keep, shift);
		computeAndPublish();
	}

	public boolean isRunning() {
		return running;
	}

	// True if real signal has actually been flowing through recently -
	// nonzero samples that are also changing, not just a technically-
	// running AudioRecord that's gone silently dead (e.g. the system
	// revoked the projection without the onStop() callback firing).
	// Safe to call from any thread: the two timestamps it reads are
	// volatile, written only from the dedicated recording thread.
	public boolean isReceivingAudio() {
		long now = System.nanoTime();
		return running
				&& now - lastNonZeroNanos < HEALTH_WINDOW_NANOS
				&& now - lastChangeNanos < HEALTH_WINDOW_NANOS;
	}

	private void updateHealthSignal(@NonNull short[] buffer, int count) {
		boolean nonZero = false;
		boolean changed = count != lastHealthSnapshot.length;
		int compareLength = Math.min(count, lastHealthSnapshot.length);
		for (int i = 0; i < count; ++i) {
			if (buffer[i] != 0) {
				nonZero = true;
			}
			if (!changed && i < compareLength &&
					Math.abs(buffer[i] - lastHealthSnapshot[i]) > CHANGE_EPSILON) {
				changed = true;
			}
		}
		long now = System.nanoTime();
		if (nonZero) {
			lastNonZeroNanos = now;
		}
		if (changed) {
			lastChangeNanos = now;
		}
		if (lastHealthSnapshot.length != count) {
			lastHealthSnapshot = new short[count];
		}
		System.arraycopy(buffer, 0, lastHealthSnapshot, 0, count);
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
		if (visualizer != null) {
			visualizer.release();
			visualizer = null;
		}
	}

	@NonNull
	public ByteBuffer getSpectrumPixels() {
		if (fullInterval) {
			computeAndPublishInterval();
		}
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
				updateHealthSignal(readBuffer, read);
				if (fullInterval) {
					appendIntervalSamples(readBuffer, read);
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

	private void appendIntervalSamples(@NonNull short[] buffer, int count) {
		synchronized (intervalLock) {
			int newLength = intervalLength + count;
			if (newLength > MAX_INTERVAL_SAMPLES) {
				int drop = newLength - MAX_INTERVAL_SAMPLES;
				if (drop >= intervalLength) {
					intervalLength = 0;
				} else {
					System.arraycopy(intervalSamples, drop, intervalSamples, 0,
							intervalLength - drop);
					intervalLength -= drop;
				}
				newLength = intervalLength + count;
			}
			if (newLength > intervalSamples.length) {
				short[] grown = new short[Math.max(newLength, intervalSamples.length * 2)];
				System.arraycopy(intervalSamples, 0, grown, 0, intervalLength);
				intervalSamples = grown;
			}
			System.arraycopy(buffer, 0, intervalSamples, intervalLength, count);
			intervalLength += count;
		}
	}

	// Returns everything accumulated since the last call, or null if
	// too little has arrived yet to be worth computing a spectrum from
	// (the caller should just keep showing the previous frame's pixels
	// in that case, rather than publish a near-meaningless tiny window).
	@Nullable
	private short[] drainIntervalSamples() {
		synchronized (intervalLock) {
			if (intervalLength < MIN_INTERVAL_SAMPLES) {
				return null;
			}
			short[] drained = java.util.Arrays.copyOf(intervalSamples, intervalLength);
			intervalLength = 0;
			return drained;
		}
	}

	// Full-interval mode: computes the FFT over everything captured
	// since the previous call (instead of a short, fixed-size window),
	// zero-padded up to the next power of two, then resizes both the
	// resulting spectrum and the raw waveform down to TEXTURE_WIDTH via
	// block-averaging.
	private void computeAndPublishInterval() {
		short[] samples = drainIntervalSamples();
		if (samples == null) {
			return;
		}
		int n = samples.length;
		int fftSize = nextPowerOfTwo(n);
		double[] re = new double[fftSize];
		double[] im = new double[fftSize];
		for (int i = 0; i < n; ++i) {
			double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, n - 1));
			re[i] = (samples[i] / 32768.0) * hann;
		}
		fft(re, im);

		int rawBins = fftSize / 2;
		double[] magnitudes = new double[rawBins];
		for (int i = 0; i < rawBins; ++i) {
			magnitudes[i] = Math.hypot(re[i], im[i]) / (n / 4.0);
		}

		ByteBuffer pixels = ByteBuffer.allocateDirect(TEXTURE_WIDTH * TEXTURE_HEIGHT * 4);

		float[] resizedSpectrum = new float[TEXTURE_WIDTH];
		resizeAverage(magnitudes, rawBins, resizedSpectrum);
		for (int i = 0; i < TEXTURE_WIDTH; ++i) {
			double compressed = Math.sqrt(Math.min(resizedSpectrum[i], 1.0));
			smoothedSpectrum[i] += (float) ((compressed - smoothedSpectrum[i]) * SMOOTHING);
			putGray(pixels, toByte(smoothedSpectrum[i]));
		}

		double[] waveform = new double[n];
		for (int i = 0; i < n; ++i) {
			waveform[i] = samples[i] / 32768.0 * 0.5 + 0.5;
		}
		float[] resizedWaveform = new float[TEXTURE_WIDTH];
		resizeAverage(waveform, n, resizedWaveform);
		for (int i = 0; i < TEXTURE_WIDTH; ++i) {
			putGray(pixels, toByte(resizedWaveform[i]));
		}

		pixels.rewind();
		spectrumPixels = pixels;
	}

	private static int nextPowerOfTwo(int n) {
		int p = 1;
		while (p < n) {
			p <<= 1;
		}
		return p;
	}

	// Block-averages src[0, srcLen) down to exactly dst.length buckets.
	private static void resizeAverage(@NonNull double[] src, int srcLen, @NonNull float[] dst) {
		int dstLen = dst.length;
		for (int i = 0; i < dstLen; ++i) {
			int start = (int) ((long) i * srcLen / dstLen);
			int end = (int) ((long) (i + 1) * srcLen / dstLen);
			end = Math.max(end, start + 1);
			end = Math.min(end, srcLen);
			double sum = 0;
			int count = 0;
			for (int j = start; j < end; ++j) {
				sum += src[j];
				++count;
			}
			dst[i] = count > 0 ? (float) (sum / count) : (i > 0 ? dst[i - 1] : 0f);
		}
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
