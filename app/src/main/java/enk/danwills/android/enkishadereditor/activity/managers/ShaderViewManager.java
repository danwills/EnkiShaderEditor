package enk.danwills.android.enkishadereditor.activity.managers;

import android.app.Activity;
import android.media.projection.MediaProjection;
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

	public void onPause() {
		if (shaderView.getVisibility() == View.VISIBLE) {
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
	// and execution (the GL render thread is stopped, so the last
	// rendered frame stays on screen and uniforms like `time` stop
	// advancing).
	public void setPaused(boolean paused) {
		if (this.paused == paused) {
			return;
		}
		this.paused = paused;
		if (shaderView.getVisibility() != View.VISIBLE) {
			return;
		}
		if (paused) {
			shaderView.onPause();
		} else {
			shaderView.onResume();
		}
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
