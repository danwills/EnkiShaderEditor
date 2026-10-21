package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;

// What one of the app's own players (a Sound pass, a tracker module) is
// playing, kept as mono for audioSpectrum - see BuiltinAudioUniforms. The
// player writes each block as it hands it to its track, then says how
// much of what it's written is still to be heard, so a reader gets
// what's coming out of the speaker now rather than what's queued.
final class OwnSoundTap {
	// Holds the queued part plus the longest look back (16384 samples,
	// AudioSpectrumListener's full-interval maximum) with room to spare.
	private static final int RING = 1 << 16;
	private static final long PLAYING_NANOS = 300_000_000L;

	private final short[] ring = new short[RING];
	// Samples written since the last reset(), and of those the ones heard.
	private volatile long written;
	private volatile long heard;
	// When `heard` last moved (0 never), and bumped by reset().
	private volatile long movedNanos;
	private volatile int epoch;

	// Player thread: `frames` stereo samples from `stereo`.
	void write(@NonNull short[] stereo, int frames) {
		long w = written;
		for (int i = 0; i < frames; ++i) {
			ring[(int) ((w + i) & (RING - 1))] =
					(short) ((stereo[i * 2] + stereo[i * 2 + 1]) >> 1);
		}
		written = w + frames;
	}

	// Player thread: of what's been written, how much the track has yet
	// to play.
	void setPending(long frames) {
		long h = Math.max(0, written - Math.max(0, frames));
		if (h != heard) {
			heard = h;
			movedNanos = System.nanoTime();
		}
	}

	// Player thread: back to nothing written (a restart or a new track).
	void reset() {
		written = 0;
		heard = 0;
		movedNanos = 0;
		++epoch;
	}

	// Whether something's been heard lately - paused or stopped, it isn't.
	boolean isPlaying() {
		long moved = movedNanos;
		return moved != 0 && System.nanoTime() - moved < PLAYING_NANOS;
	}

	long getHeard() {
		return heard;
	}

	int getEpoch() {
		return epoch;
	}

	// The `count` samples before `end` (at most heard, and at most
	// 16384 back), into out.
	void read(long end, int count, @NonNull short[] out) {
		long from = end - count;
		for (int i = 0; i < count; ++i) {
			out[i] = ring[(int) ((from + i) & (RING - 1))];
		}
	}
}
