package enk.danwills.android.enkishadereditor.activity;

import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
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
	private ActivityResultLauncher<Intent> mediaProjectionLauncher;
	private SharedPreferences.OnSharedPreferenceChangeListener audioPreferenceListener;
	// Backs out of fullscreen and/or the hidden-toolbar mode, in that
	// order, instead of the normal back action. Enabled only while at
	// least one of those is active.
	private final OnBackPressedCallback specialModeBackCallback =
			new OnBackPressedCallback(false) {
				@Override
				public void handleOnBackPressed() {
					if (uiManager.isFullscreen()) {
						setFullscreen(false);
					} else if (uiManager.isToolbarHidden()) {
						setToolbarHidden(false);
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
		editorFragment.setOnTextModifiedListener(() -> shaderManager.setModified(true));
		editorFragment.setCodeCompletionListener(extraKeysManager::setCompletions);
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

	private void showFindInShaderDialog() {
		View view = getLayoutInflater().inflate(R.layout.dialog_find_in_shader, null);
		EditText queryView = view.findViewById(R.id.query);

		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.find_in_shader)
				.setView(view)
				.setPositiveButton(R.string.find_next, null)
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> SoftKeyboard.hide(this, queryView))
				.show();

		Runnable findNext = () -> {
			String query = queryView.getText().toString();
			if (query.isEmpty()) {
				return;
			}
			if (!editorFragment.findNext(query)) {
				Toast.makeText(this, R.string.no_matches_found, Toast.LENGTH_SHORT).show();
			}
		};
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> findNext.run());
		queryView.setOnEditorActionListener((v, actionId, event) -> {
			findNext.run();
			return true;
		});
	}

	private void selectShader(long id) {
		isInitialLoad = false;
		if (ShaderEditorApp.preferences.autoSave()) {
			shaderManager.saveShader();
		}
		shaderManager.selectShader(id);
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
						applyNameGroupRename(targetId, parentName, postfix, confirmIfAlreadyNamed);
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
		List<DataRecords.ShaderInfo> matches = Collections.emptyList();
		int index = -1;
		String key;
	}

	private void showFindShaderDialog() {
		View view = getLayoutInflater().inflate(R.layout.dialog_find_shader, null);
		EditText queryView = view.findViewById(R.id.query);
		CheckBox searchSourceBox = view.findViewById(R.id.search_source);
		CheckBox fuzzyBox = view.findViewById(R.id.fuzzy_matching);
		View resultRow = view.findViewById(R.id.result_row);
		ImageView resultIcon = view.findViewById(R.id.result_icon);
		TextView resultTitle = view.findViewById(R.id.result_title);
		TextView resultPosition = view.findViewById(R.id.result_position);
		Button resultLoad = view.findViewById(R.id.result_load);
		View navRow = view.findViewById(R.id.find_nav_row);
		View previousButton = view.findViewById(R.id.find_previous);
		View randomButton = view.findViewById(R.id.find_random);
		View nextButton = view.findViewById(R.id.find_next_match);
		ShaderSearchState state = new ShaderSearchState();
		Random random = new Random();

		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.find_shader)
				.setView(view)
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> SoftKeyboard.hide(this, queryView))
				.show();

		Runnable showCurrentMatch = () -> {
			DataRecords.ShaderInfo match = state.matches.get(state.index);
			resultRow.setVisibility(View.VISIBLE);
			navRow.setVisibility(View.VISIBLE);
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
				navRow.setVisibility(View.GONE);
				Toast.makeText(this, R.string.no_matches_found, Toast.LENGTH_SHORT).show();
				return false;
			}
			return true;
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
		updateSpecialModeBackCallback();
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
		updateSpecialModeBackCallback();
	}

	private void updateSpecialModeBackCallback() {
		specialModeBackCallback.setEnabled(
				uiManager.isFullscreen() || uiManager.isToolbarHidden());
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

	private void runShader() {
		String src = editorFragment.getText();
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
