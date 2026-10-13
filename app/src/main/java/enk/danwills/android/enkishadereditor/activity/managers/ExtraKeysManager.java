package enk.danwills.android.enkishadereditor.activity.managers;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Objects;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.adapter.CompletionsAdapter;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;

public class ExtraKeysManager implements ViewTreeObserver.OnGlobalLayoutListener {
	public interface Editor {
		void insert(@NonNull CharSequence text);
	}

	@NonNull
	private final View extraKeysView;
	@NonNull
	private final View insertTabView;
	@NonNull
	private final Button pauseShaderButton;
	@NonNull
	private final CompletionsAdapter completionsAdapter;

	public ExtraKeysManager(@NonNull Activity activity,
			@NonNull View rootView, @NonNull Editor editor,
			@NonNull Runnable onTogglePauseShader) {
		this.extraKeysView = rootView.findViewById(R.id.extra_keys);

		insertTabView = extraKeysView.findViewById(R.id.insert_tab);
		insertTabView.setOnClickListener(v -> editor.insert("\t"));

		pauseShaderButton = extraKeysView.findViewById(R.id.extra_pause_shader);
		pauseShaderButton.setOnClickListener(v -> onTogglePauseShader.run());

		RecyclerView completions = extraKeysView.findViewById(R.id.completions);
		completions.setLayoutManager(new LinearLayoutManager(
				activity, RecyclerView.HORIZONTAL, false));
		completionsAdapter = new CompletionsAdapter(editor::insert);
		completions.setAdapter(completionsAdapter);

		var divider = new DividerItemDecoration(
				completions.getContext(), DividerItemDecoration.HORIZONTAL);
		divider.setDrawable(Objects.requireNonNull(ContextCompat.getDrawable(
				activity, R.drawable.divider_with_padding_vertical)));
		completions.addItemDecoration(divider);
		completions.getViewTreeObserver().addOnGlobalLayoutListener(this);

		updateVisibility();
	}

	public void setCompletions(List<String> completions, int position) {
		completionsAdapter.submitCompletions(completions, position);
	}

	public void updateVisibility() {
		var preferences = ShaderEditorApp.preferences;
		extraKeysView.setVisibility(
				getVisibility(preferences.showExtraKeys()));
		insertTabView.setVisibility(
				getVisibility(preferences.doesShowInsertTab()));
	}

	public void setVisible(boolean visible) {
		extraKeysView.setVisibility(getVisibility(visible));
	}

	public void setPauseButtonState(boolean paused) {
		((MaterialButton) pauseShaderButton).setIconResource(
				paused ? R.drawable.ic_play_arrow : R.drawable.ic_pause);
		pauseShaderButton.setContentDescription(extraKeysView.getContext().getString(
				paused ? R.string.resume_shader : R.string.pause_shader));
	}

	@Override
	public void onGlobalLayout() {
		var preferences = ShaderEditorApp.preferences;
		if (!preferences.autoHideExtraKeys()) {
			return;
		}
		if (!preferences.showExtraKeys()) {
			extraKeysView.setVisibility(View.GONE);
			return;
		}

		Rect r = new Rect();
		extraKeysView.getWindowVisibleDisplayFrame(r);
		int screenHeight = extraKeysView.getRootView().getHeight();

		// r.bottom is the position above soft keypad or device button.
		// if keypad is shown, the r.bottom is smaller than that before.
		int keypadHeight = screenHeight - r.bottom;
		// 0.15 ratio is perhaps enough to determine keypad height.
		extraKeysView.setVisibility(getVisibility(
				keypadHeight > screenHeight * 0.15));
	}

	private static int getVisibility(boolean isVisible) {
		return isVisible ? View.VISIBLE : View.GONE;
	}
}
