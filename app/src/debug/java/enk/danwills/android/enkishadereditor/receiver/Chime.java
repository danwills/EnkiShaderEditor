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
				var track = new AudioTrack.Builder()
						.setAudioAttributes(new AudioAttributes.Builder()
								.setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
								.setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
						.setAudioFormat(new AudioFormat.Builder()
								.setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
								.setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
						.setTransferMode(AudioTrack.MODE_STATIC)
						.setBufferSizeInBytes(n * 2).build();
				track.write(pcm, 0, n);
				track.play();
				Thread.sleep((long) (1000.0 * n / rate) + 200);
				track.release();
			} catch (RuntimeException | InterruptedException e) {
				Log.w("Chime", "No chime", e);
			}
		}).start();
	}
}
