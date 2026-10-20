package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.media.projection.MediaProjection;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;

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
	// ShaderToy uniforms - see bindShaderToyUniforms().
	private final float[] stResolution = new float[]{0, 0, 1};
	private final float[] stMouse = new float[]{0, 0, 0, 0};
	private final float[] stDate = new float[]{0, 0, 0, 0};
	private final Calendar calendar = Calendar.getInstance();
	// Set by a touch going down, cleared once a frame has seen it - iMouse.w
	// is positive only on the first frame of a press.
	private volatile boolean stMousePressed;
	// `time` as of the last completed frame, for iTimeDelta; < 0 before
	// the first one (since a restart).
	private float previousFrameTime = -1;
	// previousFrameTime before the last endFrame() - see rewindFrame().
	private float frameTimeBeforeLast = -1;
	private float lastBoundTime;
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
	// Adjust Defines' live values - see LiveDefines. Empty, and so
	// binding nothing, unless live mode is on.
	@NonNull
	private final LiveDefines liveDefines = new LiveDefines();
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
	// See setQuality(). Set on the UI thread, consumed on the GL thread.
	private volatile boolean keepClockOnNextSurface;
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
		// While paused, time reads as of the moment it paused - so start
		// from there, or `time` would start out negative (see
		// ShaderRenderer.restartForCapture()).
		startTime = pausedAtNanos >= 0 ? pausedAtNanos : now;
		startRandom = (float) Math.random();
		frameNum = 0;
		previousFrameTime = -1;
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

	// Separate from (and in addition to) the pointerCount reset inside
	// pauseClock() above: that one only runs on an actual pause-state
	// *transition*, so it misses the equally common case of the shader
	// already being paused (ShaderViewManager.setPaused()/MainActivity's
	// setShaderPaused() both no-op if already at the target state) -
	// e.g. gesturing with the view (any number of fingers) *while*
	// already paused, then starting a capture without an intervening
	// resume/re-pause. Called unconditionally at the start of every
	// capture (see ShaderRenderer's own capture methods), regardless of
	// whether this particular call just paused anything.
	void clearTouchState() {
		pointerCount = 0;
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

	// A quality change is a resolution change of the *same* running
	// shader (hotkeys, the quality spinner/picker), so unlike other
	// surface changes it shouldn't restart time - see updateSurface() -
	// and positions already in render-pixel units (touch, touchStart,
	// pointers) are rescaled to the new resolution so a finger held
	// down, or a shader's last-touch position, stays where it was on
	// screen instead of jumping. (mouse is normalised, so it's unchanged.)
	void setQuality(float quality) {
		if (quality != this.quality && this.quality > 0) {
			float f = quality / this.quality;
			touch[0] *= f;
			touch[1] *= f;
			touchStart[0] *= f;
			touchStart[1] *= f;
			for (int i = 0; i < pointers.length; i += 3) {
				pointers[i] *= f;
				pointers[i + 1] *= f;
			}
			for (int i = 0; i < stMouse.length; ++i) {
				stMouse[i] *= f;
			}
			keepClockOnNextSurface = true;
		}
		this.quality = quality;
	}

	// `program` is the Image pass, which the shared bindings start out
	// targeting; `uniforms` covers every pass's program (see
	// UniformPresence), deciding which sensors etc. to start.
	void configure(
			@NonNull GlDevice device,
			@NonNull GlProgram program,
			@NonNull UniformPresence uniforms,
			float fTimeMax,
			@NonNull ShaderTextureResources textureResources) {
		releaseModules();
		this.fTimeMax = fTimeMax;
		this.textureResources = textureResources;
		programBindings = new ProgramBindings(program);

		sensorUniforms.configure(device, uniforms);
		systemUniforms.configure(device, uniforms);
		cameraUniforms.configure(device, uniforms, textureResources);
		audioUniforms.configure(device, uniforms, textureResources);
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

	void setLocalPlaybackAudioSession(int audioSessionId) {
		audioUniforms.setLocalPlaybackAudioSession(audioSessionId);
	}

	@NonNull
	SurfaceState updateSurface(int width, int height, long now) {
		// Excludes the recreate gap from `runtime` either way.
		runtimeClock.pause();
		if (keepClockOnNextSurface) {
			// See setQuality() - time, frame and startRandom carry on.
			keepClockOnNextSurface = false;
		} else {
			pausedAtNanos = -1;
			startTime = now;
			startRandom = (float) Math.random();
			frameNum = 0;
			previousFrameTime = -1;
		}

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

		// ShaderToy's iMouse: xy follows the touch while it's down, zw is
		// where it went down - both positive while down, z negated once
		// it's lifted, w positive only on the press's first frame (see
		// bindShaderToyUniforms()).
		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				touchStart[0] = touch[0];
				touchStart[1] = touch[1];
				stMouse[0] = touch[0];
				stMouse[1] = touch[1];
				stMouse[2] = touch[0];
				stMouse[3] = touch[1];
				stMousePressed = true;
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				pointerCount = 0;
				stMouse[2] = -Math.abs(stMouse[2]);
				stMouse[3] = -Math.abs(stMouse[3]);
				return;
			default:
				stMouse[0] = touch[0];
				stMouse[1] = touch[1];
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
	// See getBeginFrameCount().
	private int beginFrameCount = 0;

	PreparedFrame beginFrame(@NonNull GlDevice device, @Nullable GlTexture2D backBufferTexture) {
		var bindings = programBindings;
		if (bindings == null) {
			return null;
		}
		++beginFrameCount;

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
		liveDefines.apply(bindings);

		return new PreparedFrame(
				bindings,
				(int) surfaceResolution[0],
				(int) surfaceResolution[1],
				now);
	}

	@NonNull
	LiveDefines getLiveDefines() {
		return liveDefines;
	}

	void endFrame() {
		++frameNum;
		frameTimeBeforeLast = previousFrameTime;
		previousFrameTime = lastBoundTime;
	}

	// Puts `frame` and the time delta back to the last frame's, so it can
	// be drawn again - see ShaderRenderer.requestRedoLastStep().
	void rewindFrame() {
		if (frameNum > 0) {
			--frameNum;
			previousFrameTime = frameTimeBeforeLast;
		}
	}

	// Keeps `time`, `frame` and startRandom going through the next
	// surface change rather than restarting them - for a shader swapped
	// without restarting it (see ShaderRenderer.setFragmentShader()'s
	// keepState), which recreates the surface on the way.
	void keepClockThroughNextSurface() {
		keepClockOnNextSurface = true;
	}

	// Bumped by every beginFrame(), whoever called it - lets a frame
	// spread across several onDrawFrame() calls (progressive live
	// rendering) notice that something else (a high-res capture, a
	// sequence's stepSimulationFrame()) rebuilt the one shared
	// programBindings in between, so it can't keep drawing with them.
	int getBeginFrameCount() {
		return beginFrameCount;
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
		bindShaderToyUniforms(bindings, delta);
	}

	private void bindShaderToyUniforms(
			@NonNull ProgramBindings bindings,
			float delta) {
		lastBoundTime = delta;
		float timeDelta = previousFrameTime < 0 ? 0f : Math.max(0f, delta - previousFrameTime);
		bindings.setFloat(ShaderRenderer.UNIFORM_ST_TIME, delta);
		bindings.setFloat(ShaderRenderer.UNIFORM_ST_TIME_DELTA, timeDelta);
		bindings.setFloat(ShaderRenderer.UNIFORM_ST_FRAME_RATE,
				timeDelta > 0f ? 1f / timeDelta : 0f);
		bindings.setInt(ShaderRenderer.UNIFORM_ST_FRAME, frameNum);
		stResolution[0] = resolution[0];
		stResolution[1] = resolution[1];
		bindings.setFloat3(ShaderRenderer.UNIFORM_ST_RESOLUTION, stResolution);
		if (stMousePressed) {
			stMousePressed = false;
		} else {
			stMouse[3] = -Math.abs(stMouse[3]);
		}
		bindings.setFloat4(ShaderRenderer.UNIFORM_ST_MOUSE, stMouse);
		calendar.setTimeInMillis(System.currentTimeMillis());
		stDate[0] = calendar.get(Calendar.YEAR);
		// 0-based, like JavaScript's Date (which ShaderToy passes on).
		stDate[1] = calendar.get(Calendar.MONTH);
		stDate[2] = calendar.get(Calendar.DAY_OF_MONTH);
		stDate[3] = calendar.get(Calendar.HOUR_OF_DAY) * 3600f +
				calendar.get(Calendar.MINUTE) * 60f +
				calendar.get(Calendar.SECOND) +
				calendar.get(Calendar.MILLISECOND) / 1000f;
		bindings.setFloat4(ShaderRenderer.UNIFORM_ST_DATE, stDate);
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
