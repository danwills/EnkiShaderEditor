package enk.danwills.android.enkishadereditor.receiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

// Soft synthesised chimes, so the kit's moments don't sound like any other
// notification (ToneGenerator's are the phone's sharp telephone beeps):
// struck-marimba notes, each a sine with some of its overtones (a phone's
// speaker can hardly play the low fundamentals by themselves), eased in
// over 25 ms and dying away.
public final class Chime {
	private Chime() {}

	private static final double C4 = 261.63, EB4 = 311.13, G4 = 392.00;

	/** The run's over: G falling to C, a fifth landing home, settled and content. */
	public static void finished() { play(new double[] {G4, C4}, 0.22, 0.9, 0.45); }
	/** It stopped (went badly, or was stopped): E-flat down to C, a minor third, a little downcast. */
	public static void stopped() { play(new double[] {EB4, C4}, 0.22, 0.9, 0.45); }
	/** Asking whether Claude can use the phone: a quick C up to G, to catch an ear. */
	public static void asking() { play(new double[] {C4, G4}, 0.12, 0.6, 0.35); }

	/**
	 * A thumbs up/down question: a short low chirp rising from C to G, like
	 * a questioning "eh?".
	 */
	public static void wondering() {
		new Thread(() -> {
			final int rate = 44100;
			final double len = 0.2;
			int n = (int) (rate * len);
			short[] pcm = new short[n];
			double phase = 0;
			for (int i = 0; i < n; i++) {
				double t = i / (double) rate;
				double rise = Math.max(0, Math.min(1, (t - 0.02) / 0.12));
				rise = rise * rise * (3 - 2 * rise);
				double f = C4 * Math.pow(G4 / C4, rise);
				phase += 2 * Math.PI * f / rate;
				double env = Math.min(1, t / 0.015) * Math.min(1, (len - t) / 0.06);
				double v = env * (Math.sin(phase) + 0.4 * Math.sin(2 * phase) + 0.15 * Math.sin(3 * phase));
				pcm[i] = (short) (v * 0.3 * 32767);
			}
			pcmPlay(pcm, rate);
		}).start();
	}

	/**
	 * Go ahead tapped: a car starting. The starter cranks (a motor's whine,
	 * chugging ~9 times a second), the engine catches, revs twice ("broom
	 * broom") and settles to idle. The engine's firing is low (30-80 Hz, more
	 * than a phone's speaker can play), so it's a stack of its harmonics,
	 * every other firing a little weaker, with some rough noise under it.
	 */
	public static void starting() {
		new Thread(() -> {
			final int rate = 44100;
			final double len = 1.9, caught = 0.38;
			int n = (int) (rate * len);
			short[] pcm = new short[n];
			var random = new java.util.Random(7);
			double phase = 0, crank = 0, rough = 0, peak = 1e-9;
			double[] out = new double[n];
			for (int i = 0; i < n; i++) {
				double t = i / (double) rate;
				double v;
				if (t < caught) {
					// The starter: a whine pulsing as each cylinder comes up on compression.
					crank += 2 * Math.PI * 9.0 / rate;
					double pulse = Math.pow(0.5 + 0.5 * Math.sin(crank), 3);
					double w = 2 * Math.PI * 170 * t;
					v = 0.45 * pulse * (Math.sin(w) + 0.4 * Math.sin(2 * w) + 0.2 * Math.sin(3 * w)) * Math.min(1, t / 0.03);
				} else {
					double u = t - caught;
					// Two revs, the second clearly higher, each up quickly and coasting down.
					double rev = bump(u, 0.18, 0.07, 0.22) + 1.7 * bump(u, 0.62, 0.08, 0.32);
					double f = 26 * (1 + 1.6 * rev);
					phase += 2 * Math.PI * f / rate;
					double harm = 0;
					// Mostly the low harmonics: bassy, not buzzy.
					for (int k = 1; k <= 10; k++) harm += Math.sin(k * phase) * Math.exp(-k * 0.3) / Math.sqrt(k);
					double uneven = 0.75 + 0.25 * Math.sin(phase / 2);
					rough += (random.nextGaussian() - rough) * 0.03;   // low, rumbling noise (only a little: more sounded airy)
					double env = Math.min(1, u / 0.04) * Math.min(1, (len - t) / 0.35);
					v = env * (0.55 + 0.45 * Math.min(rev, 1)) * (uneven * harm + 0.25 * rough);
				}
				out[i] = v;
				peak = Math.max(peak, Math.abs(v));
			}
			for (int i = 0; i < n; i++) pcm[i] = (short) (out[i] / peak * 0.5 * 32767);
			pcmPlay(pcm, rate);
		}).start();
	}

	/** A rise to 1 at `at` over `up` s, easing back down over `down`. */
	private static double bump(double t, double at, double up, double down) {
		double x = (t - at) / (t < at ? up : down);
		return Math.exp(-x * x);
	}

	private static void pcmPlay(short[] pcm, int rate) {
		try {
			var track = new AudioTrack.Builder()
					.setAudioAttributes(new AudioAttributes.Builder()
							.setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
							.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
					.setAudioFormat(new AudioFormat.Builder()
							.setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
							.setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
					.setTransferMode(AudioTrack.MODE_STATIC)
					.setBufferSizeInBytes(pcm.length * 2).build();
			track.write(pcm, 0, pcm.length);
			track.play();
			Thread.sleep((long) (1000.0 * pcm.length / rate) + 200);
			track.release();
		} catch (RuntimeException | InterruptedException e) {
			Log.w("Chime", "No chime", e);
		}
	}

	private static void play(double[] notes, double gap, double ring, double volume) {
		new Thread(() -> {
			try {
				final int rate = 44100;
				int n = (int) (rate * (gap * (notes.length - 1) + ring));
				short[] pcm = new short[n];
				for (int k = 0; k < notes.length; k++) {
					int start = (int) (rate * gap * k);
					for (int i = start; i < n; i++) {
						double t = (i - start) / (double) rate;
						double env = Math.min(1, t / 0.025) * Math.exp(-t * 5.0);
						double w = 2 * Math.PI * notes[k] * t;
						// The overtones die away faster than the note, so it warms as it fades.
						double v = env * (Math.sin(w) + Math.exp(-t * 4.0) * (0.5 * Math.sin(2 * w) + 0.25 * Math.sin(3 * w)));
						pcm[i] = (short) Math.max(-32767, Math.min(32767, pcm[i] + v * volume * 0.6 * 32767));
					}
				}
				pcmPlay(pcm, rate);
			} catch (RuntimeException e) {
				Log.w("Chime", "No chime", e);
			}
		}).start();
	}
}
