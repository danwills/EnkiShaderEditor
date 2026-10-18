package enk.danwills.android.enkishadereditor.activity.managers;

import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.function.Consumer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.button.MaterialButton;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.fragment.EditorFragment;
import enk.danwills.android.enkishadereditor.io.ScreenshotSaver;
import enk.danwills.android.enkishadereditor.widget.FlowLayout;
import enk.danwills.android.enkishadereditor.widget.FullscreenExitGestureDetector;
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
	private final TextView fullscreenExitHint;
	@Nullable
	private final View searchMatchIndicator;
	@NonNull
	private final FlowLayout actionButtonsRow;
	@NonNull
	private final TextView fpsLabel;
	@NonNull
	private final TextView shaderTitleLabel;
	@NonNull
	private final View shaderTitleRow;
	@Nullable
	private View pauseShaderNavButton;
	@Nullable
	private Consumer<Boolean> onCodeVisibilityChanged;
	private final Handler overlayHandler = new Handler(Looper.getMainLooper());
	private final Runnable hideOverlayRunnable = this::fadeOutShaderNameOverlay;

	public final ActionBarDrawerToggle drawerToggle;

	private boolean fullscreen = false;
	private boolean toolbarHidden = false;
	private boolean drawerOpen = false;
	@Nullable
	private Runnable onDrawerOpenedListener;

	public UIManager(@NonNull AppCompatActivity activity,
			@NonNull EditorFragment editorFragment,
			@NonNull ExtraKeysManager extraKeysManager,
			@NonNull ShaderViewManager shaderViewManager) {
		this.activity = activity;
		this.editorFragment = editorFragment;
		this.extraKeysManager = extraKeysManager;
		this.shaderViewManager = shaderViewManager;

		// Zero-size and invisible (see toolbar_main.xml) - kept only so
		// ActionBarDrawerToggle below has a real Toolbar to drive.
		toolbar = activity.findViewById(R.id.toolbar);
		activity.setSupportActionBar(toolbar);

		drawerLayout = activity.findViewById(R.id.drawer_layout);
		drawerToggle = new ActionBarDrawerToggle(
				activity, drawerLayout, toolbar,
				R.string.drawer_open, R.string.drawer_close);
		drawerToggle.setDrawerIndicatorEnabled(true);
		drawerLayout.addDrawerListener(drawerToggle);
		// The sidebar's own top buttons sit right where shaderTitleLabel
		// floats, so hide the label whenever the drawer is open rather
		// than let it cover them.
		drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
			@Override
			public void onDrawerOpened(@NonNull View drawerView) {
				drawerOpen = true;
				updateShaderTitleLabelVisibility();
				if (onDrawerOpenedListener != null) {
					onDrawerOpenedListener.run();
				}
			}

			@Override
			public void onDrawerClosed(@NonNull View drawerView) {
				drawerOpen = false;
				updateShaderTitleLabelVisibility();
			}
		});

		mainLayout = activity.findViewById(R.id.main_layout);
		navbar = activity.findViewById(R.id.navbar);
		toolbarContainer = activity.findViewById(R.id.toolbar_container);
		shaderNameOverlay = activity.findViewById(R.id.shader_name_overlay);
		fullscreenExitHint = activity.findViewById(R.id.fullscreen_exit_hint);
		searchMatchIndicator = activity.findViewById(R.id.search_match_indicator);
		actionButtonsRow = activity.findViewById(R.id.action_buttons_row);
		fpsLabel = activity.findViewById(R.id.fps_label);
		shaderTitleLabel = activity.findViewById(R.id.shader_title_label);
		shaderTitleRow = activity.findViewById(R.id.shader_title_row);

		// Keeps shaderTitleRow pinned just below the toolbar no matter
		// how many rows it's currently wrapped to (icon size, rotation,
		// or being hidden entirely all change its height at runtime).
		if (toolbarContainer != null) {
			toolbarContainer.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
				int toolbarHeight = toolbarContainer.getHeight();
				var lp = (ViewGroup.MarginLayoutParams) shaderTitleRow.getLayoutParams();
				if (lp.topMargin != toolbarHeight) {
					lp.topMargin = toolbarHeight;
					shaderTitleRow.setLayoutParams(lp);
				}
			});
		}
		// Pushes the editor down by exactly shaderTitleRow's own
		// (variable - name length/wrapping can change it) height, so a
		// long wrapped name can never overlap the code below it.
		shaderTitleRow.getViewTreeObserver().addOnGlobalLayoutListener(() ->
				editorFragment.setTopInset(shaderTitleRow.getHeight()));
		// Matches editorContainer's default XML visibility (visible) -
		// toggleCodeVisibility() keeps this in sync from here on.
		applyShaderTitleRowBackground(true);

		// The real, user-visible drawer button: flows as an ordinary
		// member of actionButtonsRow instead of living inside the hidden
		// toolbar above. Uses a plain static icon (set in XML) rather
		// than ActionBarDrawerToggle's animated hamburger<->arrow
		// Drawable - that Drawable is also being driven by the hidden,
		// zero-size toolbar above, and sharing one Drawable instance
		// between two differently-sized Views fighting over its bounds
		// each layout pass produced visibly corrupted icon rendering.
		// Clicks are handled directly since this button isn't wired
		// through the toolbar's own navigation-icon click path.
		View drawerToggleBtn = activity.findViewById(R.id.drawer_toggle_btn);
		ViewCompat.setTooltipText(drawerToggleBtn, activity.getText(R.string.drawer_open));
		drawerToggleBtn.setOnClickListener(v -> {
			if (isDrawerOpen()) {
				closeDrawer();
			} else {
				drawerLayout.openDrawer(GravityCompat.START);
			}
		});
	}

	public void setupToolbar(View.OnClickListener menuClickListener,
			View.OnClickListener runClickListener,
			View.OnClickListener toggleCodeClickListener) {
		View menuBtn = activity.findViewById(R.id.menu_btn);
		ViewCompat.setTooltipText(menuBtn, activity.getText(R.string.menu_btn));
		menuBtn.setOnClickListener(menuClickListener);

		View runCode = activity.findViewById(R.id.run_code);
		ViewCompat.setTooltipText(runCode, activity.getText(R.string.run_code));
		runCode.setOnClickListener(runClickListener);

		View toggleCode = activity.findViewById(R.id.toggle_code);
		ViewCompat.setTooltipText(toggleCode, activity.getText(R.string.toggle_code));
		toggleCode.setOnClickListener(toggleCodeClickListener);
	}

	// Tapping the shader-name label opens the same rename dialog as
	// long-pressing the shader in the sidebar; long-pressing the label
	// itself just shows the tooltip, same as every other toolbar button.
	public void setupShaderTitleLabel(View.OnClickListener renameClickListener) {
		ViewCompat.setTooltipText(shaderTitleLabel,
				activity.getText(R.string.rename_shader_tooltip));
		shaderTitleLabel.setOnClickListener(renameClickListener);
	}

	// Hidden outright on API <29, where ScreenshotSaver can't write to
	// the gallery (see its own doc comment) - a dead button would be
	// worse than no button there.
	public void setupScreenshotButton(View.OnClickListener screenshotClickListener) {
		View screenshotBtn = activity.findViewById(R.id.screenshot_nav);
		if (!ScreenshotSaver.isSupported()) {
			screenshotBtn.setVisibility(View.GONE);
			return;
		}
		ViewCompat.setTooltipText(screenshotBtn, activity.getText(R.string.save_screenshot));
		screenshotBtn.setOnClickListener(screenshotClickListener);
	}

	public void setupShaderNavRow(View.OnClickListener previousClickListener,
			View.OnClickListener nextClickListener,
			View.OnClickListener randomClickListener,
			View.OnClickListener undoClickListener,
			View.OnClickListener pauseClickListener,
			View.OnClickListener restartClickListener,
			View.OnClickListener fullscreenClickListener) {
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

		View restart = activity.findViewById(R.id.restart_shader_nav);
		ViewCompat.setTooltipText(restart, activity.getText(R.string.restart_shader));
		restart.setOnClickListener(restartClickListener);

		View fullscreen = activity.findViewById(R.id.fullscreen_nav);
		ViewCompat.setTooltipText(fullscreen, activity.getText(R.string.fullscreen));
		fullscreen.setOnClickListener(fullscreenClickListener);
	}

	public void setupAudioNavRow(View.OnClickListener reacquireClickListener) {
		View reacquire = activity.findViewById(R.id.reacquire_audio);
		ViewCompat.setTooltipText(reacquire, activity.getText(R.string.reacquire_audio));
		reacquire.setOnClickListener(reacquireClickListener);
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
		applyStatusBarVisibility();
		applyToolbarButtonVisibility();
		boolean runInBackground =
				ShaderEditorApp.preferences.doesRunInBackground();
		if (!runInBackground && !editorFragment.isCodeVisible()) {
			toggleCodeVisibility();
		}
		extraKeysManager.updateVisibility();
		editorFragment.setShowLineNumbers(
				ShaderEditorApp.preferences.showLineNumbers());
		editorFragment.updateHighlighting();
		activity.invalidateOptionsMenu();
	}

	// Just the subset of updateUiToPreferences() above that affects the
	// action-buttons FlowLayout's own line-wrapping (toggle_code/
	// run_code/reacquire_audio's visibility, every button's size) -
	// split out so MainActivity.onCreate() can apply it before the
	// toolbar is ever actually drawn, not just from onResume() (which
	// runs after the very first layout pass, since it only fires once
	// the Activity's already on its way to becoming visible). Safe to
	// call that early specifically because it never touches
	// editorFragment - unlike the rest of updateUiToPreferences() above,
	// its view isn't guaranteed to exist yet at that point (the
	// fragment transaction that creates it is merely queued at the
	// point onCreate() needs this, not yet executed).
	public void applyToolbarButtonVisibility() {
		boolean runInBackground =
				ShaderEditorApp.preferences.doesRunInBackground();
		shaderViewManager.setVisibility(runInBackground);
		activity.findViewById(R.id.toggle_code).setVisibility(
				runInBackground ? View.VISIBLE : View.GONE);
		activity.findViewById(R.id.run_code).setVisibility(
				!ShaderEditorApp.preferences.doesRunOnChange()
						? View.VISIBLE
						: View.GONE);
		activity.findViewById(R.id.reacquire_audio).setVisibility(
				ShaderEditorApp.preferences.useSystemAudio() ? View.VISIBLE : View.GONE);
		applyIconSize();
	}

	// Resizes every button (and the quality spinner's height) in the
	// action-buttons flow row to the "Icon Size" preference. Generic
	// over whatever children the row happens to contain, rather than a
	// hardcoded id list, so it keeps working if buttons are added or
	// removed there later.
	private void applyIconSize() {
		var preferences = ShaderEditorApp.preferences;
		int buttonSizePx = dpToPx(preferences.getIconButtonSizeDp());
		int iconSizePx = dpToPx(preferences.getIconGlyphSizeDp());
		for (int i = 0; i < actionButtonsRow.getChildCount(); i++) {
			View child = actionButtonsRow.getChildAt(i);
			ViewGroup.LayoutParams lp = child.getLayoutParams();
			if (child instanceof MaterialButton button) {
				lp.width = buttonSizePx;
				lp.height = buttonSizePx;
				button.setIconSize(iconSizePx);
			} else {
				lp.height = buttonSizePx;
			}
			child.setLayoutParams(lp);
		}
	}

	private int dpToPx(int dp) {
		return Math.round(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, dp, activity.getResources().getDisplayMetrics()));
	}

	public void toggleCodeVisibility() {
		boolean wasVisible = editorFragment.toggleCode();
		boolean nowVisible = !wasVisible;
		drawerLayout.setTouchThru(wasVisible);
		extraKeysManager.setVisible(!wasVisible &&
				ShaderEditorApp.preferences.showExtraKeys());
		applyShaderTitleRowBackground(nowVisible);
		applyStatusBarVisibility();
		if (onCodeVisibilityChanged != null) {
			onCodeVisibilityChanged.accept(nowVisible);
		}
	}

	// codeVisible: an edge-to-edge darkened bar so shaderTitleRow (the
	// name, and the fps reading beside it) stays readable over the
	// editor's code text - at 75% opacity rather than fully opaque, so
	// the shader is still faintly visible through it instead of fully
	// hidden behind a solid strip; !codeVisible (the shader preview
	// showing through instead): no background needed. Built from the
	// theme color in code, not an XML drawable resource, since baking a
	// fixed alpha into XML would fight the color's own light/dark theme
	// resolution - resolving the attribute here keeps that automatic.
	private void applyShaderTitleRowBackground(boolean codeVisible) {
		if (!codeVisible) {
			shaderTitleRow.setBackground(null);
			return;
		}
		TypedValue typedValue = new TypedValue();
		activity.getTheme().resolveAttribute(
				com.google.android.material.R.attr.colorSurfaceContainerLow, typedValue, true);
		int opaque = typedValue.data;
		int translucent = (opaque & 0x00FFFFFF) | (Math.round(0.75f * 255) << 24);
		shaderTitleRow.setBackgroundColor(translucent);
	}

	// shaderTitleRow lives outside mainLayout (so it can float over the
	// code editor without being clipped by it), so neither fullscreen
	// mode nor the drawer opening automatically hides it the way the
	// rest of the UI does - hide it explicitly whenever either is true,
	// since it'd otherwise cover the fullscreen shader or the sidebar's
	// own top buttons.
	private void updateShaderTitleLabelVisibility() {
		shaderTitleRow.setVisibility(
				fullscreen || drawerOpen ? View.GONE : View.VISIBLE);
	}

	// Called whenever the code editor's visibility actually changes -
	// via the toolbar's toggle button, state restoration, or being
	// forced visible by updateUiToPreferences() below - so a listener
	// never misses a transition regardless of what triggered it.
	public void setOnCodeVisibilityChangedListener(@Nullable Consumer<Boolean> listener) {
		onCodeVisibilityChanged = listener;
	}

	// Also resets the fps label so it doesn't keep showing a stale
	// reading from whatever shader was previously selected.
	public void setToolbarTitle(String name) {
		fpsLabel.setText(null);
		shaderTitleLabel.setText(name);
	}

	public void setToolbarSubtitle(String subtitle) {
		fpsLabel.post(() -> fpsLabel.setText(subtitle));
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
		updateShaderTitleLabelVisibility();
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
			// Only the nav bar is unconditionally restored here - the
			// status bar instead defers to whether the code editor is
			// visible, same as it would on any other (non-fullscreen)
			// screen in the app.
			controller.show(WindowInsetsCompat.Type.navigationBars());
			applyStatusBarVisibility();
		}
	}

	// Shows the status bar while the code editor is visible (so the
	// clock/notifications stay reachable during a long edit) and hides
	// it otherwise, maximizing the shader preview - independent of
	// setFullscreen(), which already hides every system bar outright
	// while active. Swiping from the top edge still reveals a hidden
	// status bar transiently either way.
	public void applyStatusBarVisibility() {
		if (fullscreen) {
			return;
		}
		WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(
				activity.getWindow(), activity.getWindow().getDecorView());
		if (editorFragment.isCodeVisible()) {
			controller.show(WindowInsetsCompat.Type.statusBars());
		} else {
			controller.setSystemBarsBehavior(
					WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
			controller.hide(WindowInsetsCompat.Type.statusBars());
		}
		// Some OEM skins don't reliably redeliver onApplyWindowInsets()
		// off the back of a show()/hide() call above on their own -
		// without this, action_buttons_row's top padding (set in
		// SystemBarMetrics, compensating for exactly this bar) can go
		// stale and the toolbar ends up drawn partly underneath the
		// now-visible status bar.
		if (mainLayout != null) {
			ViewCompat.requestApplyInsets(mainLayout);
		}
	}

	public boolean isDrawerOpen() {
		return drawerLayout.isDrawerOpen(GravityCompat.START);
	}

	// Notified every time the drawer finishes opening, regardless of how
	// (hamburger tap, swipe, openDrawer()/openDrawer(Runnable)) - for
	// state that should drop once the sidebar comes up, like the
	// Find-in-Shader ring no longer being relevant once the editor's
	// covered.
	public void setOnDrawerOpenedListener(@Nullable Runnable listener) {
		onDrawerOpenedListener = listener;
	}

	public void closeDrawer() {
		drawerLayout.closeDrawer(GravityCompat.START);
	}

	public void openDrawer() {
		drawerLayout.openDrawer(GravityCompat.START);
	}

	// Like openDrawer(), but runs `onOpened` once the drawer has actually
	// settled open rather than immediately - opening is an animated
	// slide, so code that needs the drawer's content laid out at its
	// final size (e.g. scrolling its list to a specific row) run right
	// after a plain openDrawer() can race that animation and silently
	// have no visible effect. Runs `onOpened` synchronously instead if
	// the drawer is already open.
	public void openDrawer(@NonNull Runnable onOpened) {
		if (isDrawerOpen()) {
			onOpened.run();
			return;
		}
		drawerLayout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
			@Override
			public void onDrawerOpened(@NonNull View drawerView) {
				drawerLayout.removeDrawerListener(this);
				onOpened.run();
			}
		});
		drawerLayout.openDrawer(GravityCompat.START);
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

	// Draws an animated ring around `screenRect` (absolute screen
	// coordinates, e.g. from ShaderEditor.getMatchBoundsOnScreen()) -
	// pops in with a quick scale-down-to-size + fade-in and then stays
	// put. Used to make a Find-in-Shader match obvious at a glance once
	// the editor's scrolled it into view, not just technically selected.
	// Call hideSearchMatch() to remove it (e.g. once the source changes).
	public void flashSearchMatch(@NonNull Rect screenRect) {
		if (searchMatchIndicator == null) {
			return;
		}
		positionSearchMatchIndicator(screenRect);

		searchMatchIndicator.animate().cancel();
		searchMatchIndicator.setVisibility(View.VISIBLE);
		searchMatchIndicator.setAlpha(0f);
		searchMatchIndicator.setScaleX(1.6f);
		searchMatchIndicator.setScaleY(1.6f);
		searchMatchIndicator.animate()
				.alpha(1f)
				.scaleX(1f)
				.scaleY(1f)
				.setDuration(220)
				.start();
	}

	// Moves the ring already shown by flashSearchMatch() to `screenRect`
	// without animating or touching visibility/alpha - for keeping it
	// tracking the match's actual on-screen position as the editor
	// scrolls underneath it. A no-op if the ring isn't currently shown.
	public void repositionSearchMatch(@NonNull Rect screenRect) {
		if (searchMatchIndicator == null || searchMatchIndicator.getVisibility() != View.VISIBLE) {
			return;
		}
		positionSearchMatchIndicator(screenRect);
	}

	private void positionSearchMatchIndicator(@NonNull Rect screenRect) {
		ViewGroup parent = (ViewGroup) searchMatchIndicator.getParent();
		int[] parentLocation = new int[2];
		parent.getLocationOnScreen(parentLocation);

		int pad = dpToPx(6);
		ViewGroup.LayoutParams lp = searchMatchIndicator.getLayoutParams();
		lp.width = screenRect.width() + pad * 2;
		lp.height = screenRect.height() + pad * 2;
		searchMatchIndicator.setLayoutParams(lp);
		searchMatchIndicator.setX(screenRect.left - parentLocation[0] - pad);
		searchMatchIndicator.setY(screenRect.top - parentLocation[1] - pad);
	}

	// Removes the ring drawn by flashSearchMatch(), if present.
	public void hideSearchMatch() {
		if (searchMatchIndicator == null) {
			return;
		}
		searchMatchIndicator.animate().cancel();
		searchMatchIndicator.setVisibility(View.GONE);
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

	// The fullscreen-exit gesture's "about to exit" cue, in its PENDING
	// phase: fades in over FullscreenExitGestureDetector.ARM_DELAY_MS,
	// the same duration the gesture needs to hold before arming, so
	// release only starts counting as an exit once this finishes
	// appearing. Stays up for as long as the gesture is pending/armed,
	// however long that turns out to be - dismissed explicitly via
	// hideFullscreenExitHint() instead of any auto-hide.
	public void showFullscreenExitHintPending() {
		if (fullscreenExitHint == null) {
			return;
		}
		fullscreenExitHint.animate().cancel();
		fullscreenExitHint.setBackgroundResource(R.drawable.background_menu);
		fullscreenExitHint.setVisibility(View.VISIBLE);
		fullscreenExitHint.setAlpha(0f);
		fullscreenExitHint.animate()
				.alpha(1f)
				.setDuration(FullscreenExitGestureDetector.ARM_DELAY_MS)
				.start();
	}

	// The gesture's ARMED phase: releasing now actually exits
	// fullscreen, marked with a yellow outline on the already-visible
	// hint from showFullscreenExitHintPending().
	public void armFullscreenExitHint() {
		if (fullscreenExitHint == null) {
			return;
		}
		fullscreenExitHint.setBackgroundResource(R.drawable.background_fullscreen_exit_armed);
	}

	public void hideFullscreenExitHint() {
		if (fullscreenExitHint == null) {
			return;
		}
		fullscreenExitHint.animate()
				.alpha(0f)
				.setDuration(150)
				.withEndAction(() -> fullscreenExitHint.setVisibility(View.GONE))
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
