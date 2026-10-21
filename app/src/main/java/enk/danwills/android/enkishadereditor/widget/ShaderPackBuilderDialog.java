package enk.danwills.android.enkishadereditor.widget;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.io.ShaderPackWriter;
import enk.danwills.android.enkishadereditor.opengl.ShaderSourcePreparer;
import enk.danwills.android.enkishadereditor.pack.PackShow;
import enk.danwills.android.enkishadereditor.shaderorg.ShaderSimilarity;

// Build Shader Pack: pick shaders (a filterable checklist, optionally
// starting from a sidebar group's - see ShaderListManager), describe the
// pack, and save it as a pack file - see ShaderPackWriter.
public final class ShaderPackBuilderDialog {
	private static final String PREFS = "shader_pack_builder";
	private static final String KEY_AUTHOR = "author";
	// The rest of the last save, put back next time - see restore().
	private static final String KEY_TITLE = "title";
	private static final String KEY_ID = "pack_id";
	private static final String KEY_DESCRIPTION = "description";
	private static final String KEY_SHADERS = "shaders";
	private static final String KEY_SHOW = "show";
	private static final String KEY_SECONDS = "seconds";
	private static final String KEY_SOUNDTRACK = "soundtrack";
	private static final String KEY_TIMELINE = "timeline";
	private static final String KEY_QUALITY = "quality";
	// The last version saved of each pack id: "version:<id>".
	private static final String KEY_VERSION_PREFIX = "version:";

	private ShaderPackBuilderDialog() {
	}

	// What the dialog asks of its activity, on the UI thread.
	public interface Host {
		// Picks a tracker module file and imports it (see MainActivity's
		// Import Tracker Module), handing back its name - or null,
		// cancelled or not a module.
		void pickModule(@NonNull Consumer<String> onImported);

		// Save & Install put a new version of packId in the library, in
		// place of `replaced` (the old version's shaders, if any).
		void packInstalled(@NonNull String packId, @NonNull Set<Long> replaced);
	}

	// An installed pack being edited: the dialog starts from it rather
	// than the last save.
	private record Preset(@NonNull String title, @NonNull String packId,
			@Nullable String description, @Nullable String author, @NonNull Set<Long> shaderIds,
			@Nullable PackShow show) {
	}

	// suggestedTitle: e.g. the sidebar group the pack is being built from.
	// picker: offers "Choose a module file..." as a soundtrack, and Save &
	// Install, if given.
	public static void show(@NonNull Activity activity, @NonNull Collection<Long> preselected,
			@Nullable String suggestedTitle, @Nullable Host picker) {
		open(activity, preselected, suggestedTitle, picker, null);
	}

	// From the menu: the open shader (if any) starts ticked, along with
	// the last save's.
	public static void show(@NonNull Activity activity, long openShaderId,
			@Nullable Host picker) {
		open(activity, openShaderId > 0 ? List.of(openShaderId) : List.of(), null, picker, null);
	}

	// Edit Pack: an installed pack's details, settings and shaders filled
	// in (its shaders ticked, others there to add), at the next version.
	public static void edit(@NonNull Activity activity, @NonNull String packId,
			@Nullable Host picker) {
		open(activity, List.of(), null, picker, packId);
	}

	private static void open(@NonNull Activity activity, @NonNull Collection<Long> preselected,
			@Nullable String suggestedTitle, @Nullable Host picker,
			@Nullable String editPackId) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			var dataSource = Database.getInstance(appContext).getDataSource();
			Preset preset = null;
			if (editPackId != null) {
				var pack = dataSource.pack.getPack(editPackId);
				if (pack == null) {
					return;
				}
				preset = new Preset(pack.title(), pack.packId(), pack.description(),
						pack.author(), new LinkedHashSet<>(dataSource.pack.getShaderIds(editPackId)),
						PackShow.parse(pack.show()));
			}
			var installedVersions = new HashMap<String, Integer>();
			for (var pack : dataSource.pack.getPacks()) {
				installedVersions.put(pack.packId(), pack.version());
			}
			Preset editing = preset;
			List<DataRecords.ShaderInfo> shaders = dataSource.shader.getShaders(false);
			var soundtracks = soundtrackChoices(activity, dataSource, shaders);
			if (picker != null) {
				soundtracks.add(new Soundtrack(activity.getString(R.string.pack_show_choose_module),
						null, null, PICK_MODULE));
			}
			activity.runOnUiThread(() -> {
				if (!activity.isFinishing() && !activity.isDestroyed()) {
					showDialog(activity, shaders, new LinkedHashSet<>(preselected),
							suggestedTitle, soundtracks, picker, editing, installedVersions);
				}
			});
		}).start();
	}

	// The soundtrack entry that picks a module file instead.
	private static final long PICK_MODULE = -1;

	// A show's soundtrack, as the soundtrack picker offers it: none (each
	// shader's own sound), a module, or a shader's Sound pass - or, last,
	// "Choose a module file..." (shaderId PICK_MODULE).
	private record Soundtrack(@NonNull String label, @Nullable String module,
			@Nullable String soundShader, long shaderId) {
		@NonNull
		@Override
		public String toString() {
			return label;
		}

		// How it's remembered between saves.
		@NonNull
		String key() {
			return shaderId == PICK_MODULE ? "pick"
					: module != null ? "module:" + module
					: soundShader != null ? "sound:" + soundShader : "";
		}
	}

	@NonNull
	private static List<Soundtrack> soundtrackChoices(@NonNull Context context,
			@NonNull DataSource dataSource, @NonNull List<DataRecords.ShaderInfo> shaders) {
		var choices = new ArrayList<Soundtrack>();
		choices.add(new Soundtrack(context.getString(R.string.pack_show_own_sound),
				null, null, 0));
		for (String module : dataSource.module.getModuleNames()) {
			choices.add(new Soundtrack(context.getString(R.string.pack_show_module, module),
					module, null, 0));
		}
		for (var shader : shaders) {
			String name = shader.name();
			if (name == null || name.isEmpty()) {
				continue;
			}
			for (String buffer : dataSource.buffer.getBufferSources(shader.id())) {
				// Only a Sound pass that stands alone: one reading its own
				// shader's buffers would read each slide's instead.
				if (ShaderSourcePreparer.isSoundSource(buffer) && !buffer.contains("///buf:")) {
					// As the pack file names it: an installed pack's shader
					// without its "<Pack Title>: ".
					int separator = name.indexOf(DataRecords.ShaderInfo.PACK_NAME_SEPARATOR);
					String showName = shader.isFromPack() && separator >= 0
							? name.substring(separator +
									DataRecords.ShaderInfo.PACK_NAME_SEPARATOR.length())
							: name;
					choices.add(new Soundtrack(context.getString(R.string.pack_show_sound_pass,
							name), null, showName, shader.id()));
					break;
				}
			}
		}
		return choices;
	}

	private static void showDialog(@NonNull Activity activity,
			@NonNull List<DataRecords.ShaderInfo> shaders, @NonNull Set<Long> checked,
			@Nullable String suggestedTitle, @NonNull List<Soundtrack> soundtracks,
			@Nullable Host picker, @Nullable Preset preset,
			@NonNull Map<String, Integer> installedVersions) {
		View view = activity.getLayoutInflater().inflate(R.layout.dialog_build_shader_pack,
				null);
		EditText titleView = view.findViewById(R.id.pack_title);
		EditText idView = view.findViewById(R.id.pack_id);
		EditText versionView = view.findViewById(R.id.pack_version);
		EditText authorView = view.findViewById(R.id.pack_author);
		EditText descriptionView = view.findViewById(R.id.pack_description);
		EditText filterView = view.findViewById(R.id.pack_filter);
		CheckBox similarBox = view.findViewById(R.id.pack_similar);
		TextView thresholdLabel = view.findViewById(R.id.pack_similar_threshold_label);
		SeekBar thresholdBar = view.findViewById(R.id.pack_similar_threshold);
		ListView listView = view.findViewById(R.id.pack_shaders);
		TextView countView = view.findViewById(R.id.pack_selected_count);
		CheckBox showBox = view.findViewById(R.id.pack_show);
		View showOptions = view.findViewById(R.id.pack_show_options);
		EditText secondsView = view.findViewById(R.id.pack_show_seconds);
		Spinner soundtrackView = view.findViewById(R.id.pack_show_soundtrack);
		EditText timelineView = view.findViewById(R.id.pack_show_timeline);
		Spinner qualityView = view.findViewById(R.id.pack_show_quality);
		// "Each shader's own" (0), then the editor's Render Quality steps.
		String[] qualityNames = activity.getResources().getStringArray(R.array.quality_names);
		String[] qualityStrings = activity.getResources().getStringArray(R.array.quality_values);
		var qualityLabels = new ArrayList<String>();
		float[] qualities = new float[qualityStrings.length + 1];
		qualityLabels.add(activity.getString(R.string.pack_show_quality_own));
		for (int i = 0; i < qualityStrings.length; ++i) {
			qualityLabels.add(qualityNames[i]);
			qualities[i + 1] = Float.parseFloat(qualityStrings[i]);
		}
		var qualityAdapter = new ArrayAdapter<>(activity,
				android.R.layout.simple_spinner_item, qualityLabels);
		qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		qualityView.setAdapter(qualityAdapter);
		var soundtrackAdapter = new ArrayAdapter<>(activity,
				android.R.layout.simple_spinner_item, soundtracks);
		soundtrackAdapter.setDropDownViewResource(
				android.R.layout.simple_spinner_dropdown_item);
		soundtrackView.setAdapter(soundtrackAdapter);
		showBox.setOnCheckedChangeListener((box, isChecked) ->
				showOptions.setVisibility(isChecked ? View.VISIBLE : View.GONE));

		SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		authorView.setText(prefs.getString(KEY_AUTHOR, ""));

		// The id follows the title until it's edited by hand, and the
		// version follows the id (one on from the last saved of it) until
		// that's edited by hand.
		boolean[] idEdited = {false};
		boolean[] settingId = {false};
		boolean[] versionEdited = {false};
		boolean[] settingVersion = {false};
		Runnable suggestVersion = () -> {
			if (!versionEdited[0]) {
				settingVersion[0] = true;
				versionView.setText(String.valueOf(nextVersion(prefs, installedVersions,
						slug(idView.getText().toString()))));
				settingVersion[0] = false;
			}
		};
		titleView.addTextChangedListener(new SimpleWatcher(() -> {
			if (!idEdited[0]) {
				settingId[0] = true;
				idView.setText(slug(titleView.getText().toString()));
				settingId[0] = false;
			}
		}));
		idView.addTextChangedListener(new SimpleWatcher(() -> {
			if (!settingId[0]) {
				idEdited[0] = true;
			}
			suggestVersion.run();
		}));
		versionView.addTextChangedListener(new SimpleWatcher(() -> {
			if (!settingVersion[0]) {
				versionEdited[0] = true;
			}
		}));
		suggestVersion.run();

		// The last save comes back, unless this is from a sidebar group
		// that isn't that pack: then only the show's settings do.
		String lastId = prefs.getString(KEY_ID, null);
		boolean fromGroup = suggestedTitle != null && !suggestedTitle.trim().isEmpty();
		boolean samePack = lastId != null &&
				(!fromGroup || lastId.equals(slug(suggestedTitle)));
		if (samePack) {
			titleView.setText(prefs.getString(KEY_TITLE, ""));
			if (!lastId.equals(slug(titleView.getText().toString()))) {
				idView.setText(lastId);
			}
			descriptionView.setText(prefs.getString(KEY_DESCRIPTION, ""));
		} else if (fromGroup) {
			titleView.setText(suggestedTitle.trim());
		}
		if (!fromGroup) {
			Set<Long> ids = new HashSet<>();
			for (var shader : shaders) {
				ids.add(shader.id());
			}
			for (String id : prefs.getStringSet(KEY_SHADERS, Set.of())) {
				try {
					long shaderId = Long.parseLong(id);
					// Only ones still in the library.
					if (ids.contains(shaderId)) {
						checked.add(shaderId);
					}
				} catch (NumberFormatException e) {
					// Skip it.
				}
			}
		}
		showBox.setChecked(prefs.getBoolean(KEY_SHOW, false));
		secondsView.setText(String.valueOf(prefs.getInt(KEY_SECONDS, PackShow.DEFAULT_SECONDS)));
		String soundtrackKey = prefs.getString(KEY_SOUNDTRACK, "");
		for (int i = 0; i < soundtracks.size(); ++i) {
			if (soundtracks.get(i).key().equals(soundtrackKey)) {
				soundtrackView.setSelection(i);
				break;
			}
		}
		timelineView.setText(prefs.getString(KEY_TIMELINE, ""));
		selectQuality(qualityView, qualities, prefs.getFloat(KEY_QUALITY, 0));
		if (preset != null) {
			applyPreset(preset, checked, titleView, idView, descriptionView, authorView, showBox,
					secondsView, soundtrackView, soundtracks, timelineView, qualityView, qualities);
		}

		// "Choose a module file...": import one, then it's the soundtrack -
		// or back to what was chosen before.
		int[] lastSoundtrack = {soundtrackView.getSelectedItemPosition()};
		soundtrackView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
				var chosen = soundtrackAdapter.getItem(position);
				if (chosen == null || chosen.shaderId() != PICK_MODULE || picker == null) {
					lastSoundtrack[0] = position;
					return;
				}
				soundtrackView.setSelection(lastSoundtrack[0]);
				picker.pickModule(name -> {
					if (name == null) {
						return;
					}
					var imported = new Soundtrack(
							activity.getString(R.string.pack_show_module, name), name, null, 0);
					int at = soundtrackAdapter.getPosition(chosen);
					soundtrackAdapter.insert(imported, at);
					soundtrackView.setSelection(at);
					lastSoundtrack[0] = at;
				});
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {
			}
		});

		// Ticked shaders first, so a group's pre-ticked ones are in view.
		var ordered = new ArrayList<DataRecords.ShaderInfo>(shaders.size());
		for (var shader : shaders) {
			if (checked.contains(shader.id())) {
				ordered.add(shader);
			}
		}
		for (var shader : shaders) {
			if (!checked.contains(shader.id())) {
				ordered.add(shader);
			}
		}
		var adapter = new ShaderChecklistAdapter(ordered, checked);
		listView.setAdapter(adapter);
		Runnable updateCount = () -> countView.setText(activity.getResources()
				.getQuantityString(R.plurals.pack_selected_count, checked.size(),
						checked.size()));
		updateCount.run();
		listView.setOnItemClickListener((parent, row, position, id) -> {
			long shaderId = adapter.getItemId(position);
			if (!checked.remove(shaderId)) {
				checked.add(shaderId);
			}
			adapter.notifyDataSetChanged();
			updateCount.run();
		});
		filterView.addTextChangedListener(new SimpleWatcher(() ->
				adapter.setFilter(filterView.getText().toString())));

		// Similar to ticked: the shaders like any ticked one, best first,
		// scored when it's turned on or the threshold moves - not on every
		// tick, so rows don't jump about while ticking.
		var similarity = new ShaderSimilarity();
		boolean[] indexed = {false};
		thresholdBar.setProgress((int) Math.round(ShaderSimilarity.DEFAULT_THRESHOLD * 100));
		Runnable rescore = () -> {
			if (!similarBox.isChecked()) {
				adapter.setSimilarity(null, 0);
				return;
			}
			double threshold = thresholdBar.getProgress() / 100.0;
			thresholdLabel.setText(activity.getString(R.string.similar_shaders_threshold_label,
					thresholdBar.getProgress()));
			var tickedIds = new ArrayList<>(checked);
			Context appContext = activity.getApplicationContext();
			new Thread(() -> {
				synchronized (similarity) {
					if (!indexed[0]) {
						similarity.rebuild(shaders, Database.getInstance(appContext)
								.getDataSource().shader.getAllShaderSources());
						indexed[0] = true;
					}
				}
				var best = new HashMap<Long, Double>();
				for (long ticked : tickedIds) {
					for (var entry : similarity.scoreAll(ticked, shaders).entrySet()) {
						best.merge(entry.getKey(), entry.getValue(), Math::max);
					}
				}
				activity.runOnUiThread(() -> {
					if (similarBox.isChecked()) {
						adapter.setSimilarity(best, threshold);
					}
				});
			}).start();
		};
		similarBox.setOnCheckedChangeListener((box, isChecked) -> {
			thresholdLabel.setVisibility(isChecked ? View.VISIBLE : View.GONE);
			thresholdBar.setVisibility(isChecked ? View.VISIBLE : View.GONE);
			rescore.run();
		});
		thresholdBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
				thresholdLabel.setText(activity.getString(
						R.string.similar_shaders_threshold_label, progress));
			}

			@Override
			public void onStartTrackingTouch(SeekBar bar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar bar) {
				rescore.run();
			}
		});

		var builder = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.build_shader_pack)
				.setView(view)
				.setPositiveButton(R.string.save_shader_pack, null)
				.setNegativeButton(android.R.string.cancel, null);
		if (picker != null) {
			builder.setNeutralButton(R.string.save_and_install_shader_pack, null);
		}
		AlertDialog dialog = builder.show();
		// Validated here so a missing title/selection keeps the dialog open.
		// install: Save & Install.
		Consumer<Boolean> save = install -> {
			String title = titleView.getText().toString().trim();
			String packId = slug(idView.getText().toString());
			if (title.isEmpty() || packId.isEmpty()) {
				titleView.setError(activity.getString(R.string.pack_needs_title));
				return;
			}
			if (checked.isEmpty()) {
				Toast.makeText(activity, R.string.pack_needs_shaders, Toast.LENGTH_SHORT).show();
				return;
			}
			int version;
			try {
				version = Math.max(1, Integer.parseInt(versionView.getText().toString().trim()));
			} catch (NumberFormatException e) {
				version = 1;
			}
			String show = null;
			var soundtrack = (Soundtrack) soundtrackView.getSelectedItem();
			int seconds;
			try {
				seconds = Integer.parseInt(secondsView.getText().toString().trim());
			} catch (NumberFormatException e) {
				seconds = PackShow.DEFAULT_SECONDS;
			}
			if (showBox.isChecked()) {
				String timelineText = timelineView.getText().toString().trim();
				PackShow timeline = timelineText.isEmpty() ? null : PackShow.parse(timelineText);
				if (!timelineText.isEmpty() && timeline == null) {
					timelineView.setError(activity.getString(R.string.pack_show_bad_timeline));
					return;
				}
				if (soundtrack != null && soundtrack.shaderId() == PICK_MODULE) {
					soundtrack = null;
				}
				if (soundtrack != null && soundtrack.shaderId() > 0) {
					// The soundtrack's shader has to be in the pack.
					checked.add(soundtrack.shaderId());
				}
				show = new PackShow(Math.max(PackShow.MIN_SECONDS, seconds),
						soundtrack != null ? soundtrack.module() : null,
						soundtrack != null ? soundtrack.soundShader() : null,
						timeline != null ? timeline.scenes() : List.of(),
						qualities[Math.max(0, qualityView.getSelectedItemPosition())]).toJson();
			}
			String author = authorView.getText().toString().trim();
			String description = descriptionView.getText().toString().trim();
			var shaderIds = new HashSet<String>();
			for (long id : checked) {
				shaderIds.add(String.valueOf(id));
			}
			prefs.edit()
					.putString(KEY_AUTHOR, author)
					.putString(KEY_TITLE, title)
					.putString(KEY_ID, packId)
					.putString(KEY_DESCRIPTION, description)
					.putStringSet(KEY_SHADERS, shaderIds)
					.putBoolean(KEY_SHOW, showBox.isChecked())
					.putInt(KEY_SECONDS, Math.max(PackShow.MIN_SECONDS, seconds))
					.putString(KEY_SOUNDTRACK, soundtrack != null ? soundtrack.key() : "")
					.putString(KEY_TIMELINE, timelineView.getText().toString().trim())
					.putFloat(KEY_QUALITY,
							qualities[Math.max(0, qualityView.getSelectedItemPosition())])
					.putInt(KEY_VERSION_PREFIX + packId, version)
					.apply();
			var info = new DataRecords.ShaderPack(packId, title, description,
					author.isEmpty() ? null : author, version, null, null, show);
			List<Long> ids = adapter.orderedIds(checked);
			dialog.dismiss();
			Context appContext = activity.getApplicationContext();
			new Thread(() -> {
				Set<Long> replaced = install
						? new HashSet<>(Database.getInstance(appContext).getDataSource()
								.pack.getShaderIds(packId))
						: Set.of();
				var result = ShaderPackWriter.write(appContext, ids, info, install);
				activity.runOnUiThread(() -> {
					Toast.makeText(appContext, result.message(), Toast.LENGTH_LONG).show();
					if (result.installed() && picker != null) {
						picker.packInstalled(packId, replaced);
					}
				});
			}).start();
		};
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> save.accept(false));
		if (picker != null) {
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
					v -> save.accept(true));
		}
	}

	private static void applyPreset(@NonNull Preset preset, @NonNull Set<Long> checked,
			@NonNull EditText titleView, @NonNull EditText idView,
			@NonNull EditText descriptionView, @NonNull EditText authorView,
			@NonNull CheckBox showBox, @NonNull EditText secondsView,
			@NonNull Spinner soundtrackView, @NonNull List<Soundtrack> soundtracks,
			@NonNull EditText timelineView, @NonNull Spinner qualityView,
			@NonNull float[] qualities) {
		checked.clear();
		checked.addAll(preset.shaderIds());
		titleView.setText(preset.title());
		idView.setText(preset.packId());
		descriptionView.setText(preset.description() != null ? preset.description() : "");
		if (preset.author() != null) {
			authorView.setText(preset.author());
		}
		PackShow show = preset.show();
		showBox.setChecked(show != null);
		if (show == null) {
			return;
		}
		secondsView.setText(String.valueOf(show.seconds()));
		int soundtrack = 0;
		for (int i = 0; i < soundtracks.size(); ++i) {
			var choice = soundtracks.get(i);
			if ((show.module() != null && show.module().equals(choice.module())) ||
					(show.soundShader() != null && choice.soundShader() != null &&
							show.soundShader().equalsIgnoreCase(choice.soundShader()))) {
				soundtrack = i;
				break;
			}
		}
		soundtrackView.setSelection(soundtrack);
		timelineView.setText(show.scenes().isEmpty() ? ""
				: new PackShow(show.seconds(), null, null, show.scenes(), 0).toJson());
		selectQuality(qualityView, qualities, show.quality());
	}

	private static void selectQuality(@NonNull Spinner view, @NonNull float[] qualities,
			float quality) {
		for (int i = 0; i < qualities.length; ++i) {
			if (qualities[i] == quality) {
				view.setSelection(i);
				return;
			}
		}
	}

	// One on from the last version of this pack id saved or installed,
	// else 1.
	private static int nextVersion(@NonNull SharedPreferences prefs,
			@NonNull Map<String, Integer> installedVersions, @NonNull String packId) {
		if (packId.isEmpty()) {
			return 1;
		}
		Integer installed = installedVersions.get(packId);
		return Math.max(prefs.getInt(KEY_VERSION_PREFIX + packId, 0),
				installed != null ? installed : 0) + 1;
	}

	// A pack id: lowercase letters, digits and single dashes.
	@NonNull
	static String slug(@NonNull String text) {
		return text.toLowerCase(Locale.ROOT)
				.replaceAll("[^a-z0-9]+", "-")
				.replaceAll("^-+|-+$", "");
	}

	private static final class ShaderChecklistAdapter extends BaseAdapter {
		@NonNull
		private final List<DataRecords.ShaderInfo> all;
		@NonNull
		private final Set<Long> checked;
		@NonNull
		private List<DataRecords.ShaderInfo> shown;

		ShaderChecklistAdapter(@NonNull List<DataRecords.ShaderInfo> shaders,
				@NonNull Set<Long> checked) {
			all = shaders;
			shown = shaders;
			this.checked = checked;
		}

		@NonNull
		private String filter = "";
		// Similar to ticked (see showDialog()): each shader's best score
		// against a ticked one, or null for off.
		@Nullable
		private Map<Long, Double> scores;
		private double threshold;

		void setFilter(@NonNull String filter) {
			this.filter = filter.trim().toLowerCase(Locale.ROOT);
			refresh();
		}

		void setSimilarity(@Nullable Map<Long, Double> scores, double threshold) {
			this.scores = scores;
			this.threshold = threshold;
			refresh();
		}

		private void refresh() {
			var matching = new ArrayList<DataRecords.ShaderInfo>();
			for (var shader : all) {
				if (!filter.isEmpty() &&
						!title(shader).toLowerCase(Locale.ROOT).contains(filter)) {
					continue;
				}
				if (scores != null && !checked.contains(shader.id()) &&
						score(shader) < threshold) {
					continue;
				}
				matching.add(shader);
			}
			if (scores != null) {
				// Ticked first, then the closest matches.
				matching.sort((a, b) -> Double.compare(score(b), score(a)));
			}
			shown = matching;
			notifyDataSetChanged();
		}

		private double score(@NonNull DataRecords.ShaderInfo shader) {
			if (checked.contains(shader.id())) {
				return 2;
			}
			Double score = scores != null ? scores.get(shader.id()) : null;
			return score != null ? score : 0;
		}

		// The chosen ids in list (title) order, whatever the filter shows.
		@NonNull
		List<Long> orderedIds(@NonNull Set<Long> chosen) {
			var ids = new ArrayList<Long>();
			for (var shader : all) {
				if (chosen.contains(shader.id())) {
					ids.add(shader.id());
				}
			}
			return ids;
		}

		@Override
		public int getCount() {
			return shown.size();
		}

		@Override
		public DataRecords.ShaderInfo getItem(int position) {
			return shown.get(position);
		}

		@Override
		public long getItemId(int position) {
			return shown.get(position).id();
		}

		@Override
		public View getView(int position, View convertView, ViewGroup parent) {
			CheckedTextView row = convertView instanceof CheckedTextView reuse
					? reuse
					: (CheckedTextView) View.inflate(parent.getContext(),
							android.R.layout.simple_list_item_multiple_choice, null);
			var shader = getItem(position);
			row.setText(title(shader));
			row.setChecked(checked.contains(shader.id()));
			return row;
		}

		@NonNull
		private static String title(@NonNull DataRecords.ShaderInfo shader) {
			String title = shader.getTitle();
			return title != null ? title : "";
		}
	}

	private record SimpleWatcher(@NonNull Runnable onChanged) implements TextWatcher {
		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
		}

		@Override
		public void afterTextChanged(Editable s) {
			onChanged.run();
		}
	}
}
