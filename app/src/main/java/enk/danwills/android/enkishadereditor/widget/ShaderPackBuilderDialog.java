package enk.danwills.android.enkishadereditor.widget;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.io.ShaderPackWriter;

// Build Shader Pack: pick shaders (a filterable checklist, optionally
// starting from a sidebar group's - see ShaderListManager), describe the
// pack, and save it as a pack file - see ShaderPackWriter.
public final class ShaderPackBuilderDialog {
	private static final String PREFS = "shader_pack_builder";
	private static final String KEY_AUTHOR = "author";

	private ShaderPackBuilderDialog() {
	}

	public static void show(@NonNull Activity activity, @NonNull Collection<Long> preselected) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			List<DataRecords.ShaderInfo> shaders = Database.getInstance(appContext)
					.getDataSource().shader.getShaders(false);
			activity.runOnUiThread(() -> {
				if (!activity.isFinishing() && !activity.isDestroyed()) {
					showDialog(activity, shaders, new LinkedHashSet<>(preselected));
				}
			});
		}).start();
	}

	private static void showDialog(@NonNull Activity activity,
			@NonNull List<DataRecords.ShaderInfo> shaders, @NonNull Set<Long> checked) {
		View view = activity.getLayoutInflater().inflate(R.layout.dialog_build_shader_pack,
				null);
		EditText titleView = view.findViewById(R.id.pack_title);
		EditText idView = view.findViewById(R.id.pack_id);
		EditText versionView = view.findViewById(R.id.pack_version);
		EditText authorView = view.findViewById(R.id.pack_author);
		EditText descriptionView = view.findViewById(R.id.pack_description);
		EditText filterView = view.findViewById(R.id.pack_filter);
		ListView listView = view.findViewById(R.id.pack_shaders);
		TextView countView = view.findViewById(R.id.pack_selected_count);

		SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		authorView.setText(prefs.getString(KEY_AUTHOR, ""));
		versionView.setText("1");

		// The id follows the title until it's edited by hand.
		boolean[] idEdited = {false};
		boolean[] settingId = {false};
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
		}));

		var adapter = new ShaderChecklistAdapter(shaders, checked);
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

		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.build_shader_pack)
				.setView(view)
				.setPositiveButton(R.string.save_shader_pack, null)
				.setNegativeButton(android.R.string.cancel, null)
				.show();
		// Validated here so a missing title/selection keeps the dialog open.
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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
			String author = authorView.getText().toString().trim();
			prefs.edit().putString(KEY_AUTHOR, author).apply();
			var info = new DataRecords.ShaderPack(packId, title,
					descriptionView.getText().toString().trim(),
					author.isEmpty() ? null : author, version, null, null);
			List<Long> ids = adapter.orderedIds(checked);
			dialog.dismiss();
			Context appContext = activity.getApplicationContext();
			new Thread(() -> {
				var result = ShaderPackWriter.write(appContext, ids, info);
				activity.runOnUiThread(() -> Toast.makeText(appContext, result.message(),
						Toast.LENGTH_LONG).show());
			}).start();
		});
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

		void setFilter(@NonNull String filter) {
			String needle = filter.trim().toLowerCase(Locale.ROOT);
			if (needle.isEmpty()) {
				shown = all;
			} else {
				shown = new ArrayList<>();
				for (var shader : all) {
					if (title(shader).toLowerCase(Locale.ROOT).contains(needle)) {
						shown.add(shader);
					}
				}
			}
			notifyDataSetChanged();
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
