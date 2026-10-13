package enk.danwills.android.enkishadereditor.activity;

import android.app.WallpaperManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

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
import enk.danwills.android.enkishadereditor.service.AudioCaptureForegroundService;
import enk.danwills.android.enkishadereditor.service.ShaderWallpaperService;
import enk.danwills.android.enkishadereditor.shaderorg.ShaderSimilarity;
import enk.danwills.android.enkishadereditor.view.SoftKeyboard;
import enk.danwills.android.enkishadereditor.view.SystemBarMetrics;

public class MainActivity extends AppCompatActivity {
	private static final String CODE_VISIBLE = "code_visible";
	private static final String AUDIO_CAPTURE_TAG = "AudioCapture";
	private static final String CRASH_RECOVERY_COMMENT = """
			// Shader Editor disabled this shader because it caused a crash while loading.
			// Fix the shader and remove the #if 0 wrapper to re-enable it.
			""";

	private EditorFragment editorFragment;
	private UIManager uiManager;
	private ShaderManager shaderManager;
	private ShaderListManager shaderListManager;
	private ShaderViewManager shaderViewManager;
	private ExtraKeysManager extraKeysManager;
	private NavigationManager navigationManager;
	private DataSource dataSource;
	private boolean isInitialLoad = false;
	private ActivityResultLauncher<Intent> mediaProjectionLauncher;
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
				createShaderViewListener());
		extraKeysManager = new ExtraKeysManager(this,
				findViewById(android.R.id.content),
				editorFragment::insert,
				this::togglePauseShader);
		uiManager = new UIManager(this,
				editorFragment,
				extraKeysManager,
				shaderViewManager);
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
		uiManager.setupShaderNavRow(
				v -> navigateShader(-1),
				v -> navigateShader(1),
				v -> randomShader(),
				v -> editorFragment.undo(),
				v -> togglePauseShader());

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
		shaderViewManager.onResume();
	}

	@Override
	protected void onPause() {
		ShaderEditorApp.preferences.setPendingCrashShaderId(0);
		if (ShaderEditorApp.preferences.autoSave()) {
			shaderManager.saveShader();
		}
		super.onPause();
		shaderViewManager.onPause();
	}

	@Override
	protected void onDestroy() {
		stopService(new Intent(this, AudioCaptureForegroundService.class));
		if (shaderListManager != null) {
			shaderListManager.destroy();
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
					uiManager.setToolbarTitle(name);
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
				showPickParentShaderDialog();
			}

			@Override
			public void onConfigureSimilarShaders() {
				showSimilarShadersConfigDialog();
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

			private void showError(String error) {
				Snackbar snackbar = Snackbar.make(mainCoordinator,
								error,
								Snackbar.LENGTH_LONG)
						.setAction(R.string.details,
								v -> editorFragment.showErrors());
				moveSnackBarOverActionBar(snackbar.getView());
				snackbar.show();
			}

			private void moveSnackBarOverActionBar(View snackbarView) {
				if (snackbarView == null) {
					return;
				}

				ViewGroup.LayoutParams layoutParams =
						snackbarView.getLayoutParams();
				if (!(layoutParams instanceof
						CoordinatorLayout.LayoutParams params)) {
					return;
				}

				params.gravity = Gravity.TOP;

				int topInset = 0;
				WindowInsetsCompat rootInsets =
						ViewCompat.getRootWindowInsets(mainCoordinator);
				if (rootInsets != null) {
					topInset = rootInsets.getInsets(
							WindowInsetsCompat.Type.systemBars()).top;
				}
				if (topInset == 0 && toolbar != null) {
					topInset = toolbar.getPaddingTop();
				}
				params.topMargin = topInset;
				snackbarView.setLayoutParams(params);
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
			public void onToggleFullscreen() {
				setFullscreen(!uiManager.isFullscreen());
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
			public void onUpdateWallpaper() {
				updateWallpaper();
			}

			@Override
			public void onToggleExtraKeys() {
				extraKeysManager.setVisible(ShaderEditorApp.preferences.toggleShowExtraKeys());
			}

			@Override
			public void onTogglePauseShader() {
				togglePauseShader();
			}

			@Override
			public boolean isShaderPaused() {
				return shaderViewManager.isPaused();
			}

			@Override
			public void onFindShader() {
				showFindShaderDialog();
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
					shaderListManager.loadShadersAsync();
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
		MediaProjection projection = projectionManager.getMediaProjection(
				result.getResultCode(), result.getData());
		// Required before the projection can actually be used to create
		// an AudioPlaybackCaptureConfiguration on API 34+; see
		// AudioCaptureForegroundService. This can legitimately throw
		// ForegroundServiceStartNotAllowedException (API 31+) if Android
		// doesn't consider us sufficiently "foreground" at this exact
		// lifecycle moment (right as we're returning from the picker) -
		// in that case, just skip system audio for this run rather than
		// crash; the mic fallback (if enabled) still works.
		try {
			ContextCompat.startForegroundService(
					this, new Intent(this, AudioCaptureForegroundService.class));
		} catch (RuntimeException e) {
			Log.e(AUDIO_CAPTURE_TAG, "Could not start audio capture foreground service", e);
			return;
		}
		shaderViewManager.setMediaProjection(projection);

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
		uiManager.closeDrawers();
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
	private void showPickParentShaderDialog() {
		List<DataRecords.ShaderInfo> namedShaders = new ArrayList<>();
		for (var shader : shaderListManager.getAllShaders()) {
			if (shader.name() != null && !shader.name().isEmpty()) {
				namedShaders.add(shader);
			}
		}

		View view = getLayoutInflater().inflate(R.layout.dialog_pick_parent_shader, null);
		EditText filterView = view.findViewById(R.id.filter);
		ListView parentListView = view.findViewById(R.id.parent_list);
		ShaderTreeAdapter pickerAdapter = new ShaderTreeAdapter(this);
		pickerAdapter.setData(namedShaders);
		parentListView.setAdapter(pickerAdapter);

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
			showNameGroupPostfixDialog(parentName);
		});
	}

	private void showNameGroupPostfixDialog(@NonNull String parentName) {
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
						applyNameGroupRename(parentName, postfix);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.setOnDismissListener(d -> SoftKeyboard.hide(this, postfixView))
				.show();
	}

	private void applyNameGroupRename(@NonNull String parentName, @NonNull String postfix) {
		String newName = parentName + postfix;
		long currentId = shaderManager.getSelectedShaderId();
		if (currentId < 1) {
			return;
		}
		boolean conflict = false;
		for (var shader : shaderListManager.getAllShaders()) {
			if (shader.id() != currentId && newName.equalsIgnoreCase(shader.getTitle())) {
				conflict = true;
				break;
			}
		}
		if (conflict) {
			new MaterialAlertDialogBuilder(this)
					.setMessage(getString(R.string.name_already_exists, newName))
					.setPositiveButton(android.R.string.ok,
							(dialog, which) -> doNameGroupRename(currentId, newName))
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		} else {
			doNameGroupRename(currentId, newName);
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

		StringBuilder message = new StringBuilder(
				getString(R.string.stats_lines_chars, lineCount, charCount));

		long compileNanos = shaderViewManager.getLastCompileNanos();
		message.append('\n');
		if (compileNanos >= 0) {
			long compileMillis = compileNanos / 1_000_000;
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

		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.shader_stats)
				.setMessage(message.toString())
				.setPositiveButton(android.R.string.ok, null)
				.show();
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
		Button resultLoad = view.findViewById(R.id.result_load);
		ShaderSearchState state = new ShaderSearchState();

		AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.find_shader)
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
				return;
			}
			state.index = (state.index + 1) % state.matches.size();
			DataRecords.ShaderInfo match = state.matches.get(state.index);
			resultRow.setVisibility(View.VISIBLE);
			resultTitle.setText(match.getTitle());
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
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> findNext.run());
		queryView.setOnEditorActionListener((v, actionId, event) -> {
			findNext.run();
			return true;
		});
	}

	private void setFullscreen(boolean fullscreen) {
		uiManager.setFullscreen(fullscreen);
		shaderViewManager.setCropBorderPixel(fullscreen);
		updateSpecialModeBackCallback();
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
		boolean paused = !shaderViewManager.isPaused();
		shaderViewManager.setPaused(paused);
		if (!paused) {
			// Catch up on whatever was edited while paused.
			shaderViewManager.setProjectSession(
					shaderManager.getEditedProjectSession(editorFragment.getText()));
		}
		uiManager.setPauseButtonState(paused);
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
