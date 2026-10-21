package enk.danwills.android.enkishadereditor.opengl;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

// Plays a shader's Sound pass: a buffer whose source defines ShaderToy's
// `vec2 mainSound(int samp, float time)` (see ShaderSourcePreparer.
// isSoundSource()). Each frame, on the GL thread, render() makes however
// many samples are short of `ahead` seconds of sound into a small RGBA8
// target - one texel per stereo sample, BLOCK_WIDTH to a row, each
// channel's -1..1 as 16 bits in two bytes - reads them back and hands
// them to a writer thread of its own, which feeds them to an AudioTrack.
// The GL thread never touches the track: an audio stall (a Bluetooth
// route change, say) can't hold up the picture, and a track that dies
// is made afresh by the writer. Sample numbers count up from the
// shader's start (or restart), so a sound's time lines up with `iTime`.
final class SoundOutput {
	private static final String TAG = "SoundOutput";
	static final int SAMPLE_RATE = 44100;
	static final int BLOCK_WIDTH = 512;
	// At most this many rows a frame (16 x 512 samples = 186 ms), so a
	// stall can't make one frame render seconds of sound.
	private static final int MAX_ROWS = 16;
	// How far ahead of what's playing the sound is made, and how much
	// that grows (to MAX_AHEAD) each time the track runs dry - a slow
	// shader on a slow phone settles on what it needs.
	private static final float START_AHEAD = 0.1f;
	private static final float MAX_AHEAD = 0.3f;
	private static final float AHEAD_STEP = 0.03f;
	private static final float TRACK_SECONDS = 0.1f;
	static final String UNIFORM_OFFSET = "enkiSoundOffset";
	private static final long LOG_INTERVAL_MS = 5000;

	// One block of samples for the writer, or a command.
	private record Chunk(@Nullable short[] samples, int length, int command, long generation) {
	}

	private static final int PLAY = 0;
	private static final int PAUSE = 1;
	private static final int FLUSH = 2;
	private static final int STOP = 3;

	// GL thread only.
	@Nullable
	private GlTexture2D texture;
	@Nullable
	private GlFramebuffer framebuffer;
	private final ByteBuffer pixels = ByteBuffer
			.allocateDirect(BLOCK_WIDTH * MAX_ROWS * 4)
			.order(ByteOrder.nativeOrder());
	@Nullable
	private Thread writer;
	// See readBack(): the pixel buffer (0 none yet, -1 can't), and the
	// block in it waiting to be collected.
	private int packBuffer;
	private int pendingCount;
	private long pendingGeneration;

	// Samples made since the start (GL thread writes), and of those the
	// ones the writer has handed to the track (writer writes).
	private volatile long made;
	private volatile long queued;
	// Bumped by restart() and stop(): chunks made before are dropped.
	private volatile long generation;
	private volatile float ahead = START_AHEAD;
	private volatile boolean playing;

	private final LinkedBlockingQueue<Chunk> chunks = new LinkedBlockingQueue<>();
	// What's heard, for audioSpectrum - writer thread.
	private final OwnSoundTap tap = new OwnSoundTap();

	@NonNull
	OwnSoundTap getTap() {
		return tap;
	}

	// Makes and queues what's short. GL thread only; `bindings` already
	// hold this frame's uniforms and channels for `program`.
	void render(@NonNull GlDevice device, @NonNull ShaderRenderPipeline pipeline,
			@NonNull ProgramBindings bindings, @NonNull GlProgram program) {
		ensureWriter();
		if (!ensureTarget(device)) {
			return;
		}
		// Last frame's block first: by now the GPU has made it, so
		// mapping it doesn't wait (see readBack()).
		collectPending();
		long want = queued + pendingCount + (long) ((ahead + TRACK_SECONDS) * SAMPLE_RATE) -
				made;
		if (want > 0) {
			int rows = (int) Math.min(MAX_ROWS, (want + BLOCK_WIDTH - 1) / BLOCK_WIDTH);
			bindings.setInt(UNIFORM_OFFSET, (int) made);
			pipeline.renderPassRows(bindings, program, framebuffer, BLOCK_WIDTH, rows, 0, rows);
			readBack(rows);
			device.bindFramebuffer(null);
			made += (long) rows * BLOCK_WIDTH;
		}
		if (!playing) {
			playing = true;
			chunks.add(new Chunk(null, 0, PLAY, generation));
		}
	}

	// Reads `rows` of the target back. With a pixel buffer (GLES 3) the
	// copy happens on the GPU, and collectPending() maps it next frame -
	// a plain glReadPixels makes the CPU wait for every draw queued so
	// far, which with a heavy picture costs whole frames. Without one,
	// it's read straight away.
	private void readBack(int rows) {
		if (packBuffer == 0) {
			int[] id = new int[1];
			GLES30.glGenBuffers(1, id, 0);
			packBuffer = id[0];
			GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, packBuffer);
			GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, BLOCK_WIDTH * MAX_ROWS * 4, null,
					GLES30.GL_STREAM_READ);
			GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
			if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
				packBuffer = -1;
			}
		}
		if (packBuffer > 0) {
			GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, packBuffer);
			GLES30.glReadPixels(0, 0, BLOCK_WIDTH, rows, GLES20.GL_RGBA,
					GLES20.GL_UNSIGNED_BYTE, 0);
			GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
			pendingCount = rows * BLOCK_WIDTH;
			pendingGeneration = generation;
			return;
		}
		pixels.clear();
		GLES20.glReadPixels(0, 0, BLOCK_WIDTH, rows, GLES20.GL_RGBA,
				GLES20.GL_UNSIGNED_BYTE, pixels);
		enqueue(pixels, rows * BLOCK_WIDTH);
	}

	// Hands last frame's pixel-buffer block to the writer.
	private void collectPending() {
		if (pendingCount == 0) {
			return;
		}
		int count = pendingCount;
		pendingCount = 0;
		GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, packBuffer);
		java.nio.Buffer mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0,
				count * 4, GLES30.GL_MAP_READ_BIT);
		if (mapped instanceof ByteBuffer bytes && pendingGeneration == generation) {
			enqueue(bytes.order(ByteOrder.nativeOrder()), count);
		}
		GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
		GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
	}

	private void enqueue(@NonNull ByteBuffer bytes, int count) {
		short[] samples = new short[count * 2];
		for (int i = 0; i < count; ++i) {
			int at = i * 4;
			int left = (bytes.get(at) & 0xff) | (bytes.get(at + 1) & 0xff) << 8;
			int right = (bytes.get(at + 2) & 0xff) | (bytes.get(at + 3) & 0xff) << 8;
			samples[i * 2] = (short) (left - 32768);
			samples[i * 2 + 1] = (short) (right - 32768);
		}
		chunks.add(new Chunk(samples, count * 2, PLAY, generation));
	}

	// The time of the next sample to be made, for `soundTime` - so a
	// buffer can stamp an event (a pad pressed) with exactly the moment
	// the Sound pass will play it from.
	float nextTime() {
		return made / (float) SAMPLE_RATE;
	}

	// The same as a sample number, exactly - for `soundSample`.
	int nextSample() {
		return (int) made;
	}

	// While the shader's paused: the track stops where it is, and picks up
	// from there. Any thread.
	void pause() {
		if (playing) {
			playing = false;
			chunks.add(new Chunk(null, 0, PAUSE, generation));
		}
	}

	// Back to sample 0, with nothing queued - a restart, or a new shader.
	// GL thread.
	void restart() {
		++generation;
		pendingCount = 0;
		chunks.clear();
		made = 0;
		queued = 0;
		playing = false;
		chunks.add(new Chunk(null, 0, FLUSH, generation));
	}

	// No Sound pass any more: silent, and the track let go. GL thread.
	void stop() {
		++generation;
		pendingCount = 0;
		chunks.clear();
		made = 0;
		queued = 0;
		playing = false;
		if (writer != null) {
			chunks.add(new Chunk(null, 0, STOP, generation));
			writer = null;
		}
	}

	// The GL context is gone: just drop the target.
	void discardTarget() {
		texture = null;
		framebuffer = null;
		packBuffer = 0;
		pendingCount = 0;
	}

	void releaseTarget(@NonNull GlDevice device) {
		device.deleteFramebuffer(framebuffer);
		device.deleteTexture(texture);
		if (packBuffer > 0) {
			GLES30.glDeleteBuffers(1, new int[]{packBuffer}, 0);
		}
		discardTarget();
	}

	private void ensureWriter() {
		if (writer == null) {
			writer = new Thread(this::write, "SoundOutput");
			writer.start();
		}
	}

	// The writer thread: feeds the track, blocking as it fills, and makes
	// a new one if it fails (a dead track after an audio route change).
	private void write() {
		Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
		AudioTrack track = null;
		// Frames handed to this track, against its playback head, so the
		// tap knows how much is still to be heard.
		long trackWritten = 0;
		int lastUnderruns = 0;
		long lastLogMs = 0;
		try {
			while (true) {
				Chunk chunk = chunks.poll(1, TimeUnit.SECONDS);
				if (chunk == null) {
					continue;
				}
				if (chunk.command() == STOP) {
					tap.reset();
					break;
				}
				if (chunk.generation() != generation) {
					continue;
				}
				if (track == null) {
					track = newTrack();
					lastUnderruns = 0;
					trackWritten = 0;
					tap.reset();
					if (track == null) {
						SystemClock.sleep(500);
						continue;
					}
				}
				switch (chunk.command()) {
					case PAUSE -> track.pause();
					case FLUSH -> {
						track.pause();
						track.flush();
						trackWritten = track.getPlaybackHeadPosition() & 0xffffffffL;
						tap.reset();
					}
					default -> {
						if (chunk.samples() != null) {
							int done = track.write(chunk.samples(), 0, chunk.length(),
									AudioTrack.WRITE_BLOCKING);
							if (done < 0) {
								Log.w(TAG, "Track write failed (" + done + "), making a new one");
								track.release();
								track = null;
								continue;
							}
							if (chunk.generation() == generation) {
								queued += done / 2;
							}
							tap.write(chunk.samples(), done / 2);
							trackWritten += done / 2;
						}
						if (playing && track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
							track.play();
						}
						tap.setPending(trackWritten -
								(track.getPlaybackHeadPosition() & 0xffffffffL));
					}
				}
				int underruns = track.getUnderrunCount();
				if (underruns > lastUnderruns) {
					lastUnderruns = underruns;
					ahead = Math.min(MAX_AHEAD, ahead + AHEAD_STEP);
				}
				long now = SystemClock.elapsedRealtime();
				if (now - lastLogMs >= LOG_INTERVAL_MS) {
					lastLogMs = now;
					Log.d(TAG, "underruns " + underruns + ", ahead " + ahead + " s");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			if (track != null) {
				track.pause();
				track.flush();
				track.release();
			}
		}
	}

	@Nullable
	private static AudioTrack newTrack() {
		int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
				AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
		int size = Math.max(min, (int) (TRACK_SECONDS * SAMPLE_RATE) * 4);
		try {
			AudioTrack track = new AudioTrack.Builder()
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
			// A track otherwise waits for its whole buffer before it starts.
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				track.setStartThresholdInFrames(BLOCK_WIDTH);
			}
			return track;
		} catch (IllegalArgumentException | UnsupportedOperationException e) {
			Log.e(TAG, "No audio track", e);
			return null;
		}
	}

	private boolean ensureTarget(@NonNull GlDevice device) {
		if (framebuffer != null) {
			return true;
		}
		texture = device.createTexture2D();
		device.allocateTexture2D(texture, BLOCK_WIDTH, MAX_ROWS);
		device.applyTextureParameters(texture, new TextureParameters(
				GLES20.GL_NEAREST, GLES20.GL_NEAREST,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		framebuffer = device.createFramebuffer();
		device.attachColor(framebuffer, texture);
		if (device.checkFramebufferStatus(framebuffer) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			Log.e(TAG, "Sound target incomplete");
			releaseTarget(device);
			return false;
		}
		device.bindFramebuffer(null);
		return true;
	}
}
