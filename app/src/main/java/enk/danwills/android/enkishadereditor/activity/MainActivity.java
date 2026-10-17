package enk.danwills.android.enkishadereditor.activity;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
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
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.activity.managers.ExtraKeysManager;
import enk.danwills.android.enkishadereditor.activity.managers.MainMenuManager;
import enk.danwills.android.enkishadereditor.activity.managers.NavigationManager;
import enk.danwills.android.enkishadereditor.activity.managers.ShaderListManager;
import enk.danwills.android.enkishadereditor.activity.managers.ShaderManager;
import enk.danwills.android.enkishadereditor.activity.managers.ShaderViewManager;
import enk.danwills.android.enkishadereditor.activity.managers.UIManager;
import enk.danwills.android.enkishadereditor.adapter.ShaderTreeAdapter;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.fragment.EditorFragment;
import enk.danwills.android.enkishadereditor.io.ScreenshotSaver;
import enk.danwills.android.enkishadereditor.io.VideoEncoder;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.opengl.SharedCaptureContext;
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
		shaderViewManager = new ShaderViewManager(this,
				findViewById(R.id.preview),
				findViewById(R.id.quality),
				dataSource,
				createShaderViewListener());
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
		int[] selected = {defaultIndex};
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.select_quality)
				.setSingleChoiceItems(qualityNames, defaultIndex,
						(dialog, which) -> selected[0] = which)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					float quality = Float.parseFloat(qualityValues[selected[0]]);
					long newId = dataSource.shader.insertShader(
							shaderSource, null, null, quality);
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

	// Multipliers of the device's own screen resolution offered by the
	// picker below - only those whose final ARGB_8888 bitmap would stay
	// within a conservative share of this app's heap budget are actually
	// shown (larger multipliers only need *more* memory, so the first
	// one that doesn't fit means none of the later, bigger ones would
	// either).
	// 1x deliberately omitted - that's just a plain screenshot, already
	// covered by "Just Save"/"Save and Open" on the dialog this picker
	// opens from.
	private static final int[] HIGHER_RES_MULTIPLIERS = {2, 4, 6, 8};
	// android:largeHeap is on specifically so this has real headroom to
	// work with - getLargeMemoryClass() (not getMemoryClass()) reports
	// what that's actually worth on this device.
	private static final double HIGHER_RES_MAX_HEAP_FRACTION = 0.6;

	// Remembers the last values used in the higher-res/custom-res/frame-
	// sequence dialogs, written right before each dialog actually runs
	// its action and read back the next time that dialog opens - plain
	// SharedPreferences keys rather than anything added to the app's own
	// Preferences class, since these are transient "what did I last type
	// here" dialog state, not documented user-facing settings.
	private static final String PREF_HIGHER_RES_PRESERVE_BACKBUFFER = "higher_res_preserve_backbuffer";
	private static final String PREF_HIGHER_RES_MULTIPLIER = "higher_res_multiplier";
	private static final String PREF_CUSTOM_RES_WIDTH = "custom_res_width";
	private static final String PREF_CUSTOM_RES_HEIGHT = "custom_res_height";
	private static final String PREF_CUSTOM_RES_OVERSAMPLE = "custom_res_oversample";
	private static final String PREF_CUSTOM_RES_SEQUENCE_ENABLED = "custom_res_sequence_enabled";
	private static final String PREF_CUSTOM_RES_SEQUENCE_FRAMES = "custom_res_sequence_frames";
	private static final String PREF_CUSTOM_RES_SEQUENCE_FPS = "custom_res_sequence_fps";
	private static final String PREF_CUSTOM_RES_SEQUENCE_SKIP = "custom_res_sequence_skip";
	private static final String PREF_CUSTOM_RES_CREATE_VIDEO = "custom_res_create_video";
	private static final String PREF_CUSTOM_RES_CLEANUP_IMAGES = "custom_res_cleanup_images";
	private static final String PREF_CUSTOM_RES_VIDEO_QUALITY = "custom_res_video_quality";
	private static final String PREF_LAST_SCREENSHOT_ACTION = "last_screenshot_action";
	private static final String PREF_LAST_HIGHER_RES_ACTION = "last_higher_res_action";

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

	// Index-aligned with R.array.custom_res_presets, index 0 ("Custom")
	// excepted - that one's a no-op handled separately, not a dimension
	// pair. Indices 1-3 are multiples of the screen's own resolution
	// (computed at selection time, not fixed here); 4+ are fixed pixel
	// sizes, the HD/4K ones deliberately landscape per their usual
	// meaning regardless of the device's own orientation.
	private static final float[] CUSTOM_RES_SCREEN_MULTIPLIERS = {0.5f, 1f, 2f};
	private static final int[][] CUSTOM_RES_FIXED_PRESETS = {
			{512, 512},
			{1024, 1024},
			{2048, 2048},
			{1280, 720},
			{1920, 1080},
			{3840, 2160},
	};

	// wasPaused: the shader's pause state from *before* this whole Save
	// At Higher Res flow started (threaded in from showScreenshotActionsDialog,
	// which already paused it the same way the normal screenshot does) -
	// kept paused continuously through this picker, the capture, and the
	// save, only restored once everything's actually done (see
	// runHighResCapture()'s onComplete callback) - not released early
	// just because a dialog in the middle of the flow got dismissed.
	private void showSaveAtHigherResDialog(boolean wasPaused) {
		DisplayMetrics metrics = getResources().getDisplayMetrics();
		int screenWidth = metrics.widthPixels;
		int screenHeight = metrics.heightPixels;

		ActivityManager activityManager =
				(ActivityManager) getSystemService(ACTIVITY_SERVICE);
		long heapBudgetBytes =
				(activityManager != null ? activityManager.getLargeMemoryClass() : 256)
						* 1024L * 1024L;
		long maxBitmapBytes = (long) (heapBudgetBytes * HIGHER_RES_MAX_HEAP_FRACTION);

		List<Integer> multipliers = new ArrayList<>();
		List<String> labels = new ArrayList<>();
		for (int multiplier : HIGHER_RES_MULTIPLIERS) {
			long width = (long) screenWidth * multiplier;
			long height = (long) screenHeight * multiplier;
			long bytes = width * height * 4;
			if (bytes > maxBitmapBytes) {
				break;
			}
			multipliers.add(multiplier);
			labels.add(getString(R.string.higher_res_option,
					multiplier, width, height, bytes / (1024 * 1024)));
		}

		View view = getLayoutInflater().inflate(R.layout.dialog_higher_res_picker, null);
		RadioGroup optionsGroup = view.findViewById(R.id.resolution_options);
		CheckBox preserveBackbufferBox = view.findViewById(R.id.preserve_backbuffer_edges);
		preserveBackbufferBox.setChecked(
				dialogPrefs().getBoolean(PREF_HIGHER_RES_PRESERVE_BACKBUFFER, true));
		if (multipliers.isEmpty()) {
			view.findViewById(R.id.no_options_message).setVisibility(View.VISIBLE);
		} else {
			// Pre-select whichever multiplier was used last time, falling
			// back to the first (smallest) option if that one's no longer
			// in this device's available list (e.g. memory got tighter).
			int rememberedMultiplier = dialogPrefs().getInt(PREF_HIGHER_RES_MULTIPLIER, -1);
			int selectIndex = Math.max(0, multipliers.indexOf(rememberedMultiplier));
			for (int i = 0; i < labels.size(); ++i) {
				RadioButton button = new RadioButton(this);
				button.setId(View.generateViewId());
				button.setText(labels.get(i));
				optionsGroup.addView(button);
				if (i == selectIndex) {
					button.setChecked(true);
				}
			}
		}

		// Plain custom buttons at the bottom of the view (not AlertDialog's
		// built-in slots) - those reorder depending on whether Android
		// lays them out in a row or stacks them vertically, which isn't
		// reliably under our control, making "Cancel last" unreliable
		// via setPositiveButton/etc. alone.
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.save_at_higher_res)
				.setView(view)
				.setOnCancelListener(d -> setShaderPaused(wasPaused))
				.show();
		Button customButton = view.findViewById(R.id.higher_res_custom_button);
		Button okButton = view.findViewById(R.id.higher_res_ok);
		highlightLastUsedButton(PREF_LAST_HIGHER_RES_ACTION, customButton, okButton);
		customButton.setOnClickListener(v -> {
			rememberLastUsedButton(PREF_LAST_HIGHER_RES_ACTION, R.id.higher_res_custom_button);
			dialog.dismiss();
			showCustomResolutionDialog(wasPaused);
		});
		if (multipliers.isEmpty()) {
			okButton.setVisibility(View.GONE);
		} else {
			okButton.setOnClickListener(v -> {
				rememberLastUsedButton(PREF_LAST_HIGHER_RES_ACTION, R.id.higher_res_ok);
				dialog.dismiss();
				int checkedIndex = optionsGroup.indexOfChild(
						optionsGroup.findViewById(optionsGroup.getCheckedRadioButtonId()));
				int multiplier = multipliers.get(Math.max(0, checkedIndex));
				dialogPrefs().edit()
						.putBoolean(PREF_HIGHER_RES_PRESERVE_BACKBUFFER, preserveBackbufferBox.isChecked())
						.putInt(PREF_HIGHER_RES_MULTIPLIER, multiplier)
						.apply();
				runHighResCapture(screenWidth * multiplier, screenHeight * multiplier, 1,
						preserveBackbufferBox.isChecked(), wasPaused);
			});
		}
		view.findViewById(R.id.higher_res_cancel).setOnClickListener(v -> {
			dialog.dismiss();
			setShaderPaused(wasPaused);
		});
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
		// A remembered value (from last time this dialog was actually
		// used) always wins over the device-resolution default - that
		// default only applies the very first time, before anything's
		// been remembered yet.
		widthView.setText(String.valueOf(
				prefs.getInt(PREF_CUSTOM_RES_WIDTH, metrics.widthPixels)));
		heightView.setText(String.valueOf(
				prefs.getInt(PREF_CUSTOM_RES_HEIGHT, metrics.heightPixels)));
		Spinner presetSpinner = view.findViewById(R.id.custom_res_preset);
		presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
				if (position == 0) {
					return;
				}
				int presetWidth;
				int presetHeight;
				if (position <= CUSTOM_RES_SCREEN_MULTIPLIERS.length) {
					float multiplier = CUSTOM_RES_SCREEN_MULTIPLIERS[position - 1];
					presetWidth = Math.round(metrics.widthPixels * multiplier);
					presetHeight = Math.round(metrics.heightPixels * multiplier);
				} else {
					int[] fixed = CUSTOM_RES_FIXED_PRESETS[
							position - 1 - CUSTOM_RES_SCREEN_MULTIPLIERS.length];
					presetWidth = fixed[0];
					presetHeight = fixed[1];
				}
				widthView.setText(String.valueOf(presetWidth));
				heightView.setText(String.valueOf(presetHeight));
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});
		EditText oversampleView = view.findViewById(R.id.custom_oversample);
		oversampleView.setText(String.valueOf(prefs.getInt(PREF_CUSTOM_RES_OVERSAMPLE, 1)));
		CheckBox preserveBackbufferBox = view.findViewById(R.id.preserve_backbuffer_edges);
		preserveBackbufferBox.setChecked(
				prefs.getBoolean(PREF_HIGHER_RES_PRESERVE_BACKBUFFER, true));
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

		// Plain custom buttons - see showSaveAtHigherResDialog().
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.higher_res_custom)
				.setView(view)
				.setOnCancelListener(d -> setShaderPaused(wasPaused))
				.show();
		view.findViewById(R.id.custom_res_ok).setOnClickListener(v -> {
			int width = parsePositiveInt(widthView.getText().toString());
			int height = parsePositiveInt(heightView.getText().toString());
			int oversample = Math.max(1, parsePositiveInt(oversampleView.getText().toString()));
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
					.putBoolean(PREF_HIGHER_RES_PRESERVE_BACKBUFFER, preserveBackbufferBox.isChecked())
					.putBoolean(PREF_CUSTOM_RES_SEQUENCE_ENABLED, sequenceBox.isChecked());
			if (sequenceBox.isChecked()) {
				int frameCount = parsePositiveInt(frameCountView.getText().toString());
				float fps = parsePositiveFloat(fpsView.getText().toString());
				int skipFrames = Math.max(0, parsePositiveInt(skipFramesView.getText().toString()));
				if (frameCount <= 0 || fps <= 0) {
					Toast.makeText(this, R.string.higher_res_sequence_invalid, Toast.LENGTH_SHORT).show();
					setShaderPaused(wasPaused);
					dialog.dismiss();
					return;
				}
				int videoQuality = qualitySeekBar.getProgress();
				prefsEditor.putInt(PREF_CUSTOM_RES_SEQUENCE_FRAMES, frameCount)
						.putFloat(PREF_CUSTOM_RES_SEQUENCE_FPS, fps)
						.putInt(PREF_CUSTOM_RES_SEQUENCE_SKIP, skipFrames)
						.putBoolean(PREF_CUSTOM_RES_CREATE_VIDEO, createVideoBox.isChecked())
						.putInt(PREF_CUSTOM_RES_VIDEO_QUALITY, videoQuality)
						.putBoolean(PREF_CUSTOM_RES_CLEANUP_IMAGES, cleanupImagesBox.isChecked())
						.apply();
				dialog.dismiss();
				runFrameSequenceCapture(width, height, oversample, preserveBackbufferBox.isChecked(),
						skipFrames, frameCount, fps, createVideoBox.isChecked(), videoQuality,
						cleanupImagesBox.isChecked(), wasPaused);
				return;
			}
			prefsEditor.apply();
			dialog.dismiss();
			runHighResCapture(width, height, oversample, preserveBackbufferBox.isChecked(), wasPaused);
		});
		view.findViewById(R.id.custom_res_cancel).setOnClickListener(v -> {
			dialog.dismiss();
			setShaderPaused(wasPaused);
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

	private final Random random = new Random();

	@NonNull
	private String pickRandomStatus(int arrayResId) {
		String[] variants = getResources().getStringArray(arrayResId);
		return variants[random.nextInt(variants.length)];
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

	// wasPaused: see showSaveAtHigherResDialog() - the pause state from
	// before the whole flow started, restored only once the capture AND
	// the save (and whatever the chosen action does after - here always
	// Save and Open) have both finished, so the shader stays still for
	// the entire flow rather than resuming the moment pixels are read
	// back. Tiling a large image can take anywhere from a couple of
	// seconds to minutes (large oversample factors especially), so the
	// progress dialog switches from indeterminate to a tile-count
	// progress bar as soon as the first progress callback arrives.
	private void runHighResCapture(int width, int height, int oversample,
			boolean preserveBackbufferEdges, boolean wasPaused) {
		setShaderPaused(true);
		View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
		TextView statusMessage = progressView.findViewById(R.id.status_message);
		ProgressBar overallBar = progressView.findViewById(R.id.progress_overall);
		ProgressBar subtaskBar = progressView.findViewById(R.id.progress_subtask);
		Button cancelButton = progressView.findViewById(R.id.progress_cancel);
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
				}
				overallBar.setProgress(PROGRESS_COMPILE_UNITS + tilesDone);
				subtaskBar.setProgress(tilesDone);
				statusMessage.setText(
						String.format(Locale.getDefault(), tileVariant[0], tilesDone, totalTiles));
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
			}
			byte[] png = backgroundCapable
					? shaderViewManager.takeHighResScreenshotOnSharedContext(sharedContext,
							width, height, oversample, preserveBackbufferEdges, progressListener)
					: shaderViewManager.takeHighResScreenshot(
							width, height, oversample, preserveBackbufferEdges, progressListener);
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
			boolean preserveBackbufferEdges, int skipFrames, int numFrames, float virtualFps,
			boolean createVideo, int videoQuality, boolean cleanupImages, boolean wasPaused) {
		setShaderPaused(true);
		var shader = dataSource.shader.getShader(shaderManager.getSelectedShaderId());
		String baseShaderName = sanitizeFilename(shader != null ? shader.getTitle() : "shader");

		View progressView = getLayoutInflater().inflate(R.layout.dialog_progress, null);
		TextView statusMessage = progressView.findViewById(R.id.status_message);
		ProgressBar overallBar = progressView.findViewById(R.id.progress_overall);
		ProgressBar subtaskBar = progressView.findViewById(R.id.progress_subtask);
		Button cancelButton = progressView.findViewById(R.id.progress_cancel);
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

		ShaderRenderer.SequenceProgressListener sequenceProgressListener = (skipping, stepIndex, totalSteps) -> {
			runOnUiThread(() -> {
				if (!skipping) {
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
			});
			return !cancelRequested.get();
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
				overallBar.setProgress(PROGRESS_COMPILE_UNITS + skipFrames +
						currentFrameIndex[0] * tilesPerFrame[0] + tilesDone);
				subtaskBar.setVisibility(View.VISIBLE);
				subtaskBar.setMax(totalTiles);
				subtaskBar.setProgress(tilesDone);
				statusMessage.setText(numFrames > 1
						? String.format(Locale.getDefault(), tileVariant[0],
								currentFrameIndex[0] + 1, numFrames, tilesDone, totalTiles)
						: String.format(Locale.getDefault(), tileVariant[0], tilesDone, totalTiles));
			});
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
		};

		new Thread(() -> {
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
			}
			if (backgroundCapable) {
				shaderViewManager.takeHighResScreenshotSequenceOnSharedContext(sharedContext,
						width, height, oversample, preserveBackbufferEdges, skipFrames, numFrames,
						virtualFps, tileProgressListener, sequenceProgressListener, frameListener);
				// Done with the shared context itself (every frame's tiled
				// render is now complete) - but the foreground service
				// stays up a bit longer, through any video encoding and
				// cleanup below, since those can also take a while and
				// deserve the same process-survival protection.
				sharedContext.destroy();
			} else {
				shaderViewManager.takeHighResScreenshotSequence(
						width, height, oversample, preserveBackbufferEdges, skipFrames, numFrames,
						virtualFps, tileProgressListener, sequenceProgressListener, frameListener);
			}

			boolean cancelled = cancelRequested.get();
			Uri videoUri = null;
			boolean cleanedUp = false;
			// Never attempts a video from a deliberately-interrupted
			// sequence - see cancelButton's own doc above.
			if (!cancelled && createVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
					&& !frameUris.isEmpty()) {
				int tilesPerFrameFinal = Math.max(0, tilesPerFrame[0]);
				int encodeBaseUnits = PROGRESS_COMPILE_UNITS + skipFrames + numFrames * tilesPerFrameFinal;
				runOnUiThread(() -> statusMessage.setText(R.string.status_wrapping_up));
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
					});
					return !cancelRequested.get();
				};
				String videoFilename = baseShaderName + "." + proseDateString(new Date()) + ".mp4";
				videoUri = VideoEncoder.encode(this, frameUris, width, height, virtualFps, videoQuality,
						videoFilename, videoProgress);
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
	private void openMedia(@NonNull Uri uri, @NonNull String mimeType, int noAppMessageRes) {
		Intent viewIntent = new Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, mimeType)
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
		try {
			startActivity(viewIntent);
		} catch (ActivityNotFoundException e) {
			Toast.makeText(this, noAppMessageRes, Toast.LENGTH_SHORT).show();
		}
	}

	private void openVideo(@NonNull Uri uri) {
		openMedia(uri, "video/mp4", R.string.no_app_to_open_video);
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
			// would briefly un-pause it right as the resolution picker
			// comes up. runHighResCapture() is the only thing that
			// should ever restore it now, once the capture+save is fully
			// done - see showSaveAtHigherResDialog().
			dialog.setOnDismissListener(null);
			dialog.dismiss();
			showSaveAtHigherResDialog(wasPaused);
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
						openMedia(saved.uri(), "image/png", R.string.no_app_to_open_image);
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
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> {
					SoftKeyboard.hide(this, queryView);
					state.query = queryView.getText().toString();
					state.searchSource = searchSourceBox.isChecked();
					state.fuzzy = fuzzyBox.isChecked();
				})
				.show();

		Runnable showCurrentMatch = () -> {
			DataRecords.ShaderInfo match = state.matches.get(state.index);
			// Opened (not just scrolled) so the reveal+highlight below is
			// actually visible - otherwise it happens behind a closed
			// drawer the user never sees move. Deferred until the drawer
			// has actually finished opening (not just requested to) -
			// scrolling the list to a row while the drawer's open
			// animation is still mid-flight races its own layout pass
			// and silently has no visible effect.
			uiManager.openDrawer(() -> shaderListManager.revealAndHighlightShader(match.id()));
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
		// Live match count, updated as the query/options change - shown
		// even before the user explicitly searches. Reverts from a
		// shown result_row back to this whenever the query/options
		// change underneath it, since the previously-shown match's
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
				state.index = -1;
				state.key = key;
			}
			resultRow.setVisibility(View.GONE);
			matchCount.setVisibility(View.VISIBLE);
			matchCount.setText(state.matches.isEmpty()
					? getString(R.string.no_matches_found)
					: getResources().getQuantityString(R.plurals.match_count,
							state.matches.size(), state.matches.size()));
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
		setShaderPaused(!shaderViewManager.isPaused());
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
