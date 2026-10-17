package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class BuiltinUniforms {
	record SurfaceState(int renderWidth, int renderHeight, boolean renderTargetsChanged) {
		@NonNull
		static SurfaceState empty() {
			return new SurfaceState(0, 0, false);
		}
	}

	record PreparedFrame(
			@NonNull ProgramBindings bindings,
			int surfaceWidth,
			int surfaceHeight,
			long now) {
	}

	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_FTIME_MAX = 3f;
	private static final int MAX_POINTERS = 10;

	private final RuntimeClock runtimeClock = new RuntimeClock();
	private final float[] surfaceResolution = new float[]{0, 0};
	private final float[] resolution = new float[]{0, 0};
	private final float[] touch = new float[]{0, 0};
	private final float[] touchStart = new float[]{0, 0};
	private final float[] mouse = new float[]{0, 0};
	private final float[] pointers = new float[MAX_POINTERS * 3];
	private final float[] offset = new float[]{0, 0};
	@NonNull
	private final Context context;
	@NonNull
	private final BuiltinSensorUniforms sensorUniforms;
	@NonNull
	private final BuiltinSystemUniforms systemUniforms;
	@NonNull
	private final BuiltinCameraUniforms cameraUniforms;
	@NonNull
	private final BuiltinAudioUniforms audioUniforms;

	@Nullable
	private ProgramBindings programBindings;
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int pointerCount;
	private int frameNum;
	private long startTime;
	private long pausedAtNanos = -1;
	private float fTimeMax = DEFAULT_FTIME_MAX;
	private float quality = 1f;
	private float startRandom;
	// Set/cleared by ShaderRenderer.setBackgroundCaptureActive() for the
	// duration of a HighResCaptureService capture running on a
	// SharedCaptureContext - see release() below for why. Written from
	// whichever thread starts/finishes the capture, read from the GL
	// thread inside release(); a plain volatile is enough since it's a
	// single flag with no other state to publish alongside it.
	private volatile boolean backgroundCaptureActive;

	BuiltinUniforms(@NonNull Context context) {
		this.context = context;
		sensorUniforms = new BuiltinSensorUniforms(context);
		systemUniforms = new BuiltinSystemUniforms(context);
		cameraUniforms = new BuiltinCameraUniforms(context);
		audioUniforms = new BuiltinAudioUniforms(context);
	}

	long getRuntimeNanos() {
		return runtimeClock.getElapsedNanos();
	}

	void setRuntimeNanos(long nanos) {
		runtimeClock.setElapsedNanos(nanos);
	}

	// Restarts every time-based uniform (`time`, `runtime`, `frame`,
	// `startRandom`) back to its initial state, as if the shader had
	// just started running - not just `runtime` (setRuntimeNanos()
	// above only resets that one, which most shaders don't even use;
	// `time`, driven by startTime, is the common ShaderToy-style clock).
	void restart(long now) {
		runtimeClock.setElapsedNanos(0);
		startTime = now;
		startRandom = (float) Math.random();
		frameNum = 0;
	}

	// Suspends time-based uniforms without losing their position, so
	// resumeClock() can continue smoothly from where they left off
	// instead of jumping forward by the paused duration.
	//
	// Also clears pointerCount (and with it, every shader's own
	// `pointerCount == N` gesture branches) - updateTouch() only zeroes
	// it on an ACTION_UP/ACTION_CANCEL MotionEvent, so pausing mid-
	// gesture (the common case right before a screenshot/capture, which
	// routes through here - e.g. finishing a pinch-zoom or a shader's
	// own multi-finger gesture to set up a view, then immediately
	// tapping the camera button without first lifting every finger)
	// would otherwise leave it stuck at whatever it was, with no real
	// touch ever following up to clear it. A shader using that stale
	// pointerCount to decide how `time` advances each step (e.g. "3
	// fingers = scrub time") then sees that gesture as still active on
	// literally every captured frame of a sequence, not just the one
	// live frame it was genuinely true for - which can leave the whole
	// sequence's simulation time barely advancing at all. Harmless to
	// clear unconditionally: if fingers are still genuinely down when
	// resumeClock() runs, the next real MotionEvent (a MOVE, if nothing
	// else) re-populates it correctly within a frame or two.
	void pauseClock() {
		pausedAtNanos = System.nanoTime();
		runtimeClock.pause();
		pointerCount = 0;
	}

	void resumeClock() {
		if (pausedAtNanos >= 0) {
			startTime += System.nanoTime() - pausedAtNanos;
			pausedAtNanos = -1;
		}
	}

	// Steps a paused simulation forward by exactly deltaNanos of virtual
	// time (not wall-clock) - used by frame-sequence capture to advance
	// `time`/`runtime` deterministically between frames regardless of how
	// long each frame's own (possibly slow, tiled) capture actually
	// takes. No-op if not currently paused.
	void advancePausedTime(long deltaNanos) {
		if (pausedAtNanos < 0) {
			return;
		}
		pausedAtNanos += deltaNanos;
		runtimeClock.setElapsedNanos(runtimeClock.getElapsedNanos() + deltaNanos);
	}

	void setQuality(float quality) {
		this.quality = quality;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull GlProgram program,
			float fTimeMax,
			@NonNull ShaderTextureResources textureResources) {
		releaseModules();
		this.fTimeMax = fTimeMax;
		this.textureResources = textureResources;
		programBindings = new ProgramBindings(program);

		sensorUniforms.configure(device, program);
		systemUniforms.configure(device, program);
		cameraUniforms.configure(device, program, textureResources);
		audioUniforms.configure(device, program, textureResources);
	}

	void resetAudioCaptureConsent() {
		BuiltinAudioUniforms.resetConsentGuard();
	}

	void refreshAudioCaptureSource() {
		audioUniforms.refreshCaptureSource();
	}

	void releaseMediaProjection() {
		audioUniforms.releaseMediaProjection();
	}

	boolean isReceivingAudio() {
		return audioUniforms.isReceivingAudio();
	}

	boolean consumeNeedsAudioCaptureConsent() {
		return audioUniforms.consumeNeedsAudioCaptureConsent();
	}

	void setMediaProjection(@Nullable MediaProjection mediaProjection) {
		audioUniforms.setMediaProjection(mediaProjection);
	}

	@NonNull
	SurfaceState updateSurface(int width, int height, long now) {
		runtimeClock.pause();
		pausedAtNanos = -1;
		startTime = now;
		startRandom = (float) Math.random();
		frameNum = 0;

		surfaceResolution[0] = width;
		surfaceResolution[1] = height;
		int deviceRotation = getDeviceRotation(context);
		sensorUniforms.setDeviceRotation(deviceRotation);

		float w = Math.round(width * quality);
		float h = Math.round(height * quality);
		boolean resolutionChanged = w != resolution[0] || h != resolution[1];
		resolution[0] = w;
		resolution[1] = h;

		cameraUniforms.updateSurface(
				(int) resolution[0],
				(int) resolution[1],
				deviceRotation);
		return new SurfaceState(
				(int) resolution[0],
				(int) resolution[1],
				resolutionChanged);
	}

	void updateTouch(@NonNull MotionEvent e) {
		float x = e.getX() * quality;
		float y = e.getY() * quality;

		touch[0] = x;
		touch[1] = resolution[1] - y;

		mouse[0] = x / resolution[0];
		mouse[1] = 1 - y / resolution[1];

		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				touchStart[0] = touch[0];
				touchStart[1] = touch[1];
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				pointerCount = 0;
				return;
			default:
				break;
		}

		pointerCount = Math.min(e.getPointerCount(), pointers.length / 3);
		for (int i = 0, pointerOffset = 0; i < pointerCount; ++i) {
			pointers[pointerOffset++] = e.getX(i) * quality;
			pointers[pointerOffset++] = resolution[1] - e.getY(i) * quality;
			pointers[pointerOffset++] = e.getTouchMajor(i);
		}
	}

	void updateOffset(float x, float y) {
		offset[0] = x;
		offset[1] = y;
	}

	@Nullable
	PreparedFrame beginFrame(@NonNull GlDevice device, @Nullable GlTexture2D backBufferTexture) {
		var bindings = programBindings;
		if (bindings == null) {
			return null;
		}

		// Frozen at the moment pauseClock() ran rather than a fresh
		// System.nanoTime(), while paused - without this, `time`/
		// `second`/`subsecond` and the system uniforms below kept
		// advancing by real wall-clock time even though `runtime` (via
		// runtimeClock, paused separately) correctly froze, which
		// mattered in practice for the high-res capture: it runs well
		// after the pause (through a resolution-picker dialog, a tiled
		// render taking several seconds), so without this the captured
		// image evaluated the shader at a visibly later moment than what
		// was actually on screen when the user paused it.
		long now = pausedAtNanos >= 0 ? pausedAtNanos : System.nanoTime();
		float delta = (now - startTime) / NS_PER_SECOND;

		bindings.clear();
		bindFrameUniforms(bindings, delta);
		// getElapsedNanos() (read-only) rather than advance(now) while
		// paused - advance() also records `now` as the clock's "last
		// frame" time for computing the *next* call's delta; mutating
		// that here (from this capture-only call, while genuinely
		// unpaused frames aren't happening) would make the next real
		// frame after resuming see a huge gap back to this frozen `now`
		// and add the entire paused duration to `runtime` in one jump.
		long runtimeNanos = pausedAtNanos >= 0
				? runtimeClock.getElapsedNanos()
				: runtimeClock.advance(now);
		bindings.setFloat(ShaderRenderer.UNIFORM_RUNTIME, (float) (runtimeNanos / 1000000000.0));
		systemUniforms.apply(bindings, now);
		sensorUniforms.apply(bindings);
		if (backBufferTexture != null) {
			bindings.setTexture(ShaderRenderer.UNIFORM_BACKBUFFER, backBufferTexture);
		}
		cameraUniforms.apply(bindings);
		audioUniforms.apply(device);
		textureResources.applyTo(bindings);

		return new PreparedFrame(
				bindings,
				(int) surfaceResolution[0],
				(int) surfaceResolution[1],
				now);
	}

	void endFrame() {
		++frameNum;
	}

	void clearConfiguration() {
		runtimeClock.pause();
		fTimeMax = DEFAULT_FTIME_MAX;
		textureResources = ShaderTextureResources.empty();
		programBindings = null;
	}

	// Called from ShaderView.onPause() on every real pause, including
	// just the app being backgrounded while a capture keeps running on
	// its own SharedCaptureContext (see HighResCaptureService) - sensors/
	// camera/audio still get released as normal (same as any other
	// backgrounded app, and this renderer's own live playback is paused
	// anyway during a capture), but clearConfiguration() is skipped while
	// backgroundCaptureActive: it nulls programBindings and resets
	// textureResources/fTimeMax, which beginFrame() (still being called
	// by that capture, on its own thread, against this same instance)
	// needs to keep returning real bindings instead of null. Skipping it
	// is safe because configure() (the only thing that would need a
	// clean slate) always calls releaseModules() itself first regardless
	// of this flag - clearConfiguration()'s own effects are purely
	// defensive cleanup for an *idle* paused renderer, not something a
	// fresh configure() depends on having already happened.
	void release() {
		releaseModules();
		if (!backgroundCaptureActive) {
			clearConfiguration();
		}
	}

	void setBackgroundCaptureActive(boolean active) {
		backgroundCaptureActive = active;
	}

	private void bindFrameUniforms(
			@NonNull ProgramBindings bindings,
			float delta) {
		bindings.setFloat(ShaderRenderer.UNIFORM_TIME, delta);
		bindings.setInt(ShaderRenderer.UNIFORM_SECOND, (int) delta);
		bindings.setFloat(
				ShaderRenderer.UNIFORM_SUB_SECOND,
				delta - (int) delta);
		bindings.setInt(ShaderRenderer.UNIFORM_FRAME_NUMBER, frameNum);
		bindings.setFloat(
				ShaderRenderer.UNIFORM_FTIME,
				((delta % fTimeMax) / fTimeMax * 2f - 1f));
		bindings.setFloat2(ShaderRenderer.UNIFORM_RESOLUTION, resolution);
		bindings.setFloat2(ShaderRenderer.UNIFORM_TOUCH, touch);
		bindings.setFloat2(ShaderRenderer.UNIFORM_TOUCH_START, touchStart);
		bindings.setFloat2(ShaderRenderer.UNIFORM_MOUSE, mouse);
		bindings.setInt(ShaderRenderer.UNIFORM_POINTER_COUNT, pointerCount);
		if (pointerCount > 0) {
			bindings.setFloat3(
					ShaderRenderer.UNIFORM_POINTERS,
					pointerCount,
					pointers);
		}
		bindings.setFloat2(ShaderRenderer.UNIFORM_OFFSET, offset);
		bindings.setFloat(ShaderRenderer.UNIFORM_START_RANDOM, startRandom);
	}

	private void releaseModules() {
		sensorUniforms.release();
		systemUniforms.release();
		cameraUniforms.release();
		audioUniforms.release();
	}

	private static int getDeviceRotation(@NonNull Context context) {
		WindowManager windowManager = (WindowManager) context.getSystemService(
				Context.WINDOW_SERVICE);
		if (windowManager == null) {
			return 0;
		}

		return windowManager.getDefaultDisplay().getRotation();
	}
}
