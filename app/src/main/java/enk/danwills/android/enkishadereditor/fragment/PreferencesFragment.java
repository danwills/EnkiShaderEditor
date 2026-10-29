package enk.danwills.android.enkishadereditor.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.io.CompileAllShadersRunner;
import enk.danwills.android.enkishadereditor.io.DatabaseExporter;
import enk.danwills.android.enkishadereditor.io.DatabaseImporter;
import enk.danwills.android.enkishadereditor.io.ImportExportAsFiles;
import enk.danwills.android.enkishadereditor.io.VideoFormat;
import enk.danwills.android.enkishadereditor.opengl.Upscaling;
import enk.danwills.android.enkishadereditor.preference.Preferences;
import enk.danwills.android.enkishadereditor.preference.ShaderListPreference;
import enk.danwills.android.enkishadereditor.receiver.BatteryLevelReceiver;
import enk.danwills.android.enkishadereditor.widget.UpscaleDialog;

public class PreferencesFragment
		extends PreferenceFragmentCompat
		implements SharedPreferences.OnSharedPreferenceChangeListener {
	private ActivityResultLauncher<Intent> pickFileLauncher;
	private ActivityResultLauncher<String> requestReadPermissionLauncher;
	private ActivityResultLauncher<String> requestWritePermissionLauncher;
	private DataSource dataSource;

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		dataSource = Database.getInstance(requireContext()).getDataSource();

		// Register ActivityResultLaunchers
		pickFileLauncher = registerForActivityResult(
				new ActivityResultContracts.StartActivityForResult(),
				result -> {
					if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
						Context context = getContext();
						if (context != null) {
							String message = DatabaseImporter.importDatabase(
									context, result.getData().getData());
							Toast.makeText(context, message, Toast.LENGTH_LONG).show();
						}
					}
				});

		requestReadPermissionLauncher = registerForActivityResult(
				new ActivityResultContracts.RequestPermission(),
				isGranted -> {
					if (isGranted) {
						ImportExportAsFiles.importFromDirectory(getContext());
					} else {
						Toast.makeText(getActivity(),
								getString(R.string.read_access_required),
								Toast.LENGTH_LONG).show();
					}
				});

		requestWritePermissionLauncher = registerForActivityResult(
				new ActivityResultContracts.RequestPermission(),
				isGranted -> {
					if (isGranted) {
						ImportExportAsFiles.exportToDirectory(getContext());
					} else {
						Toast.makeText(getActivity(),
								getString(R.string.write_access_required),
								Toast.LENGTH_LONG).show();
					}
				});
	}

	@Override
	public void onCreatePreferences(Bundle state, String rootKey) {
		setPreferencesFromResource(R.xml.preferences, rootKey);
		wireImportExport();
		limitVideoFormats();
		EditTextPreference breathingRoom = findPreference(Preferences.BREATHING_ROOM);
		if (breathingRoom != null) {
			breathingRoom.setOnBindEditTextListener(editText -> {
				editText.setInputType(InputType.TYPE_CLASS_NUMBER |
						InputType.TYPE_NUMBER_FLAG_DECIMAL);
				editText.setHint(R.string.breathing_room_auto_hint);
				editText.setSelection(editText.getText().length());
			});
		}
	}

	// Lists only the video formats this phone has an encoder for.
	private void limitVideoFormats() {
		ListPreference videoFormat = findPreference(Preferences.VIDEO_FORMAT);
		if (videoFormat == null) {
			return;
		}
		CharSequence[] names = videoFormat.getEntries();
		CharSequence[] keys = videoFormat.getEntryValues();
		List<VideoFormat> supported = VideoFormat.supported();
		List<CharSequence> supportedNames = new ArrayList<>();
		List<CharSequence> supportedKeys = new ArrayList<>();
		for (int i = 0; i < keys.length; ++i) {
			if (supported.contains(VideoFormat.fromKey(keys[i].toString()))) {
				supportedNames.add(names[i]);
				supportedKeys.add(keys[i]);
			}
		}
		videoFormat.setEntries(supportedNames.toArray(new CharSequence[0]));
		videoFormat.setEntryValues(supportedKeys.toArray(new CharSequence[0]));
	}

	@Override
	public void onResume() {
		super.onResume();
		SharedPreferences preferences = getPreferenceScreen().getSharedPreferences();
		if (preferences != null) {
			preferences.registerOnSharedPreferenceChangeListener(this);
		}

		setSummaries(getPreferenceScreen());
	}

	@Override
	public void onPause() {
		super.onPause();

		SharedPreferences preferences = getPreferenceScreen().getSharedPreferences();
		if (preferences != null) {
			preferences.unregisterOnSharedPreferenceChangeListener(this);
		}
	}

	@Override
	public void onSharedPreferenceChanged(
			SharedPreferences sharedPreferences,
			String key) {
		Preference preference = findPreference(key);
		if (preference == null) {
			return;
		}

		ShaderEditorApp.preferences.update(getContext());
		setSummary(preference);

		if (Preferences.SAVE_BATTERY.equals(key) &&
				ShaderEditorApp.preferences.isBatteryLow()) {
			BatteryLevelReceiver.setLowPowerMode(
					ShaderEditorApp.preferences.saveBattery());
		}
	}

	@Override
	public void onDisplayPreferenceDialog(@NonNull Preference preference) {
		if (preference instanceof ShaderListPreference listPreference) {
			String key = listPreference.getKey();
			DialogFragment f = ShaderListPreferenceDialogFragment.newInstance(key);
			// FIXME: Periodically check whether androidx.preference still uses TargetFragment
			// noinspection deprecation
			f.setTargetFragment(this, 0);

			f.show(getParentFragmentManager(), "ShaderListPreferenceDialogFragment");

			return;
		}

		super.onDisplayPreferenceDialog(preference);
	}

	private void setSummaries(@NonNull PreferenceGroup screen) {
		for (int i = screen.getPreferenceCount(); i-- > 0; ) {
			setSummary(screen.getPreference(i));
		}
	}

	private void setSummary(Preference preference) {
		if (preference instanceof ShaderListPreference) {
			long id = Preferences.WALLPAPER_SHADER.equals(preference.getKey())
					? ShaderEditorApp.preferences.getWallpaperShader()
					: ShaderEditorApp.preferences.getDefaultNewShader();
			preference.setSummary(getShaderSummary(id));
		} else if (Preferences.LIVE_RENDER_MODE.equals(preference.getKey()) &&
				preference instanceof ListPreference listPreference) {
			preference.setSummary(getString(R.string.live_render_mode_summary, listPreference.getEntry()));
		} else if (Preferences.STATE_ROWS.equals(preference.getKey()) &&
				preference instanceof ListPreference listPreference) {
			preference.setSummary(getString(R.string.state_rows_summary, listPreference.getEntry()));
		} else if (Preferences.GPU_PRIORITY.equals(preference.getKey()) &&
				preference instanceof ListPreference listPreference) {
			preference.setSummary(getString(R.string.gpu_priority_summary, listPreference.getEntry()));
		} else if (Preferences.BREATHING_ROOM.equals(preference.getKey())) {
			// What's stored is the raw text (blank = Auto) - summarise the
			// value it actually parses to, so a typo or out-of-range number
			// shows what will really be used, not what was typed.
			float factor = ShaderEditorApp.preferences.getBreathingRoom();
			preference.setSummary(factor <= 0f
					? getString(R.string.breathing_room_summary_auto)
					: getString(R.string.breathing_room_summary_value,
							String.format(Locale.getDefault(), "%.2f", factor),
							Math.round((factor - 1f) * 100f)));
		} else if (Preferences.UPDATE_DELAY.equals(preference.getKey()) &&
				preference instanceof ListPreference listPreference) {
			// The bare "1.0s" every other ListPreference gets says nothing
			// about *what* is delayed - spell it out (see ShaderEditor's
			// updateRunnable and MainActivity's OnEditPausedListener).
			preference.setSummary(getString(R.string.update_delay_summary, listPreference.getEntry()));
		} else if (Preferences.IMAGE_FORMAT.equals(preference.getKey()) &&
				preference instanceof ListPreference listPreference) {
			preference.setSummary(listPreference.getEntry());
			Preference quality = findPreference(Preferences.IMAGE_QUALITY);
			if (quality != null) {
				quality.setEnabled(ShaderEditorApp.preferences.getImageEncoding().format().lossy);
			}
		} else if (preference instanceof ListPreference) {
			preference.setSummary(((ListPreference) preference).getEntry());
		} else if (preference instanceof PreferenceGroup) {
			setSummaries((PreferenceGroup) preference);
		}
	}

	private String getShaderSummary(long id) {
		if (id <= 0) {
			return getString(R.string.no_shader_selected);
		}
		DataRecords.Shader shader = dataSource.shader.getShader(id);
		if (shader == null) {
			return getString(R.string.no_shader_selected);
		}
		return shader.getTitle();
	}

	private boolean checkExternalStoragePermission(@NonNull String permission) {
		FragmentActivity activity = getActivity();
		if (activity != null &&
				ContextCompat.checkSelfPermission(activity, permission)
						!= PackageManager.PERMISSION_GRANTED) {

			if (permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
				requestWritePermissionLauncher.launch(permission);
			} else if (permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE)) {
				requestReadPermissionLauncher.launch(permission);
			}
			return false;
		}

		return true;
	}

	private void wireImportExport() {
		Preference importFromDirectory = findPreference(
				Preferences.IMPORT_FROM_DIRECTORY);
		Preference exportToDirectory = findPreference(
				Preferences.EXPORT_TO_DIRECTORY);
		if (importFromDirectory != null && exportToDirectory != null) {
			if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
				importFromDirectory.setOnPreferenceClickListener(preference -> {
					if (checkExternalStoragePermission(
							Manifest.permission.READ_EXTERNAL_STORAGE)) {
						ImportExportAsFiles.importFromDirectory(getContext());
					}
					return true;
				});
				exportToDirectory.setOnPreferenceClickListener(preference -> {
					if (checkExternalStoragePermission(
							Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
						ImportExportAsFiles.exportToDirectory(getContext());
					}
					return true;
				});
			} else {
				PreferenceCategory cat = findPreference("import_export");
				if (cat != null) {
					cat.removePreference(importFromDirectory);
					cat.removePreference(exportToDirectory);
				}
			}
		}
		Preference importDatabase = findPreference(Preferences.IMPORT_DATABASE);
		Preference exportDatabase = findPreference(Preferences.EXPORT_DATABASE);
		if (importDatabase != null && exportDatabase != null) {
			importDatabase.setOnPreferenceClickListener(preference -> {
				Intent chooseFile = new Intent(Intent.ACTION_GET_CONTENT);
				// In theory, it should be "application/x-sqlite3"
				// or the newer "application/vnd.sqlite3" but
				// only "application/octet-stream" works.
				chooseFile.setType("application/octet-stream");
				// Some devices don't allow selecting sqlite files with just
				// "application/octet-stream". Therefore, we also allow
				// "application/x-sqlite3" and "application/vnd.sqlite3".
				chooseFile.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
						"application/x-sqlite3",
						"application/vnd.sqlite3",
						"application/octet-stream"
				});
				pickFileLauncher.launch(
						Intent.createChooser(
								chooseFile,
								getString(R.string.import_database)));
				return true;
			});
			exportDatabase.setOnPreferenceClickListener(preference -> {
				Context context = getContext();
				if (context != null) {
					Toast.makeText(context,
							DatabaseExporter.exportDatabase(context),
							Toast.LENGTH_LONG).show();
				}
				return true;
			});
		}
		// The same picker as the quality menu's Upscaling…, its choice kept
		// as the one each session starts with.
		Preference defaultUpscale = findPreference(Preferences.DEFAULT_UPSCALE);
		if (defaultUpscale != null) {
			defaultUpscale.setSummary(UpscaleDialog.filterName(
					ShaderEditorApp.preferences.getDefaultUpscale()));
			defaultUpscale.setOnPreferenceClickListener(preference -> {
				var activity = getActivity();
				if (activity == null) {
					return true;
				}
				UpscaleDialog.show(activity, ShaderEditorApp.preferences.getDefaultUpscale(),
						new UpscaleDialog.Host() {
							@Override
							public void preview(@NonNull Upscaling.Upscale upscale) {
							}

							@Override
							public void apply(@NonNull Upscaling.Upscale upscale) {
								ShaderEditorApp.preferences.setDefaultUpscale(upscale);
								preference.setSummary(UpscaleDialog.filterName(upscale));
							}
						}, true);
				return true;
			});
		}
		Preference compileAllShaders = findPreference(Preferences.COMPILE_ALL_SHADERS);
		if (compileAllShaders != null) {
			compileAllShaders.setOnPreferenceClickListener(preference -> {
				Context context = getContext();
				if (context == null) {
					return true;
				}
				View view = LayoutInflater.from(context).inflate(
						R.layout.dialog_compile_all_shaders, null);
				CheckBox forceRecompileBox = view.findViewById(R.id.force_recompile);
				new MaterialAlertDialogBuilder(context)
						.setTitle(R.string.compile_all_shaders)
						.setView(view)
						.setPositiveButton(R.string.compile_all_shaders, (d, w) -> {
							CompileAllShadersRunner.start(
									context, dataSource, forceRecompileBox.isChecked());
							Toast.makeText(context,
									R.string.compile_all_shaders_started,
									Toast.LENGTH_SHORT).show();
						})
						.setNegativeButton(android.R.string.cancel, null)
						.show();
				return true;
			});
		}
	}
}
