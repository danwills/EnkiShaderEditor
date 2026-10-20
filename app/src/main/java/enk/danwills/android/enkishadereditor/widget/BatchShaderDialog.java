package enk.danwills.android.enkishadereditor.widget;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.database.dao.ShaderDao;

// Batch Rename & Delete: tick shaders - by a name prefix, so everything
// under one sidebar group, or just the unnamed ones - and rename them to
// one numbered base name, or delete them. Also applies a rename list (a
// JSON file of renames and deletes, matched by id and creation time - see
// applyRenameList()). None of it touches a shader's dates.
public final class BatchShaderDialog {
	// Most shaders listed in a delete confirmation before "and N more".
	private static final int LISTED_IN_CONFIRMATION = 8;

	private BatchShaderDialog() {
	}

	public interface Host {
		// After renaming or deleting: refresh the list, and cope with the
		// open shader being one of them.
		void onShadersChanged(@NonNull Set<Long> renamed, @NonNull Set<Long> deleted);

		// Lets the user pick a rename list - see applyRenameList().
		void pickRenameList();
	}

	public static void show(@NonNull Activity activity, @NonNull Host host) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			List<DataRecords.ShaderInfo> shaders = Database.getInstance(appContext)
					.getDataSource().shader.getShaders(false);
			activity.runOnUiThread(() -> {
				if (!activity.isFinishing() && !activity.isDestroyed()) {
					showDialog(activity, host, shaders);
				}
			});
		}).start();
	}

	private static void showDialog(@NonNull Activity activity, @NonNull Host host,
			@NonNull List<DataRecords.ShaderInfo> shaders) {
		View view = activity.getLayoutInflater().inflate(R.layout.dialog_batch_shaders, null);
		EditText filterView = view.findViewById(R.id.batch_filter);
		CheckBox unnamedOnly = view.findViewById(R.id.batch_unnamed_only);
		CheckBox selectAll = view.findViewById(R.id.batch_select_all);
		TextView countView = view.findViewById(R.id.batch_count);
		ListView listView = view.findViewById(R.id.batch_shaders);

		// Oldest first, so numbering follows creation order.
		var ordered = new ArrayList<>(shaders);
		Collections.sort(ordered, (a, b) -> Long.compare(a.id(), b.id()));
		var checked = new HashSet<Long>();
		var adapter = new Adapter(ordered, checked);
		listView.setAdapter(adapter);

		Runnable updateCount = () -> countView.setText(activity.getString(
				R.string.batch_count, checked.size(), adapter.getCount()));
		Runnable refilter = () -> {
			adapter.setFilter(filterView.getText().toString(), unnamedOnly.isChecked());
			// Only shown shaders stay ticked, so nothing hidden by the
			// filter gets renamed or deleted by surprise.
			checked.retainAll(adapter.shownIds());
			selectAll.setChecked(!checked.isEmpty() && checked.size() == adapter.getCount());
			updateCount.run();
		};
		listView.setOnItemClickListener((parent, row, position, id) -> {
			long shaderId = adapter.getItemId(position);
			if (!checked.remove(shaderId)) {
				checked.add(shaderId);
			}
			adapter.notifyDataSetChanged();
			updateCount.run();
		});
		filterView.addTextChangedListener(new SimpleWatcher(refilter));
		unnamedOnly.setOnCheckedChangeListener((b, isChecked) -> refilter.run());
		selectAll.setOnClickListener(v -> {
			checked.clear();
			if (selectAll.isChecked()) {
				checked.addAll(adapter.shownIds());
			}
			adapter.notifyDataSetChanged();
			updateCount.run();
		});
		updateCount.run();

		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.batch_shaders)
				.setView(view)
				.setPositiveButton(R.string.batch_done, null)
				.show();

		view.findViewById(R.id.batch_rename).setOnClickListener(v -> {
			List<DataRecords.ShaderInfo> chosen = adapter.chosen();
			if (chosen.isEmpty()) {
				Toast.makeText(activity, R.string.batch_none_ticked, Toast.LENGTH_SHORT).show();
				return;
			}
			askBaseName(activity, chosen, filterView.getText().toString().trim(), base -> {
				dialog.dismiss();
				renameAll(activity, host, chosen, base);
			});
		});
		view.findViewById(R.id.batch_delete).setOnClickListener(v -> {
			List<DataRecords.ShaderInfo> chosen = adapter.chosen();
			if (chosen.isEmpty()) {
				Toast.makeText(activity, R.string.batch_none_ticked, Toast.LENGTH_SHORT).show();
				return;
			}
			var titles = new ArrayList<String>();
			var ids = new HashSet<Long>();
			for (var shader : chosen) {
				titles.add(shader.getTitle());
				ids.add(shader.id());
			}
			confirmDelete(activity, titles, () -> {
				dialog.dismiss();
				deleteAll(activity, host, ids);
			});
		});
		view.findViewById(R.id.batch_apply_list).setOnClickListener(v -> {
			dialog.dismiss();
			host.pickRenameList();
		});
	}

	public interface OnName {
		void onName(@NonNull String name);
	}

	private static void askBaseName(@NonNull Activity activity,
			@NonNull List<DataRecords.ShaderInfo> chosen, @NonNull String suggestion,
			@NonNull OnName onName) {
		var input = new EditText(activity);
		input.setSingleLine(true);
		input.setText(suggestion);
		input.setSelection(input.getText().length());
		int padding = Math.round(20 * activity.getResources().getDisplayMetrics().density);
		var frame = new android.widget.FrameLayout(activity);
		frame.setPadding(padding, 0, padding, 0);
		frame.addView(input);
		new MaterialAlertDialogBuilder(activity)
				.setTitle(activity.getResources().getQuantityString(
						R.plurals.batch_rename_title, chosen.size(), chosen.size()))
				.setMessage(chosen.size() > 1
						? R.string.batch_rename_numbered
						: R.string.batch_rename_single)
				.setView(frame)
				.setPositiveButton(R.string.batch_rename, (d, w) -> {
					String base = input.getText().toString().trim();
					if (!base.isEmpty()) {
						onName.onName(base);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// "Base V01", "Base V02"... in creation order - or just "Base" for one.
	private static void renameAll(@NonNull Activity activity, @NonNull Host host,
			@NonNull List<DataRecords.ShaderInfo> chosen, @NonNull String base) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			ShaderDao dao = Database.getInstance(appContext).getDataSource().shader;
			int digits = Math.max(2, String.valueOf(chosen.size()).length());
			var renamed = new HashSet<Long>();
			for (int i = 0; i < chosen.size(); ++i) {
				String name = chosen.size() == 1
						? base
						: String.format(Locale.US, "%s V%0" + digits + "d", base, i + 1);
				dao.updateShaderName(chosen.get(i).id(), name);
				renamed.add(chosen.get(i).id());
			}
			activity.runOnUiThread(() -> {
				Toast.makeText(appContext, activity.getResources().getQuantityString(
						R.plurals.batch_renamed, renamed.size(), renamed.size()),
						Toast.LENGTH_SHORT).show();
				host.onShadersChanged(renamed, Collections.emptySet());
			});
		}).start();
	}

	private static void deleteAll(@NonNull Activity activity, @NonNull Host host,
			@NonNull Set<Long> ids) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			ShaderDao dao = Database.getInstance(appContext).getDataSource().shader;
			for (long id : ids) {
				dao.removeShader(id);
			}
			activity.runOnUiThread(() -> {
				Toast.makeText(appContext, activity.getResources().getQuantityString(
						R.plurals.batch_deleted, ids.size(), ids.size()), Toast.LENGTH_SHORT).show();
				host.onShadersChanged(Collections.emptySet(), ids);
			});
		}).start();
	}

	// The loud one: a warning icon, the count in the title and on the
	// button, and the shaders listed - deleting can't be undone.
	private static void confirmDelete(@NonNull Activity activity, @NonNull List<String> titles,
			@NonNull Runnable onConfirmed) {
		var message = new StringBuilder(activity.getString(R.string.batch_delete_warning));
		message.append("\n");
		for (int i = 0; i < titles.size() && i < LISTED_IN_CONFIRMATION; ++i) {
			message.append("\n• ").append(titles.get(i));
		}
		if (titles.size() > LISTED_IN_CONFIRMATION) {
			message.append("\n").append(activity.getString(R.string.batch_and_more,
					titles.size() - LISTED_IN_CONFIRMATION));
		}
		new MaterialAlertDialogBuilder(activity)
				.setIcon(R.drawable.ic_warning)
				.setTitle(activity.getResources().getQuantityString(
						R.plurals.batch_delete_title, titles.size(), titles.size()))
				.setMessage(message)
				.setPositiveButton(activity.getResources().getQuantityString(
						R.plurals.batch_delete_confirm, titles.size(), titles.size()),
						(d, w) -> onConfirmed.run())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// A rename list: {"format": 1, "rename": [{"id", "created", "name"}],
	// "delete": [{"id", "created"}]} - made from an exported copy of this
	// library, so each entry names the shader by id and creation time and
	// is skipped if either doesn't match. Deletes ask the loud
	// confirmation first.
	public static void applyRenameList(@NonNull Activity activity, @NonNull Host host,
			@NonNull Uri uri) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			RenameList list;
			try {
				list = RenameList.read(appContext, uri);
			} catch (IOException | JSONException | SecurityException e) {
				activity.runOnUiThread(() -> explain(activity,
						activity.getString(R.string.batch_list_unreadable)));
				return;
			}
			activity.runOnUiThread(() -> {
				Runnable apply = () -> applyList(activity, host, list);
				if (list.deletes.isEmpty()) {
					new MaterialAlertDialogBuilder(activity)
							.setTitle(R.string.batch_apply_list)
							.setMessage(activity.getResources().getQuantityString(
									R.plurals.batch_list_summary, list.renames.size(),
									list.renames.size()))
							.setPositiveButton(R.string.batch_apply, (d, w) -> apply.run())
							.setNegativeButton(android.R.string.cancel, null)
							.show();
				} else {
					var titles = new ArrayList<String>();
					for (var entry : list.deletes) {
						titles.add(entry.created);
					}
					confirmDelete(activity, titles, apply);
				}
			});
		}).start();
	}

	private static void applyList(@NonNull Activity activity, @NonNull Host host,
			@NonNull RenameList list) {
		Context appContext = activity.getApplicationContext();
		new Thread(() -> {
			ShaderDao dao = Database.getInstance(appContext).getDataSource().shader;
			var renamed = new HashSet<Long>();
			var deleted = new HashSet<Long>();
			int skipped = 0;
			for (var entry : list.renames) {
				if (dao.renameShaderIfCreated(entry.id, entry.created, entry.name)) {
					renamed.add(entry.id);
				} else {
					++skipped;
				}
			}
			for (var entry : list.deletes) {
				if (dao.removeShaderIfCreated(entry.id, entry.created)) {
					deleted.add(entry.id);
				} else {
					++skipped;
				}
			}
			int skippedCount = skipped;
			activity.runOnUiThread(() -> {
				explain(activity, activity.getString(R.string.batch_list_applied,
						renamed.size(), deleted.size(), skippedCount));
				host.onShadersChanged(renamed, deleted);
			});
		}).start();
	}

	private static void explain(@NonNull Activity activity, @NonNull String message) {
		if (!activity.isFinishing() && !activity.isDestroyed()) {
			new MaterialAlertDialogBuilder(activity)
					.setMessage(message)
					.setPositiveButton(android.R.string.ok, null)
					.show();
		}
	}

	private record Entry(long id, @NonNull String created, @Nullable String name) {
	}

	private static final class RenameList {
		final List<Entry> renames = new ArrayList<>();
		final List<Entry> deletes = new ArrayList<>();

		@NonNull
		static RenameList read(@NonNull Context context, @NonNull Uri uri)
				throws IOException, JSONException {
			String text;
			try (InputStream in = context.getContentResolver().openInputStream(uri)) {
				if (in == null) {
					throw new IOException("no stream");
				}
				var out = new ByteArrayOutputStream();
				byte[] buffer = new byte[16 * 1024];
				int n;
				while ((n = in.read(buffer)) > 0) {
					out.write(buffer, 0, n);
				}
				text = out.toString(StandardCharsets.UTF_8.name());
			}
			var json = new JSONObject(text);
			var list = new RenameList();
			JSONArray renames = json.optJSONArray("rename");
			for (int i = 0; renames != null && i < renames.length(); ++i) {
				JSONObject o = renames.getJSONObject(i);
				String name = o.getString("name").trim();
				if (!name.isEmpty()) {
					list.renames.add(new Entry(o.getLong("id"), o.getString("created"), name));
				}
			}
			JSONArray deletes = json.optJSONArray("delete");
			for (int i = 0; deletes != null && i < deletes.length(); ++i) {
				JSONObject o = deletes.getJSONObject(i);
				list.deletes.add(new Entry(o.getLong("id"), o.getString("created"), null));
			}
			if (list.renames.isEmpty() && list.deletes.isEmpty()) {
				throw new JSONException("empty list");
			}
			return list;
		}
	}

	private static final class Adapter extends BaseAdapter {
		@NonNull
		private final List<DataRecords.ShaderInfo> all;
		@NonNull
		private final Set<Long> checked;
		@NonNull
		private List<DataRecords.ShaderInfo> shown;

		Adapter(@NonNull List<DataRecords.ShaderInfo> shaders, @NonNull Set<Long> checked) {
			all = shaders;
			shown = shaders;
			this.checked = checked;
		}

		// prefix: names starting with it (case-insensitive) - so a sidebar
		// group's name picks everything under it.
		void setFilter(@NonNull String prefix, boolean unnamedOnly) {
			String needle = prefix.trim().toLowerCase(Locale.ROOT);
			shown = new ArrayList<>();
			for (var shader : all) {
				String name = shader.name();
				boolean unnamed = name == null || name.trim().isEmpty();
				if (unnamedOnly && !unnamed) {
					continue;
				}
				if (!needle.isEmpty() && !shader.getTitle().toLowerCase(Locale.ROOT)
						.startsWith(needle)) {
					continue;
				}
				shown.add(shader);
			}
			notifyDataSetChanged();
		}

		@NonNull
		Set<Long> shownIds() {
			var ids = new HashSet<Long>();
			for (var shader : shown) {
				ids.add(shader.id());
			}
			return ids;
		}

		// The ticked shaders, oldest first.
		@NonNull
		List<DataRecords.ShaderInfo> chosen() {
			var list = new ArrayList<DataRecords.ShaderInfo>();
			for (var shader : all) {
				if (checked.contains(shader.id())) {
					list.add(shader);
				}
			}
			return list;
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
			row.setText(shader.getTitle());
			row.setChecked(checked.contains(shader.id()));
			return row;
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
