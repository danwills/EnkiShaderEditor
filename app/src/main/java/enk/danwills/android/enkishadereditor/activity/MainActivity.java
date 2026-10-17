package enk.danwills.android.enkishadereditor.activity;

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
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
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
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Locale;
import java.util.Set;
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
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.preference.Preferences;
import enk.danwills.android.enkishadereditor.service.AudioCaptureForegroundService;
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
		if (multipliers.isEmpty()) {
			view.findViewById(R.id.no_options_message).setVisibility(View.VISIBLE);
		} else {
			for (int i = 0; i < labels.size(); ++i) {
				RadioButton button = new RadioButton(this);
				button.setId(View.generateViewId());
				button.setText(labels.get(i));
				optionsGroup.addView(button);
				if (i == 0) {
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
		view.findViewById(R.id.higher_res_custom_button).setOnClickListener(v -> {
			dialog.dismiss();
			showCustomResolutionDialog(wasPaused);
		});
		Button okButton = view.findViewById(R.id.higher_res_ok);
		if (multipliers.isEmpty()) {
			okButton.setVisibility(View.GONE);
		} else {
			okButton.setOnClickListener(v -> {
				dialog.dismiss();
				int checkedIndex = optionsGroup.indexOfChild(
						optionsGroup.findViewById(optionsGroup.getCheckedRadioButtonId()));
				int multiplier = multipliers.get(Math.max(0, checkedIndex));
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
		EditText widthView = view.findViewById(R.id.custom_width);
		EditText heightView = view.findViewById(R.id.custom_height);
		EditText oversampleView = view.findViewById(R.id.custom_oversample);
		CheckBox preserveBackbufferBox = view.findViewById(R.id.preserve_backbuffer_edges);

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
				// Defaults height to a square (same as width) the first
				// time it's reached with width already filled in and
				// height still blank - still freely overwritable since
				// it's selected, same as the other fields.
				if (heightView.getText().toString().isBlank() &&
						!widthView.getText().toString().isBlank()) {
					heightView.setText(widthView.getText());
				}
				heightView.selectAll();
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
			String widthText = widthView.getText().toString();
			String heightText = heightView.getText().toString();
			// Same square default as the focus-change handler above, as
			// a fallback for going straight from width to OK without
			// ever focusing the height field.
			if (heightText.isBlank() && !widthText.isBlank()) {
				heightText = widthText;
			}
			int width = parsePositiveInt(widthText);
			int height = parsePositiveInt(heightText);
			int oversample = Math.max(1, parsePositiveInt(oversampleView.getText().toString()));
			if (width <= 0 || height <= 0) {
				Toast.makeText(this, R.string.higher_res_custom_invalid, Toast.LENGTH_SHORT).show();
				setShaderPaused(wasPaused);
				dialog.dismiss();
				return;
			}
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
		TextView progressMessage = progressView.findViewById(R.id.message);
		progressMessage.setText(R.string.higher_res_capturing);
		View indeterminateBar = progressView.findViewById(R.id.progress_indeterminate);
		ProgressBar determinateBar = progressView.findViewById(R.id.progress_determinate);
		AlertDialog progressDialog = new MaterialAlertDialogBuilder(this)
				.setView(progressView)
				.setCancelable(false)
				.show();
		// A tiled capture can run for several seconds (or, at a large
		// oversample factor, minutes) entirely on the GL thread. Without
		// this, a screen timeout mid-capture lets Android tear down the
		// GLSurfaceView's EGL context from under it (same as backgrounding
		// the app) - every GL object the capture was still using becomes
		// invalid, and uniform/readback calls start returning garbage for
		// whatever tiles hadn't rendered yet. Cleared once the capture
		// (success or failure) is done, below.
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		ShaderRenderer.HighResProgressListener progressListener = (tilesDone, totalTiles) ->
				runOnUiThread(() -> {
					indeterminateBar.setVisibility(View.GONE);
					determinateBar.setVisibility(View.VISIBLE);
					determinateBar.setMax(totalTiles);
					determinateBar.setProgress(tilesDone);
					progressMessage.setText(
							getString(R.string.higher_res_capturing_tiles, tilesDone, totalTiles));
				});
		new Thread(() -> {
			byte[] png = shaderViewManager.takeHighResScreenshot(
					width, height, oversample, preserveBackbufferEdges, progressListener);
			runOnUiThread(() -> {
				getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				progressDialog.dismiss();
				if (png == null) {
					Toast.makeText(this, R.string.screenshot_save_failed, Toast.LENGTH_SHORT).show();
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

	private enum ScreenshotAction {COPY, SAVE, SAVE_AND_OPEN}

	private void showScreenshotActionsDialog(@NonNull byte[] png, boolean wasPaused) {
		View view = getLayoutInflater().inflate(R.layout.dialog_screenshot_actions, null);
		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.save_screenshot)
				.setView(view)
				.setOnDismissListener(d -> setShaderPaused(wasPaused))
				.show();
		view.findViewById(R.id.screenshot_copy).setOnClickListener(v -> {
			dialog.dismiss();
			handleScreenshotAction(png, ScreenshotAction.COPY);
		});
		view.findViewById(R.id.screenshot_save).setOnClickListener(v -> {
			dialog.dismiss();
			handleScreenshotAction(png, ScreenshotAction.SAVE);
		});
		view.findViewById(R.id.screenshot_save_and_open).setOnClickListener(v -> {
			dialog.dismiss();
			handleScreenshotAction(png, ScreenshotAction.SAVE_AND_OPEN);
		});
		view.findViewById(R.id.screenshot_higher_res).setOnClickListener(v -> {
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
						// NEW_TASK (+ MULTIPLE_TASK so it doesn't just fold
						// into some other already-running task of the
						// viewer app) - the viewer then gets its own entry
						// in the recent-apps switcher, so backing out of it
						// to return here means alt-tab/recents, not this
						// Activity's own back stack.
						Intent viewIntent = new Intent(Intent.ACTION_VIEW)
								.setDataAndType(saved.uri(), "image/png")
								.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
								.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
								.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
						try {
							startActivity(viewIntent);
						} catch (ActivityNotFoundException e) {
							Toast.makeText(this, R.string.no_app_to_open_image,
									Toast.LENGTH_SHORT).show();
						}
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
