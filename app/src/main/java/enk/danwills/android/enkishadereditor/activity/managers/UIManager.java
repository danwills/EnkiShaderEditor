package enk.danwills.android.enkishadereditor.activity.managers;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.fragment.EditorFragment;
import enk.danwills.android.enkishadereditor.widget.TouchThruDrawerLayout;

public class UIManager {
	private final AppCompatActivity activity;
	private final EditorFragment editorFragment;
	private final ExtraKeysManager extraKeysManager;
	private final ShaderViewManager shaderViewManager;
	private final Toolbar toolbar;
	private final TouchThruDrawerLayout drawerLayout;
	@Nullable
	private final View mainLayout;
	@Nullable
	private final View navbar;
	@Nullable
	private final View toolbarContainer;
	@Nullable
	private final TextView shaderNameOverlay;
	@Nullable
	private View pauseShaderNavButton;
	private final Handler overlayHandler = new Handler(Looper.getMainLooper());
	private final Runnable hideOverlayRunnable = this::fadeOutShaderNameOverlay;

	public final ActionBarDrawerToggle drawerToggle;

	private boolean fullscreen = false;
	private boolean toolbarHidden = false;

	public UIManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editorFragment,
			@NonNull ExtraKeysManager extraKeysManager,
			@NonNull ShaderViewManager shaderViewManager) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.extraKeysManager = extraKeysManager;
		this.shaderViewManager = shaderViewManager;

		toolbar = activity.findViewById(R.id.toolbar);
		activity.setSupportActionBar(toolbar);

		drawerLayout = activity.findViewById(R.id.drawer_layout);
		drawerToggle = new ActionBarDrawerToggle(
				activity, drawerLayout, toolbar,
				R.string.drawer_open, R.string.drawer_close);
		drawerToggle.setDrawerIndicatorEnabled(true);
		drawerLayout.addDrawerListener(drawerToggle);

		mainLayout = activity.findViewById(R.id.main_layout);
		navbar = activity.findViewById(R.id.navbar);
		toolbarContainer = activity.findViewById(R.id.toolbar_container);
		shaderNameOverlay = activity.findViewById(R.id.shader_name_overlay);
	}

	public void setupToolbar(View.OnClickListener menuClickListener,
			View.OnClickListener runClickListener,
			View.OnClickListener toggleCodeClickListener,
			View.OnClickListener showErrorClickListener) {
		View menuBtn = toolbar.findViewById(R.id.menu_btn);
		ViewCompat.setTooltipText(menuBtn, activity.getText(R.string.menu_btn));
		menuBtn.setOnClickListener(menuClickListener);

		View runCode = toolbar.findViewById(R.id.run_code);
		ViewCompat.setTooltipText(runCode, activity.getText(R.string.run_code));
		runCode.setOnClickListener(runClickListener);

		View toggleCode = toolbar.findViewById(R.id.toggle_code);
		ViewCompat.setTooltipText(toggleCode, activity.getText(R.string.toggle_code));
		toggleCode.setOnClickListener(toggleCodeClickListener);

		View showErrors = toolbar.findViewById(R.id.show_errors);
		ViewCompat.setTooltipText(showErrors, activity.getText(R.string.show_errors));
		showErrors.setOnClickListener(showErrorClickListener);
	}

	public void setupShaderNavRow(View.OnClickListener previousClickListener,
			View.OnClickListener nextClickListener,
			View.OnClickListener randomClickListener,
			View.OnClickListener undoClickListener,
			View.OnClickListener pauseClickListener) {
		View previous = activity.findViewById(R.id.previous_shader);
		ViewCompat.setTooltipText(previous, activity.getText(R.string.previous_shader));
		previous.setOnClickListener(previousClickListener);

		View next = activity.findViewById(R.id.next_shader);
		ViewCompat.setTooltipText(next, activity.getText(R.string.next_shader));
		next.setOnClickListener(nextClickListener);

		View random = activity.findViewById(R.id.random_shader);
		ViewCompat.setTooltipText(random, activity.getText(R.string.random_shader));
		random.setOnClickListener(randomClickListener);

		View undo = activity.findViewById(R.id.undo_nav);
		ViewCompat.setTooltipText(undo, activity.getText(R.string.undo));
		undo.setOnClickListener(undoClickListener);

		pauseShaderNavButton = activity.findViewById(R.id.pause_shader_nav);
		ViewCompat.setTooltipText(pauseShaderNavButton, activity.getText(R.string.pause_shader));
		pauseShaderNavButton.setOnClickListener(pauseClickListener);
	}

	// Keeps the extra-keys-bar pause button and this always-visible
	// toolbar one in sync, since either can be used to toggle pause.
	public void setPauseButtonState(boolean paused) {
		extraKeysManager.setPauseButtonState(paused);
		if (pauseShaderNavButton instanceof MaterialButton button) {
			button.setIconResource(paused ? R.drawable.ic_play_arrow : R.drawable.ic_pause);
		}
		if (pauseShaderNavButton != null) {
			pauseShaderNavButton.setContentDescription(activity.getText(
					paused ? R.string.resume_shader : R.string.pause_shader));
		}
	}

	public void updateUiToPreferences() {
		boolean runInBackground =
				ShaderEditorApp.preferences.doesRunInBackground();
		shaderViewManager.setVisibility(runInBackground);
		toolbar.findViewById(R.id.toggle_code).setVisibility(
				runInBackground ? View.VISIBLE : View.GONE);
		if (!runInBackground && !editorFragment.isCodeVisible()) {
			toggleCodeVisibility();
		}
		toolbar.findViewById(R.id.run_code).setVisibility(
				!ShaderEditorApp.preferences.doesRunOnChange()
						? View.VISIBLE
						: View.GONE);
		extraKeysManager.updateVisibility();
		editorFragment.setShowLineNumbers(
				ShaderEditorApp.preferences.showLineNumbers());
		editorFragment.updateHighlighting();
		activity.invalidateOptionsMenu();
	}

	public void toggleCodeVisibility() {
		boolean isVisible = editorFragment.toggleCode();
		drawerLayout.setTouchThru(isVisible);
		extraKeysManager.setVisible(!isVisible &&
				ShaderEditorApp.preferences.showExtraKeys());
	}

	public void setToolbarTitle(String name) {
		toolbar.setTitle(name);
		toolbar.setSubtitle(null);
	}

	public void closeDrawers() {
		drawerLayout.closeDrawers();
	}

	public void setToolbarSubtitle(String subtitle) {
		toolbar.post(() -> toolbar.setSubtitle(subtitle));
	}

	public boolean isFullscreen() {
		return fullscreen;
	}

	// Hides every bit of our own UI (toolbar, drawer/editor, extra keys)
	// as well as the system status/navigation bars, leaving only the
	// shader visible. The system bars stay revealable by the usual
	// edge-swipe gesture rather than being fully locked out.
	public void setFullscreen(boolean fullscreen) {
		this.fullscreen = fullscreen;

		if (mainLayout != null) {
			mainLayout.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
		}
		if (navbar != null) {
			boolean showNavbarOverlay = !fullscreen &&
					Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM;
			navbar.setVisibility(showNavbarOverlay ? View.VISIBLE : View.GONE);
		}

		WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
				activity.getWindow(), activity.getWindow().getDecorView());
		if (fullscreen) {
			controller.setSystemBarsBehavior(
					WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			controller.hide(WindowInsetsCompat.Type.systemBars());
		} else {
			controller.show(WindowInsetsCompat.Type.systemBars());
		}
	}

	public boolean isToolbarHidden() {
		return toolbarHidden;
	}

	// Hides just the toolbar (both rows), unlike setFullscreen() this
	// leaves the drawer, editor, extra keys and system bars alone.
	public void setToolbarHidden(boolean toolbarHidden) {
		this.toolbarHidden = toolbarHidden;
		if (toolbarContainer != null) {
			toolbarContainer.setVisibility(toolbarHidden ? View.GONE : View.VISIBLE);
		}
	}

	public void showShaderNameOverlay(@Nullable String name) {
		if (shaderNameOverlay == null || name == null || name.isEmpty()) {
			return;
		}
		overlayHandler.removeCallbacks(hideOverlayRunnable);
		shaderNameOverlay.animate().cancel();
		shaderNameOverlay.setText(name);
		shaderNameOverlay.setVisibility(View.VISIBLE);
		shaderNameOverlay.setAlpha(0f);
		shaderNameOverlay.animate()
				.alpha(1f)
				.setDuration(150)
				.withEndAction(() ->
						overlayHandler.postDelayed(hideOverlayRunnable, 1200))
				.start();
	}

	private void fadeOutShaderNameOverlay() {
		if (shaderNameOverlay == null) {
			return;
		}
		shaderNameOverlay.animate()
				.alpha(0f)
				.setDuration(400)
				.withEndAction(() -> shaderNameOverlay.setVisibility(View.GONE))
				.start();
	}
}
