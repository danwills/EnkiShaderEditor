package enk.danwills.android.enkishadereditor.activity.managers;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.media.projection.MediaProjection;
import android.opengl.GLSurfaceView;
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
					@NonNull String sourceHash, @NonNull String driverSignature) {
				try {
					binaryCacheExecutor.execute(() -> dataSource.shader.saveCompiledBinary(
							shaderId, binary, sourceHash, driverSignature));
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
		if (paused) {
			return;
		}
		if (shaderView.getVisibility() == View.VISIBLE) {
			shaderView.onResume();
		}
	}

	public boolean isPaused() {
		return paused;
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
