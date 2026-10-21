package enk.danwills.android.enkishadereditor.opengl;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.opengl.GLES20;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import ibxm.Channel;
import ibxm.IBXM;
import ibxm.Module;
import ibxm.Note;
import ibxm.Pattern;

// Plays a tracker module (MOD, S3M, XM - see ModuleDao) for a shader
// with a `///mod:name` line, with IBXM, on a thread of its own, and
// shows the shader what's being heard:
// - `moduleInfo` (64 x 72, RGBA bytes): row 0 is the song position -
//   texel 0 (order, orders in the song, row, rows in the pattern),
//   texel 1 (speed, tempo, channels, pattern), texel 2 (ticks into the
//   row, 0, 0, 0); row 1 is each channel x as (note, instrument, volume,
//   panning), the note 0 for none and 1 = C-0; row 2 each channel's
//   (frequency's high byte, low byte, key held, 0). Rows 8-71 are the
//   pattern rows from 24 before the playing one to 39 after: texel x < 32
//   is channel x's (note, instrument, volume column, effect), texel 32 + x
//   its (effect parameter, 255, 0, 0).
// - `moduleScope` (512 x 64, R): rows 0-31 each channel's last 512
//   samples (0.5 is silence), rows 32-63 a 64-band spectrum of the same
//   in R, low to high.
// - `moduleTime` (seconds) and `moduleRow` (the pattern row, with how
//   far through it as the fraction).
// What's shown matches what's heard: snapshots are taken per tick as
// the audio's made, and the one the speaker's reached is shown.
final class ModulePlayer {
	private static final String TAG = "ModulePlayer";
	static final int SAMPLE_RATE = 44100;
	static final int INFO_WIDTH = 64;
	static final int INFO_HEIGHT = 72;
	static final int SCOPE_WIDTH = 512;
	static final int SCOPE_HEIGHT = 64;
	private static final int MAX_CHANNELS = 32;
	private static final int WINDOW_BEFORE = 24;
	private static final int WINDOW_ROWS = 64;
	private static final int SNAPSHOTS = 512;
	private static final int SCOPE_RING = 16384;
	private static final int BANDS = 64;
	private static final float TRACK_SECONDS = 0.12f;
	private static final int DFT_LENGTH = 256;
	// Each band's frequency (40 Hz to 16 kHz, log-spaced) as cosines and
	// sines over the DFT's length, with a Hann window folded in to keep
	// the bands apart - see fillScope().
	private static final float[][] BAND_COS = new float[BANDS][DFT_LENGTH];
	private static final float[][] BAND_SIN = new float[BANDS][DFT_LENGTH];

	static {
		for (int b = 0; b < BANDS; ++b) {
			double f = 40.0 * Math.pow(400.0, b / (double) (BANDS - 1));
			double w = 2 * Math.PI * f / SAMPLE_RATE;
			for (int x = 0; x < DFT_LENGTH; ++x) {
				double hann = 0.5 - 0.5 * Math.cos(2 * Math.PI * x / (DFT_LENGTH - 1));
				BAND_COS[b][x] = (float) (Math.cos(w * x) * hann);
				BAND_SIN[b][x] = (float) (Math.sin(w * x) * hann);
			}
		}
	}

	// What was playing at one tick - see snapshot().
	private static final class Snapshot {
		long sample;
		int seqPos, row, tickInRow, speed, tempo;
		final int[] note = new int[MAX_CHANNELS];
		final int[] instrument = new int[MAX_CHANNELS];
		final int[] volume = new int[MAX_CHANNELS];
		final int[] panning = new int[MAX_CHANNELS];
		final int[] frequency = new int[MAX_CHANNELS];
		final boolean[] keyOn = new boolean[MAX_CHANNELS];
	}

	@NonNull
	private final String name;
	@NonNull
	private final Module module;
	private final int channels;
	private final Snapshot[] snapshots = new Snapshot[SNAPSHOTS];
	private final short[][] scopes = new short[MAX_CHANNELS][SCOPE_RING];
	// The whole mix, for audioSpectrum.
	private final OwnSoundTap tap = new OwnSoundTap();
	// Written by the player thread.
	private volatile long made;
	private volatile long heard;
	private volatile int snapshotCount;
	private volatile boolean paused;
	private volatile boolean restartWanted;
	private volatile boolean stopped;
	@Nullable
	private Thread thread;

	// GL thread only.
	@Nullable
	private GlTexture2D infoTexture;
	@Nullable
	private GlTexture2D scopeTexture;
	private final ByteBuffer info = ByteBuffer.allocateDirect(INFO_WIDTH * INFO_HEIGHT * 4)
			.order(ByteOrder.nativeOrder());
	private final ByteBuffer scope = ByteBuffer.allocateDirect(SCOPE_WIDTH * SCOPE_HEIGHT * 4)
			.order(ByteOrder.nativeOrder());
	private final float[] window = new float[DFT_LENGTH];
	private float time;
	private float row;

	private ModulePlayer(@NonNull String name, @NonNull Module module) {
		this.name = name;
		this.module = module;
		channels = Math.min(module.numChannels, MAX_CHANNELS);
		for (int i = 0; i < SNAPSHOTS; ++i) {
			snapshots[i] = new Snapshot();
		}
	}

	// Null if `data` isn't a module IBXM can play.
	@Nullable
	static ModulePlayer create(@NonNull String name, @NonNull byte[] data) {
		try {
			return new ModulePlayer(name, new Module(data));
		} catch (Exception e) {
			Log.w(TAG, "Can't play " + name, e);
			return null;
		}
	}

	@NonNull
	String getName() {
		return name;
	}

	@NonNull
	OwnSoundTap getTap() {
		return tap;
	}

	void start() {
		if (thread == null) {
			thread = new Thread(this::play, "ModulePlayer");
			thread.start();
		}
	}

	void setPaused(boolean paused) {
		this.paused = paused;
	}

	// Back to the start of the song.
	void restart() {
		restartWanted = true;
	}

	void stop() {
		stopped = true;
		if (thread != null) {
			thread.interrupt();
			thread = null;
		}
	}

	float getTime() {
		return time;
	}

	float getRow() {
		return row;
	}

	// The player thread: makes the song a tick at a time, feeding the
	// track (which blocks as it fills) and keeping a snapshot and each
	// channel's output for every tick.
	private void play() {
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
		IBXM ibxm = new IBXM(module, SAMPLE_RATE);
		int[] mix = new int[ibxm.getMixBufferLength()];
		int[] channelBuf = new int[ibxm.getMixBufferLength()];
		int[][] channelOut = new int[channels][ibxm.getMixBufferLength() / 2];
		short[] out = new short[ibxm.getMixBufferLength()];
		AudioTrack track = newTrack();
		if (track == null) {
			return;
		}
		long base = 0;
		boolean playing = false;
		try {
			while (!stopped) {
				if (restartWanted) {
					restartWanted = false;
					track.pause();
					track.flush();
					playing = false;
					ibxm.setSequencePos(0);
					base = track.getPlaybackHeadPosition() & 0xffffffffL;
					made = 0;
					heard = 0;
					snapshotCount = 0;
					tap.reset();
				}
				if (paused) {
					if (playing) {
						track.pause();
						playing = false;
					}
					Thread.sleep(20);
					continue;
				}
				snapshot(ibxm, made);
				int count = ibxm.getAudio(mix, channelOut, channelBuf);
				for (int i = 0; i < count * 2; ++i) {
					out[i] = (short) Math.max(-32768, Math.min(32767, mix[i]));
				}
				for (int c = 0; c < channels; ++c) {
					short[] ring = scopes[c];
					int[] src = channelOut[c];
					for (int i = 0; i < count; ++i) {
						ring[(int) ((made + i) % SCOPE_RING)] =
								(short) Math.max(-32768, Math.min(32767, src[i]));
					}
				}
				made += count;
				tap.write(out, count);
				int done = track.write(out, 0, count * 2, AudioTrack.WRITE_BLOCKING);
				if (done < 0) {
					Log.w(TAG, "Track write failed (" + done + "), making a new one");
					track.release();
					track = newTrack();
					if (track == null) {
						return;
					}
					base = -made;
					playing = false;
					continue;
				}
				if (!playing) {
					track.play();
					playing = true;
				}
				heard = (track.getPlaybackHeadPosition() & 0xffffffffL) - base;
				tap.setPending(made - heard);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			track.pause();
			track.flush();
			track.release();
		}
	}

	private void snapshot(@NonNull IBXM ibxm, long sample) {
		Snapshot s = snapshots[snapshotCount % SNAPSHOTS];
		s.sample = sample;
		s.seqPos = ibxm.getSequencePos();
		s.row = ibxm.getRow();
		s.speed = ibxm.getSpeed();
		s.tempo = ibxm.getTempo();
		s.tickInRow = Math.max(0, ibxm.getSpeed() - ibxm.getTick());
		for (int c = 0; c < channels; ++c) {
			Channel ch = ibxm.getChannel(c);
			s.note[c] = ch.getNoteKey();
			s.instrument[c] = ch.getNoteInstrument();
			s.volume[c] = Math.min(255, ch.getAmplitude() >> 6);
			s.panning[c] = Math.max(0, Math.min(255, ch.getPanning()));
			s.frequency[c] = ch.getFrequency();
			s.keyOn[c] = ch.isKeyOn();
		}
		snapshotCount = snapshotCount + 1;
	}

	@Nullable
	private static AudioTrack newTrack() {
		int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
				AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
		int size = Math.max(min, (int) (TRACK_SECONDS * SAMPLE_RATE) * 4);
		try {
			return new AudioTrack.Builder()
					.setAudioAttributes(new AudioAttributes.Builder()
							.setUsage(AudioAttributes.USAGE_MEDIA)
							.setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
							.build())
					.setAudioFormat(new AudioFormat.Builder()
							.setSampleRate(SAMPLE_RATE)
							.setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
							.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
							.build())
					.setBufferSizeInBytes(size)
					.setTransferMode(AudioTrack.MODE_STREAM)
					.build();
		} catch (IllegalArgumentException | UnsupportedOperationException e) {
			Log.e(TAG, "No audio track", e);
			return null;
		}
	}

	// Fills the textures with what's being heard now. GL thread only.
	void update(@NonNull GlDevice device) {
		if (infoTexture == null) {
			infoTexture = newTexture(device, INFO_WIDTH, INFO_HEIGHT);
			scopeTexture = newTexture(device, SCOPE_WIDTH, SCOPE_HEIGHT);
		}
		long at = heard;
		Snapshot s = heardSnapshot(at);
		time = at / (float) SAMPLE_RATE;
		info.clear();
		for (int i = 0; i < INFO_WIDTH * INFO_HEIGHT * 4; ++i) {
			info.put(i, (byte) 0);
		}
		if (s != null) {
			fillInfo(s);
			float tickLength = s.tempo > 0 ? SAMPLE_RATE * 5f / (s.tempo * 2f) : SAMPLE_RATE;
			float intoTick = Math.min(1f, (at - s.sample) / tickLength);
			row = s.row + Math.min(0.999f, (s.tickInRow + intoTick) / Math.max(1, s.speed));
		}
		fillScope(at);
		device.updateTexture2D(infoTexture, info);
		device.updateTexture2D(scopeTexture, scope);
	}

	void bind(@NonNull ProgramBindings bindings) {
		if (infoTexture != null) {
			bindings.setTexture(ShaderRenderer.UNIFORM_MODULE_INFO, infoTexture);
			bindings.setTexture(ShaderRenderer.UNIFORM_MODULE_SCOPE, scopeTexture);
		}
		bindings.setFloat(ShaderRenderer.UNIFORM_MODULE_TIME, time);
		bindings.setFloat(ShaderRenderer.UNIFORM_MODULE_ROW, row);
	}

	void releaseTextures(@NonNull GlDevice device) {
		device.deleteTexture(infoTexture);
		device.deleteTexture(scopeTexture);
		discardTextures();
	}

	void discardTextures() {
		infoTexture = null;
		scopeTexture = null;
	}

	// The latest snapshot at or before sample `at`.
	@Nullable
	private Snapshot heardSnapshot(long at) {
		int count = snapshotCount;
		for (int k = count - 1; k >= Math.max(0, count - SNAPSHOTS + 1); --k) {
			Snapshot s = snapshots[k % SNAPSHOTS];
			if (s.sample <= at) {
				return s;
			}
		}
		return count > 0 ? snapshots[Math.max(0, count - SNAPSHOTS + 1) % SNAPSHOTS] : null;
	}

	private void fillInfo(@NonNull Snapshot s) {
		int sequence = Math.max(1, module.sequenceLength);
		int seqPos = Math.max(0, Math.min(s.seqPos, sequence - 1));
		int patternIndex = module.sequence[seqPos];
		Pattern pattern = patternIndex < module.numPatterns ? module.patterns[patternIndex] : null;
		putTexel(0, 0, seqPos, sequence, s.row, pattern != null ? pattern.numRows : 0);
		putTexel(1, 0, s.speed, s.tempo, channels, patternIndex);
		putTexel(2, 0, s.tickInRow, 0, 0, 0);
		for (int c = 0; c < channels; ++c) {
			putTexel(c, 1, s.note[c], s.instrument[c], s.volume[c], s.panning[c]);
			putTexel(c, 2, (s.frequency[c] >> 8) & 255, s.frequency[c] & 255,
					s.keyOn[c] ? 255 : 0, 0);
		}
		// The pattern rows around the playing one, across pattern ends.
		Note note = new Note();
		int pos = seqPos;
		int r = s.row - WINDOW_BEFORE;
		while (r < 0) {
			pos = (pos - 1 + sequence) % sequence;
			r += rowsAt(pos);
		}
		for (int i = 0; i < WINDOW_ROWS; ++i) {
			int rows = rowsAt(pos);
			while (r >= rows && rows > 0) {
				r -= rows;
				pos = (pos + 1) % sequence;
				rows = rowsAt(pos);
			}
			int index = module.sequence[pos];
			if (index >= module.numPatterns || rows == 0) {
				break;
			}
			Pattern p = module.patterns[index];
			for (int c = 0; c < channels; ++c) {
				p.getNote(r * module.numChannels + c, note);
				putTexel(c, 8 + i, note.key, note.instrument, note.volume, note.effect);
				putTexel(32 + c, 8 + i, note.param, 255, 0, 0);
			}
			++r;
		}
	}

	private int rowsAt(int pos) {
		int index = module.sequence[pos];
		return index < module.numPatterns ? module.patterns[index].numRows : 0;
	}

	private void putTexel(int x, int y, int a, int b, int c, int d) {
		int at = (y * INFO_WIDTH + x) * 4;
		info.put(at, (byte) a);
		info.put(at + 1, (byte) b);
		info.put(at + 2, (byte) c);
		info.put(at + 3, (byte) d);
	}

	// Each channel's last SCOPE_WIDTH samples heard, and a spectrum of
	// the last 256 - a plain DFT at 64 log-spaced bands, cheap enough at
	// this size.
	private void fillScope(long at) {
		scope.clear();
		for (int i = 0; i < SCOPE_WIDTH * SCOPE_HEIGHT * 4; ++i) {
			scope.put(i, (byte) 0);
		}
		long start = Math.max(0, at - SCOPE_WIDTH);
		for (int c = 0; c < channels; ++c) {
			short[] ring = scopes[c];
			for (int x = 0; x < SCOPE_WIDTH; ++x) {
				long i = start + x;
				int v = i < at ? ring[(int) (i % SCOPE_RING)] : 0;
				put(x, c, (int) (128 + v / 256f * 1.6f));
			}
			for (int x = 0; x < window.length; ++x) {
				long i = at - window.length + x;
				window[x] = i >= 0 ? ring[(int) (i % SCOPE_RING)] / 32768f : 0f;
			}
			for (int b = 0; b < BANDS; ++b) {
				float[] cos = BAND_COS[b];
				float[] sin = BAND_SIN[b];
				float re = 0f;
				float im = 0f;
				for (int x = 0; x < window.length; ++x) {
					re += window[x] * cos[x];
					im -= window[x] * sin[x];
				}
				float magnitude = (float) Math.sqrt(re * re + im * im) / (window.length / 4f);
				// In decibels, from -60 to 0.
				float db = 20f * (float) Math.log10(Math.max(magnitude, 1e-6f));
				int level = (int) Math.max(0f, Math.min(255f, (db + 60f) / 60f * 255f));
				for (int x = 0; x < SCOPE_WIDTH / BANDS; ++x) {
					put(b * (SCOPE_WIDTH / BANDS) + x, 32 + c, level);
				}
			}
		}
	}

	private void put(int x, int y, int v) {
		int at = (y * SCOPE_WIDTH + x) * 4;
		byte b = (byte) Math.max(0, Math.min(255, v));
		scope.put(at, b);
		scope.put(at + 1, b);
		scope.put(at + 2, b);
		scope.put(at + 3, (byte) 255);
	}

	@NonNull
	private static GlTexture2D newTexture(@NonNull GlDevice device, int width, int height) {
		var texture = device.createTexture2D();
		device.allocateTexture2D(texture, width, height);
		device.applyTextureParameters(texture, new TextureParameters(
				GLES20.GL_NEAREST, GLES20.GL_NEAREST,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		return texture;
	}
}
