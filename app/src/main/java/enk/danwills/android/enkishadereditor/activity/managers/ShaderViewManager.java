package enk.danwills.android.enkishadereditor.activity.managers;

import android.app.Activity;
import android.media.projection.MediaProjection;
import android.opengl.GLSurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.project.ShaderProjectSession;
import enk.danwills.android.enkishadereditor.widget.ShaderView;

public class ShaderViewManager {
	public interface Listener {
		void onFramesPerSecond(int fps);

		void onInfoLog(@NonNull List<ShaderError> infoLog);

		void onQualityChanged(float quality);

		void onRequestAudioCapture();
	}

	@NonNull
	private final ShaderView shaderView;
	@NonNull
	private final Spinner qualitySpinner;
	@NonNull
	private final Listener listener;
	@NonNull
	private float[] qualityValues;
	private float quality = 1f;
	private boolean paused = false;
	private boolean needsRealResume = false;

	public ShaderViewManager(@NonNull Activity activity,
			@NonNull ShaderView shaderView,
			@NonNull Spinner qualitySpinner,
			@NonNull Listener listener) {
		this.shaderView = shaderView;
		this.qualitySpinner = qualitySpinner;
		this.listener = listener;

		initQualitySpinner(activity);
		initShaderView();
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
		shaderView.setFragmentShader(src, quality);
	}

	public void setProjectSession(@NonNull ShaderProjectSession projectSession) {
		if (paused) {
			return;
		}
		setQuality(projectSession.getQuality());
		shaderView.setFragmentShader(
				projectSession.getEntryPointSource(),
				projectSession.getQuality());
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

	private void initShaderView() {
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
				android.R.layout.simple_spinner_item);
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
