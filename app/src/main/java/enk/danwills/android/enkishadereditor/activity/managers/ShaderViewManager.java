package enk.danwills.android.enkishadereditor.activity.managers;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.media.projection.MediaProjection;
import android.opengl.GLSurfaceView;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinary;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinaryCache;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.opengl.SharedCaptureContext;
import enk.danwills.android.enkishadereditor.project.ShaderProjectSession;
import enk.danwills.android.enkishadereditor.widget.FullscreenExitGestureDetector;
import enk.danwills.android.enkishadereditor.widget.ShaderView;

public class ShaderViewManager {
	public interface Listener {
		void onFramesPerSecond(int fps);

		void onInfoLog(@NonNull List<ShaderError> infoLog);

		void onQualityChanged(float quality);

		void onRequestAudioCapture();

		// Fired as the fullscreen-exit gesture changes phase (3+ fingers
		// down, then back to exactly 1 held and PENDING, then ARMED once
		// held long enough) and when it actually triggers (that last
		// finger released while ARMED). See FullscreenExitGestureDetector.
		void onFullscreenExitPhaseChanged(@NonNull FullscreenExitGestureDetector.Phase phase);

		void onFullscreenExitGesture();
	}

	@NonNull
	private final Activity activity;
	@NonNull
	private final ShaderView shaderView;
	@NonNull
	private final Spinner qualitySpinner;
	@NonNull
	private final Listener listener;
	@NonNull
	private final FullscreenExitGestureDetector fullscreenExitGestureDetector;
	@NonNull
	private float[] qualityValues;
	private float quality = 1f;
	private boolean paused = false;
	private boolean needsRealResume = false;
	private boolean backgroundCaptureActive = false;
	// Persists compiled program binaries so re-selecting a shader (or
	// relaunching the app) can skip straight to glProgramBinary() instead
	// of a full source recompile - see ProgramBinaryCache. A single
	// background thread is enough: writes are infrequent (once per
	// shader per driver version) and ordering doesn't matter between
	// different shaders' cache entries.
	private final ExecutorService binaryCacheExecutor = Executors.newSingleThreadExecutor();

	public ShaderViewManager(@NonNull Activity activity,
			@NonNull ShaderView shaderView,
			@NonNull Spinner qualitySpinner,
			@NonNull DataSource dataSource,
			@NonNull Listener listener) {
		this.activity = activity;
		this.shaderView = shaderView;
		this.qualitySpinner = qualitySpinner;
		this.listener = listener;
		this.fullscreenExitGestureDetector = new FullscreenExitGestureDetector(
				new FullscreenExitGestureDetector.Listener() {
					@Override
					public void onPhaseChanged(@NonNull FullscreenExitGestureDetector.Phase phase) {
						listener.onFullscreenExitPhaseChanged(phase);
					}

					@Override
					public void onExitTriggered() {
						listener.onFullscreenExitGesture();
					}
				});

		initQualitySpinner(activity);
		initShaderView();
		shaderView.getRenderer().setProgramBinaryCache(new ProgramBinaryCache() {
			@Override
			@Nullable
			public ProgramBinary get(long shaderId, @NonNull String sourceHash,
					@NonNull String driverSignature) {
				return dataSource.shader.getCompiledBinary(shaderId, sourceHash, driverSignature);
			}

			@Override
			public void put(long shaderId, @NonNull ProgramBinary binary,
					@NonNull String sourceHash, @NonNull String driverSignature,
					long compileTimeMs) {
				try {
					binaryCacheExecutor.execute(() -> dataSource.shader.saveCompiledBinary(
							shaderId, binary, sourceHash, driverSignature, compileTimeMs));
				} catch (RejectedExecutionException e) {
					// The executor was already shut down (destroy() ran) -
					// nothing worth caching for at this point.
				}
			}
		});
	}

	// Only meaningful while in fullscreen, where there's no toolbar back
	// button to tap - see FullscreenExitGestureDetector.
	public void setFullscreenExitGestureEnabled(boolean enabled) {
		fullscreenExitGestureDetector.setEnabled(enabled);
	}

	// Called from MainActivity.onDestroy(). Not queued onto the GL
	// thread like the other MediaProjection-related calls above: by the
	// time the Activity is actually being destroyed, the GL thread has
	// already been stopped by the preceding onPause() in the normal
	// case, and if a crash skipped that, this needs to run regardless
	// of whether the GL thread ever wakes up again - see
	// BuiltinAudioUniforms.releaseMediaProjection().
	public void destroy() {
		shaderView.getRenderer().releaseMediaProjection();
		binaryCacheExecutor.shutdownNow();
	}

	public void onPause() {
		if (shaderView.getVisibility() == View.VISIBLE) {
			// If we're already manually paused, the GL thread is only idling
			// (see setPaused() below), not actually stopped, so this real
			// pause needs a matching real onResume() to recover from later,
			// even though setPaused(false) wouldn't otherwise ask for one.
			if (paused) {
				needsRealResume = true;
			}
			shaderView.onPause();
		}
	}

	public void onResume() {
		if (shaderView.getVisibility() != View.VISIBLE) {
			return;
		}
		if (paused) {
			// A real pause (screen off, backgrounding) happened while we
			// were only soft-paused - shaderView.onPause() already tore
			// down the EGL context, and without a matching onResume()
			// here it stays torn down (no GL context at all, not even
			// for a capture queued via queueEvent()) for as long as we
			// remain soft-paused - which, mid-flow in a capture dialog,
			// can be the whole rest of that flow, including the capture
			// itself. Recreate it promptly, but stay on
			// RENDERMODE_WHEN_DIRTY and re-assert the paused clock - this
			// is still a soft pause, just with a live context again, not
			// an invitation to resume continuous playback.
			// If HighResCaptureService has a capture running on a
			// SharedCaptureContext right now, leave the live context torn
			// down and needsRealResume pending - recreating it here would
			// mean two threads touching the same shared
			// programManager/renderPipeline/builtinUniforms at once (this
			// one via a fresh onSurfaceCreated(), which recompiles the
			// main program; the capture thread via the shared context).
			// setBackgroundCaptureActive(false) performs this same
			// recreation once that capture is actually done.
			if (needsRealResume && !backgroundCaptureActive) {
				needsRealResume = false;
				shaderView.onResume();
				shaderView.queueEvent(() -> shaderView.getRenderer().pauseClock());
			}
			return;
		}
		shaderView.onResume();
	}

	public boolean isPaused() {
		return paused;
	}

	// Called by MainActivity around a capture that may outlive the
	// activity being foregrounded (see HighResCaptureService) - while
	// active, suppresses onResume()'s and setPaused(false)'s normal
	// live-context recreation/resumption, since both would otherwise
	// race against the capture thread's concurrent use of the exact
	// same renderer objects on its own SharedCaptureContext. Also
	// forwarded to the renderer itself, so ShaderView.onPause() (which
	// fires the moment the app backgrounds, regardless of any of the
	// above) skips clearing BuiltinUniforms' live configuration out from
	// under that same capture - see ShaderRenderer.
	// setBackgroundCaptureActive() and BuiltinUniforms.release(). Turning
	// this off performs whichever live-context resume was deferred, if
	// the activity is currently resumed.
	public void setBackgroundCaptureActive(boolean active) {
		backgroundCaptureActive = active;
		shaderView.getRenderer().setBackgroundCaptureActive(active);
		if (active || shaderView.getVisibility() != View.VISIBLE) {
			return;
		}
		if (paused && needsRealResume) {
			needsRealResume = false;
			shaderView.onResume();
			shaderView.queueEvent(() -> shaderView.getRenderer().pauseClock());
		}
	}

	// Pausing freezes both compilation (new shader sources are
	// dropped, see setFragmentShader()/setProjectSession() below)
	// and execution (the view switches to on-demand rendering, so the
	// last rendered frame stays on screen and uniforms like `time` stop
	// advancing). This deliberately avoids GLSurfaceView's own
	// onPause()/onResume(), which would tear down and recreate the EGL
	// context - and with it, force a full shader recompile - on every
	// toggle. A real recompile still happens when the app is actually
	// backgrounded and resumed (see onPause()/onResume() above), since
	// that goes through the real GL lifecycle methods.
	public void setPaused(boolean paused) {
		if (this.paused == paused) {
			return;
		}
		// Ignore attempts to unpause while HighResCaptureService still has
		// a capture running on its SharedCaptureContext - switching the
		// live GL thread back to RENDERMODE_CONTINUOUSLY now would start
		// it rendering again concurrently with that capture thread, both
		// touching the same shared renderer objects at once. Callers
		// should wait for setBackgroundCaptureActive(false), which
		// performs any resume this call would otherwise have done.
		if (!paused && backgroundCaptureActive) {
			return;
		}
		this.paused = paused;
		if (shaderView.getVisibility() != View.VISIBLE) {
			return;
		}
		if (paused) {
			shaderView.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
			shaderView.queueEvent(() -> shaderView.getRenderer().pauseClock());
			return;
		}
		if (needsRealResume) {
			needsRealResume = false;
			shaderView.onResume();
		}
		shaderView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
		shaderView.queueEvent(() -> shaderView.getRenderer().resumeClock());
	}

	// Restarts the shader's time-based uniforms (`time`, `runtime`,
	// `frame`, `startRandom`) without recompiling, so a "restart" action
	// can rewind a running or paused shader to its initial state.
	public void restartRuntime() {
		shaderView.queueEvent(() -> shaderView.getRenderer().restartTime());
	}

	public void setFragmentShader(@Nullable String src) {
		if (paused) {
			return;
		}
		shaderView.setFragmentShader(src, quality, 0);
	}

	public void setProjectSession(@NonNull ShaderProjectSession projectSession) {
		if (paused) {
			return;
		}
		setQuality(projectSession.getQuality());
		shaderView.setFragmentShader(
				projectSession.getEntryPointSource(),
				projectSession.getQuality(),
				projectSession.getShaderId());
	}

	public void setVisibility(boolean visible) {
		shaderView.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	public byte[] getThumbnail() {
		return shaderView.getRenderer().getThumbnail();
	}

	// Blocks until the next frame renders - call off the main thread.
	@Nullable
	public byte[] takeScreenshot() {
		// RENDERMODE_WHEN_DIRTY (set when paused) only draws a frame on an
		// explicit request, so without this the capture flag below would
		// never get serviced and the wait would just time out.
		shaderView.requestRender();
		return shaderView.getRenderer().captureFullScreenshot();
	}

	// Blocks until the capture finishes rendering - call off the main
	// thread. Unlike takeScreenshot() above, this doesn't ride the
	// normal per-frame render loop (no requestRender() dance needed) -
	// the capture runs as a standalone GL-thread operation dispatched
	// via queueEvent(), so it works the same whether playback is
	// currently paused or not. progressListener (may be null) is called
	// after every tile; each call also resets this method's own timeout,
	// so an oversample factor large enough to make the *whole* capture
	// take minutes doesn't get mistaken for a hang - only actually
	// making no progress for 60s does.
	@Nullable
	public byte[] takeHighResScreenshot(int width, int height, int oversample,
			boolean preserveBackbufferEdges,
			@Nullable ShaderRenderer.HighResProgressListener progressListener) {
		Object lock = new Object();
		byte[][] resultHolder = new byte[1][];
		boolean[] doneHolder = new boolean[1];
		long[] lastProgressMillis = {System.currentTimeMillis()};
		ShaderRenderer.HighResProgressListener innerListener = (tilesDone, totalTiles) -> {
			synchronized (lock) {
				lastProgressMillis[0] = System.currentTimeMillis();
			}
			boolean continueCapture = progressListener == null || progressListener.onProgress(tilesDone, totalTiles);
			pacedProgressDelay();
			return continueCapture;
		};
		shaderView.queueEvent(() -> {
			byte[] result = shaderView.getRenderer()
					.captureHighResScreenshot(
						width, height, oversample, preserveBackbufferEdges, innerListener);
			synchronized (lock) {
				resultHolder[0] = result;
				doneHolder[0] = true;
				lock.notifyAll();
			}
		});
		synchronized (lock) {
			// Looped against the remaining time on a per-progress-tick
			// deadline (unlike captureFullScreenshot()'s single wait()) -
			// tiling a large image can genuinely take several seconds (or,
			// at a large oversample factor, minutes overall), long enough
			// that a spurious wakeup (Object.wait() can return early
			// without being notified or timing out - rare, but real) must
			// not be mistaken for a timeout and cut the wait short.
			long timeoutMillis = 60000;
			while (!doneHolder[0]) {
				// Already holding `lock` here (this whole loop is inside
				// the enclosing synchronized block), so this read is safe
				// without a further nested acquire - same monitor as the
				// write in innerListener above.
				long remaining = (lastProgressMillis[0] + timeoutMillis) - System.currentTimeMillis();
				if (remaining <= 0) {
					Log.e("HighResCapture", "takeHighResScreenshot: timed out waiting for GL thread");
					return null;
				}
				try {
					lock.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
			return resultHolder[0];
		}
	}

	// Like takeHighResScreenshot() above, but captures numFrames frames
	// spaced virtualFps seconds apart in simulated (not wall-clock)
	// time, stepping the shader's own state forward between frames - see
	// ShaderRenderer.stepSimulationFrame(). skipFrames steps forward
	// that many times first, without saving anything. frameListener is
	// invoked once per completed (saved) frame, sequenceProgressListener
	// once per step of either phase - both still on the GL thread (same
	// hand-off expectations as progressListener - callers needing UI
	// updates or slower work like saving to disk should expect this to
	// briefly block the GL thread). Returns false if any frame failed to
	// capture (frameListener may already have been called for whichever
	// frames succeeded before that).
	public boolean takeHighResScreenshotSequence(int width, int height, int oversample,
			boolean preserveBackbufferEdges, int skipFrames, int numFrames, float virtualFps,
			@Nullable ShaderRenderer.HighResProgressListener progressListener,
			@Nullable ShaderRenderer.SequenceProgressListener sequenceProgressListener,
			@NonNull ShaderRenderer.FrameListener frameListener) {
		Object lock = new Object();
		boolean[] doneHolder = new boolean[1];
		boolean[] successHolder = new boolean[1];
		long[] lastProgressMillis = {System.currentTimeMillis()};
		ShaderRenderer.HighResProgressListener innerTileListener = (tilesDone, totalTiles) -> {
			synchronized (lock) {
				lastProgressMillis[0] = System.currentTimeMillis();
			}
			boolean continueCapture = progressListener == null || progressListener.onProgress(tilesDone, totalTiles);
			pacedProgressDelay();
			return continueCapture;
		};
		ShaderRenderer.SequenceProgressListener innerSequenceListener = (skipping, stepIndex, totalSteps) -> {
			synchronized (lock) {
				lastProgressMillis[0] = System.currentTimeMillis();
			}
			return sequenceProgressListener == null ||
					sequenceProgressListener.onSequenceProgress(skipping, stepIndex, totalSteps);
		};
		ShaderRenderer.FrameListener innerFrameListener = (frameIndex, totalFrames, png) -> {
			synchronized (lock) {
				lastProgressMillis[0] = System.currentTimeMillis();
			}
			frameListener.onFrame(frameIndex, totalFrames, png);
		};
		shaderView.queueEvent(() -> {
			boolean success = shaderView.getRenderer().captureHighResScreenshotSequence(
					width, height, oversample, preserveBackbufferEdges, skipFrames, numFrames, virtualFps,
					innerTileListener, innerSequenceListener, innerFrameListener);
			synchronized (lock) {
				successHolder[0] = success;
				doneHolder[0] = true;
				lock.notifyAll();
			}
		});
		synchronized (lock) {
			long timeoutMillis = 60000;
			while (!doneHolder[0]) {
				long remaining = (lastProgressMillis[0] + timeoutMillis) - System.currentTimeMillis();
				if (remaining <= 0) {
					Log.e("HighResCapture",
							"takeHighResScreenshotSequence: timed out waiting for GL thread");
					return false;
				}
				try {
					lock.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
			return successHolder[0];
		}
	}

	// Gives the caller direct access to the live ShaderRenderer - needed
	// by HighResCaptureService, which (unlike the two methods above)
	// doesn't run its capture via queueEvent() on the GL thread at all,
	// precisely so it can keep running after that thread's own context
	// is torn down - see createSharedCaptureContext() below and
	// ShaderRenderer.captureHighResScreenshotOnSharedContext().
	@NonNull
	public ShaderRenderer getRenderer() {
		return shaderView.getRenderer();
	}

	// Blocks until a SharedCaptureContext is created against whatever
	// context is current on the GL thread right now - call this *before*
	// handing off to HighResCaptureService, while the live context still
	// definitely exists (e.g. right as a capture is kicked off from the
	// UI, not after the service has already started). Returns null if
	// the GL thread doesn't have a live context at that moment (should
	// only happen if the view's already been torn down some other way)
	// or creation otherwise fails - see SharedCaptureContext's own doc.
	@Nullable
	public SharedCaptureContext createSharedCaptureContext() {
		Object lock = new Object();
		SharedCaptureContext[] resultHolder = new SharedCaptureContext[1];
		boolean[] doneHolder = new boolean[1];
		shaderView.queueEvent(() -> {
			var result = shaderView.getRenderer().createSharedCaptureContext();
			synchronized (lock) {
				resultHolder[0] = result;
				doneHolder[0] = true;
				lock.notifyAll();
			}
		});
		synchronized (lock) {
			long deadlineMillis = System.currentTimeMillis() + 5000;
			while (!doneHolder[0]) {
				long remaining = deadlineMillis - System.currentTimeMillis();
				if (remaining <= 0) {
					Log.e("HighResCapture",
							"createSharedCaptureContext: timed out waiting for GL thread");
					return null;
				}
				try {
					lock.wait(remaining);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
			return resultHolder[0];
		}
	}

	// Like takeHighResScreenshot() above, but runs directly on the
	// calling thread against a SharedCaptureContext obtained earlier
	// from createSharedCaptureContext() - see HighResCaptureService,
	// whose whole point is to keep that calling thread (and therefore
	// this call) alive even if the app gets backgrounded partway
	// through. No queueEvent()/lock/timeout dance needed here, unlike
	// the live-context version: this isn't hopping to another thread,
	// it's already running on whichever thread the caller is keeping
	// the shared context current on. Callers must have already called
	// setBackgroundCaptureActive(true) before this, and must call
	// setBackgroundCaptureActive(false) (and sharedContext.destroy(), on
	// this same thread) once done - see those methods' own doc for why.
	@Nullable
	public byte[] takeHighResScreenshotOnSharedContext(@NonNull SharedCaptureContext sharedContext,
			int width, int height, int oversample, boolean preserveBackbufferEdges,
			@Nullable ShaderRenderer.HighResProgressListener progressListener) {
		ShaderRenderer.HighResProgressListener innerListener = progressListener == null ? null :
				(tilesDone, totalTiles) -> {
					boolean continueCapture = progressListener.onProgress(tilesDone, totalTiles);
					pacedProgressDelay();
					return continueCapture;
				};
		return shaderView.getRenderer().captureHighResScreenshotOnSharedContext(
				sharedContext, width, height, oversample, preserveBackbufferEdges, innerListener);
	}

	// Sequence counterpart to takeHighResScreenshotOnSharedContext()
	// above, same relationship to takeHighResScreenshotSequence() as
	// that method has to takeHighResScreenshot().
	public boolean takeHighResScreenshotSequenceOnSharedContext(
			@NonNull SharedCaptureContext sharedContext, int width, int height, int oversample,
			boolean preserveBackbufferEdges, int skipFrames, int numFrames, float virtualFps,
			@Nullable ShaderRenderer.HighResProgressListener progressListener,
			@Nullable ShaderRenderer.SequenceProgressListener sequenceProgressListener,
			@NonNull ShaderRenderer.FrameListener frameListener) {
		ShaderRenderer.HighResProgressListener innerTileListener = progressListener == null ? null :
				(tilesDone, totalTiles) -> {
					boolean continueCapture = progressListener.onProgress(tilesDone, totalTiles);
					pacedProgressDelay();
					return continueCapture;
				};
		return shaderView.getRenderer().captureHighResScreenshotSequenceOnSharedContext(
				sharedContext, width, height, oversample, preserveBackbufferEdges, skipFrames,
				numFrames, virtualFps, innerTileListener, sequenceProgressListener, frameListener);
	}

	// A 512px tile (chosen to dodge the GPU watchdog - see
	// HighResScreenshotCapture) can render and read back in well under
	// a display refresh interval for anything but the heaviest shaders.
	// Without this, runOnUiThread() progress updates on the UI side get
	// posted and coalesced faster than Android can actually paint a
	// frame for each one - only the *last* value before each vsync ever
	// gets drawn, so a tile-progress bar visibly jumps straight to
	// "done" instead of stepping through. Pacing each tile's completion
	// by roughly one frame interval (called from the GL thread, same
	// thread the whole capture already runs on) costs very little next
	// to the capture's own overall runtime but gives the UI thread a
	// real chance to paint each intermediate value.
	private static final long PROGRESS_PACE_MILLIS = 16;

	private static void pacedProgressDelay() {
		try {
			Thread.sleep(PROGRESS_PACE_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public long getLastCompileNanos() {
		return shaderView.getRenderer().getLastCompileNanos();
	}

	public boolean wasLastCompileSuccessful() {
		return shaderView.getRenderer().wasLastCompileSuccessful();
	}

	public long getLastProgramBinaryBytes() {
		return shaderView.getRenderer().getLastProgramBinaryBytes();
	}

	public int getLastFps() {
		return shaderView.getRenderer().getLastFps();
	}

	public void setMediaProjection(@Nullable MediaProjection mediaProjection) {
		shaderView.queueEvent(() ->
				shaderView.getRenderer().setMediaProjection(mediaProjection));
	}

	// Call when the "use system playback audio" preference changes, so
	// re-enabling it (after a prior denial, or after toggling it off and
	// back on) is asked about again instead of silently staying on the
	// microphone fallback forever.
	public void refreshAudioCaptureSource() {
		shaderView.getRenderer().resetAudioCaptureConsent();
		shaderView.queueEvent(() -> shaderView.getRenderer().refreshAudioCaptureSource());
	}

	// Forces a fresh MediaProjection consent prompt even if an existing
	// grant is still technically held, for a quick manual recovery when
	// system audio capture has silently died (e.g. the system revoked
	// the projection) - refreshAudioCaptureSource() alone wouldn't
	// re-prompt in that case, since it only asks again when the held
	// MediaProjection reference is null. First checks whether capture is
	// demonstrably still working (see ShaderRenderer.isReceivingAudio())
	// and skips the whole disruptive dance if so.
	public void reacquireSystemAudio() {
		shaderView.queueEvent(() -> {
			ShaderRenderer renderer = shaderView.getRenderer();
			if (renderer.isReceivingAudio()) {
				activity.runOnUiThread(() -> Toast.makeText(activity,
						R.string.audio_capture_already_working, Toast.LENGTH_SHORT).show());
				return;
			}
			renderer.releaseMediaProjection();
			renderer.resetAudioCaptureConsent();
			renderer.refreshAudioCaptureSource();
		});
	}

	public void setCropBorderPixel(boolean cropBorderPixel) {
		shaderView.queueEvent(() ->
				shaderView.getRenderer().setCropBorderPixel(cropBorderPixel));
	}

	public void setQuality(float quality) {
		for (int i = 0; i < qualityValues.length; ++i) {
			if (qualityValues[i] == quality) {
				qualitySpinner.setSelection(i);
				this.quality = quality;
				return;
			}
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private void initShaderView() {
		// Always returns false from the listener, so this never affects
		// ShaderView.onTouchEvent()'s own handling (e.g. touch-reactive
		// shader uniforms) - it only observes events alongside it.
		shaderView.setOnTouchListener((v, event) -> fullscreenExitGestureDetector.onTouch(event));
		shaderView.getRenderer().setOnRendererListener(new ShaderRenderer.OnRendererListener() {
			@Override
			public void onFramesPerSecond(int fps) {
				listener.onFramesPerSecond(fps);
			}

			@Override
			public void onInfoLog(@NonNull List<ShaderError> infoLog) {
				listener.onInfoLog(infoLog);
			}

			@Override
			public void onRequestAudioCapture() {
				listener.onRequestAudioCapture();
			}
		});
	}

	private void initQualitySpinner(@NonNull Activity activity) {
		String[] qualityStringValues =
				activity.getResources().getStringArray(R.array.quality_values);
		qualityValues = new float[qualityStringValues.length];
		for (int i = 0; i < qualityStringValues.length; ++i) {
			qualityValues[i] = Float.parseFloat(qualityStringValues[i]);
		}

		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
				activity,
				R.array.quality_names,
				R.layout.spinner_item_quality);
		adapter.setDropDownViewResource(
				android.R.layout.simple_spinner_dropdown_item);
		qualitySpinner.setAdapter(adapter);
		qualitySpinner.setOnItemSelectedListener(new Spinner.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View view,
					int position, long id) {
				float q = qualityValues[position];
				if (q == quality) {
					return;
				}
				quality = q;
				listener.onQualityChanged(quality);
				// Refresh renderer with new quality
				shaderView.getRenderer().setQuality(quality);
				if (!paused) {
					shaderView.onPause();
					shaderView.onResume();
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
	}
}
