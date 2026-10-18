package enk.danwills.android.enkishadereditor.activity;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.jetbrains.annotations.Contract;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.activity.managers.ExtraKeysManager;
import enk.danwills.android.enkishadereditor.activity.managers.MainMenuManager;
import enk.danwills.android.enkishadereditor.activity.managers.NavigationManager;
import enk.danwills.android.enkishadereditor.activity.managers.ShaderListManager;
import enk.danwills.android.enkishadereditor.activity.managers.ShaderManager;
import enk.danwills.android.enkishadereditor.activity.managers.ShaderViewManager;
import enk.danwills.android.enkishadereditor.activity.managers.UIManager;
import enk.danwills.android.enkishadereditor.adapter.RecordingAdapter;
import enk.danwills.android.enkishadereditor.adapter.ShaderTreeAdapter;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.fragment.EditorFragment;
import enk.danwills.android.enkishadereditor.io.RecentRecording;
import enk.danwills.android.enkishadereditor.io.ScreenshotSaver;
import enk.danwills.android.enkishadereditor.io.VideoEncoder;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.opengl.SharedCaptureContext;
import enk.danwills.android.enkishadereditor.opengl.TileBucketSize;
import enk.danwills.android.enkishadereditor.opengl.TileRenderOrder;
import enk.danwills.android.enkishadereditor.preference.Preferences;
import enk.danwills.android.enkishadereditor.service.AudioCaptureForegroundService;
import enk.danwills.android.enkishadereditor.service.HighResCaptureService;
import enk.danwills.android.enkishadereditor.service.ShaderWallpaperService;
import enk.danwills.android.enkishadereditor.shaderorg.ShaderSimilarity;
import enk.danwills.android.enkishadereditor.view.SoftKeyboard;
import enk.danwills.android.enkishadereditor.view.SystemBarMetrics;
import enk.danwills.android.enkishadereditor.widget.FullscreenExitGestureDetector;

public class MainActivity extends AppCompatActivity {
	private static final String CODE_VISIBLE = "code_visible";
	private static final String AUDIO_CAPTURE_TAG = "AudioCapture";
	private static final String CRASH_RECOVERY_COMMENT = """
			// Shader Editor disabled this shader because it caused a crash while loading.
			// Fix the shader and remove the #if 0 wrapper to re-enable it.
			""";
	// Every builtin uniform a shader could declare, for the "Shader
	// stats" dialog to report which ones this particular shader
	// actually uses. Deliberately excludes ShaderRenderer.UNIFORM_POSITION,
	// which names a vertex attribute, not a fragment uniform.
	private static final String[] BUILTIN_UNIFORM_NAMES = {
			ShaderRenderer.UNIFORM_TIME,
			ShaderRenderer.UNIFORM_RUNTIME,
			ShaderRenderer.UNIFORM_RESOLUTION,
			ShaderRenderer.UNIFORM_MOUSE,
			ShaderRenderer.UNIFORM_TOUCH,
			ShaderRenderer.UNIFORM_TOUCH_START,
			ShaderRenderer.UNIFORM_POINTERS,
			ShaderRenderer.UNIFORM_POINTER_COUNT,
			ShaderRenderer.UNIFORM_OFFSET,
			ShaderRenderer.UNIFORM_FRAME_NUMBER,
			ShaderRenderer.UNIFORM_FTIME,
			ShaderRenderer.UNIFORM_SECOND,
			ShaderRenderer.UNIFORM_SUB_SECOND,
			ShaderRenderer.UNIFORM_START_RANDOM,
			ShaderRenderer.UNIFORM_BACKBUFFER,
			ShaderRenderer.UNIFORM_AUDIO_SPECTRUM,
			ShaderRenderer.UNIFORM_MEDIA_VOLUME,
			ShaderRenderer.UNIFORM_MIC_AMPLITUDE,
			ShaderRenderer.UNIFORM_CAMERA_FRONT,
			ShaderRenderer.UNIFORM_CAMERA_BACK,
			ShaderRenderer.UNIFORM_CAMERA_ADDENT,
			ShaderRenderer.UNIFORM_CAMERA_ORIENTATION,
			ShaderRenderer.UNIFORM_BATTERY,
			ShaderRenderer.UNIFORM_POWER_CONNECTED,
			ShaderRenderer.UNIFORM_DATE,
			ShaderRenderer.UNIFORM_DAYTIME,
			ShaderRenderer.UNIFORM_NIGHT_MODE,
			ShaderRenderer.UNIFORM_NOTIFICATION_COUNT,
			ShaderRenderer.UNIFORM_LAST_NOTIFICATION_TIME,
			ShaderRenderer.UNIFORM_ORIENTATION,
			ShaderRenderer.UNIFORM_GRAVITY,
			ShaderRenderer.UNIFORM_LINEAR,
			ShaderRenderer.UNIFORM_MAGNETIC,
			ShaderRenderer.UNIFORM_GYROSCOPE,
			ShaderRenderer.UNIFORM_ROTATION_VECTOR,
			ShaderRenderer.UNIFORM_ROTATION_MATRIX,
			ShaderRenderer.UNIFORM_INCLINATION,
			ShaderRenderer.UNIFORM_INCLINATION_MATRIX,
			ShaderRenderer.UNIFORM_PRESSURE,
			ShaderRenderer.UNIFORM_PROXIMITY,
			ShaderRenderer.UNIFORM_LIGHT,
	};
	// Sampler-typed builtin uniforms, excluded when counting a shader's
	// *custom* texture declarations in the stats dialog.
	private static final Set<String> BUILTIN_SAMPLER_NAMES = Set.of(
			ShaderRenderer.UNIFORM_AUDIO_SPECTRUM,
			ShaderRenderer.UNIFORM_BACKBUFFER,
			ShaderRenderer.UNIFORM_CAMERA_FRONT,
			ShaderRenderer.UNIFORM_CAMERA_BACK);

	private EditorFragment editorFragment;
	private UIManager uiManager;
	private ShaderManager shaderManager;
	private ShaderListManager shaderListManager;
	private ShaderViewManager shaderViewManager;
	private ExtraKeysManager extraKeysManager;
	private NavigationManager navigationManager;
	private DataSource dataSource;
	private boolean isInitialLoad = false;
	// Browser-style shader navigation history: every shader selectShader()
	// actually switches *to* (not away from) gets appended here, capped at
	// SHADER_HISTORY_MAX entries (oldest dropped first), with
	// shaderHistoryIndex tracking "where we are" so Ctrl+Left/Ctrl+Right
	// (see dispatchKeyEvent()) can step back/forward through it, wrapping
	// around at either end. navigatingHistory suppresses re-recording a
	// new entry while a Ctrl+Left/Right-driven selectShader() call is in
	// flight, so revisiting history doesn't also rewrite it.
	private final java.util.ArrayList<Long> shaderHistory = new java.util.ArrayList<>();
	private int shaderHistoryIndex = -1;
	private static final int SHADER_HISTORY_MAX = 20;
	private boolean navigatingHistory = false;
	// See dispatchKeyEvent()'s Ctrl+Shift+R handling, and its own
	// key listener set up right after this is assigned, in onCreate().
	private Spinner qualitySpinnerView;
	// Tracked manually (rather than relying on isFinishing()/the
	// lifecycle API) so a HighResCaptureService capture finishing while
	// backgrounded can tell whether it's safe to act on its result right
	// now - see showCaptureResult() below.
	private boolean activityResumed = false;
	@Nullable
	private CharSequence pendingCaptureToast;
	@Nullable
	private Uri pendingCaptureVideoUri;
	private String textAtPauseTime = "";
	private boolean keyboardVisibleBeforeFullscreen = false;
	private int selectionStartBeforeFullscreen = -1;
	private int selectionEndBeforeFullscreen = -1;
	private boolean pausedBeforeFullscreen = false;
	// Remembered across dialog opens (not just within one open dialog)
	// so repeated searches don't have to be re-typed from scratch.
	private final EditorSearchState findInShaderState = new EditorSearchState();
	private final ShaderSearchState findShaderState = new ShaderSearchState();
	// Offsets of the Find-in-Shader match the ring overlay is currently
	// tracking (-1 when none), so the scroll listener wired up in
	// onCreate() can keep it aligned with the actual text as the editor
	// scrolls underneath it - see repositionFindMatchRing().
	private int trackedFindMatchStart = -1;
	private int trackedFindMatchLength = 0;
	private ActivityResultLauncher<Intent> mediaProjectionLauncher;
	private SharedPreferences.OnSharedPreferenceChangeListener audioPreferenceListener;
	// Steps back through the UI one layer at a time instead of the
	// normal "exit the app" back action: closes the sidebar if it's
	// open, then hides the code editor, then the toolbar/menu, then
	// finally goes fullscreen - at most 4 taps from the fully-open
	// state, fewer from anywhere already partway there. Always enabled
	// (not conditional on some special state already being active),
	// since even the ordinary starting state has somewhere for this to
	// take it. Exiting fullscreen is the one case that isn't another
	// step in the same direction - it jumps straight back to showing
	// the toolbar too, on the theory that anyone who reached fullscreen
	// via this progression got there specifically by hiding it.
	private final OnBackPressedCallback specialModeBackCallback =
			new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					if (uiManager.isFullscreen()) {
						setFullscreen(false);
						setToolbarHidden(false);
					} else if (uiManager.isDrawerOpen()) {
						uiManager.closeDrawer();
					} else if (editorFragment.isCodeVisible()) {
						uiManager.toggleCodeVisibility();
					} else if (!uiManager.isToolbarHidden()) {
						setToolbarHidden(true);
					} else {
						setFullscreen(true);
					}
				}
			};

	@Override
	protected void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		Log.i(AUDIO_CAPTURE_TAG, "MainActivity.onCreate (savedState=" + (state != null) + ")");
		mediaProjectionLauncher = registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				this::onMediaProjectionResult);
		getOnBackPressedDispatcher().addCallback(this, specialModeBackCallback);
		setContentView(R.layout.activity_main);
		SystemBarMetrics.initMainLayout(this, null);

		dataSource = Database.getInstance(this).getDataSource();
		recoverFromCrashIfNeeded();

		editorFragment = state == null ? new EditorFragment() : (EditorFragment)
				getSupportFragmentManager().findFragmentByTag(EditorFragment.TAG);

		if (state == null) {
			getSupportFragmentManager().beginTransaction()
					.replace(R.id.content_frame, editorFragment, EditorFragment.TAG)
					.commit();
		}

		navigationManager = new NavigationManager(this);
		qualitySpinnerView = findViewById(R.id.quality);
		shaderViewManager = new ShaderViewManager(this,
				findViewById(R.id.preview),
				qualitySpinnerView,
				dataSource,
				createShaderViewListener());
		// Ctrl+Shift+R (see dispatchKeyEvent()) focuses this spinner so
		// its own native hardware-keyboard handling can browse qualities
		// with Up/Down directly - nothing needed here for that part.
		// Only Enter needs its default action (opening the dropdown)
		// overridden, to instead just confirm (unpause) whatever
		// Up/Down already live-applied by the time Enter is pressed.
		qualitySpinnerView.setOnKeyListener((v, keyCode, event) -> {
			if (event.getAction() == KeyEvent.ACTION_DOWN &&
					(keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)) {
				setShaderPaused(false);
				return true;
			}
			return false;
		});
		audioPreferenceListener = (preferences, key) -> {
			if (Preferences.USE_SYSTEM_AUDIO.equals(key)) {
				shaderViewManager.refreshAudioCaptureSource();
			}
		};
		ShaderEditorApp.preferences.getSharedPreferences()
				.registerOnSharedPreferenceChangeListener(audioPreferenceListener);
		extraKeysManager = new ExtraKeysManager(this,
				findViewById(android.R.id.content),
				editorFragment::insert,
				this::togglePauseShader);
		uiManager = new UIManager(this,
				editorFragment,
				extraKeysManager,
				shaderViewManager);
		uiManager.setOnCodeVisibilityChangedListener(this::updatePauseForEditingVisibility);
		shaderListManager = new ShaderListManager(this,
				findViewById(R.id.shaders),
				dataSource,
				createShaderListListener());
		shaderManager = new ShaderManager(this,
				editorFragment,
				shaderViewManager,
				shaderListManager,
				uiManager,
				dataSource,
				createShaderViewListener());
		shaderManager.setOnShaderSwitchedListener(this::clearFindMatchRing);
		uiManager.setOnDrawerOpenedListener(this::clearFindMatchRing);

		MainMenuManager mainMenuManager = new MainMenuManager(
				this,
				createEditorActions(),
				createShaderActions(extraKeysManager),
				createNavigationActions());

		uiManager.setupToolbar(mainMenuManager::show,
				v -> this.runShader(),
				v -> uiManager.toggleCodeVisibility(),
				v -> editorFragment.showErrors());
		uiManager.setupShaderTitleLabel(v -> {
			long id = shaderManager.getSelectedShaderId();
			if (id > 0) {
				var shader = dataSource.shader.getShader(id);
				String title = shader != null ? shader.getTitle() : null;
				shaderListManager.editShaderName(id, title != null ? title : "");
			}
		});
		uiManager.setupShaderNavRow(
				v -> navigateShader(-1),
				v -> navigateShader(1),
				v -> randomShader(),
				v -> editorFragment.undo(),
				v -> togglePauseShader(),
				v -> restartShader(),
				v -> setFullscreen(!uiManager.isFullscreen()));
		uiManager.setupScreenshotButton(v -> takeScreenshot());
		uiManager.setupAudioNavRow(v -> shaderViewManager.reacquireSystemAudio());

		// updateUiToPreferences() itself also runs every onResume() (to
		// pick up e.g. a Settings change made while away), but that's
		// after the very first layout pass - every toolbar button above
		// just got inflated with its layout XML's own default visibility
		// (several are "visible" there, since the XML has no way to know
		// the user's actual preferences), not necessarily what the
		// user's prefs say should actually show. Without applying that
		// subset here too, the very first frame (and every subsequent
		// cold start) briefly shows that XML-default set of buttons -
		// sometimes wrapping the FlowLayout above to an extra row - and
		// then reflows the instant onResume() runs moments later and
		// corrects it, which can shift a button out from under a tap
		// aimed at the wrong-looking toolbar. Only this narrower subset
		// (not the full updateUiToPreferences()) is safe to call this
		// early - see its own doc for why.
		uiManager.applyToolbarButtonVisibility();

		shaderManager.handleSendText(getIntent());

		if (state == null) {
			Intent intent = getIntent();
			String action = intent != null ? intent.getAction() : null;
			if (!Intent.ACTION_SEND.equals(action) &&
					!Intent.ACTION_VIEW.equals(action)) {
				isInitialLoad = true;
			}
		}

		editorFragment.setOnEditPausedListener(text -> {
			if (ShaderEditorApp.preferences.doesRunOnChange()) {
				if (editorFragment.hasErrors()) {
					editorFragment.clearError();
					editorFragment.highlightErrors();
				}
				shaderViewManager.setProjectSession(
						shaderManager.getEditedProjectSession(text));
			}
		});
		editorFragment.setOnTextModifiedListener(() -> {
			shaderManager.setModified(true);
			clearFindMatchRing();
		});
		editorFragment.setCodeCompletionListener(extraKeysManager::setCompletions);
		editorFragment.setOnScrollChangedListener(this::repositionFindMatchRing);
	}

	// Global hotkeys, for anyone with a hardware/Bluetooth keyboard
	// attached - see R.array.hotkeys_list (shown via the More Options
	// menu's own "Hotkeys" entry) for the full, user-facing list; keep
	// the two in sync if either changes. Intercepted here (rather than
	// e.g. individual View.setOnKeyListener calls) so they work
	// regardless of which view currently has focus, as long as no
	// dialog is showing - a dialog's own window gets first claim on its
	// own key events before this Activity's dispatchKeyEvent() ever
	// sees them, so these never fight a dialog's own text fields/
	// shortcuts (see e.g. showFindShaderDialog()'s own Enter/Ctrl+Enter
	// handling).
	@Override
	public boolean dispatchKeyEvent(@NonNull KeyEvent event) {
		if (event.getAction() == KeyEvent.ACTION_DOWN && handleHotkeyDown(event)) {
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	// True if this key (plus whichever modifiers were held) was handled
	// as a hotkey and should be consumed - false lets it fall through to
	// normal dispatch (typing into whatever's focused, scrolling, the
	// system's own handling, etc). Every case here that doesn't actually
	// match its own modifier/focus requirements falls through to a
	// plain `return false` for exactly that reason - e.g. a bare "R"
	// keypress (no Ctrl) must still type the letter normally.
	private boolean handleHotkeyDown(@NonNull KeyEvent event) {
		boolean ctrl = event.isCtrlPressed();
		boolean shift = event.isShiftPressed();
		// Several shortcuts below (Up, Left/Right, Tab) are repurposed
		// from their normal meaning only outside the source editor, so
		// typing/cursor movement there is never hijacked - this one
		// check covers all of them, computed once since none of it
		// changes mid-call.
		boolean editorFocused = editorFragment.isSourceEditorFocused();
		switch (event.getKeyCode()) {
			case KeyEvent.KEYCODE_R:
				if (ctrl && shift) {
					openQualityPickerViaHotkey();
					showHotkeyToast("Ctrl+Shift+R", R.string.quality_picker);
					return true;
				}
				if (ctrl) {
					openRenderDialogViaHotkey();
					showHotkeyToast("Ctrl+R", R.string.open_render_dialog);
					return true;
				}
				return false;
			case KeyEvent.KEYCODE_F:
				if (!ctrl) {
					return false;
				}
				// Find in Shader only when the cursor's actually in the
				// source - Find Shader otherwise (fullscreen, label-only,
				// shader sidebar already showing, or any other mode),
				// since that's the broadly useful default and Find in
				// Shader only makes sense while actively editing source.
				if (editorFocused) {
					showFindInShaderDialog();
					showHotkeyToast("Ctrl+F", R.string.find_in_shader);
				} else {
					showFindShaderDialog();
					showHotkeyToast("Ctrl+F", R.string.find_shader);
				}
				return true;
			case KeyEvent.KEYCODE_S:
				if (ctrl && shift) {
					pasteAsNewShader();
					showHotkeyToast("Ctrl+Shift+S", R.string.paste_as_new_shader);
					return true;
				}
				if (ctrl) {
					shaderManager.saveShader();
					showHotkeyToast("Ctrl+S", R.string.save_shader);
					return true;
				}
				return false;
			case KeyEvent.KEYCODE_ESCAPE:
				getOnBackPressedDispatcher().onBackPressed();
				showHotkeyToast("Esc", R.string.back);
				return true;
			case KeyEvent.KEYCODE_F11:
				setFullscreen(!uiManager.isFullscreen());
				showHotkeyToast("F11", R.string.fullscreen);
				return true;
			case KeyEvent.KEYCODE_DPAD_UP:
				// Never while the quality spinner itself has focus (see
				// openQualityPickerViaHotkey()) - it needs Up for its
				// own native browse-by-focus handling instead.
				if (qualitySpinnerView.isFocused() || editorFocused) {
					return false;
				}
				if (ctrl) {
					restartShader();
					showHotkeyToast("Ctrl+Up", R.string.restart_shader);
				} else {
					// Deliberately setShaderPaused(), not
					// togglePauseShader() - that one additionally blanks
					// the screen on pause (see its own doc), which is
					// exactly right for the manual pause *button* but
					// not for this key, which should just freeze on
					// whatever's currently showing and keep showing it.
					boolean pausing = !shaderViewManager.isPaused();
					setShaderPaused(pausing);
					showHotkeyToast("Up", pausing ? R.string.pause_shader : R.string.resume_shader);
				}
				return true;
			case KeyEvent.KEYCODE_DPAD_LEFT:
				if (ctrl) {
					navigateShaderHistory(-1);
					showHotkeyToast("Ctrl+Left", R.string.history_back);
					return true;
				}
				if (editorFocused) {
					return false;
				}
				navigateToFirstShader();
				showHotkeyToast("Left", R.string.first_shader);
				return true;
			case KeyEvent.KEYCODE_DPAD_RIGHT:
				if (ctrl) {
					navigateShaderHistory(1);
					showHotkeyToast("Ctrl+Right", R.string.history_forward);
					return true;
				}
				if (editorFocused) {
					return false;
				}
				randomShader();
				showHotkeyToast("Right", R.string.random_shader);
				return true;
			case KeyEvent.KEYCODE_TAB:
				if (editorFocused) {
					return false;
				}
				if (uiManager.isDrawerOpen()) {
					uiManager.closeDrawer();
				} else {
					uiManager.openDrawer();
				}
				showHotkeyToast("Tab", R.string.toggle_sidebar);
				return true;
			case KeyEvent.KEYCODE_Z:
				if (!ctrl) {
					return false;
				}
				if (shift) {
					if (editorFragment.canRedo()) {
						editorFragment.redo();
						showHotkeyToast("Ctrl+Shift+Z", R.string.redo);
					}
				} else if (editorFragment.canUndo()) {
					editorFragment.undo();
					showHotkeyToast("Ctrl+Z", R.string.undo);
				}
				return true;
			case KeyEvent.KEYCODE_Y:
				if (!ctrl) {
					return false;
				}
				if (editorFragment.canRedo()) {
					editorFragment.redo();
					showHotkeyToast("Ctrl+Y", R.string.redo);
				}
				return true;
			case KeyEvent.KEYCODE_E:
				if (!ctrl || !shift || !uiManager.isFullscreen()) {
					return false;
				}
				uiManager.toggleCodeVisibility();
				setShaderPaused(editorFragment.isCodeVisible());
				showHotkeyToast("Ctrl+Shift+E", R.string.toggle_editor_and_pause);
				return true;
			case KeyEvent.KEYCODE_Q:
				if (!ctrl) {
					return false;
				}
				// No toast here - quitApp() finishes the activity right
				// away, so there'd be nothing left to show it on.
				quitApp();
				return true;
			default:
				return false;
		}
	}

	// Shows a brief toast naming the hotkey and action it just triggered
	// - see the user-facing request behind this in handleHotkeyDown()'s
	// own commit: makes it easier to learn/keep track of the hotkeys as
	// they're used. combo is deliberately a literal, untranslated string
	// (matching R.array.hotkeys_list's own convention) rather than a
	// string resource - see hotkeys.xml's own comment for why.
	private void showHotkeyToast(@NonNull String combo, @StringRes int actionNameRes) {
		Toast.makeText(this, getString(R.string.hotkey_toast_format, combo, getString(actionNameRes)),
				Toast.LENGTH_SHORT).show();
	}

	// Mirrors the camera button's own pause/restore bookkeeping (see
	// takeScreenshot()) but skips straight to the Custom Res/Seq dialog
	// itself, rather than the screenshot-actions dialog it's normally
	// one tap further in behind.
	private void openRenderDialogViaHotkey() {
		boolean wasPaused = shaderViewManager.isPaused();
		setShaderPaused(true);
		showCustomResolutionDialog(wasPaused);
	}

	// Pauses, then focuses the quality spinner so a hardware keyboard's
	// own Up/Down keys can browse it directly (standard AdapterView
	// behavior once focused - nothing further needed here for that
	// part); see the key listener on qualitySpinnerView, set up right
	// after it's assigned in onCreate(), for what makes Enter confirm
	// (unpause) instead of opening the dropdown.
	private void openQualityPickerViaHotkey() {
		setShaderPaused(true);
		qualitySpinnerView.requestFocus();
	}

	private void quitApp() {
		if (ShaderEditorApp.preferences.autoSave()) {
			shaderManager.saveShader();
		}
		finishAffinity();
	}

	// Keeps the Find-in-Shader ring aligned with its match as the editor
	// scrolls underneath it (vertically or horizontally) - without this
	// the ring stays at a fixed screen position while the matched text
	// moves out from under it. A no-op once nothing is tracked (see
	// trackedFindMatchStart).
	private void repositionFindMatchRing() {
		if (trackedFindMatchStart < 0) {
			return;
		}
		Rect bounds = editorFragment.getMatchBoundsOnScreen(
				trackedFindMatchStart, trackedFindMatchLength);
		if (bounds != null) {
			uiManager.repositionSearchMatch(bounds);
		}
	}

	// Clears the Find-in-Shader ring/highlight and stops tracking its
	// match - called whenever the context it was found in goes stale:
	// an actual source edit, switching to a different shader, a fresh
	// compile, or reopening either find dialog for a new search.
	private void clearFindMatchRing() {
		editorFragment.clearMatchHighlight();
		uiManager.hideSearchMatch();
		trackedFindMatchStart = -1;
	}

	@Override
	protected void onRestoreInstanceState(@NonNull Bundle state) {
		super.onRestoreInstanceState(state);
		shaderManager.restoreState(state);
		if (!state.getBoolean(CODE_VISIBLE, true)) {
			uiManager.toggleCodeVisibility();
		}
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		shaderManager.saveState(state);
		state.putBoolean(CODE_VISIBLE, editorFragment.isCodeVisible());
		super.onSaveInstanceState(state);
	}

	@Override
	protected void onResume() {
		super.onResume();
		activityResumed = true;
		// A HighResCaptureService capture that finished while this
		// Activity wasn't in the foreground couldn't safely show its
		// result then (a Toast posted from a backgrounded/cached process
		// can be silently dropped, and starting the video viewer Activity
		// would be blocked outright as a background activity launch) -
		// see showCaptureResult(). Replay it now that we're back.
		if (pendingCaptureToast != null) {
			Toast.makeText(this, pendingCaptureToast, Toast.LENGTH_LONG).show();
			pendingCaptureToast = null;
		}
		if (pendingCaptureVideoUri != null) {
			openVideo(pendingCaptureVideoUri);
			pendingCaptureVideoUri = null;
		}
		uiManager.updateUiToPreferences();
		shaderListManager.loadShadersAsync();
		// Must run before updatePauseForEditingVisibility() below: this
		// restarts the real GL thread after a real background/foreground
		// cycle (e.g. returning from Settings). If the editing-visibility
		// pause ran first and set paused=true for the first time here,
		// ShaderViewManager.onResume()'s own "if (paused) return;" guard
		// would skip restarting the GL thread entirely - permanently, since
		// nothing else ever calls the real resume - leaving the view stuck
		// black. See the "Run when not editing" bug.
		shaderViewManager.onResume();
		// Covers the case where the "Run when not editing" mode was
		// just turned on (or off) from Settings while code visibility
		// itself didn't change - updateUiToPreferences() above only
		// reconciles the other run-mode axis (background rendering),
		// and toggleCodeVisibility()'s own listener only fires on an
		// actual visibility change.
		updatePauseForEditingVisibility(editorFragment.isCodeVisible());
	}

	@Override
	protected void onPause() {
		if (ShaderEditorApp.preferences.autoSave()) {
			shaderManager.saveShader();
		}
		// Must run after the autosave above, not before: saveShader()
		// re-arms the pending-crash watchdog via updatePendingCrashShaderId()
		// whenever the shader was modified, since it has no way to tell a
		// routine pause apart from a fresh shader selection. Clearing the
		// flag last is what actually marks this as a clean exit - doing it
		// first (the previous order) let the autosave immediately re-arm
		// it, so any ordinary pause of a modified, running shader (not
		// just a screen rotation recreating the Activity, but simply
		// backgrounding the app) left the flag wrongly armed. The next
		// onCreate() - immediate on rotation, or the next cold launch
		// otherwise - would then see it, assume this shader had crashed,
		// and disable it, even though nothing crashed.
		ShaderEditorApp.preferences.setPendingCrashShaderId(0);
		activityResumed = false;
		super.onPause();
		shaderViewManager.onPause();
	}

	@Override
	protected void onDestroy() {
		stopService(new Intent(this, AudioCaptureForegroundService.class));
		if (shaderListManager != null) {
			shaderListManager.destroy();
		}
		if (shaderViewManager != null) {
			shaderViewManager.destroy();
		}
		if (audioPreferenceListener != null) {
			ShaderEditorApp.preferences.getSharedPreferences()
					.unregisterOnSharedPreferenceChangeListener(audioPreferenceListener);
		}
		super.onDestroy();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		shaderManager.handleSendText(intent);
	}

	@Override
	protected void onPostCreate(Bundle state) {
		super.onPostCreate(state);
		if (uiManager.drawerToggle != null) {
			uiManager.drawerToggle.syncState();
		}
	}

	@NonNull
	@Contract(" -> new")
	private ShaderListManager.Listener createShaderListListener() {
		return new ShaderListManager.Listener() {
			@Override
			public void onShaderSelected(long id) {
				selectShader(id);
			}

			@Override
			public void onShaderRenamed(long id, @NonNull String name) {
				if (id == shaderManager.getSelectedShaderId()) {
					// Re-fetch rather than using name directly - renaming
					// to blank is valid and should fall back to the
					// shader's date, same as everywhere else getTitle()
					// is used, not show a blank label.
					var shader = dataSource.shader.getShader(id);
					uiManager.setToolbarTitle(shader != null ? shader.getTitle() : name);
				}
				shaderListManager.loadShadersAsync();
			}

			@Override
			public void onAllShadersDeleted() {
				isInitialLoad = false;
				shaderManager.selectShader(0);
			}

			@Override
			public void onAddShaderToNameGroup() {
				showPickParentShaderDialog(shaderManager.getSelectedShaderId(), false);
			}

			@Override
			public void onReparentShader(long id) {
				showPickParentShaderDialog(id, true);
			}

			@Override
			public void onConfigureSimilarShaders() {
				showSimilarShadersConfigDialog();
			}

			@Override
			public void onFindShader() {
				showFindShaderDialog();
			}

			@Override
			public void onShadersLoaded(@NonNull List<DataRecords.ShaderInfo> shaders) {
				if (isInitialLoad && !shaders.isEmpty()) {
					// Try to load the last opened shader.
					// NOTE: This assumes a method like `getLastOpenedShader()` exists
					// in your SharedPreferences helper class.
					long lastOpenedId = ShaderEditorApp.preferences.getLastOpenedShader();
					long idToLoad = 0;

					// Check if the last opened shader still exists in the list.
					if (lastOpenedId > 0) {
						for (DataRecords.ShaderInfo shader : shaders) {
							if (shader.id() == lastOpenedId) {
								idToLoad = lastOpenedId;
								break;
							}
						}
					}

					// If no last-opened shader was found (or it was deleted),
					// fall back to the first shader in the list.
					if (idToLoad == 0) {
						idToLoad = shaders.get(0).id();
					}

					shaderManager.selectShader(idToLoad);
				}
				isInitialLoad = false;
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private ShaderViewManager.Listener createShaderViewListener() {
		return new ShaderViewManager.Listener() {
			private final View showErrors = findViewById(R.id.show_errors);
			private final View mainCoordinator = findViewById(R.id.main_coordinator);
			private final View toolbar = findViewById(R.id.toolbar);

			@Override
			public void onFramesPerSecond(int fps) {
				if (fps > 0) {
					ShaderEditorApp.preferences.setPendingCrashShaderId(0);
					uiManager.setToolbarSubtitle(fps + " fps");
				}
			}

			@Override
			public void onInfoLog(@NonNull List<ShaderError> infoLog) {
				runOnUiThread(() -> {
					editorFragment.setErrors(infoLog);
					showErrors.setVisibility(editorFragment.hasErrors()
							? View.VISIBLE
							: View.GONE);
					if (editorFragment.hasErrors()) {
						showError(infoLog.get(0).toString());
					}
				});
			}

			@Override
			public void onQualityChanged(float quality) {
				shaderManager.setQuality(quality);
			}

			@Override
			public void onRequestAudioCapture() {
				runOnUiThread(MainActivity.this::requestAudioCapture);
			}

			// Touch dispatch (unlike rendering) already runs on the UI
			// thread, so these two - unlike the GL-thread-sourced
			// callbacks above - need no runOnUiThread() wrapping.
			@Override
			public void onFullscreenExitPhaseChanged(
					@NonNull FullscreenExitGestureDetector.Phase phase) {
				switch (phase) {
					case PENDING:
						uiManager.showFullscreenExitHintPending();
						break;
					case ARMED:
						uiManager.armFullscreenExitHint();
						break;
					case IDLE:
					default:
						uiManager.hideFullscreenExitHint();
						break;
				}
			}

			@Override
			public void onFullscreenExitGesture() {
				setFullscreen(false);
			}

			private void showError(String error) {
				Snackbar.make(mainCoordinator,
								error,
								Snackbar.LENGTH_LONG)
						.setAction(R.string.details,
								v -> editorFragment.showErrors())
						.show();
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.EditorActions createEditorActions() {
		return new MainMenuManager.EditorActions() {
			@Override
			public void onUndo() {
				editorFragment.undo();
			}

			@Override
			public void onRedo() {
				editorFragment.redo();
			}

			@Override
			public boolean canUndo() {
				return editorFragment != null && editorFragment.canUndo();
			}

			@Override
			public boolean canRedo() {
				return editorFragment != null && editorFragment.canRedo();
			}

			@Override
			public void onFindInShader() {
				showFindInShaderDialog();
			}

			@Override
			public void onHideToolbar() {
				setToolbarHidden(true);
			}
		};
	}

	@NonNull
	@Contract("_ -> new")
	private MainMenuManager.ShaderActions createShaderActions(ExtraKeysManager extraKeysManager) {
		return new MainMenuManager.ShaderActions() {
			@Override
			public void onAddShader() {
				long defaultId = ShaderEditorApp.preferences.getDefaultNewShader();
				if (defaultId > 0 && dataSource.shader.getShader(defaultId) != null) {
					duplicateShader(defaultId);
				} else {
					long newId = dataSource.shader.insertNewShader();
					shaderManager.selectShader(newId);
					shaderListManager.loadShadersAsync();
				}
			}

			@Override
			public void onPasteAsNewShader() {
				pasteAsNewShader();
			}

			@Override
			public void onPasteOverShader() {
				pasteOverCurrentShader();
			}

			@Override
			public void onSaveShader() {
				shaderManager.saveShader(true);
			}

			@Override
			public void onDuplicateShader() {
				if (editorFragment == null || shaderManager.getSelectedShaderId() < 1) {
					return;
				}
				if (shaderManager.isModified()) {
					shaderManager.saveShader();
				}
				duplicateShader(shaderManager.getSelectedShaderId());
			}

			@Override
			public void onDeleteShader() {
				if (shaderManager.getSelectedShaderId() < 1) {
					return;
				}
				new MaterialAlertDialogBuilder(MainActivity.this)
						.setMessage(R.string.sure_remove_shader)
						.setPositiveButton(android.R.string.ok, (dialog, which) -> {
							shaderManager.deleteShader(shaderManager.getSelectedShaderId());
							shaderManager.selectShader(dataSource.shader.getFirstShaderId());
							shaderListManager.loadShadersAsync();
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
			}

			@Override
			public void onShareShader() {
				navigationManager.shareShader(editorFragment.getText());
			}

			@Override
			public void onCopyShader() {
				copyShaderToClipboard();
			}

			@Override
			public void onCopyDefines() {
				copyDefinesToClipboard();
			}

			@Override
			public void onUpdateWallpaper() {
				updateWallpaper();
			}

			@Override
			public void onToggleExtraKeys() {
				extraKeysManager.setVisible(ShaderEditorApp.preferences.toggleShowExtraKeys());
			}

			@Override
			public long getSelectedShaderId() {
				return shaderManager.getSelectedShaderId();
			}
		};
	}

	@NonNull
	@Contract(" -> new")
	private MainMenuManager.NavigationActions createNavigationActions() {
		return new MainMenuManager.NavigationActions() {
			@Override
			public void onAddUniform() {
				navigationManager.goToAddUniform(shaderManager.addUniformLauncher);
			}

			@Override
			public void onLoadSample() {
				navigationManager.goToLoadSample(shaderManager.loadSampleLauncher);
			}

			@Override
			public void onShowSettings() {
				navigationManager.goToPreferences();
			}

			@Override
			public void onShowFaq() {
				navigationManager.goToFaq();
			}

			@Override
			public void onShowShaderStats() {
				showShaderStatsDialog();
			}

			@Override
			public void onPlayRecentRecordings() {
				showRecentRecordingsDialog();
			}

			@Override
			public void onShowHotkeys() {
				showHotkeysDialog();
			}
		};
	}

	private void duplicateShader(long id) {
		// The ShaderManager is now responsible for getting the shader details.
		// We just need to pass the ID.
		long newId = shaderManager.duplicateShader(id);
		if (newId > 0) {
			shaderManager.selectShader(newId);
			shaderListManager.loadShadersAsync();
		}
	}

	private void updateWallpaper() {
		if (shaderManager.getSelectedShaderId() < 1) {
			return;
		}
		if (shaderManager.isModified()) {
			shaderManager.saveShader();
		}

		ShaderEditorApp.preferences.setWallpaperShader(0); // Force change
		ShaderEditorApp.preferences.setWallpaperShader(shaderManager.getSelectedShaderId());

		int messageId = R.string.wallpaper_set;
		if (!WallpaperManager.getInstance(this).isWallpaperSupported()) {
			messageId = R.string.cannot_set_wallpaper;
		} else if (!ShaderWallpaperService.isRunning()) {
			Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
					.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
							new ComponentName(this, ShaderWallpaperService.class));
			try {
				startActivity(intent);
				return;
			} catch (Exception e) {
				messageId = R.string.pick_live_wallpaper_manually;
			}
		}
		Toast.makeText(this, messageId, Toast.LENGTH_SHORT).show();
	}

	private void pasteAsNewShader() {
		ClipboardManager clipboard = getSystemService(ClipboardManager.class);
		ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
		CharSequence text = clip != null && clip.getItemCount() > 0
				? clip.getItemAt(0).coerceToText(this)
				: null;
		if (text == null || text.toString().isBlank()) {
			Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
			return;
		}
		showQualityPickerDialog(text.toString());
	}

	private void pasteOverCurrentShader() {
		ClipboardManager clipboard = getSystemService(ClipboardManager.class);
		ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
		CharSequence text = clip != null && clip.getItemCount() > 0
				? clip.getItemAt(0).coerceToText(this)
				: null;
		if (text == null || text.toString().isBlank()) {
			Toast.makeText(this, R.string.clipboard_empty, Toast.LENGTH_SHORT).show();
			return;
		}
		String pastedSource = text.toString();
		new MaterialAlertDialogBuilder(this)
				.setIcon(R.drawable.ic_warning)
				.setTitle(R.string.paste_over_warning_title)
				.setMessage(R.string.sure_paste_over_shader)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					editorFragment.setText(pastedSource);
					shaderManager.saveShader(true);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showQualityPickerDialog(@NonNull String shaderSource) {
		String[] qualityNames = getResources().getStringArray(R.array.quality_names);
		String[] qualityValues = getResources().getStringArray(R.array.quality_values);
		int defaultIndex = 0;
		for (int i = 0; i < qualityValues.length; ++i) {
			if (Float.parseFloat(qualityValues[i]) == 0.25f) {
				defaultIndex = i;
				break;
			}
		}

		View view = getLayoutInflater().inflate(R.layout.dialog_paste_shader, null);
		EditText nameView = view.findViewById(R.id.paste_shader_name);
		RadioGroup qualityGroup = view.findViewById(R.id.paste_shader_quality_options);
		for (int i = 0; i < qualityNames.length; ++i) {
			RadioButton button = new RadioButton(this);
			button.setId(View.generateViewId());
			button.setText(qualityNames[i]);
			qualityGroup.addView(button);
			if (i == defaultIndex) {
				button.setChecked(true);
			}
		}

		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.paste_shader_title)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					int selectedIndex = qualityGroup.indexOfChild(
							qualityGroup.findViewById(qualityGroup.getCheckedRadioButtonId()));
					float quality = Float.parseFloat(qualityValues[Math.max(0, selectedIndex)]);
					// Blank stays unnamed (same fallback-to-last-modified
					// behavior as any other unnamed shader - see
					// DataRecords.ShaderInfo.getTitle()), rather than
					// saving an empty-string name.
					String name = nameView.getText().toString().trim();
					long newId = dataSource.shader.insertShader(
							shaderSource, name.isEmpty() ? null : name, null, quality);
					shaderManager.selectShader(newId);
					shaderManager.saveShader(true);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void requestAudioCapture() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			return;
		}
		MediaProjectionManager projectionManager =
				getSystemService(MediaProjectionManager.class);
		if (projectionManager == null) {
			return;
		}
		Log.i(AUDIO_CAPTURE_TAG, "requestAudioCapture: launching consent intent");
		mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent());
	}

	private void onMediaProjectionResult(ActivityResult result) {
		Log.i(AUDIO_CAPTURE_TAG, "onMediaProjectionResult: resultCode=" + result.getResultCode() +
				" hasData=" + (result.getData() != null));
		if (result.getResultCode() != RESULT_OK || result.getData() == null) {
			return;
		}
		MediaProjectionManager projectionManager =
				getSystemService(MediaProjectionManager.class);
		if (projectionManager == null) {
			return;
		}
		int resultCode = result.getResultCode();
		Intent data = result.getData();
		// getMediaProjection() requires a foreground service of type
		// mediaProjection to already be running on API 34+, or it throws
		// SecurityException - and that only becomes true once the
		// service's startForeground() call actually runs, which (since
		// the service shares this same main thread) can only happen
		// after this method returns, not merely after
		// startForegroundService() is called below. So getMediaProjection()
		// is deferred to the service's callback instead of called here.
		// This can legitimately never fire (e.g.
		// ForegroundServiceStartNotAllowedException, API 31+, if Android
		// doesn't consider us sufficiently "foreground" at this exact
		// lifecycle moment, right as we're returning from the picker) -
		// in that case, system audio is just skipped for this run rather
		// than crashing; the mic fallback (if enabled) still works.
		AudioCaptureForegroundService.setListener(
				new AudioCaptureForegroundService.Listener() {
					@Override
					public void onForegroundServiceStarted() {
						AudioCaptureForegroundService.setListener(null);
						MediaProjection projection =
								projectionManager.getMediaProjection(resultCode, data);
						shaderViewManager.setMediaProjection(projection);
					}

					@Override
					public void onForegroundServiceFailed() {
						AudioCaptureForegroundService.setListener(null);
					}
				});
		try {
			ContextCompat.startForegroundService(
					this, new Intent(this, AudioCaptureForegroundService.class));
		} catch (RuntimeException e) {
			Log.e(AUDIO_CAPTURE_TAG, "Could not start audio capture foreground service", e);
			AudioCaptureForegroundService.setListener(null);
		}

		// Picking a target (an app, or sometimes "entire screen") in the
		// system picker can leave that target - or the home screen - in
		// the foreground instead of returning here. Explicitly reclaim
		// the foreground; this is a no-op if we're already there.
		try {
			Intent bringToFront = new Intent(this, MainActivity.class);
			bringToFront.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
			startActivity(bringToFront);
		} catch (RuntimeException e) {
			Log.e(AUDIO_CAPTURE_TAG, "Could not bring task to front", e);
		}
	}

	private void copyShaderToClipboard() {
		ClipboardManager clipboard = getSystemService(ClipboardManager.class);
		if (clipboard == null) {
			return;
		}
		clipboard.setPrimaryClip(ClipData.newPlainText(
				getString(R.string.app_name), editorFragment.getText()));
	}

	private void copyDefinesToClipboard() {
		List<String> defines = findDefineLines(editorFragment.getText());
		if (defines.isEmpty()) {
			Toast.makeText(this, R.string.no_defines_found, Toast.LENGTH_SHORT).show();
			return;
		}
		ClipboardManager clipboard = getSystemService(ClipboardManager.class);
		if (clipboard == null) {
			return;
		}
		clipboard.setPrimaryClip(ClipData.newPlainText(
				getString(R.string.app_name), String.join("\n", defines)));
	}

	@NonNull
	private static List<String> findDefineLines(@NonNull String text) {
		List<String> defines = new ArrayList<>();
		for (String line : text.split("\n", -1)) {
			String trimmed = line.strip();
			if (trimmed.startsWith("#define")) {
				defines.add(trimmed);
			}
		}
		return defines;
	}

	private int dpToPx(int dp) {
		return Math.round(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics()));
	}

	// Material AlertDialogs in this theme are vertically centered, so
	// this derives the bottom edge from just the dialog's own measured
	// height - no need to ask the window for its actual position.
	// Falls back to the middle of the screen if that height isn't
	// available yet (called before the dialog has had a layout pass).
	private int dialogBottomOnScreen(@NonNull AlertDialog dialog) {
		int screenHeight = getResources().getDisplayMetrics().heightPixels;
		Window window = dialog.getWindow();
		int dialogHeight = window != null ? window.getDecorView().getHeight() : 0;
		return dialogHeight > 0 ? (screenHeight + dialogHeight) / 2 : screenHeight / 2;
	}

	private void showFindInShaderDialog() {
		clearFindMatchRing();
		View view = getLayoutInflater().inflate(R.layout.dialog_find_in_shader, null);
		EditText queryView = view.findViewById(R.id.query);
		TextView resultPosition = view.findViewById(R.id.result_position);
		View navRow = view.findViewById(R.id.find_nav_row);
		View previousButton = view.findViewById(R.id.find_previous);
		View randomButton = view.findViewById(R.id.find_random);
		View nextButton = view.findViewById(R.id.find_next_match);
		EditorSearchState state = findInShaderState;
		Random random = new Random();

		queryView.setText(state.query);
		// Pre-selected rather than just cursor-at-end, so typing a new
		// search immediately replaces the old one instead of appending.
		queryView.selectAll();
		// Visible from the start (not just after a first successful
		// search) so there's always an obvious, tappable way to
		// actually run the search - not everyone has a keyboard with a
		// visible search/enter key.
		navRow.setVisibility(View.VISIBLE);

		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.find_in_shader)
				.setView(view)
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> {
					SoftKeyboard.hide(this, queryView);
					state.query = queryView.getText().toString();
					// Deliberately left highlighted - cleared on the next
					// actual edit instead (see setOnTextModifiedListener),
					// so it's still there to refer back to after closing
					// the dialog to go look at the matched code.
				})
				.show();

		Runnable showCurrentMatch = () -> {
			int start = state.matches.get(state.index);
			int length = state.query.length();
			// Set before any scrolling below so that if selectMatch()'s
			// focus change triggers the framework's own (possibly
			// deferred) scroll-into-view, repositionFindMatchRing() -
			// wired to every scroll change - has the right target to
			// snap the ring to rather than stale/absent state.
			trackedFindMatchStart = start;
			trackedFindMatchLength = length;
			// Scrolls the match to just below the dialog instead of
			// wherever a generic "bring into view" would leave it -
			// otherwise easy to miss since the dialog covers the top of
			// the screen and a plain scroll-into-view is satisfied by
			// the match landing anywhere on screen, dialog included.
			editorFragment.scrollMatchToScreenY(
					start, length, dialogBottomOnScreen(dialog) + dpToPx(16));
			editorFragment.selectMatch(start, length);
			editorFragment.highlightMatch(start, length);
			Rect bounds = editorFragment.getMatchBoundsOnScreen(start, length);
			if (bounds != null) {
				uiManager.flashSearchMatch(bounds);
			}
			resultPosition.setText(getString(R.string.match_position_format,
					state.index + 1, state.matches.size()));
			resultPosition.setVisibility(View.VISIBLE);
		};
		// Re-runs the search if the query changed since the last match
		// was shown. Returns false (and hides the result UI) when
		// there's nothing to show, so callers can bail out early.
		java.util.function.BooleanSupplier ensureMatches = () -> {
			String query = queryView.getText().toString();
			if (query.isEmpty()) {
				return false;
			}
			state.query = query;
			if (!query.equals(state.key)) {
				state.matches = editorFragment.findAllMatches(query);
				state.index = -1;
				state.key = query;
			}
			if (state.matches.isEmpty()) {
				resultPosition.setVisibility(View.GONE);
				Toast.makeText(this, R.string.no_matches_found, Toast.LENGTH_SHORT).show();
				return false;
			}
			return true;
		};
		// Live match count, updated on every keystroke - shown even
		// before the user explicitly searches, and reverts to "N
		// matches" (rather than the "x / y" position format) whenever
		// the query changes underneath an already-shown match, since
		// that match's position within the new result set is no longer
		// meaningful.
		queryView.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				String query = s.toString();
				state.query = query;
				if (query.isEmpty()) {
					state.matches = Collections.emptyList();
					state.index = -1;
					state.key = null;
					resultPosition.setVisibility(View.GONE);
					return;
				}
				if (!query.equals(state.key)) {
					state.matches = editorFragment.findAllMatches(query);
					state.index = -1;
					state.key = query;
				}
				resultPosition.setVisibility(View.VISIBLE);
				resultPosition.setText(state.matches.isEmpty()
						? getString(R.string.no_matches_found)
						: getResources().getQuantityString(R.plurals.match_count,
								state.matches.size(), state.matches.size()));
			}
		});
		// Shows the live count immediately for a query resumed from the
		// last time this dialog was open, rather than waiting for the
		// user to type something before it appears.
		if (!state.query.isEmpty()) {
			resultPosition.setVisibility(View.VISIBLE);
			resultPosition.setText(getResources().getQuantityString(
					R.plurals.match_count, state.matches.size(), state.matches.size()));
		}

		Runnable findNext = () -> {
			if (!ensureMatches.getAsBoolean()) {
				return;
			}
			state.index = (state.index + 1) % state.matches.size();
			showCurrentMatch.run();
		};
		Runnable findPrevious = () -> {
			if (!ensureMatches.getAsBoolean()) {
				return;
			}
			int size = state.matches.size();
			int from = Math.max(state.index, 0);
			state.index = Math.floorMod(from - 1, size);
			showCurrentMatch.run();
		};
		Runnable findRandom = () -> {
			if (!ensureMatches.getAsBoolean()) {
				return;
			}
			int size = state.matches.size();
			int from = Math.max(state.index, 0);
			if (size > 1) {
				// A signed jump averaging about half the match list's
				// length - likely to land somewhere nearby, occasionally
				// far enough that two lucky jumps could cross the whole
				// list.
				int range = size - 1;
				int offset = random.nextInt(2 * range + 1) - range;
				state.index = Math.floorMod(from + offset, size);
			} else {
				state.index = 0;
			}
			showCurrentMatch.run();
		};
		previousButton.setOnClickListener(v -> findPrevious.run());
		randomButton.setOnClickListener(v -> findRandom.run());
		nextButton.setOnClickListener(v -> findNext.run());
		queryView.setOnEditorActionListener((v, actionId, event) -> {
			findNext.run();
			return true;
		});
		// See the twin dialog's own copy of this (showFindShaderDialog())
		// for why - same reasoning, minus the Ctrl+Enter-to-load case,
		// which doesn't apply here (a match is a position within the
		// shader already open, not a separate shader to load).
		queryView.setOnKeyListener((v, keyCode, event) -> {
			if (event.getAction() != KeyEvent.ACTION_DOWN ||
					(keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)) {
				return false;
			}
			findNext.run();
			return true;
		});
	}

	// Below this, no warning - a handful of seconds isn't worth
	// interrupting navigation for.
	private static final long SLOW_COMPILE_WARN_MS = 3000;
	// At or above this, require an explicit "Compile" tap rather than
	// just showing the heads-up and proceeding - long enough that
	// blocking on an accidental tap is worse than asking first.
	private static final long SLOW_COMPILE_CONFIRM_MS = 7000;

	private void selectShader(long id) {
		isInitialLoad = false;
		if (!navigatingHistory) {
			recordShaderHistory(id);
		}
		if (ShaderEditorApp.preferences.autoSave()) {
			shaderManager.saveShader();
		}
		DataRecords.Shader target = id > 0 ? dataSource.shader.getShader(id) : null;
		if (target != null && maybeWarnAboutSlowCompile(
				target, target.fragmentShader(), () -> shaderManager.selectShader(id))) {
			return;
		}
		shaderManager.selectShader(id);
	}

	// Checks whether `candidateSource` (about to actually be compiled -
	// the target shader's stored source when switching shaders, or the
	// currently-edited text when running the one already open) is likely
	// to trigger a slow fresh compile per `shader`'s last timed one, and
	// if so shows a dialog before calling onProceed. Returns true if a
	// dialog is now showing (caller must not proceed itself - onProceed
	// will if/when the user agrees); false if nothing's wrong and the
	// caller should just proceed immediately.
	private boolean maybeWarnAboutSlowCompile(@NonNull DataRecords.Shader shader,
			@NonNull String candidateSource, @NonNull Runnable onProceed) {
		if (!ShaderEditorApp.preferences.warnSlowCompile() ||
				shader.compileTimeMs() == null ||
				shader.compileTimeMs() < SLOW_COMPILE_WARN_MS) {
			return false;
		}
		Integer lastLength = shader.compileTimeSourceLength();
		if (lastLength != null && lastLength == candidateSource.length()) {
			// Nothing detectably changed since that timing was taken -
			// the binary cache should still hit, so no slow compile is
			// actually imminent regardless of how slow it was once.
			return false;
		}
		confirmSlowCompile(shader, candidateSource.length(), onProceed);
		return true;
	}

	// Informational-only below SLOW_COMPILE_CONFIRM_MS (a single
	// dismiss-and-proceed button); at or above it, an explicit
	// confirmation - onProceed only runs if the user agrees, per
	// maybeWarnAboutSlowCompile() above already having ruled out the
	// "will probably hit the cache anyway" case.
	private void confirmSlowCompile(
			@NonNull DataRecords.Shader shader, int currentLength, @NonNull Runnable onProceed) {
		long lastMs = shader.compileTimeMs();
		int lastLength = shader.compileTimeSourceLength() != null
				? shader.compileTimeSourceLength()
				: currentLength;

		String message;
		if (lastLength <= 0 || currentLength == lastLength) {
			message = getString(R.string.slow_compile_warning_same_size, lastMs / 1000.0);
		} else {
			double ratio = (double) currentLength / lastLength;
			long projectedMs = Math.round(lastMs * ratio);
			int percentChange = Math.round((currentLength - lastLength) * 100f / lastLength);
			message = getString(R.string.slow_compile_warning_size_changed,
					lastMs / 1000.0, percentChange, projectedMs / 1000.0);
		}

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.slow_compile_title)
				.setMessage(message)
				.setPositiveButton(R.string.slow_compile_continue, (d, w) -> onProceed.run());
		if (lastMs >= SLOW_COMPILE_CONFIRM_MS) {
			builder.setNegativeButton(android.R.string.cancel, null);
		}
		builder.show();
	}

	private void navigateShader(int direction) {
		var shader = shaderListManager.getAdjacentShader(
				shaderManager.getSelectedShaderId(), direction);
		if (shader != null) {
			selectShader(shader.id());
			uiManager.showShaderNameOverlay(shader.getTitle());
			shaderListManager.revealShader(shader.id());
		}
	}

	// Jumps straight to the first shader in the currently filtered list
	// (Down key, see dispatchKeyEvent()) - same reveal/overlay treatment
	// as navigateShader() above.
	private void navigateToFirstShader() {
		var shader = shaderListManager.getFirstShader();
		if (shader != null) {
			selectShader(shader.id());
			uiManager.showShaderNameOverlay(shader.getTitle());
			shaderListManager.revealShader(shader.id());
		}
	}

	// Appends id to the browser-style navigation history unless it's a
	// no-op re-select of the entry already at the current position,
	// capping the list at SHADER_HISTORY_MAX (oldest dropped first).
	private void recordShaderHistory(long id) {
		if (id <= 0) {
			return;
		}
		if (shaderHistoryIndex >= 0 && shaderHistoryIndex < shaderHistory.size()
				&& shaderHistory.get(shaderHistoryIndex) == id) {
			return;
		}
		shaderHistory.add(id);
		if (shaderHistory.size() > SHADER_HISTORY_MAX) {
			shaderHistory.remove(0);
		}
		shaderHistoryIndex = shaderHistory.size() - 1;
	}

	// Steps back (direction < 0) or forward (direction > 0) through the
	// navigation history, wrapping around at either end (Ctrl+Left /
	// Ctrl+Right - see dispatchKeyEvent()).
	private void navigateShaderHistory(int direction) {
		if (shaderHistory.isEmpty()) {
			return;
		}
		shaderHistoryIndex = Math.floorMod(shaderHistoryIndex + direction, shaderHistory.size());
		navigatingHistory = true;
		try {
			long id = shaderHistory.get(shaderHistoryIndex);
			selectShader(id);
			DataRecords.Shader shader = dataSource.shader.getShader(id);
			if (shader != null) {
				uiManager.showShaderNameOverlay(shader.getTitle());
			}
			shaderListManager.revealShader(id);
		} finally {
			navigatingHistory = false;
		}
	}

	// Pauses first - reads straight from the GLSurfaceView's own
	// framebuffer (see ShaderRenderer.captureFullScreenshot()), so the
	// saved image never has any toolbar/editor/dialog chrome in it, but
	// an un-paused shader would otherwise keep animating underneath the
	// action dialog while the user is still deciding what to do with a
	// frame they've already seen and chosen. Resumed (if it wasn't
	// already paused) once the dialog closes, however it closes.
	private void takeScreenshot() {
		boolean wasPaused = shaderViewManager.isPaused();
		setShaderPaused(true);
		new Thread(() -> {
			byte[] png = shaderViewManager.takeScreenshot();
			runOnUiThread(() -> {
				if (png == null) {
					Toast.makeText(this, R.string.screenshot_save_failed, Toast.LENGTH_SHORT).show();
					setShaderPaused(wasPaused);
					return;
				}
				showScreenshotActionsDialog(png, wasPaused);
			});
		}, "ScreenshotCapture").start();
	}

	// Remembers the last values used in the custom-res/frame-sequence
	// dialog, written right before it actually runs its action and read
	// back the next time the dialog opens - plain SharedPreferences keys
	// rather than anything added to the app's own Preferences class,
	// since these are transient "what did I last type here" dialog
	// state, not documented user-facing settings.
	private static final String PREF_HIGHER_RES_PRESERVE_BACKBUFFER = "higher_res_preserve_backbuffer";
	private static final String PREF_CUSTOM_RES_WIDTH = "custom_res_width";
	private static final String PREF_CUSTOM_RES_HEIGHT = "custom_res_height";
	private static final String PREF_CUSTOM_RES_OVERSAMPLE = "custom_res_oversample";
	private static final String PREF_CUSTOM_RES_TILE_ORDER = "custom_res_tile_order";
	private static final String PREF_CUSTOM_RES_BUCKET_SIZE = "custom_res_bucket_size";
	private static final String PREF_CUSTOM_RES_SEQUENCE_ENABLED = "custom_res_sequence_enabled";
	private static final String PREF_CUSTOM_RES_SEQUENCE_FRAMES = "custom_res_sequence_frames";
	private static final String PREF_CUSTOM_RES_SEQUENCE_FPS = "custom_res_sequence_fps";
	private static final String PREF_CUSTOM_RES_SEQUENCE_SKIP = "custom_res_sequence_skip";
	private static final String PREF_CUSTOM_RES_CREATE_VIDEO = "custom_res_create_video";
	private static final String PREF_CUSTOM_RES_CLEANUP_IMAGES = "custom_res_cleanup_images";
	private static final String PREF_CUSTOM_RES_VIDEO_QUALITY = "custom_res_video_quality";
	private static final String PREF_CUSTOM_RES_VIDEO_SCALE = "custom_res_video_scale";
	private static final String PREF_CUSTOM_RES_MATCH_DEVICE_PLAYBACK = "custom_res_match_device_playback";
	private static final String PREF_LAST_SCREENSHOT_ACTION = "last_screenshot_action";

	@NonNull
	private SharedPreferences dialogPrefs() {
		return ShaderEditorApp.preferences.getSharedPreferences();
	}

	// Subtly marks whichever of these buttons was clicked last time this
	// exact dialog was shown (bold text, nothing more dramatic) - purely
	// a visual memory aid, not read anywhere else.
	private void highlightLastUsedButton(@NonNull String prefKey, @NonNull Button... buttons) {
		int lastUsedId = dialogPrefs().getInt(prefKey, -1);
		for (Button button : buttons) {
			button.setTypeface(null, button.getId() == lastUsedId ? Typeface.BOLD : Typeface.NORMAL);
		}
	}

	private void rememberLastUsedButton(@NonNull String prefKey, int buttonId) {
		dialogPrefs().edit().putInt(prefKey, buttonId).apply();
	}

	// Index-aligned with R.array.custom_res_presets, index 0
	// ("Resolution") excepted - that one's a no-op handled separately,
	// not a dimension pair. Indices 1..CUSTOM_RES_SCREEN_MULTIPLIERS.length
	// are multiples of the device's own live render resolution (the
	// quality-scaled size, not necessarily the raw screen - see
	// showCustomResolutionDialog()'s own deviceResWidth/deviceResHeight,
	// computed at selection time, not fixed here) - this is also what
	// used to be a separate "Save at
	// Higher Res" picker's own fixed {2, 4, 6, 8} list before it got
	// folded into this one dialog's preset dropdown, hence covering the
	// same range (and well below/above it, for deliberately-tiny or
	// deliberately-huge captures) here instead. Indices past that are
	// fixed pixel sizes, the HD/4K ones deliberately landscape per their
	// usual meaning regardless of the device's own orientation.
	private static final float[] CUSTOM_RES_SCREEN_MULTIPLIERS =
			{1 / 32f, 1 / 16f, 1 / 8f, 1 / 4f, 0.5f, 1f, 2f, 4f, 8f};
	private static final int[][] CUSTOM_RES_FIXED_PRESETS = {
			{256, 256},
			{512, 512},
			{1024, 1024},
			{2048, 2048},
			{1280, 720},
			{1920, 1080},
			{3840, 2160},
	};

	// Bundles the views of dialog_custom_resolution.xml's "render
	// sequence / create video" options block, returned by
	// wireSequenceOptions() below - split out from
	// showCustomResolutionDialog() purely to keep that one (already
	// long) method's own body shorter, not because anything else uses
	// this block.
	private static final class SequenceOptionsViews {
		final CheckBox sequenceBox;
		final EditText frameCountView;
		final EditText fpsView;
		final EditText skipFramesView;
		final CheckBox createVideoBox;
		final SeekBar qualitySeekBar;
		final CheckBox cleanupImagesBox;
		final EditText videoScaleView;

		SequenceOptionsViews(@NonNull CheckBox sequenceBox, @NonNull EditText frameCountView,
				@NonNull EditText fpsView, @NonNull EditText skipFramesView,
				@NonNull CheckBox createVideoBox, @NonNull SeekBar qualitySeekBar,
				@NonNull CheckBox cleanupImagesBox, @NonNull EditText videoScaleView) {
			this.sequenceBox = sequenceBox;
			this.frameCountView = frameCountView;
			this.fpsView = fpsView;
			this.skipFramesView = skipFramesView;
			this.createVideoBox = createVideoBox;
			this.qualitySeekBar = qualitySeekBar;
			this.cleanupImagesBox = cleanupImagesBox;
			this.videoScaleView = videoScaleView;
		}
	}

	// Finds and wires up every view in showCustomResolutionDialog()'s
	// "render sequence" options block (initial state from the
	// PREF_CUSTOM_RES_SEQUENCE_* prefs, visibility toggles, the live
	// duration estimate, the quality seekbar's label).
	@NonNull
	private SequenceOptionsViews wireSequenceOptions(@NonNull View view) {
		SharedPreferences prefs = dialogPrefs();
		CheckBox sequenceBox = view.findViewById(R.id.render_sequence);
		View sequenceOptions = view.findViewById(R.id.sequence_options);
		EditText frameCountView = view.findViewById(R.id.sequence_frame_count);
		EditText fpsView = view.findViewById(R.id.sequence_fps);
		EditText skipFramesView = view.findViewById(R.id.sequence_skip_frames);
		TextView durationView = view.findViewById(R.id.sequence_duration);
		CheckBox createVideoBox = view.findViewById(R.id.sequence_create_video);
		View videoOptionsView = view.findViewById(R.id.sequence_video_options);
		TextView qualityLabelView = view.findViewById(R.id.sequence_video_quality_label);
		SeekBar qualitySeekBar = view.findViewById(R.id.sequence_video_quality);
		CheckBox cleanupImagesBox = view.findViewById(R.id.sequence_cleanup_images);
		EditText videoScaleView = view.findViewById(R.id.sequence_video_scale);

		sequenceBox.setChecked(prefs.getBoolean(PREF_CUSTOM_RES_SEQUENCE_ENABLED, false));
		sequenceOptions.setVisibility(sequenceBox.isChecked() ? View.VISIBLE : View.GONE);
		frameCountView.setText(String.valueOf(prefs.getInt(PREF_CUSTOM_RES_SEQUENCE_FRAMES, 30)));
		if (prefs.contains(PREF_CUSTOM_RES_SEQUENCE_FPS)) {
			float fps = prefs.getFloat(PREF_CUSTOM_RES_SEQUENCE_FPS, 30f);
			fpsView.setText(fps == Math.rint(fps)
					? String.valueOf((int) fps)
					: String.valueOf(fps));
		}
		skipFramesView.setText(String.valueOf(prefs.getInt(PREF_CUSTOM_RES_SEQUENCE_SKIP, 0)));
		createVideoBox.setChecked(prefs.getBoolean(PREF_CUSTOM_RES_CREATE_VIDEO, true));
		videoOptionsView.setVisibility(createVideoBox.isChecked() ? View.VISIBLE : View.GONE);
		// Nearly-highest by default - most people reaching for this want
		// the good stuff, not a file-size-optimized compromise.
		int initialQuality = prefs.getInt(PREF_CUSTOM_RES_VIDEO_QUALITY, 90);
		qualitySeekBar.setProgress(initialQuality);
		qualityLabelView.setText(getString(R.string.higher_res_sequence_video_quality, initialQuality));
		cleanupImagesBox.setChecked(prefs.getBoolean(PREF_CUSTOM_RES_CLEANUP_IMAGES, true));
		videoScaleView.setText(String.valueOf(prefs.getInt(PREF_CUSTOM_RES_VIDEO_SCALE, 1)));

		sequenceBox.setOnCheckedChangeListener((button, checked) ->
				sequenceOptions.setVisibility(checked ? View.VISIBLE : View.GONE));
		createVideoBox.setOnCheckedChangeListener((button, checked) ->
				videoOptionsView.setVisibility(checked ? View.VISIBLE : View.GONE));
		qualitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				qualityLabelView.setText(
						getString(R.string.higher_res_sequence_video_quality, progress));
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});

		// Updated live from both fields, so changing either one keeps
		// the estimate honest without needing to tab away first.
		TextWatcher durationWatcher = new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				int frameCount = parsePositiveInt(frameCountView.getText().toString());
				float fps = parsePositiveFloat(fpsView.getText().toString());
				if (frameCount > 0 && fps > 0) {
					durationView.setText(getString(R.string.higher_res_sequence_duration,
							String.format(Locale.US, "%.1f", frameCount / fps)));
					durationView.setVisibility(View.VISIBLE);
				} else {
					durationView.setVisibility(View.GONE);
				}
			}
		};
		frameCountView.addTextChangedListener(durationWatcher);
		fpsView.addTextChangedListener(durationWatcher);
		durationWatcher.afterTextChanged(null);

		return new SequenceOptionsViews(sequenceBox, frameCountView, fpsView, skipFramesView,
				createVideoBox, qualitySeekBar, cleanupImagesBox, videoScaleView);
	}

	// Any resolution, not just a multiplier of the screen - still tiled
	// the same way, so there's no upper bound beyond what the device's
	// memory can actually hold (see HighResScreenshotCapture).
	private void showCustomResolutionDialog(boolean wasPaused) {
		View view = getLayoutInflater().inflate(R.layout.dialog_custom_resolution, null);
		SharedPreferences prefs = dialogPrefs();
		EditText widthView = view.findViewById(R.id.custom_width);
		EditText heightView = view.findViewById(R.id.custom_height);
		DisplayMetrics metrics = getResources().getDisplayMetrics();
		// "1x Device Res" below means the device's own actual screen
		// resolution - plain DisplayMetrics, deliberately *not* scaled
		// by the shader's own quality setting (that's what Match App Res
		// Setting, further down, is for instead) - so "1x" always means
		// "full native res, Video Scale 1" regardless of whatever
		// quality the shader happens to be set to right now.
		int deviceResWidth = metrics.widthPixels;
		int deviceResHeight = metrics.heightPixels;
		// A remembered value (from last time this dialog was actually
		// used) always wins over the current-mode-resolution default -
		// that default only applies the very first time, before
		// anything's been remembered yet.
		widthView.setText(String.valueOf(
				prefs.getInt(PREF_CUSTOM_RES_WIDTH, deviceResWidth)));
		heightView.setText(String.valueOf(
				prefs.getInt(PREF_CUSTOM_RES_HEIGHT, deviceResHeight)));
		Spinner presetSpinner = view.findViewById(R.id.custom_res_preset);
		CheckBox matchAppResBox = view.findViewById(R.id.custom_match_device_playback);
		matchAppResBox.setChecked(
				prefs.getBoolean(PREF_CUSTOM_RES_MATCH_DEVICE_PLAYBACK, false));
		explainOnLongPress(matchAppResBox, R.string.higher_res_match_device_playback);
		EditText oversampleView = view.findViewById(R.id.custom_oversample);
		oversampleView.setText(String.valueOf(prefs.getInt(PREF_CUSTOM_RES_OVERSAMPLE, 1)));
		explainOnLongPress(view.findViewById(R.id.custom_oversample_label), R.string.higher_res_oversample);
		Spinner tileOrderSpinner = view.findViewById(R.id.custom_tile_order);
		tileOrderSpinner.setSelection(prefs.getInt(
				PREF_CUSTOM_RES_TILE_ORDER, TileRenderOrder.HILBERT.ordinal()));
		Spinner bucketSizeSpinner = view.findViewById(R.id.custom_bucket_size);
		bucketSizeSpinner.setSelection(prefs.getInt(
				PREF_CUSTOM_RES_BUCKET_SIZE, TileBucketSize.NORMAL.ordinal()));
		CheckBox preserveBackbufferBox = view.findViewById(R.id.preserve_backbuffer_edges);
		preserveBackbufferBox.setChecked(
				prefs.getBoolean(PREF_HIGHER_RES_PRESERVE_BACKBUFFER, true));
		explainOnLongPress(preserveBackbufferBox, R.string.higher_res_preserve_backbuffer);
		SequenceOptionsViews sequenceViews = wireSequenceOptions(view);
		explainOnLongPress(view.findViewById(R.id.sequence_video_scale_label),
				R.string.higher_res_sequence_video_scale);

		// While Match App Res Setting is on, every parameter below it is
		// entirely computed from the shader's own current live
		// resolution/quality setting (see applyMatchAppResSetting
		// below), ignoring the preset dropdown entirely - disabled here
		// (dropdown included) so it's visually obvious none of them are
		// free to hand-edit or pick a preset right now, rather than
		// silently overwriting whatever the user just set the next time
		// this re-applies. Turning the checkbox back off immediately
		// frees everything up again, still holding whatever values they
		// last had.
		Runnable updateMatchAppResEnablement = () -> {
			boolean matching = matchAppResBox.isChecked();
			presetSpinner.setEnabled(!matching);
			widthView.setEnabled(!matching);
			heightView.setEnabled(!matching);
			oversampleView.setEnabled(!matching);
			bucketSizeSpinner.setEnabled(!matching);
			sequenceViews.videoScaleView.setEnabled(!matching);
		};
		// Mirrors the shader's own current live resolution exactly
		// (reading the renderer's actual surface size directly, not
		// re-deriving it from DisplayMetrics * quality - see
		// ShaderRenderer.getRenderWidth()'s own doc for why that's not
		// quite the same thing) - oversampling/bucket size are fixed
		// (1/Max) since there's no tiling overhead concern once the
		// target is already this close to the live render size; Video
		// Scale is the reciprocal of the live quality, landing back on
		// the device's actual native resolution for 1:1 fullscreen
		// playback, same idea as a "x Device Res" preset below but
		// anchored to the *current* quality instead of a fixed
		// multiplier of it.
		Runnable applyMatchAppResSetting = () -> {
			int liveRenderWidth = shaderViewManager.getRenderWidth();
			int liveRenderHeight = shaderViewManager.getRenderHeight();
			float quality = shaderManager.getQuality();
			int matchWidth = liveRenderWidth > 0
					? liveRenderWidth : Math.max(1, Math.round(deviceResWidth * quality));
			int matchHeight = liveRenderHeight > 0
					? liveRenderHeight : Math.max(1, Math.round(deviceResHeight * quality));
			widthView.setText(String.valueOf(matchWidth));
			heightView.setText(String.valueOf(matchHeight));
			oversampleView.setText("1");
			bucketSizeSpinner.setSelection(TileBucketSize.MAX.ordinal());
			sequenceViews.videoScaleView.setText(
					String.valueOf(Math.max(1, Math.round(1f / quality))));
		};
		updateMatchAppResEnablement.run();
		if (matchAppResBox.isChecked()) {
			applyMatchAppResSetting.run();
		}
		matchAppResBox.setOnCheckedChangeListener((button, checked) -> {
			updateMatchAppResEnablement.run();
			if (checked) {
				applyMatchAppResSetting.run();
			}
		});

		presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
				if (position == 0) {
					return;
				}
				boolean isDeviceResPreset = position <= CUSTOM_RES_SCREEN_MULTIPLIERS.length;
				int presetWidth;
				int presetHeight;
				// Video Scale is set by every preset here - a "x Device
				// Res" preset sets it to whatever reciprocal lands back
				// on the device's own native resolution (so the saved
				// video plays back fullscreen 1:1 by default), a fixed
				// pixel-size preset resets it to 1 (no scaling - there's
				// no "native resolution" relationship to aim for with an
				// arbitrary fixed size). Always stays editable
				// afterward - this listener can't even fire while Match
				// App Res Setting has the preset spinner disabled (see
				// updateMatchAppResEnablement above), so there's no
				// mode-dependent branching needed here any more.
				int videoScale;
				if (isDeviceResPreset) {
					float multiplier = CUSTOM_RES_SCREEN_MULTIPLIERS[position - 1];
					presetWidth = Math.round(deviceResWidth * multiplier);
					presetHeight = Math.round(deviceResHeight * multiplier);
					videoScale = Math.max(1, Math.round(1f / multiplier));
				} else {
					int[] fixed = CUSTOM_RES_FIXED_PRESETS[
							position - 1 - CUSTOM_RES_SCREEN_MULTIPLIERS.length];
					presetWidth = fixed[0];
					presetHeight = fixed[1];
					videoScale = 1;
				}
				widthView.setText(String.valueOf(presetWidth));
				heightView.setText(String.valueOf(presetHeight));
				sequenceViews.videoScaleView.setText(String.valueOf(videoScale));
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		// Select-all on focus for every field, so tabbing/tapping in
		// immediately lets typing replace the old value instead of
		// appending to it.
		widthView.setOnFocusChangeListener((v, hasFocus) -> {
			if (hasFocus) {
				widthView.selectAll();
			}
		});
		heightView.setOnFocusChangeListener((v, hasFocus) -> {
			if (hasFocus) {
				heightView.selectAll();
			}
		});
		// Keeps height mirroring width live, unconditionally - every
		// width keystroke overwrites height to match, not just the
		// first time or only while height is still blank. A non-square
		// shot means editing height *after* width is otherwise final,
		// since going back to tweak width again will re-square it.
		widthView.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				heightView.setText(s);
			}
		});
		oversampleView.setOnFocusChangeListener((v, hasFocus) -> {
			if (hasFocus) {
				oversampleView.selectAll();
			}
		});

		// Plain custom buttons (not AlertDialog's built-in positive/
		// negative/neutral slots) - those reorder depending on whether
		// Android lays them out in a row or stacks them vertically (not
		// reliably under our control), which made "highlight last used"
		// unreliable via setPositiveButton/etc. alone.
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.higher_res_custom)
				.setView(view)
				.setOnCancelListener(d -> setShaderPaused(wasPaused))
				.show();
		view.findViewById(R.id.custom_res_ok).setOnClickListener(v -> {
			int width = parsePositiveInt(widthView.getText().toString());
			int height = parsePositiveInt(heightView.getText().toString());
			int oversample = Math.max(1, parsePositiveInt(oversampleView.getText().toString()));
			TileRenderOrder tileOrder = TileRenderOrder.values()[tileOrderSpinner.getSelectedItemPosition()];
			TileBucketSize bucketSize = TileBucketSize.values()[bucketSizeSpinner.getSelectedItemPosition()];
			if (width <= 0 || height <= 0) {
				Toast.makeText(this, R.string.higher_res_custom_invalid, Toast.LENGTH_SHORT).show();
				setShaderPaused(wasPaused);
				dialog.dismiss();
				return;
			}
			SharedPreferences.Editor prefsEditor = dialogPrefs().edit()
					.putInt(PREF_CUSTOM_RES_WIDTH, width)
					.putInt(PREF_CUSTOM_RES_HEIGHT, height)
					.putInt(PREF_CUSTOM_RES_OVERSAMPLE, oversample)
					.putInt(PREF_CUSTOM_RES_TILE_ORDER, tileOrder.ordinal())
					.putInt(PREF_CUSTOM_RES_BUCKET_SIZE, bucketSize.ordinal())
					.putBoolean(PREF_HIGHER_RES_PRESERVE_BACKBUFFER, preserveBackbufferBox.isChecked())
					.putBoolean(PREF_CUSTOM_RES_MATCH_DEVICE_PLAYBACK,
							matchAppResBox.isChecked())
					.putBoolean(PREF_CUSTOM_RES_SEQUENCE_ENABLED, sequenceViews.sequenceBox.isChecked());
			if (sequenceViews.sequenceBox.isChecked()) {
				int frameCount = parsePositiveInt(sequenceViews.frameCountView.getText().toString());
				float fps = parsePositiveFloat(sequenceViews.fpsView.getText().toString());
				int skipFrames = Math.max(0,
						parsePositiveInt(sequenceViews.skipFramesView.getText().toString()));
				if (frameCount <= 0 || fps <= 0) {
					Toast.makeText(this, R.string.higher_res_sequence_invalid, Toast.LENGTH_SHORT).show();
					setShaderPaused(wasPaused);
					dialog.dismiss();
					return;
				}
				int videoQuality = sequenceViews.qualitySeekBar.getProgress();
				int videoScale = Math.max(1,
						parsePositiveInt(sequenceViews.videoScaleView.getText().toString()));
				prefsEditor.putInt(PREF_CUSTOM_RES_SEQUENCE_FRAMES, frameCount)
						.putFloat(PREF_CUSTOM_RES_SEQUENCE_FPS, fps)
						.putInt(PREF_CUSTOM_RES_SEQUENCE_SKIP, skipFrames)
						.putBoolean(PREF_CUSTOM_RES_CREATE_VIDEO, sequenceViews.createVideoBox.isChecked())
						.putInt(PREF_CUSTOM_RES_VIDEO_QUALITY, videoQuality)
						.putInt(PREF_CUSTOM_RES_VIDEO_SCALE, videoScale)
						.putBoolean(PREF_CUSTOM_RES_CLEANUP_IMAGES,
								sequenceViews.cleanupImagesBox.isChecked())
						.apply();
				dialog.dismiss();
				runFrameSequenceCapture(width, height, oversample, preserveBackbufferBox.isChecked(),
						bucketSize, tileOrder, skipFrames, frameCount, fps,
						sequenceViews.createVideoBox.isChecked(), videoQuality, videoScale,
						sequenceViews.cleanupImagesBox.isChecked(), wasPaused);
				return;
			}
			prefsEditor.apply();
			dialog.dismiss();
			runHighResCapture(width, height, oversample, preserveBackbufferBox.isChecked(), bucketSize,
					tileOrder, wasPaused);
		});
		view.findViewById(R.id.custom_res_cancel).setOnClickListener(v -> {
			dialog.dismiss();
			setShaderPaused(wasPaused);
		});
	}

	// Long-press hint for a shortened label - shows its full explanation
	// as a Toast rather than android:tooltipText, since the system
	// tooltip popup can clip long text against this dialog's own window
	// bounds instead of reliably wrapping to fit it all. A Toast always
	// sizes itself to its full (wrapped) content instead.
	private void explainOnLongPress(@NonNull View view, @StringRes int explanationResId) {
		view.setOnLongClickListener(v -> {
			Toast.makeText(this, explanationResId, Toast.LENGTH_LONG).show();
			return true;
		});
	}

	private static int parsePositiveInt(@NonNull String text) {
		try {
			return Integer.parseInt(text.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static float parsePositiveFloat(@NonNull String text) {
		try {
			float value = Float.parseFloat(text.trim());
			return value > 0 ? value : -1;
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	// For use as a filename (a frame sequence's shared prefix) - strips
	// anything that isn't safely portable across MediaStore/filesystems
	// rather than trying to allow-list every legal character.
	private static String sanitizeFilename(@NonNull String name) {
		String sanitized = name.trim().replaceAll("[^A-Za-z0-9 _-]", "_");
		return sanitized.isBlank() ? "shader" : sanitized;
	}

	// A fixed, deliberately small weight for the "compiling" phase within
	// the single end-to-end progress bar (see runHighResCapture()/
	// runFrameSequenceCapture()) - compiling is fast next to tiled
	// rendering (which can be hundreds or thousands of tile-units), so a
	// small fixed slice keeps the bar's early motion proportionate
	// instead of racing through a large chunk of the bar for the
	// cheapest part of the whole job.
	private static final int PROGRESS_COMPILE_UNITS = 10;

	// Longest edge of the live tile-preview Bitmap on the progress
	// dialog (R.id.tile_preview) - small on purpose, since every tile's
	// contribution gets downsampled into it, and a bigger one would mean
	// more pixels to touch per tile for a preview nobody's examining
	// pixel-by-pixel anyway.
	private static final int TILE_PREVIEW_MAX_DIMENSION = 256;

	// Updated after every sequence+video capture's real encoding phase
	// (see the videoProgress listener in runFrameSequenceCapture())
	// finishes at least one frame - reused as the best available
	// estimate of "how long will encoding take per frame" for *future*
	// captures' render-phase ETA, which otherwise has no way to know
	// that before encoding has actually started even once. -1 means
	// nothing's been measured yet this process (first-ever sequence
	// capture), in which case ESTIMATED_ENCODE_SECONDS_PER_FRAME_FALLBACK
	// is used instead - a rough guess, not a measurement.
	private static volatile double lastKnownEncodeSecondsPerFrame = -1;
	private static final double ESTIMATED_ENCODE_SECONDS_PER_FRAME_FALLBACK = 0.3;

	private final Random random = new Random();

	@NonNull
	private String pickRandomStatus(int arrayResId) {
		String[] variants = getResources().getStringArray(arrayResId);
		return variants[random.nextInt(variants.length)];
	}

	// Mirrors overallBar's progress into HighResCaptureService's
	// notification, so someone who's backgrounded the app mid-capture can
	// tell how far along it is without reopening it - only while that
	// service is actually running (serviceActive[0], set true by the
	// capture thread once it knows a SharedCaptureContext was available),
	// and only when the integer percentage actually changed
	// (lastNotifiedPercent[0]) so a notify() isn't issued for every single
	// tile. Called from the UI thread, same as the progress bar update it
	// always follows.
	private void notifyCaptureProgress(
			@NonNull boolean[] serviceActive, @NonNull int[] lastNotifiedPercent,
			@NonNull ProgressBar overallBar) {
		if (!serviceActive[0] || overallBar.getMax() <= 0) {
			return;
		}
		int percent = (int) (100L * overallBar.getProgress() / overallBar.getMax());
		if (percent != lastNotifiedPercent[0]) {
			lastNotifiedPercent[0] = percent;
			HighResCaptureService.updateProgress(this, percent);
		}
	}

	// Shows "Avg <seconds>s/<tile-or-frame> · ETA <mm:ss>" on timingView,
	// averaged over every completed unit (tile, for a single capture;
	// frame, for a sequence - formatResId picks the right noun) since
	// startNanos. No-op (stays hidden) until at least one unit has
	// completed, since there's nothing to average yet before that.
	private void updateTimingEstimate(@NonNull TextView timingView, int formatResId,
			long startNanos, int completedUnits, int remainingUnits) {
		if (completedUnits <= 0) {
			return;
		}
		double avgSeconds = (System.nanoTime() - startNanos) / 1e9 / completedUnits;
		long etaSeconds = Math.round(avgSeconds * remainingUnits);
		timingView.setVisibility(View.VISIBLE);
		timingView.setText(getString(formatResId, avgSeconds, formatEtaDuration(etaSeconds)));
	}

	@NonNull
	private static String formatEtaDuration(long totalSeconds) {
		long minutes = totalSeconds / 60;
		long seconds = totalSeconds % 60;
		return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds);
	}

	// Fits width x height within TILE_PREVIEW_MAX_DIMENSION on its longer
	// edge, preserving aspect ratio - the size to allocate the progress
	// dialog's tile_preview Bitmap at.
	@NonNull
	private static int[] computeTilePreviewSize(int width, int height) {
		if (width <= 0 || height <= 0) {
			return new int[]{1, 1};
		}
		float scale = Math.min(1f, (float) TILE_PREVIEW_MAX_DIMENSION / Math.max(width, height));
		return new int[]{
				Math.max(1, Math.round(width * scale)),
				Math.max(1, Math.round(height * scale))};
	}

	// A fresh Bitmap defaults to fully transparent, which can blend
	// invisibly into the dialog's own background until enough tiles have
	// painted over it - erasing to a neutral opaque color first means
	// every tile's arrival is visible as a distinct patch regardless of
	// what colors the shader itself happens to render.
	@NonNull
	private static Bitmap createTilePreviewBitmap(int width, int height) {
		Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		bitmap.eraseColor(0xff202020);
		return bitmap;
	}

	// Builds a TilePreviewListener that nearest-neighbor-downsamples each
	// tile's own pixels straight into the matching sub-rect of
	// previewBitmap and shows/invalidates previewView - shared by both
	// runHighResCapture() and runFrameSequenceCapture() (a sequence just
	// keeps overwriting the same bitmap, frame after frame). The
	// downsample loop itself is bounded by the destination sub-rect's
	// size (a small fraction of previewBitmap's already-small area, not
	// the tile's own up-to-512x512 size), so this stays cheap regardless
	// of how big the real capture is; only the final Bitmap.setPixels()
	// + invalidate() needs the UI thread.
	@NonNull
	private ShaderRenderer.TilePreviewListener createTilePreviewListener(
			@NonNull ImageView previewView, @NonNull Bitmap previewBitmap,
			@NonNull ImageView lastTileView, boolean singlePass) {
		int previewWidth = previewBitmap.getWidth();
		int previewHeight = previewBitmap.getHeight();
		return (contentX0, contentY0, contentW, contentH, targetWidth, targetHeight, argb) -> {
			if (targetWidth <= 0 || targetHeight <= 0) {
				return;
			}
			// Nothing to "fill in" with only one tile (the whole image)
			// - skip the small zoomed-out overview entirely (it stays
			// at its default "gone", which also lets lastTileView below
			// expand to the full row width instead of sharing it) and
			// just show that one tile, i.e. the whole image, full
			// resolution, centered.
			if (singlePass) {
				Bitmap wholeImageBitmap =
						Bitmap.createBitmap(argb, contentW, contentH, Bitmap.Config.ARGB_8888);
				runOnUiThread(() -> {
					lastTileView.setVisibility(View.VISIBLE);
					lastTileView.setImageBitmap(wholeImageBitmap);
				});
				return;
			}
			int dstX0 = contentX0 * previewWidth / targetWidth;
			int dstY0 = contentY0 * previewHeight / targetHeight;
			int dstX1 = Math.min(previewWidth,
					Math.max(dstX0 + 1, (contentX0 + contentW) * previewWidth / targetWidth));
			int dstY1 = Math.min(previewHeight,
					Math.max(dstY0 + 1, (contentY0 + contentH) * previewHeight / targetHeight));
			int dstW = dstX1 - dstX0;
			int dstH = dstY1 - dstY0;
			if (dstW <= 0 || dstH <= 0) {
				return;
			}
			int[] previewPixels = new int[dstW * dstH];
			for (int dy = 0; dy < dstH; ++dy) {
				int srcY = Math.min(contentH - 1, dy * contentH / dstH);
				for (int dx = 0; dx < dstW; ++dx) {
					int srcX = Math.min(contentW - 1, dx * contentW / dstW);
					previewPixels[dy * dstW + dx] = argb[srcY * contentW + srcX];
				}
			}
			// argb is already exactly contentW x contentH, top-down,
			// ARGB_8888-ordered - the same shape Bitmap.createBitmap()'s
			// int[] constructor wants, so the tile's own pixels need no
			// further processing for this close-up view (unlike
			// previewPixels above, which deliberately downsamples).
			// Rebuilt fresh each tile (not mutated in place like
			// previewBitmap) since tile size itself varies at the
			// right/bottom edges.
			Bitmap lastTileBitmap = Bitmap.createBitmap(argb, contentW, contentH, Bitmap.Config.ARGB_8888);
			runOnUiThread(() -> {
				previewBitmap.setPixels(previewPixels, 0, dstW, dstX0, dstY0, dstW, dstH);
				previewView.setVisibility(View.VISIBLE);
				previewView.invalidate();
				lastTileView.setVisibility(View.VISIBLE);
				lastTileView.setImageBitmap(lastTileBitmap);
			});
		};
	}

	// Compiling the capture-only (tile-offset-injected) program has no
	// real progress signal of its own - GL offers no mid-compile
	// callback - so this fakes one, informed by how long the *live*
	// shader's last compile took (a close proxy: near-identical source,
	// just with a small macro prepended). Animates the bar toward ~92%
	// of PROGRESS_COMPILE_UNITS over that long (with a safety margin,
	// since this is an estimate) using a decelerating curve, so it
	// visibly slows down rather than snapping to a false "done" - never
	// claims the full compile slice itself, since the real tile-progress
	// callback (which cancels this and extends the bar's max with the
	// now-known real tile count) is what actually knows when compilation
	// finished. Falls back to a static "compiling" message with no
	// animation if there's no prior compile time to go on yet (e.g. this
	// shader has never been compiled this launch).
	@Nullable
	private ValueAnimator startCompileProgressAnimation(
			@NonNull TextView statusMessage, @NonNull ProgressBar overallBar) {
		overallBar.setMax(PROGRESS_COMPILE_UNITS);
		overallBar.setProgress(0);
		long lastCompileNanos = shaderViewManager.getLastCompileNanos();
		if (lastCompileNanos <= 0) {
			statusMessage.setText(R.string.higher_res_compiling);
			return null;
		}
		long lastCompileMs = lastCompileNanos / 1_000_000;
		statusMessage.setText(getString(R.string.higher_res_compiling_estimate, lastCompileMs));
		long estimatedMs = Math.max(200, (long) (lastCompileMs * 1.2));
		ValueAnimator animator = ValueAnimator.ofInt(0, (int) (PROGRESS_COMPILE_UNITS * 0.92));
		animator.setDuration(estimatedMs);
		animator.setInterpolator(new DecelerateInterpolator());
		animator.addUpdateListener(a -> overallBar.setProgress((int) a.getAnimatedValue()));
		animator.start();
		return animator;
	}

	// wasPaused: see showCustomResolutionDialog() - the pause state from
	// before the whole flow started, restored only once the capture AND
	// the save (and whatever the chosen action does after - here always
	// Save and Open) have both finished, so the shader stays still for
	// the entire flow rather than resuming the moment pixels are read
	// back. Tiling a large image can take anywhere from a couple of
	// seconds to minutes (large oversample factors especially), so the
	// progress dialog switches from indeterminate to a tile-count
	// progress bar as soon as the first progress callback arrives.
	private void runHighResCapture(int width, int height, int oversample,
			boolean preserveBackbufferEdges, @NonNull TileBucketSize bucketSize,
			@NonNull TileRenderOrder tileOrder, boolean wasPaused) {
		setShaderPaused(true);
		View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
		TextView statusMessage = progressView.findViewById(R.id.status_message);
		TextView timingView = progressView.findViewById(R.id.progress_timing);
		ProgressBar overallBar = progressView.findViewById(R.id.progress_overall);
		ProgressBar subtaskBar = progressView.findViewById(R.id.progress_subtask);
		ImageView tilePreviewView = progressView.findViewById(R.id.tile_preview);
		ImageView lastTileView = progressView.findViewById(R.id.last_tile_preview);
		Button cancelButton = progressView.findViewById(R.id.progress_cancel);
		int[] previewSize = computeTilePreviewSize(width, height);
		Bitmap previewBitmap = createTilePreviewBitmap(previewSize[0], previewSize[1]);
		tilePreviewView.setImageBitmap(previewBitmap);
		// Approximates ShaderRenderer's own singlePass eligibility check
		// (it also requires the resolution to fit in one GL texture,
		// which only the GL thread can confirm) - close enough for
		// deciding how to lay out the preview; the rare case where the
		// GL thread falls back to tiling anyway just means this ends up
		// showing the untiled layout for a tiled capture, not a crash.
		boolean singlePass = bucketSize == TileBucketSize.MAX && oversample == 1;
		ShaderRenderer.TilePreviewListener tilePreviewListener =
				createTilePreviewListener(tilePreviewView, previewBitmap, lastTileView, singlePass);
		AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
				.setView(progressView)
				.setCancelable(false)
				.show();
		// Checked (synchronously, on the capture thread) by progressListener
		// below, between every tile - there's no partial file to clean up
		// for a single image (nothing gets saved until the whole thing
		// finishes), so cancelling here is just "stop and don't save",
		// unlike the sequence version's keep/delete choice.
		AtomicBoolean cancelRequested = new AtomicBoolean(false);
		cancelButton.setOnClickListener(v -> {
			cancelRequested.set(true);
			cancelButton.setEnabled(false);
		});
		// A tiled capture can run for several seconds (or, at a large
		// oversample factor, minutes) entirely on the GL thread. Without
		// this, a screen timeout mid-capture lets Android tear down the
		// GLSurfaceView's EGL context from under it (same as backgrounding
		// the app) - every GL object the capture was still using becomes
		// invalid, and uniform/readback calls start returning garbage for
		// whatever tiles hadn't rendered yet. Cleared once the capture
		// (success or failure) is done, below.
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		ValueAnimator[] compileAnim = {startCompileProgressAnimation(statusMessage, overallBar)};
		// Picked once, the first time real tile progress arrives (not
		// re-rolled every tile) - see R.array.status_tile_single's own
		// doc. Gated on its own flag, not on compileAnim being non-null -
		// the latter starts out null already (no crash either way, just
		// never set) when there's no prior compile time to animate
		// toward in the first place.
		boolean[] tileInfoKnown = {false};
		String[] tileVariant = {null};
		// Set once the capture thread below knows whether
		// HighResCaptureService is actually running for this capture -
		// progressListener is built before that's known, so it reads this
		// flag (and throttles via lastNotifiedPercent) each callback
		// rather than capturing a value.
		boolean[] serviceActive = {false};
		int[] lastNotifiedPercent = {-1};
		// Marked the moment the total tile count becomes known (the
		// first tile), so the average below is over actual tile-render
		// time only, not however long compiling took first.
		long[] captureStartNanos = {0};
		ShaderRenderer.HighResProgressListener progressListener = (tilesDone, totalTiles) -> {
			runOnUiThread(() -> {
				if (compileAnim[0] != null) {
					compileAnim[0].cancel();
					compileAnim[0] = null;
				}
				if (!tileInfoKnown[0]) {
					tileInfoKnown[0] = true;
					overallBar.setMax(PROGRESS_COMPILE_UNITS + totalTiles);
					subtaskBar.setVisibility(View.VISIBLE);
					subtaskBar.setMax(totalTiles);
					tileVariant[0] = pickRandomStatus(R.array.status_tile_single);
					captureStartNanos[0] = System.nanoTime();
				}
				overallBar.setProgress(PROGRESS_COMPILE_UNITS + tilesDone);
				subtaskBar.setProgress(tilesDone);
				statusMessage.setText(
						String.format(Locale.getDefault(), tileVariant[0], tilesDone, totalTiles));
				updateTimingEstimate(timingView, R.string.progress_timing_tile,
						captureStartNanos[0], tilesDone, totalTiles - tilesDone);
				notifyCaptureProgress(serviceActive, lastNotifiedPercent, overallBar);
			});
			return !cancelRequested.get();
		};
		new Thread(() -> {
			// Grabbing a SharedCaptureContext here, right as the capture
			// starts (so the live GL context is definitely still around
			// to share against), and running the tiled capture against it
			// instead of the live context directly, means this whole
			// capture keeps working even if the user backgrounds the app
			// partway through - see ShaderRenderer.
			// captureHighResScreenshotOnSharedContext() and
			// HighResCaptureService for why. Falls back to the
			// live-context path (today's original behavior, same risk of
			// being interrupted by backgrounding as before) if a shared
			// context couldn't be created for any reason.
			SharedCaptureContext sharedContext = shaderViewManager.createSharedCaptureContext();
			boolean backgroundCapable = sharedContext != null;
			if (backgroundCapable) {
				shaderViewManager.setBackgroundCaptureActive(true);
				HighResCaptureService.start(this);
				serviceActive[0] = true;
			}
			byte[] png = backgroundCapable
					? shaderViewManager.takeHighResScreenshotOnSharedContext(sharedContext,
							width, height, oversample, preserveBackbufferEdges, bucketSize, tileOrder,
							progressListener, tilePreviewListener)
					: shaderViewManager.takeHighResScreenshot(
							width, height, oversample, preserveBackbufferEdges, bucketSize, tileOrder,
							progressListener, tilePreviewListener);
			if (backgroundCapable) {
				sharedContext.destroy();
				HighResCaptureService.stop(this);
			}
			runOnUiThread(() -> {
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				progressDialog.dismiss();
				if (backgroundCapable) {
					shaderViewManager.setBackgroundCaptureActive(false);
				}
				if (png == null) {
					Toast.makeText(this,
							cancelRequested.get() ? R.string.higher_res_cancelled
									: R.string.screenshot_save_failed,
							Toast.LENGTH_SHORT).show();
					setShaderPaused(wasPaused);
					return;
				}
				// Straight to Save and Open (no action picker) - by the
				// time someone's picked a resolution and waited out the
				// tiled render, they want to see the result, not choose
				// again from a dialog they've effectively already been
				// through once for this capture. Pause is only restored
				// once this (the save itself) is also done.
				handleScreenshotAction(png, ScreenshotAction.SAVE_AND_OPEN,
						() -> setShaderPaused(wasPaused));
			});
		}, "HighResScreenshotCapture").start();
	}

	private static final int[] ORDINAL_SUFFIXES_11_13 = {11, 12, 13};

	private static String ordinal(int day) {
		for (int special : ORDINAL_SUFFIXES_11_13) {
			if (day == special) {
				return day + "th";
			}
		}
		return switch (day % 10) {
			case 1 -> day + "st";
			case 2 -> day + "nd";
			case 3 -> day + "rd";
			default -> day + "th";
		};
	}

	// e.g. "8:15am5thJune2026" - deliberately prose-y rather than a
	// sortable timestamp, for a video filename someone's actually going
	// to read, not just a machine-generated sequence like the per-frame
	// images already have.
	private static String proseDateString(@NonNull Date date) {
		Calendar calendar = Calendar.getInstance();
		calendar.setTime(date);
		int hour24 = calendar.get(Calendar.HOUR_OF_DAY);
		int hour12 = hour24 % 12 == 0 ? 12 : hour24 % 12;
		int minute = calendar.get(Calendar.MINUTE);
		String amPm = hour24 < 12 ? "am" : "pm";
		int day = calendar.get(Calendar.DAY_OF_MONTH);
		String month = new SimpleDateFormat("MMMM", Locale.US).format(date);
		int year = calendar.get(Calendar.YEAR);
		return String.format(Locale.US, "%d:%02d%s%s%s%d", hour12, minute, amPm, ordinal(day), month, year);
	}

	// Like runHighResCapture() above, but renders numFrames frames -
	// each virtualFps seconds apart in the shader's own simulated time,
	// not wall-clock, after first stepping skipFrames frames forward
	// without saving anything - naming each saved one
	// "<shader>.NNNNNN.png" (1-indexed) and saving it into its own
	// subfolder as soon as it's ready, rather than holding every frame's
	// PNG in memory until the whole sequence finishes. The subfolder
	// name gets a numeric suffix (shaderName, shaderName1, ...) if a
	// previous run already used the plain one, so re-rendering the same
	// shader doesn't mix a new run's frames in with an old one's. If
	// createVideo is on, assembles the saved frames into an MP4
	// afterwards (see VideoEncoder) and, if cleanupImages is also on and
	// that succeeded, deletes the per-frame PNGs. No "Save and Open" at
	// the end - opening a single image wouldn't make sense for a batch
	// of them - just a toast summarizing what happened.
	private void runFrameSequenceCapture(int width, int height, int oversample,
			boolean preserveBackbufferEdges, @NonNull TileBucketSize bucketSize,
			@NonNull TileRenderOrder initialTileOrder, int skipFrames,
			int numFrames, float virtualFps, boolean createVideo, int videoQuality,
			int videoScale, boolean cleanupImages, boolean wasPaused) {
		setShaderPaused(true);
		var shader = dataSource.shader.getShader(shaderManager.getSelectedShaderId());
		String baseShaderName = sanitizeFilename(shader != null ? shader.getTitle() : "shader");

		View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
		TextView statusMessage = progressView.findViewById(R.id.status_message);
		TextView timingView = progressView.findViewById(R.id.progress_timing);
		ProgressBar overallBar = progressView.findViewById(R.id.progress_overall);
		ProgressBar subtaskBar = progressView.findViewById(R.id.progress_subtask);
		ImageView tilePreviewView = progressView.findViewById(R.id.tile_preview);
		ImageView lastTileView = progressView.findViewById(R.id.last_tile_preview);
		Button cancelButton = progressView.findViewById(R.id.progress_cancel);
		int[] previewSize = computeTilePreviewSize(width, height);
		Bitmap previewBitmap = createTilePreviewBitmap(previewSize[0], previewSize[1]);
		tilePreviewView.setImageBitmap(previewBitmap);
		// Same approximation as runHighResCapture()'s own copy of this.
		boolean singlePass = bucketSize == TileBucketSize.MAX && oversample == 1;
		ShaderRenderer.TilePreviewListener tilePreviewListener =
				createTilePreviewListener(tilePreviewView, previewBitmap, lastTileView, singlePass);
		// Live-changeable mid-sequence (see TileOrderSupplier's own doc) -
		// seeded from whatever was picked in the Custom Resolution
		// dialog, and written back to that same persisted preference on
		// every change here too, so "last used" stays accurate regardless
		// of which of the two pickers was used last.
		AtomicReference<TileRenderOrder> liveTileOrder = new AtomicReference<>(initialTileOrder);
		ShaderRenderer.TileOrderSupplier tileOrderSupplier = liveTileOrder::get;
		Spinner tileOrderSpinner = progressView.findViewById(R.id.progress_tile_order);
		tileOrderSpinner.setVisibility(View.VISIBLE);
		tileOrderSpinner.setSelection(initialTileOrder.ordinal());
		tileOrderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
				TileRenderOrder selected = TileRenderOrder.values()[position];
				liveTileOrder.set(selected);
				dialogPrefs().edit().putInt(PREF_CUSTOM_RES_TILE_ORDER, selected.ordinal()).apply();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
		AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
				.setView(progressView)
				.setCancelable(false)
				.show();
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		// Checked (synchronously, on the capture thread) by
		// sequenceProgressListener/tileProgressListener below, between
		// every skip step, frame and tile - a sequence saves each frame
		// to disk as soon as it's captured (see frameListener), so unlike
		// the single-image capture, cancelling partway through can leave
		// real files behind; deletePartialOnCancel (only meaningful once
		// cancelRequested is set) records which of Delete/Keep was
		// chosen. Video encoding is always skipped on cancellation
		// regardless of createVideo - assembling a video from a
		// deliberately-interrupted sequence isn't what anyone wants once
		// they've asked for the thing to stop.
		AtomicBoolean cancelRequested = new AtomicBoolean(false);
		AtomicBoolean deletePartialOnCancel = new AtomicBoolean(false);
		cancelButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.cancel_capture_title)
				.setMessage(R.string.cancel_capture_message)
				.setPositiveButton(R.string.cancel_capture_delete, (d, w) -> {
					deletePartialOnCancel.set(true);
					cancelRequested.set(true);
					cancelButton.setEnabled(false);
				})
				.setNegativeButton(R.string.cancel_capture_keep, (d, w) -> {
					cancelRequested.set(true);
					cancelButton.setEnabled(false);
				})
				.setNeutralButton(R.string.cancel_capture_keep_rendering, null)
				.show());

		// Only offered when a video is actually being made - stopping
		// early has nothing extra to offer Cancel otherwise. Unlike
		// Cancel, needs no confirmation dialog: nothing destructive
		// happens (frames already on disk are kept either way). Lets
		// whichever frame is currently tiling finish normally (see
		// tileProgressListener's return value below) rather than
		// discarding it mid-render - only refuses to *start* another
		// frame or skip-step afterward (sequenceProgressListener's
		// return value) - then heads straight to encoding whatever was
		// captured, same as a normal finish.
		AtomicBoolean interruptRequested = new AtomicBoolean(false);
		Button interruptButton = progressView.findViewById(R.id.progress_interrupt_make_video);
		if (createVideo) {
			interruptButton.setVisibility(View.VISIBLE);
			interruptButton.setOnClickListener(v -> {
				interruptRequested.set(true);
				interruptButton.setEnabled(false);
				cancelButton.setEnabled(false);
			});
		}

		// One bar for the whole job: compiling (a small fixed slice),
		// stepping through any skipped frames (1 unit each), tiled
		// rendering (1 unit per tile - the dominant cost, found out only
		// once the first tile of the first frame actually reports its
		// own total, at which point the bar's max gets extended from
		// this provisional estimate to the real figure), then video
		// encoding if asked for (1 unit per frame). currentFrameIndex is
		// advanced by frameListener once each frame's capture
		// completes, so the tile listener (which only reports progress
		// *within* whichever frame is currently rendering) knows which
		// frame's worth of units it's currently contributing to.
		int provisionalTotal = PROGRESS_COMPILE_UNITS + skipFrames + numFrames +
				(createVideo ? numFrames : 0);
		overallBar.setMax(provisionalTotal);
		ValueAnimator[] compileAnim = {startCompileProgressAnimation(statusMessage, overallBar)};
		int[] tilesPerFrame = {-1};
		int[] currentFrameIndex = {0};
		String[] skipVariant = {null};
		String[] tileVariant = {null};
		String[] encodeVariant = {null};
		// Same "set once the capture thread knows" / throttle contract as
		// runHighResCapture()'s own copy of these two.
		boolean[] serviceActive = {false};
		int[] lastNotifiedPercent = {-1};
		// Marked by tileProgressListener at the first tile of whichever
		// frame is currently rendering, read back by frameListener once
		// that frame completes - the two together give one frame's
		// actual wall-clock render time, averaged across every frame
		// completed so far (frameAvgTotalSeconds / framesTimed) for the
		// Avg/ETA line, same idea as runHighResCapture()'s own
		// per-tile version but at frame granularity.
		long[] frameStartNanos = {0};
		double[] frameAvgTotalSeconds = {0};
		int[] framesTimed = {0};

		ShaderRenderer.SequenceProgressListener sequenceProgressListener = (skipping, stepIndex, totalSteps) -> {
			// Resolved before posting to the UI thread (not re-checked
			// inside the Runnable) so the preview-clear below never
			// fires for a frame that's actually about to be refused -
			// see the !skipping branch's own doc.
			boolean willContinue = !cancelRequested.get() && !interruptRequested.get();
			runOnUiThread(() -> {
				if (!skipping) {
					// Clears the whole-image preview right as the *next*
					// frame is about to start rendering, rather than the
					// instant the previous one finished (frameListener
					// used to do this) - so whatever frame just
					// completed stays visible as long as possible,
					// through the status/progress-bar churn between
					// frames, right up until there's actually something
					// new to start overwriting it with. Skipped if this
					// frame was about to be refused (Cancel/Interrupt),
					// so the last real frame stays on screen instead of
					// being wiped for a frame that will never render -
					// and skipped entirely for a single pass, which
					// never uses this overview in the first place (see
					// createTilePreviewListener()) and just has the next
					// frame's full image plainly replace the last one in
					// lastTileView, no separate clear needed or wanted.
					if (willContinue && !singlePass) {
						previewBitmap.eraseColor(0xff202020);
						tilePreviewView.invalidate();
					}
					return;
				}
				if (compileAnim[0] != null) {
					compileAnim[0].cancel();
					compileAnim[0] = null;
				}
				if (skipVariant[0] == null) {
					skipVariant[0] = pickRandomStatus(R.array.status_skip);
				}
				overallBar.setProgress(PROGRESS_COMPILE_UNITS + stepIndex);
				subtaskBar.setVisibility(View.VISIBLE);
				subtaskBar.setMax(totalSteps);
				subtaskBar.setProgress(stepIndex);
				statusMessage.setText(
						String.format(Locale.getDefault(), skipVariant[0], stepIndex, totalSteps));
				notifyCaptureProgress(serviceActive, lastNotifiedPercent, overallBar);
			});
			return willContinue;
		};
		ShaderRenderer.HighResProgressListener tileProgressListener = (tilesDone, totalTiles) -> {
			runOnUiThread(() -> {
				if (compileAnim[0] != null) {
					compileAnim[0].cancel();
					compileAnim[0] = null;
				}
				if (tilesPerFrame[0] < 0) {
					tilesPerFrame[0] = totalTiles;
					overallBar.setMax(PROGRESS_COMPILE_UNITS + skipFrames +
							numFrames * tilesPerFrame[0] + (createVideo ? numFrames : 0));
					tileVariant[0] = pickRandomStatus(
							numFrames > 1 ? R.array.status_tile_sequence : R.array.status_tile_single);
				}
				if (tilesDone == 1) {
					frameStartNanos[0] = System.nanoTime();
				}
				overallBar.setProgress(PROGRESS_COMPILE_UNITS + skipFrames +
						currentFrameIndex[0] * tilesPerFrame[0] + tilesDone);
				subtaskBar.setVisibility(View.VISIBLE);
				subtaskBar.setMax(totalTiles);
				subtaskBar.setProgress(tilesDone);
				// Interrupt lets this (the currently-tiling) frame finish
				// rather than abandoning it - see tileProgressListener's
				// return value below - so the narrated status is
				// overridden here to say so, rather than continuing to
				// cycle through the normal randomized tile-progress text
				// as if nothing had changed.
				statusMessage.setText(interruptRequested.get()
						? getString(R.string.status_interrupting_after_frame)
						: numFrames > 1
								? String.format(Locale.getDefault(), tileVariant[0],
										currentFrameIndex[0] + 1, numFrames, tilesDone, totalTiles)
								: String.format(Locale.getDefault(), tileVariant[0], tilesDone, totalTiles));
				notifyCaptureProgress(serviceActive, lastNotifiedPercent, overallBar);
			});
			// Deliberately cancelRequested-only, unlike
			// sequenceProgressListener above: Interrupt means "stop
			// starting new frames", not "abandon the one currently
			// tiling" - letting it finish means the video gets one more
			// frame instead of discarding whatever fraction of it had
			// already rendered. sequenceProgressListener (the only place
			// a *new* frame or skip-step gets started) is where
			// interruptRequested actually takes effect.
			return !cancelRequested.get();
		};

		// Resolved just before capture starts (see the background Thread
		// below) - held in a 1-element array only so frameListener
		// (defined before that resolution happens) can see the final
		// value once it does.
		String[] frameSubAlbum = {baseShaderName};
		List<Uri> frameUris = new ArrayList<>();
		ShaderRenderer.FrameListener frameListener = (frameIndex, totalFrames, png) -> {
			String filename = String.format(Locale.US, "%s.%06d.png", baseShaderName, frameIndex + 1);
			ScreenshotSaver.SavedScreenshot saved = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
					? ScreenshotSaver.save(this, png, filename, frameSubAlbum[0])
					: null;
			if (saved != null) {
				frameUris.add(saved.uri());
			}
			currentFrameIndex[0] = frameIndex + 1;
			double frameSeconds = (System.nanoTime() - frameStartNanos[0]) / 1e9;
			frameAvgTotalSeconds[0] += frameSeconds;
			++framesTimed[0];
			int remainingFrames = totalFrames - currentFrameIndex[0];
			// How much virtual-time footage has actually been rendered
			// so far, at the sequence's own virtualFps - independent of
			// how long rendering it took in the real world (that's what
			// the Avg/ETA line below is for).
			double footageSeconds = virtualFps > 0 ? currentFrameIndex[0] / virtualFps : 0;
			runOnUiThread(() -> {
				if (framesTimed[0] > 0) {
					double avgSeconds = frameAvgTotalSeconds[0] / framesTimed[0];
					// Projects all the way to the end of the whole job,
					// not just the rendering - if a video's being made,
					// adds an estimate for encoding every frame this
					// sequence will eventually have (using whatever's
					// actually been measured from a past encode this
					// process, or a rough guess if nothing has yet).
					double encodeEtaSeconds = createVideo
							? totalFrames * (lastKnownEncodeSecondsPerFrame >= 0
									? lastKnownEncodeSecondsPerFrame
									: ESTIMATED_ENCODE_SECONDS_PER_FRAME_FALLBACK)
							: 0;
					long etaSeconds = Math.round(avgSeconds * remainingFrames + encodeEtaSeconds);
					timingView.setVisibility(View.VISIBLE);
					timingView.setText(getString(R.string.progress_timing_frame, avgSeconds,
							formatEtaDuration(etaSeconds))
							+ "\n" + getString(R.string.progress_footage_length, footageSeconds));
				}
			});
		};

		new Thread(() -> {
			Process.setThreadPriority(ShaderEditorApp.preferences.getCaptureThreadPriority());
			// Recorded so the capture notification's priority buttons
			// (see HighResCaptureService) can re-prioritize *this*
			// thread live, not just change what the next capture starts
			// at - cleared in the finally-equivalent cleanup below
			// regardless of how this thread finishes, since a tid can
			// be reused for an unrelated thread once this one exits.
			HighResCaptureService.setCaptureThreadId(Process.myTid());
			// Resolved once, before capture starts, so every frame in
			// this run lands in the same (possibly-suffixed) folder.
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				frameSubAlbum[0] = ScreenshotSaver.findAvailableSubAlbum(this, baseShaderName);
			}
			// Same SharedCaptureContext approach as runHighResCapture()
			// above, but held for the whole sequence (every frame, not
			// just one capture) - see
			// ShaderRenderer.captureHighResScreenshotSequenceOnSharedContext()
			// and HighResCaptureService. A sequence is exactly the case
			// most likely to run long enough for someone to background
			// the app partway through, so this is the capture this
			// feature matters most for.
			SharedCaptureContext sharedContext = shaderViewManager.createSharedCaptureContext();
			boolean backgroundCapable = sharedContext != null;
			if (backgroundCapable) {
				shaderViewManager.setBackgroundCaptureActive(true);
				HighResCaptureService.start(this);
				serviceActive[0] = true;
			}
			if (backgroundCapable) {
				shaderViewManager.takeHighResScreenshotSequenceOnSharedContext(sharedContext,
						width, height, oversample, preserveBackbufferEdges, skipFrames, numFrames,
						virtualFps, bucketSize, tileOrderSupplier, tileProgressListener,
						tilePreviewListener, sequenceProgressListener, frameListener);
				// Done with the shared context itself (every frame's tiled
				// render is now complete) - but the foreground service
				// stays up a bit longer, through any video encoding and
				// cleanup below, since those can also take a while and
				// deserve the same process-survival protection.
				sharedContext.destroy();
			} else {
				shaderViewManager.takeHighResScreenshotSequence(
						width, height, oversample, preserveBackbufferEdges, skipFrames, numFrames,
						virtualFps, bucketSize, tileOrderSupplier, tileProgressListener,
						tilePreviewListener, sequenceProgressListener, frameListener);
			}

			boolean cancelled = cancelRequested.get();
			boolean interrupted = interruptRequested.get();
			Uri videoUri = null;
			boolean cleanedUp = false;
			// Cancel never attempts a video - see cancelButton's own doc
			// above - but Interrupt deliberately does, from whatever
			// frames got captured before it was pressed (frameUris.size(),
			// not the originally-requested numFrames, which an interrupted
			// run never reaches).
			if (!cancelled && createVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
					&& !frameUris.isEmpty()) {
				int actualFrameCount = frameUris.size();
				int tilesPerFrameFinal = Math.max(0, tilesPerFrame[0]);
				int encodeBaseUnits =
						PROGRESS_COMPILE_UNITS + skipFrames + actualFrameCount * tilesPerFrameFinal;
				runOnUiThread(() -> {
					overallBar.setMax(encodeBaseUnits + actualFrameCount);
					overallBar.setProgress(encodeBaseUnits);
					statusMessage.setText(interrupted
							? getString(R.string.status_interrupted_making_video, actualFrameCount)
							: getString(R.string.status_wrapping_up));
				});
				// Rendering's done by now, so the timing line's ETA only
				// has encoding left to account for - real per-frame
				// timing from here on (not the render-phase estimate's
				// guess), which also becomes lastKnownEncodeSecondsPerFrame
				// for the *next* capture's render-phase estimate.
				long encodeStartNanos = System.nanoTime();
				VideoEncoder.ProgressListener videoProgress = (frameIndex, totalFrames) -> {
					runOnUiThread(() -> {
						if (encodeVariant[0] == null) {
							encodeVariant[0] = pickRandomStatus(R.array.status_encoding);
						}
						overallBar.setProgress(encodeBaseUnits + frameIndex);
						subtaskBar.setVisibility(View.VISIBLE);
						subtaskBar.setMax(totalFrames);
						subtaskBar.setProgress(frameIndex);
						statusMessage.setText(String.format(
								Locale.getDefault(), encodeVariant[0], frameIndex, totalFrames));
						double avgEncodeSeconds = (System.nanoTime() - encodeStartNanos) / 1e9 / frameIndex;
						lastKnownEncodeSecondsPerFrame = avgEncodeSeconds;
						timingView.setVisibility(View.VISIBLE);
						timingView.setText(getString(R.string.progress_timing_frame, avgEncodeSeconds,
								formatEtaDuration(Math.round(avgEncodeSeconds * (totalFrames - frameIndex)))));
						notifyCaptureProgress(serviceActive, lastNotifiedPercent, overallBar);
					});
					return !cancelRequested.get();
				};
				String videoFilename = baseShaderName + "." + proseDateString(new Date()) + ".mp4";
				videoUri = VideoEncoder.encode(this, frameUris, width, height, videoScale, virtualFps,
						videoQuality, videoFilename, videoProgress);
				if (videoUri != null && cleanupImages) {
					runOnUiThread(() -> statusMessage.setText(R.string.status_wrapping_up));
					ScreenshotSaver.deleteSubAlbum(this, frameSubAlbum[0]);
					cleanedUp = true;
				}
			}

			if (cancelled && deletePartialOnCancel.get() && !frameUris.isEmpty()) {
				runOnUiThread(() -> statusMessage.setText(R.string.status_wrapping_up));
				ScreenshotSaver.deleteSubAlbum(this, frameSubAlbum[0]);
			}

			if (backgroundCapable) {
				HighResCaptureService.stop(this);
			}
			HighResCaptureService.clearCaptureThreadId();

			int savedCount = frameUris.size();
			Uri finalVideoUri = videoUri;
			boolean finalCleanedUp = cleanedUp;
			runOnUiThread(() -> {
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				progressDialog.dismiss();
				if (backgroundCapable) {
					shaderViewManager.setBackgroundCaptureActive(false);
				}
				if (cancelled) {
					showCaptureResult(deletePartialOnCancel.get()
							? getString(R.string.higher_res_sequence_cancelled_deleted)
							: getString(R.string.higher_res_sequence_cancelled_kept, savedCount), null);
				} else if (savedCount == 0) {
					showCaptureResult(getString(R.string.screenshot_save_failed), null);
				} else if (createVideo && finalVideoUri == null) {
					showCaptureResult(getString(R.string.higher_res_sequence_saved_video_failed,
							savedCount, frameSubAlbum[0]), null);
				} else if (finalVideoUri != null) {
					showCaptureResult(finalCleanedUp
							? getString(R.string.higher_res_sequence_video_saved_cleaned, savedCount)
							: getString(R.string.higher_res_sequence_video_saved, savedCount),
							finalVideoUri);
				} else {
					showCaptureResult(getString(R.string.higher_res_sequence_saved,
							savedCount, frameSubAlbum[0]), null);
				}
				setShaderPaused(wasPaused);
			});
		}, "HighResFrameSequenceCapture").start();
	}

	// NEW_TASK (+ MULTIPLE_TASK so it doesn't just fold into some other
	// already-running task of the viewer app) - the viewer then gets its
	// own entry in the recent-apps switcher, so backing out of it to
	// return here means alt-tab/recents, not this Activity's own back
	// stack.
	private void openMedia(@NonNull Uri uri, @NonNull String mimeType, int noAppMessageRes,
			boolean loopHint) {
		Intent viewIntent = new Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, mimeType)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		if (loopHint) {
			// Best-effort only, not a real contract: Android has no
			// standard ACTION_VIEW extra for "loop this" the way it does
			// for e.g. EXTRA_STREAM, and there's no guarantee the
			// installed video player recognizes this one - it's a guess
			// at the single most commonly used spelling, unverified
			// against any specific app's source/docs. Harmless if
			// ignored either way.
			viewIntent.putExtra("loop", true);
		}
		try {
			startActivity(viewIntent);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, noAppMessageRes, Toast.LENGTH_SHORT).show();
		}
	}

	private void openVideo(@NonNull Uri uri) {
		openMedia(uri, "video/mp4", R.string.no_app_to_open_video, true);
	}

	// Shows a capture's result toast (and opens the video, if videoUri is
	// non-null) only if this Activity is actually in the foreground right
	// now - otherwise stashes both for onResume() to replay. Needed
	// because a HighResCaptureService capture (see runFrameSequenceCapture())
	// can finish well after the user's backgrounded the app: a Toast
	// posted from a backgrounded/cached process can be silently dropped
	// (confirmed via logcat: "Toast already killed"), and starting the
	// video viewer Activity from one is flatly blocked as a background
	// activity launch - neither ever reaches the user if attempted right
	// away, and unlike a notification, nothing then prompts them to go
	// looking for it.
	private void showCaptureResult(@NonNull CharSequence message, @Nullable Uri videoUri) {
		if (activityResumed) {
			Toast.makeText(this, message, Toast.LENGTH_LONG).show();
			if (videoUri != null) {
				openVideo(videoUri);
			}
			return;
		}
		pendingCaptureToast = message;
		pendingCaptureVideoUri = videoUri;
	}

	private enum ScreenshotAction {COPY, SAVE, SAVE_AND_OPEN}

	private void showScreenshotActionsDialog(@NonNull byte[] png, boolean wasPaused) {
		View view = getLayoutInflater().inflate(R.layout.dialog_screenshot_actions, null);
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.save_screenshot)
				.setView(view)
				.setOnDismissListener(d -> setShaderPaused(wasPaused))
				.show();
		Button copyButton = view.findViewById(R.id.screenshot_copy);
		Button saveButton = view.findViewById(R.id.screenshot_save);
		Button saveAndOpenButton = view.findViewById(R.id.screenshot_save_and_open);
		Button higherResButton = view.findViewById(R.id.screenshot_higher_res);
		highlightLastUsedButton(PREF_LAST_SCREENSHOT_ACTION,
				copyButton, saveButton, saveAndOpenButton, higherResButton);
		copyButton.setOnClickListener(v -> {
			rememberLastUsedButton(PREF_LAST_SCREENSHOT_ACTION, R.id.screenshot_copy);
			dialog.dismiss();
			handleScreenshotAction(png, ScreenshotAction.COPY);
		});
		saveButton.setOnClickListener(v -> {
			rememberLastUsedButton(PREF_LAST_SCREENSHOT_ACTION, R.id.screenshot_save);
			dialog.dismiss();
			handleScreenshotAction(png, ScreenshotAction.SAVE);
		});
		saveAndOpenButton.setOnClickListener(v -> {
			rememberLastUsedButton(PREF_LAST_SCREENSHOT_ACTION, R.id.screenshot_save_and_open);
			dialog.dismiss();
			handleScreenshotAction(png, ScreenshotAction.SAVE_AND_OPEN);
		});
		higherResButton.setOnClickListener(v -> {
			rememberLastUsedButton(PREF_LAST_SCREENSHOT_ACTION, R.id.screenshot_higher_res);
			// Listener cleared first - the dismiss below would otherwise
			// restore pause state to whatever it was before this whole
			// flow started, which (if the shader wasn't already paused)
			// would briefly un-pause it right as the resolution dialog
			// comes up. runHighResCapture()/runFrameSequenceCapture() are
			// the only things that should ever restore it now, once the
			// capture+save is fully done - see showCustomResolutionDialog().
			dialog.setOnDismissListener(null);
			dialog.dismiss();
			showCustomResolutionDialog(wasPaused);
		});
		view.findViewById(R.id.screenshot_cancel).setOnClickListener(v -> dialog.dismiss());
	}

	// Every action saves (even "Copy to Clipboard" - the clipboard needs
	// a content:// URI some other app's process can still read from
	// later, and a MediaStore-backed one already is exactly that,
	// without needing a separate FileProvider/cache-file setup just for
	// the copy-only case).
	private void handleScreenshotAction(@NonNull byte[] png, @NonNull ScreenshotAction action) {
		handleScreenshotAction(png, action, () -> {
		});
	}

	// onComplete runs on the UI thread once the save (and whatever the
	// action does with it after - clipboard/open) has finished, success
	// or failure - see runHighResCapture(), which uses this to keep the
	// shader paused for the whole save instead of restoring playback the
	// instant the pixels are captured.
	private void handleScreenshotAction(
			@NonNull byte[] png, @NonNull ScreenshotAction action, @NonNull Runnable onComplete) {
		new Thread(() -> {
			ScreenshotSaver.SavedScreenshot saved =
					Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
							? ScreenshotSaver.save(this, png)
							: null;
			runOnUiThread(() -> {
				if (saved == null) {
					Toast.makeText(this, R.string.screenshot_save_failed, Toast.LENGTH_SHORT).show();
					onComplete.run();
					return;
				}
				switch (action) {
					case COPY -> {
						var clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
						clipboard.setPrimaryClip(ClipData.newUri(
								getContentResolver(), getString(R.string.save_screenshot), saved.uri()));
						Toast.makeText(this, R.string.screenshot_copied, Toast.LENGTH_SHORT).show();
					}
					case SAVE -> Toast.makeText(this,
							getString(R.string.screenshot_saved, saved.filename()),
							Toast.LENGTH_LONG).show();
					case SAVE_AND_OPEN -> {
						Toast.makeText(this,
								getString(R.string.screenshot_saved, saved.filename()),
								Toast.LENGTH_SHORT).show();
						openMedia(saved.uri(), "image/png", R.string.no_app_to_open_image, false);
					}
				}
				onComplete.run();
			});
		}, "ScreenshotSave").start();
	}

	private void randomShader() {
		var shader = shaderListManager.getRandomShader(shaderManager.getSelectedShaderId());
		if (shader != null) {
			selectShader(shader.id());
			uiManager.showShaderNameOverlay(shader.getTitle());
			shaderListManager.revealShader(shader.id());
		}
	}

	// Lets the user pick either an existing (named) shader, or any
	// intermediate group node of its name tree, as the prefix to rename
	// the current shader under. Unnamed (timestamp-titled) shaders are
	// excluded since they have nothing meaningful to group under.
	private void showPickParentShaderDialog(long targetId, boolean confirmIfAlreadyNamed) {
		List<DataRecords.ShaderInfo> namedShaders = new ArrayList<>();
		for (var shader : shaderListManager.getAllShaders()) {
			if (shader.name() != null && !shader.name().isEmpty()) {
				namedShaders.add(shader);
			}
		}

		View view = getLayoutInflater().inflate(R.layout.dialog_pick_parent_shader, null);
		EditText filterView = view.findViewById(R.id.filter);
		CheckBox sortBySimilarityBox = view.findViewById(R.id.sort_by_similarity);
		ListView parentListView = view.findViewById(R.id.parent_list);
		ShaderTreeAdapter pickerAdapter = new ShaderTreeAdapter(this);
		pickerAdapter.setData(namedShaders);
		parentListView.setAdapter(pickerAdapter);

		// Ranks branches by the best code/name similarity match they
		// contain against the shader being reparented, so the most
		// relevant groups surface at the top instead of needing to be
		// found alphabetically.
		sortBySimilarityBox.setOnCheckedChangeListener((box, checked) -> {
			if (checked) {
				ShaderSimilarity similarity = new ShaderSimilarity();
				similarity.rebuild(shaderListManager.getAllShaders(),
						dataSource.shader.getAllShaderSources());
				pickerAdapter.setSimilarityScores(similarity.scoreAll(targetId, namedShaders));
			} else {
				pickerAdapter.setSimilarityScores(null);
			}
		});

		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.add_shader_to_name_group)
				.setView(view)
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> SoftKeyboard.hide(this, filterView))
				.show();

		filterView.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				String query = s.toString().trim().toLowerCase(Locale.ROOT);
				if (query.isEmpty()) {
					pickerAdapter.setData(namedShaders);
					return;
				}
				List<DataRecords.ShaderInfo> filtered = new ArrayList<>();
				for (var shader : namedShaders) {
					String title = shader.getTitle();
					if (title != null && title.toLowerCase(Locale.ROOT).contains(query)) {
						filtered.add(shader);
					}
				}
				pickerAdapter.setData(filtered);
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});

		parentListView.setOnItemClickListener((parent, v, position, id) -> {
			// The row's path is its tokens joined with "/"; since tokens
			// are a zero-width split of the original title, joining them
			// back together with no separator reconstructs the exact
			// name prefix this node stands for.
			String parentName = pickerAdapter.getRow(position).path().replace("/", "");
			dialog.dismiss();
			showNameGroupPostfixDialog(targetId, parentName, confirmIfAlreadyNamed);
		});
	}

	private void showNameGroupPostfixDialog(
			long targetId, @NonNull String parentName, boolean confirmIfAlreadyNamed) {
		View view = getLayoutInflater().inflate(R.layout.dialog_name_group_postfix, null);
		TextView prefixLabel = view.findViewById(R.id.prefix_label);
		EditText postfixView = view.findViewById(R.id.postfix);
		prefixLabel.setText(getString(R.string.name_group_prefix_label, parentName));

		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.add_shader_to_name_group)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					String postfix = postfixView.getText().toString().trim();
					if (!postfix.isEmpty()) {
						shaderListManager.checkNamePrefixAndThen(postfix, fixedPostfix ->
								applyNameGroupRename(
										targetId, parentName, fixedPostfix, confirmIfAlreadyNamed));
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> SoftKeyboard.hide(this, postfixView))
				.show();
	}

	private void applyNameGroupRename(long targetId, @NonNull String parentName,
			@NonNull String postfix, boolean confirmIfAlreadyNamed) {
		String newName = parentName + postfix;
		if (targetId < 1) {
			return;
		}
		String currentName = null;
		for (var shader : shaderListManager.getAllShaders()) {
			if (shader.id() == targetId) {
				currentName = shader.name();
				break;
			}
		}
		if (confirmIfAlreadyNamed && currentName != null && !currentName.isEmpty()) {
			new MaterialAlertDialogBuilder(this)
					.setMessage(getString(R.string.sure_reparent_named_shader, currentName, newName))
					.setPositiveButton(android.R.string.ok,
							(dialog, which) -> checkConflictAndRename(targetId, newName))
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		} else {
			checkConflictAndRename(targetId, newName);
		}
	}

	private void checkConflictAndRename(long targetId, @NonNull String newName) {
		boolean conflict = false;
		for (var shader : shaderListManager.getAllShaders()) {
			if (shader.id() != targetId && newName.equalsIgnoreCase(shader.getTitle())) {
				conflict = true;
				break;
			}
		}
		if (conflict) {
			new MaterialAlertDialogBuilder(this)
					.setMessage(getString(R.string.name_already_exists, newName))
					.setPositiveButton(android.R.string.ok,
							(dialog, which) -> doNameGroupRename(targetId, newName))
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		} else {
			doNameGroupRename(targetId, newName);
		}
	}

	private void doNameGroupRename(long id, @NonNull String newName) {
		shaderListManager.renameShader(id, newName);
		Toast.makeText(this, getString(R.string.renamed_shader_to, newName), Toast.LENGTH_SHORT)
				.show();
	}

	// Lists the most recent frame-sequence videos VideoEncoder has saved
	// (same MediaStore album ScreenshotSaver uses for screenshots, but
	// filtered to video/mp4 - see VideoEncoder's own ALBUM constant,
	// duplicated here as a literal since neither class exposes it
	// publicly and there's nothing else in this app that would need to).
	// Requires Q, same as video creation itself (MediaStore's
	// RELATIVE_PATH column, and the only reason any such video could
	// exist in the first place) - older devices just see "no
	// recordings", never a crash from querying an unsupported column.
	private static final int RECENT_RECORDINGS_LIMIT = 20;

	private void showRecentRecordingsDialog() {
		List<RecentRecording> recordings = new ArrayList<>();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			String relativePath = Environment.DIRECTORY_PICTURES + "/Enki Shader Editor/";
			String[] projection = {
					MediaStore.Video.Media._ID,
					MediaStore.Video.Media.DISPLAY_NAME,
					MediaStore.Video.Media.DATE_ADDED,
			};
			String selection = MediaStore.Video.Media.RELATIVE_PATH + " = ?";
			String[] selectionArgs = {relativePath};
			String sortOrder = MediaStore.Video.Media.DATE_ADDED + " DESC";
			try (Cursor cursor = getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
					projection, selection, selectionArgs, sortOrder)) {
				if (cursor != null) {
					int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
					int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
					int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED);
					while (cursor.moveToNext() && recordings.size() < RECENT_RECORDINGS_LIMIT) {
						long id = cursor.getLong(idColumn);
						Uri uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
						recordings.add(RecentRecording.fromDisplayName(
								uri, cursor.getString(nameColumn), cursor.getLong(dateColumn)));
					}
				}
			}
		}
		if (recordings.isEmpty()) {
			Toast.makeText(this, R.string.no_recent_recordings, Toast.LENGTH_SHORT).show();
			return;
		}
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.play_recent_recordings)
				.setAdapter(new RecordingAdapter(recordings),
						(d, which) -> openVideo(recordings.get(which).uri()))
				.setPositiveButton(R.string.play_all_recordings,
						(d, w) -> playAllRecordings(recordings))
				.show();
	}

	// Best-effort only, same spirit as openMedia()'s loop hint: there's
	// no standard Android contract for "play these as a queue" the way
	// there is for viewing one file, so this shares the list via
	// ACTION_SEND_MULTIPLE (which at least VLC is known to open as a
	// playlist) and lets the user pick a target from a chooser, rather
	// than assuming any specific app will do the right thing with it.
	// Shared oldest-first (reversed from the dialog's own newest-first
	// listing) so "play all" plays back in the order the recordings were
	// actually made.
	private void playAllRecordings(@NonNull List<RecentRecording> recordings) {
		ArrayList<Uri> uris = new ArrayList<>();
		for (int i = recordings.size() - 1; i >= 0; --i) {
			uris.add(recordings.get(i).uri());
		}
		Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE)
				.setType("video/mp4")
				.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		try {
			startActivity(Intent.createChooser(intent, getString(R.string.play_all_recordings)));
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_app_to_open_video, Toast.LENGTH_SHORT).show();
		}
	}

	private void showShaderStatsDialog() {
		String text = editorFragment.getText();
		int lineCount = text.isEmpty() ? 1 : text.split("\n", -1).length;
		int charCount = text.length();
		int byteCount = text.getBytes(StandardCharsets.UTF_8).length;

		StringBuilder message = new StringBuilder(
				getString(R.string.stats_lines_chars, lineCount, charCount));
		message.append('\n').append(getString(R.string.stats_size_bytes, byteCount));

		long binaryBytes = shaderViewManager.getLastProgramBinaryBytes();
		message.append('\n').append(binaryBytes >= 0
				? getString(R.string.stats_compiled_size_bytes, binaryBytes)
				: getString(R.string.stats_compiled_size_unavailable));
		message.append('\n').append(getString(
				R.string.stats_define_count, findDefineLines(text).size()));
		message.append('\n').append(getString(
				R.string.stats_custom_textures, countCustomTextures(text)));

		String usedUniforms = findUsedBuiltinUniforms(text);
		message.append('\n').append(usedUniforms.isEmpty()
				? getString(R.string.stats_no_builtin_uniforms)
				: getString(R.string.stats_builtin_uniforms, usedUniforms));

		message.append('\n').append(getString(
				R.string.stats_quality, Math.round(shaderManager.getQuality() * 100)));

		long compileNanos = shaderViewManager.getLastCompileNanos();
		message.append('\n');
		if (compileNanos >= 0) {
			double compileMillis = compileNanos / 1_000_000.0;
			message.append(getString(
					shaderViewManager.wasLastCompileSuccessful()
							? R.string.stats_compile_ms_ok
							: R.string.stats_compile_ms_failed,
					compileMillis));
		} else {
			message.append(getString(R.string.stats_compile_unknown));
		}

		int fps = shaderViewManager.getLastFps();
		message.append('\n').append(fps > 0
				? getString(R.string.stats_fps, fps)
				: getString(R.string.stats_fps_unknown));

		long selectedShaderId = shaderManager.getSelectedShaderId();
		if (selectedShaderId > 0) {
			message.append('\n').append(getString(R.string.stats_shader_id, selectedShaderId));
			DataRecords.Shader shader = dataSource.shader.getShader(selectedShaderId);
			if (shader != null && shader.sourceModified() != null) {
				message.append('\n').append(
						getString(R.string.stats_last_modified, shader.sourceModified()));
			}
			if (shader != null && shader.compileTimeMs() != null) {
				message.append('\n').append(getString(R.string.stats_stored_compile_time,
						shader.compileTimeMs() / 1000.0));
			}
		} else {
			message.append('\n').append(getString(R.string.stats_not_saved));
		}

		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.shader_stats)
				.setMessage(message.toString())
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	// AlertDialog's own message TextView already scrolls on its own once
	// its content overflows the dialog's height, so a plain joined
	// string is enough here - no custom ScrollView/list layout needed
	// for this static, short reference list. See R.array.hotkeys_list
	// (and keep it in sync with dispatchKeyEvent() below if either
	// changes).
	private void showHotkeysDialog() {
		String[] hotkeys = getResources().getStringArray(R.array.hotkeys_list);
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.hotkeys)
				.setMessage(String.join("\n\n", hotkeys))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	// Counts uniform sampler2D/samplerCube declarations that aren't one
	// of the builtin sampler uniforms (audioSpectrum, backbuffer,
	// cameraFront, cameraBack) - i.e. textures the shader binds itself.
	private static int countCustomTextures(@NonNull String text) {
		Pattern pattern = Pattern.compile("\\buniform\\s+(?:sampler2D|samplerCube)\\s+(\\w+)");
		var matcher = pattern.matcher(text);
		int count = 0;
		while (matcher.find()) {
			if (!BUILTIN_SAMPLER_NAMES.contains(matcher.group(1))) {
				++count;
			}
		}
		return count;
	}

	// Scans for `uniform <type> <name>` declarations matching any of the
	// known builtin uniform names, in BUILTIN_UNIFORM_NAMES's order, and
	// returns them as a comma-separated list for the stats dialog.
	@NonNull
	private static String findUsedBuiltinUniforms(@NonNull String text) {
		StringBuilder result = new StringBuilder();
		for (String name : BUILTIN_UNIFORM_NAMES) {
			Pattern pattern = Pattern.compile("\\buniform\\s+\\w+\\s+" + Pattern.quote(name) + "\\b");
			if (pattern.matcher(text).find()) {
				if (!result.isEmpty()) {
					result.append(", ");
				}
				result.append(name);
			}
		}
		return result.toString();
	}

	private void showSimilarShadersConfigDialog() {
		View view = getLayoutInflater().inflate(R.layout.dialog_similar_shaders_config, null);
		TextView thresholdLabel = view.findViewById(R.id.threshold_label);
		SeekBar thresholdSeekBar = view.findViewById(R.id.threshold_seek_bar);
		CheckBox includeSourceBox = view.findViewById(R.id.include_source);

		int initialPercent = (int) Math.round(ShaderSimilarity.DEFAULT_THRESHOLD * 100);
		thresholdSeekBar.setProgress(initialPercent);
		thresholdLabel.setText(getString(R.string.similar_shaders_threshold_label, initialPercent));
		includeSourceBox.setChecked(true);
		thresholdSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				thresholdLabel.setText(
						getString(R.string.similar_shaders_threshold_label, progress));
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});

		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.show_similar_shaders)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					double threshold = thresholdSeekBar.getProgress() / 100.0;
					shaderListManager.enableSimilarFilter(threshold, includeSourceBox.isChecked());
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static final class ShaderSearchState {
		String query = "";
		boolean searchSource = false;
		boolean fuzzy = false;
		List<DataRecords.ShaderInfo> matches = Collections.emptyList();
		int index = -1;
		String key;
	}

	// Same shape as ShaderSearchState, but matches are offsets within
	// the currently-edited shader's source rather than DB rows.
	private static final class EditorSearchState {
		String query = "";
		List<Integer> matches = Collections.emptyList();
		int index = -1;
		String key;
	}

	private void showFindShaderDialog() {
		clearFindMatchRing();
		View view = getLayoutInflater().inflate(R.layout.dialog_find_shader, null);
		EditText queryView = view.findViewById(R.id.query);
		CheckBox searchSourceBox = view.findViewById(R.id.search_source);
		CheckBox fuzzyBox = view.findViewById(R.id.fuzzy_matching);
		TextView matchCount = view.findViewById(R.id.match_count);
		View resultRow = view.findViewById(R.id.result_row);
		ImageView resultIcon = view.findViewById(R.id.result_icon);
		TextView resultTitle = view.findViewById(R.id.result_title);
		TextView resultPosition = view.findViewById(R.id.result_position);
		Button resultLoad = view.findViewById(R.id.result_load);
		View navRow = view.findViewById(R.id.find_nav_row);
		View previousButton = view.findViewById(R.id.find_previous);
		View randomButton = view.findViewById(R.id.find_random);
		View nextButton = view.findViewById(R.id.find_next_match);
		ShaderSearchState state = findShaderState;
		Random random = new Random();

		// Picks up the query/options from the last time this dialog was
		// open, since otherwise every search session has to be re-typed
		// from scratch even when just continuing to browse the same
		// results.
		queryView.setText(state.query);
		searchSourceBox.setChecked(state.searchSource);
		fuzzyBox.setChecked(state.fuzzy);
		// Pre-selected rather than just cursor-at-end, so typing a new
		// search immediately replaces the old one instead of appending.
		queryView.selectAll();
		// Visible from the start (not just after a first successful
		// search) so there's always an obvious, tappable way to
		// actually run the search.
		navRow.setVisibility(View.VISIBLE);

		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.find_shader)
				.setView(view)
				.setNegativeButton(R.string.dismiss, null)
				.setOnDismissListener(d -> {
					SoftKeyboard.hide(this, queryView);
					state.query = queryView.getText().toString();
					state.searchSource = searchSourceBox.isChecked();
					state.fuzzy = fuzzyBox.isChecked();
				})
				.show();
		// So typing can start immediately, without an extra tap into the
		// field first - selectAll() above only pre-selects its text, it
		// doesn't focus it or raise the keyboard on its own. Declaring
		// this on the dialog's own window (rather than a direct
		// SoftKeyboard.show() call right here) lets the window manager
		// raise the keyboard once the window actually finishes gaining
		// focus, instead of racing it - calling showSoftInput()
		// immediately after show() is a well-known no-op more often
		// than not, since the dialog's window hasn't necessarily
		// finished attaching yet at that exact point.
		queryView.requestFocus();
		if (dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		}

		// Just the dialog's own inline preview (icon/name/position, and
		// what Load/Ctrl+Enter actually loads) - no drawer side effect,
		// so it's cheap enough to call on every keystroke (see
		// updateLiveCount below), not just on an explicit next/
		// previous/Enter. showCurrentMatch below adds the drawer reveal
		// on top, for those explicit navigation actions.
		Runnable showMatchPreview = () -> {
			DataRecords.ShaderInfo match = state.matches.get(state.index);
			matchCount.setVisibility(View.GONE);
			resultRow.setVisibility(View.VISIBLE);
			resultTitle.setText(match.getTitle());
			resultPosition.setText(getString(R.string.match_position_format,
					state.index + 1, state.matches.size()));
			byte[] thumb = match.thumb();
			if (thumb != null && thumb.length > 0) {
				resultIcon.setImageBitmap(BitmapFactory.decodeByteArray(thumb, 0, thumb.length));
			} else {
				resultIcon.setImageResource(R.drawable.thumbnail_new_shader);
			}
			resultLoad.setOnClickListener(v -> {
				selectShader(match.id());
				dialog.dismiss();
			});
		};
		Runnable showCurrentMatch = () -> {
			showMatchPreview.run();
			DataRecords.ShaderInfo match = state.matches.get(state.index);
			// Opened (not just scrolled) so the reveal+highlight below is
			// actually visible - otherwise it happens behind a closed
			// drawer the user never sees move. Deferred until the drawer
			// has actually finished opening (not just requested to) -
			// scrolling the list to a row while the drawer's open
			// animation is still mid-flight races its own layout pass
			// and silently has no visible effect.
			uiManager.openDrawer(() -> shaderListManager.revealAndHighlightShader(match.id()));
		};
		// Re-runs the search if the query/options changed since the last
		// match was shown. Returns false (and hides the result UI) when
		// there's nothing to show, so callers can bail out early.
		java.util.function.BooleanSupplier ensureMatches = () -> {
			String query = queryView.getText().toString();
			if (query.isEmpty()) {
				return false;
			}
			boolean searchSource = searchSourceBox.isChecked();
			boolean fuzzy = fuzzyBox.isChecked();
			String key = query + ' ' + searchSource + ' ' + fuzzy;
			if (!key.equals(state.key)) {
				state.matches = shaderListManager.findMatches(query, searchSource, fuzzy);
				state.index = -1;
				state.key = key;
			}
			if (state.matches.isEmpty()) {
				resultRow.setVisibility(View.GONE);
				Toast.makeText(this, R.string.no_matches_found, Toast.LENGTH_SHORT).show();
				return false;
			}
			return true;
		};
		// Updated as the query/options change, even before the user
		// explicitly searches - shows the actual first match's preview
		// (icon/name/position, same as showMatchPreview above) the
		// instant there's anything to show, not just a bare count, so
		// there's always something concrete visible for Load/Ctrl+Enter
		// to act on. Only falls back to a plain count when there's
		// nothing matched yet (nothing typed) or nothing matched at all.
		// Resets to the first match (index 0) whenever the query/options
		// actually change underneath an already-shown one, since its
		// position within the new result set is no longer meaningful.
		Runnable updateLiveCount = () -> {
			String query = queryView.getText().toString();
			if (query.isEmpty()) {
				state.matches = Collections.emptyList();
				state.index = -1;
				state.key = null;
				matchCount.setVisibility(View.GONE);
				resultRow.setVisibility(View.GONE);
				return;
			}
			boolean searchSource = searchSourceBox.isChecked();
			boolean fuzzy = fuzzyBox.isChecked();
			String key = query + ' ' + searchSource + ' ' + fuzzy;
			if (!key.equals(state.key)) {
				state.matches = shaderListManager.findMatches(query, searchSource, fuzzy);
				state.index = 0;
				state.key = key;
			}
			if (state.matches.isEmpty()) {
				resultRow.setVisibility(View.GONE);
				matchCount.setVisibility(View.VISIBLE);
				matchCount.setText(getString(R.string.no_matches_found));
				return;
			}
			if (state.index < 0 || state.index >= state.matches.size()) {
				state.index = 0;
			}
			showMatchPreview.run();
		};

		Runnable findNext = () -> {
			if (!ensureMatches.getAsBoolean()) {
				return;
			}
			state.index = (state.index + 1) % state.matches.size();
			showCurrentMatch.run();
		};
		Runnable findPrevious = () -> {
			if (!ensureMatches.getAsBoolean()) {
				return;
			}
			int size = state.matches.size();
			int from = Math.max(state.index, 0);
			state.index = Math.floorMod(from - 1, size);
			showCurrentMatch.run();
		};
		Runnable findRandom = () -> {
			if (!ensureMatches.getAsBoolean()) {
				return;
			}
			int size = state.matches.size();
			int from = Math.max(state.index, 0);
			if (size > 1) {
				// A signed jump averaging about half the match list's
				// length - likely to land somewhere nearby, occasionally
				// far enough that two lucky jumps could cross the whole
				// list.
				int range = size - 1;
				int offset = random.nextInt(2 * range + 1) - range;
				state.index = Math.floorMod(from + offset, size);
			} else {
				state.index = 0;
			}
			showCurrentMatch.run();
		};
		previousButton.setOnClickListener(v -> findPrevious.run());
		randomButton.setOnClickListener(v -> findRandom.run());
		nextButton.setOnClickListener(v -> findNext.run());
		queryView.setOnEditorActionListener((v, actionId, event) -> {
			findNext.run();
			return true;
		});
		// Belt-and-suspenders alongside the editor-action listener above
		// (which fires reliably for the on-screen IME's own "Search"
		// action, but not always for a physical/Bluetooth keyboard's
		// Enter key on every device) - catches Enter directly regardless
		// of source. findNext already does "search if nothing's been
		// searched yet, otherwise advance" (see ensureMatches above), so
		// repeated Enter presses naturally cycle through matches once a
		// search is active. Ctrl+Enter loads whatever match is currently
		// shown instead, same as tapping Load on it directly. Left/Right
		// also cycle through matches directly - see
		// R.string.find_shader_hotkey_hint, shown in the dialog itself.
		//
		// Attached to *every* focusable control in this dialog, not just
		// queryView - a View's own setOnKeyListener is checked before
		// its own default key handling, so without this a CheckBox that
		// happens to have focus (e.g. tabbed to from a hardware
		// keyboard) would consume Enter itself first, toggling the
		// checkbox instead of ever reaching here. This also means
		// queryView's own Left/Right cursor movement is sacrificed while
		// this listener is attached - an acceptable trade for a single-
		// line query field.
		View.OnKeyListener enterKeyListener = (v, keyCode, event) -> {
			if (event.getAction() != KeyEvent.ACTION_DOWN) {
				return false;
			}
			switch (keyCode) {
				case KeyEvent.KEYCODE_ENTER:
				case KeyEvent.KEYCODE_NUMPAD_ENTER:
					if (event.isCtrlPressed() && resultRow.getVisibility() == View.VISIBLE) {
						resultLoad.performClick();
					} else {
						findNext.run();
					}
					return true;
				case KeyEvent.KEYCODE_DPAD_LEFT:
					findPrevious.run();
					return true;
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					findNext.run();
					return true;
				default:
					return false;
			}
		};
		queryView.setOnKeyListener(enterKeyListener);
		searchSourceBox.setOnKeyListener(enterKeyListener);
		fuzzyBox.setOnKeyListener(enterKeyListener);
		previousButton.setOnKeyListener(enterKeyListener);
		randomButton.setOnKeyListener(enterKeyListener);
		nextButton.setOnKeyListener(enterKeyListener);
		queryView.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				updateLiveCount.run();
			}
		});
		searchSourceBox.setOnCheckedChangeListener((b, checked) -> updateLiveCount.run());
		fuzzyBox.setOnCheckedChangeListener((b, checked) -> updateLiveCount.run());

		updateLiveCount.run();
		// Re-shows exactly the match that was on screen last time the
		// dialog closed, rather than making the user press "next" again
		// to get back to where they left off. Forces a fresh lookup
		// (instead of trusting the possibly-stale cached list) in case
		// shaders were renamed/added/removed since.
		if (!state.query.isEmpty() && state.index >= 0) {
			int resumeIndex = state.index;
			state.key = null;
			if (ensureMatches.getAsBoolean()) {
				state.index = Math.floorMod(resumeIndex, state.matches.size());
				showCurrentMatch.run();
			}
		}
	}

	private void setFullscreen(boolean fullscreen) {
		if (fullscreen) {
			View focused = getCurrentFocus();
			keyboardVisibleBeforeFullscreen = focused != null && isKeyboardVisible(focused);
			selectionStartBeforeFullscreen = editorFragment.getSelectionStart();
			selectionEndBeforeFullscreen = editorFragment.getSelectionEnd();
			if (focused != null) {
				SoftKeyboard.hide(this, focused);
			}
			// A paused shader (manually, via the pause button, or via
			// "run when not editing" with the editor visible) would
			// otherwise show nothing useful full-screen - a frozen last
			// frame, or (see togglePauseShader()) solid black. Unpause
			// for the duration of fullscreen and restore the exact prior
			// state on the way back out, below.
			pausedBeforeFullscreen = shaderViewManager.isPaused();
			if (pausedBeforeFullscreen) {
				setShaderPaused(false);
			}
		}
		uiManager.setFullscreen(fullscreen);
		shaderViewManager.setCropBorderPixel(fullscreen);
		// Only meaningful in fullscreen, where there's no toolbar back
		// button to tap - see FullscreenExitGestureDetector.
		shaderViewManager.setFullscreenExitGestureEnabled(fullscreen);
		if (!fullscreen) {
			uiManager.hideFullscreenExitHint();
		}
		// Fullscreen hides the whole editor (and everything else) from
		// view regardless of the code editor's own visibility flag, so
		// it counts as "not editing" for "Run when not editing" too -
		// otherwise a shader left paused because the editor happened to
		// be toggled visible underneath would stay frozen the whole time
		// fullscreen is up, even though nothing editable is on screen.
		updatePauseForEditingVisibility(editorFragment.isCodeVisible());
		// Restores whatever pause state fullscreen's own entry above
		// overrode - a no-op if updatePauseForEditingVisibility() just
		// above already re-paused it (setShaderPaused() ignores a
		// request to re-enter the state it's already in).
		if (!fullscreen && pausedBeforeFullscreen) {
			pausedBeforeFullscreen = false;
			setShaderPaused(true);
			shaderViewManager.blankScreen();
		}
		// The keyboard needs explicit handling since IME visibility isn't
		// tied to View visibility and the editor doesn't regain focus on
		// its own. Selection is restored explicitly too, defensively -
		// re-focusing an EditText after it lost window focus can otherwise
		// leave the cursor wherever the IME/focus dance last put it.
		if (!fullscreen) {
			if (keyboardVisibleBeforeFullscreen) {
				keyboardVisibleBeforeFullscreen = false;
				editorFragment.showKeyboard();
			}
			if (selectionStartBeforeFullscreen >= 0) {
				editorFragment.setSelection(selectionStartBeforeFullscreen, selectionEndBeforeFullscreen);
				selectionStartBeforeFullscreen = -1;
				selectionEndBeforeFullscreen = -1;
			}
		}
	}

	private static boolean isKeyboardVisible(@NonNull View view) {
		WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
		return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
	}

	private void setToolbarHidden(boolean hidden) {
		uiManager.setToolbarHidden(hidden);
	}

	private void togglePauseShader() {
		boolean pausing = !shaderViewManager.isPaused();
		setShaderPaused(pausing);
		if (pausing) {
			// Only for this, the manual pause button - other
			// setShaderPaused(true) callers (screenshot/capture flows,
			// "run when not editing") still need the live frame visible
			// while paused, not blanked.
			shaderViewManager.blankScreen();
		}
	}

	// Explicit-target version of togglePauseShader(), so code other
	// than the pause button itself (e.g. the "Run when not editing"
	// mode below) can drive pause state without needing to know the
	// current state first. No-ops if already at the target state.
	private void setShaderPaused(boolean paused) {
		if (shaderViewManager.isPaused() == paused) {
			return;
		}
		if (paused) {
			textAtPauseTime = editorFragment.getText();
		}
		shaderViewManager.setPaused(paused);
		if (!paused && !editorFragment.getText().equals(textAtPauseTime)) {
			// Catch up on whatever was edited while paused.
			shaderViewManager.setProjectSession(
					shaderManager.getEditedProjectSession(editorFragment.getText()));
		}
		uiManager.setPauseButtonState(paused);
	}

	// Drives the "Run when not editing" mode: pauses the shader while
	// the code editor is visible, and unpauses it (picking up any
	// edits) once it's hidden again. The user can still manually
	// unpause via the pause button while the editor stays visible -
	// this only forces pausing back on the next time visibility
	// actually changes (or on returning from Settings), not
	// continuously, so a manual unpause while editing sticks until then.
	private void updatePauseForEditingVisibility(boolean codeVisible) {
		if (ShaderEditorApp.preferences.doesPauseWhileEditing()) {
			// Fullscreen hides the editor regardless of its own visibility
			// flag, so it always counts as "not editing" - see setFullscreen().
			setShaderPaused(codeVisible && !uiManager.isFullscreen());
		}
	}

	private void restartShader() {
		if (shaderViewManager.isPaused()) {
			// Also picks up any edits made while paused.
			togglePauseShader();
		}
		shaderViewManager.restartRuntime();
	}

	// Explicit "Run" only - deliberately not wired into the
	// auto-run-on-change path (setOnEditPausedListener below), which
	// fires silently and repeatedly while the user is still mid-edit;
	// a confirmation dialog popping up there would fight typing itself,
	// not just warn before a deliberate action.
	private void runShader() {
		String src = editorFragment.getText();
		long selectedId = shaderManager.getSelectedShaderId();
		DataRecords.Shader current = selectedId > 0 ? dataSource.shader.getShader(selectedId) : null;
		if (current != null && maybeWarnAboutSlowCompile(current, src, () -> doRunShader(src))) {
			return;
		}
		doRunShader(src);
	}

	private void doRunShader(@NonNull String src) {
		clearFindMatchRing();
		var projectSession = shaderManager.getEditedProjectSession(src);
		editorFragment.clearError();
		if (ShaderEditorApp.preferences.doesSaveOnRun()) {
			PreviewActivity.renderStatus.reset();
			shaderManager.saveShader();
			projectSession = shaderManager.getEditedProjectSession(src);
		}
		if (ShaderEditorApp.preferences.doesRunInBackground()) {
			shaderViewManager.setProjectSession(projectSession);
		} else {
			navigationManager.showPreview(projectSession,
					shaderManager.previewShaderLauncher);
		}
	}

	private void recoverFromCrashIfNeeded() {
		var preferences = ShaderEditorApp.preferences;
		long shaderId = preferences.getPendingCrashShaderId();
		if (shaderId <= 0) {
			return;
		}
		var shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			preferences.setPendingCrashShaderId(0);
			return;
		}
		String recoveredSource = buildCrashRecoverySource(shader.fragmentShader());
		dataSource.shader.updateShader(shaderId, recoveredSource, null, shader.quality());
		preferences.setPendingCrashShaderId(0);
		Toast.makeText(this, R.string.shader_disabled_after_crash,
				Toast.LENGTH_LONG).show();
	}

	@NonNull
	private static String buildCrashRecoverySource(@Nullable String source) {
		if (source != null && source.startsWith(CRASH_RECOVERY_COMMENT)) {
			return source;
		}
		StringBuilder builder = new StringBuilder();
		builder.append(CRASH_RECOVERY_COMMENT);
		builder.append("#if 0\n");
		if (source != null && !source.isEmpty()) {
			builder.append(source);
			if (!source.endsWith("\n")) {
				builder.append('\n');
			}
		}
		builder.append("#endif\n");
		return builder.toString();
	}
}
