package enk.danwills.android.enkishadereditor.activity.managers;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.adapter.ShaderAdapter;
import enk.danwills.android.enkishadereditor.adapter.ShaderTreeAdapter;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.database.DataRecords.ShaderInfo;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.shaderorg.ShaderSimilarity;
import enk.danwills.android.enkishadereditor.view.SoftKeyboard;

public class ShaderListManager {
	public interface Listener {
		void onShaderSelected(long id);

		void onShaderRenamed(long id, @NonNull String name);

		void onAllShadersDeleted();

		void onShadersLoaded(@NonNull List<ShaderInfo> shaders);

		void onAddShaderToNameGroup();

		// Like onAddShaderToNameGroup(), but for a specific shader rather
		// than whichever one is currently open - triggered from the
		// long-press rename dialog's "Reparent" button, which may target
		// a shader other than the selected one.
		void onReparentShader(long id);

		void onConfigureSimilarShaders();

		void onFindShader();
	}

	private final Activity activity;
	private final DataSource dataSource;
	private final Listener listener;
	private final ListView listView;
	private final ShaderAdapter shaderAdapter;
	private final ShaderTreeAdapter shaderTreeAdapter;
	private final ShaderSimilarity similarityIndex = new ShaderSimilarity();
	private final Random random = new Random();

	@Nullable
	private final Button treeToggleButton;
	@Nullable
	private final Button showSimilarButton;
	@Nullable
	private final ColorStateList treeToggleDefaultTint;
	@Nullable
	private final ColorStateList showSimilarDefaultTint;

	private List<ShaderInfo> lastLoadedShaders = new ArrayList<>();
	private boolean treeMode = false;
	private boolean similarFilterMode = false;
	private long selectedShaderId = 0;
	private long similarityReferenceId = 0;
	private double similarityThreshold = ShaderSimilarity.DEFAULT_THRESHOLD;
	private boolean textFilterEnabled = false;
	@NonNull
	private String textFilterQuery = "";

	// Use an ExecutorService for background tasks and a Handler for the main thread.
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CoalescingReloadGate reloadGate = new CoalescingReloadGate();

	public ShaderListManager(@NonNull Activity activity,
			@NonNull ListView listView,
			@NonNull DataSource dataSource,
			@NonNull Listener listener) {
		this.activity = activity;
		this.dataSource = dataSource;
		this.listener = listener;
		this.listView = listView;

		shaderAdapter = new ShaderAdapter(activity);
		shaderTreeAdapter = new ShaderTreeAdapter(activity);
		listView.setAdapter(shaderAdapter);

		listView.setEmptyView(activity.findViewById(R.id.no_shaders));
		listView.setOnItemClickListener((parent, view, position, id) -> {
			if (treeMode) {
				var row = shaderTreeAdapter.getRow(position);
				if (row.isSelectable()) {
					listener.onShaderSelected(row.node().shader.id());
				} else {
					shaderTreeAdapter.toggleExpand(position);
				}
			} else {
				listener.onShaderSelected(id);
			}
		});
		listView.setOnItemLongClickListener((parent, view, position, id) -> {
			if (treeMode) {
				var row = shaderTreeAdapter.getRow(position);
				var shader = row.node().shader;
				if (shader == null) {
					return true;
				}
				var title = shader.getTitle();
				editShaderName(shader.id(), title != null ? title : "");
			} else {
				var title = ((ShaderAdapter) parent.getAdapter()).getItem(position).getTitle();
				editShaderName(id, title != null ? title : "");
			}
			return true;
		});

		treeToggleButton = activity.findViewById(R.id.toggle_name_tree);
		treeToggleDefaultTint = treeToggleButton instanceof MaterialButton button
				? button.getIconTint()
				: null;
		if (treeToggleButton != null) {
			treeToggleButton.setOnClickListener(v -> {
				toggleTreeMode();
				updateToggleButtonStates();
			});
		}
		Button addToGroupButton = activity.findViewById(R.id.add_to_name_group);
		if (addToGroupButton != null) {
			addToGroupButton.setOnClickListener(v -> listener.onAddShaderToNameGroup());
		}
		showSimilarButton = activity.findViewById(R.id.show_similar_shaders);
		showSimilarDefaultTint = showSimilarButton instanceof MaterialButton button
				? button.getIconTint()
				: null;
		if (showSimilarButton != null) {
			showSimilarButton.setOnClickListener(v -> {
				if (similarFilterMode) {
					disableSimilarFilter();
				} else {
					listener.onConfigureSimilarShaders();
				}
			});
		}
		Button findShaderButton = activity.findViewById(R.id.find_shader);
		if (findShaderButton != null) {
			findShaderButton.setOnClickListener(v -> listener.onFindShader());
		}

		CheckBox filterEnabledBox = activity.findViewById(R.id.filter_shaders_enabled);
		EditText filterQueryView = activity.findViewById(R.id.filter_shaders_query);
		if (filterEnabledBox != null && filterQueryView != null) {
			filterEnabledBox.setOnCheckedChangeListener((box, checked) -> {
				textFilterEnabled = checked;
				filterQueryView.setEnabled(checked);
				refreshDisplayedAdapters();
			});
			filterQueryView.addTextChangedListener(new TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start, int count, int after) {
				}

				@Override
				public void onTextChanged(CharSequence s, int start, int before, int count) {
					textFilterQuery = s.toString().trim().toLowerCase(Locale.ROOT);
					if (textFilterEnabled) {
						refreshDisplayedAdapters();
					}
				}

				@Override
				public void afterTextChanged(Editable s) {
				}
			});
		}
	}

	private void updateToggleButtonStates() {
		int activeColor = ContextCompat.getColor(activity, R.color.toggle_active);
		ColorStateList activeTint = ColorStateList.valueOf(activeColor);
		if (treeToggleButton instanceof MaterialButton button) {
			button.setIconTint(treeMode ? activeTint : treeToggleDefaultTint);
		}
		if (showSimilarButton instanceof MaterialButton button) {
			button.setIconTint(similarFilterMode ? activeTint : showSimilarDefaultTint);
		}
	}

	/**
	 * Loads shaders from the database asynchronously.
	 */
	public void loadShadersAsync() {
		if (executor.isShutdown() ||
				!reloadGate.request()) {
			return;
		}
		submitLoad();
	}

	public void destroy() {
		executor.shutdownNow();
		handler.removeCallbacksAndMessages(null);
	}

	private void submitLoad() {
		try {
			executor.execute(() -> {
				List<ShaderInfo> shaders = dataSource.shader.getShaders(
						ShaderEditorApp.preferences.sortByLastModification());
				handler.post(() -> onShadersLoaded(shaders));
			});
		} catch (RejectedExecutionException ignored) {
			reloadGate.abort();
		}
	}

	private void onShadersLoaded(List<ShaderInfo> shaders) {
		if (reloadGate.finish()) {
			if (!executor.isShutdown() && isActivityAlive()) {
				submitLoad();
			} else {
				reloadGate.abort();
			}
			return;
		}
		if (!isActivityAlive()) {
			return;
		}
		List<ShaderInfo> shaderList = shaders != null
				? shaders
				: new ArrayList<>();
		listener.onShadersLoaded(shaderList);
		updateAdapter(shaderList);
	}

	private boolean isActivityAlive() {
		return !activity.isFinishing() && !activity.isDestroyed();
	}

	private void updateAdapter(List<ShaderInfo> shaders) {
		if (!isActivityAlive()) {
			return;
		}

		View progressBar = activity.findViewById(R.id.progress_bar);
		View noShadersMessage = activity.findViewById(R.id.no_shaders);

		if (progressBar != null) {
			progressBar.setVisibility(View.GONE);
		}

		List<ShaderInfo> data = shaders != null ? shaders : new ArrayList<>();

		if (data.isEmpty()) {
			if (noShadersMessage != null) {
				noShadersMessage.setVisibility(View.VISIBLE);
			}
			listener.onAllShadersDeleted();
		} else if (noShadersMessage != null) {
			noShadersMessage.setVisibility(View.GONE);
		}
		lastLoadedShaders = data;
		refreshDisplayedAdapters();
	}

	// Recomputes what's actually shown (the full list, or the similar-
	// shaders subset if that filter is on) and pushes it into both
	// adapters, so whichever one is attached is always up to date.
	private void refreshDisplayedAdapters() {
		List<ShaderInfo> data = lastLoadedShaders;
		if (similarFilterMode) {
			data = similarityIndex.findSimilar(
					similarityReferenceId, lastLoadedShaders, similarityThreshold);
			if (data.isEmpty() && !lastLoadedShaders.isEmpty()) {
				Toast.makeText(activity, R.string.no_similar_shaders, Toast.LENGTH_SHORT).show();
			}
		}
		if (textFilterEnabled && !textFilterQuery.isEmpty()) {
			List<ShaderInfo> filtered = new ArrayList<>();
			for (ShaderInfo shader : data) {
				String title = shader.getTitle();
				if (title != null && title.toLowerCase(Locale.ROOT).contains(textFilterQuery)) {
					filtered.add(shader);
				}
			}
			data = filtered;
		}
		shaderAdapter.setData(data);
		shaderTreeAdapter.setData(data);
	}

	private void toggleTreeMode() {
		treeMode = !treeMode;
		listView.setAdapter(treeMode ? shaderTreeAdapter : shaderAdapter);
	}

	// Called once the user has confirmed settings in the config dialog
	// the listener shows in response to onConfigureSimilarShaders().
	public void enableSimilarFilter(double threshold, boolean includeSource) {
		similarFilterMode = true;
		similarityReferenceId = selectedShaderId;
		similarityThreshold = threshold;
		similarityIndex.rebuild(lastLoadedShaders,
				includeSource ? dataSource.shader.getAllShaderSources() : Collections.emptyMap());
		refreshDisplayedAdapters();
		updateToggleButtonStates();
	}

	private void disableSimilarFilter() {
		similarFilterMode = false;
		refreshDisplayedAdapters();
		updateToggleButtonStates();
	}

	public void setSelectedShaderId(long id) {
		selectedShaderId = id;
		shaderAdapter.setSelectedId(id);
		shaderTreeAdapter.setSelectedId(id);
		// Selecting a shader - whether the highlighted one or some other
		// one - is as good a "context has moved on" signal as any, so
		// the find-shader highlight (if any) doesn't linger past it.
		setHighlightedShaderId(0);
	}

	// Scrolls the sidebar to the given shader, expanding tree ancestors
	// as needed so it's actually visible. Jumps straight there
	// (setSelection) rather than smoothScrollToPosition()'s animated
	// scroll, which crawls one row at a time and can take a very long
	// time to cross a large library.
	public void revealShader(long id) {
		int position = findRevealPosition(id);
		if (position < 0) {
			return;
		}
		// Posted rather than called directly - setSelection() right
		// after a layout-affecting change (the tree adapter's
		// notifyDataSetChanged() above, or the drawer having just been
		// opened by the caller) can get silently dropped because the
		// ListView hasn't actually re-measured against the new state
		// yet; posting runs it after that pending layout pass instead.
		listView.post(() -> listView.setSelection(position));
	}

	private int findRevealPosition(long id) {
		if (treeMode) {
			return shaderTreeAdapter.revealShader(id);
		}
		List<ShaderInfo> shaders = shaderAdapter.getShaders();
		for (int i = 0; i < shaders.size(); ++i) {
			if (shaders.get(i).id() == id) {
				return i;
			}
		}
		return -1;
	}

	// Scrolls to and outlines the given shader's row - the sidebar
	// counterpart to the editor's own found-it ring, used while browsing
	// Find Shader's results. Persists (no auto-clear timer) until the
	// user picks a shader - see setSelectedShaderId() - or calls
	// clearHighlightedShader() directly.
	public void revealAndHighlightShader(long id) {
		revealShader(id);
		setHighlightedShaderId(id);
	}

	public void clearHighlightedShader() {
		setHighlightedShaderId(0);
	}

	private void setHighlightedShaderId(long id) {
		shaderAdapter.setHighlightedId(id);
		shaderTreeAdapter.setHighlightedId(id);
	}

	// Steps by `direction` (-1 or +1) through the currently displayed
	// shader list, wrapping around at either end. Follows whatever
	// filtering (e.g. the similar-shaders view) is currently active.
	@Nullable
	public ShaderInfo getAdjacentShader(long currentId, int direction) {
		List<ShaderInfo> shaders = shaderAdapter.getShaders();
		int size = shaders.size();
		if (size == 0) {
			return null;
		}
		int index = 0;
		for (int i = 0; i < size; ++i) {
			if (shaders.get(i).id() == currentId) {
				index = i;
				break;
			}
		}
		int nextIndex = ((index + direction) % size + size) % size;
		return shaders.get(nextIndex);
	}

	@Nullable
	public ShaderInfo getRandomShader(long excludeId) {
		List<ShaderInfo> shaders = shaderAdapter.getShaders();
		int size = shaders.size();
		if (size == 0) {
			return null;
		}
		if (size == 1) {
			return shaders.get(0);
		}
		ShaderInfo picked;
		do {
			picked = shaders.get(random.nextInt(size));
		} while (picked.id() == excludeId);
		return picked;
	}

	// Returns matching shaders in the same order they're currently displayed.
	@NonNull
	public List<ShaderInfo> findMatches(
			@NonNull String query, boolean searchSource, boolean fuzzy) {
		List<ShaderInfo> matches = new ArrayList<>();
		if (query.isEmpty()) {
			return matches;
		}
		Map<Long, String> sources = searchSource
				? dataSource.shader.getAllShaderSources()
				: Collections.emptyMap();
		for (ShaderInfo shader : shaderAdapter.getShaders()) {
			String title = shader.getTitle();
			boolean nameMatches = title != null && matches(query, title, fuzzy);
			String source = sources.get(shader.id());
			boolean sourceMatches = searchSource && source != null &&
					matches(query, source, fuzzy);
			if (nameMatches || sourceMatches) {
				matches.add(shader);
			}
		}
		return matches;
	}

	// All currently loaded shaders (unfiltered), for pickers like the
	// "add to name group" parent-shader chooser.
	@NonNull
	public List<ShaderInfo> getAllShaders() {
		return lastLoadedShaders;
	}

	public void renameShader(long id, @NonNull String newName) {
		dataSource.shader.updateShaderName(id, newName);
		listener.onShaderRenamed(id, newName);
	}

	private static boolean matches(
			@NonNull String query, @NonNull String target, boolean fuzzy) {
		String q = query.toLowerCase(Locale.ROOT);
		String t = target.toLowerCase(Locale.ROOT);
		return fuzzy ? fuzzyMatch(q, t) : t.contains(q);
	}

	// Subsequence match: every character of `query` must appear in
	// `target` in order, though not necessarily contiguously.
	private static boolean fuzzyMatch(@NonNull String query, @NonNull String target) {
		int qi = 0;
		for (int ti = 0; ti < target.length() && qi < query.length(); ++ti) {
			if (target.charAt(ti) == query.charAt(qi)) {
				++qi;
			}
		}
		return qi == query.length();
	}

	public void editShaderName(final long id, @NonNull String name) {
		View view = activity.getLayoutInflater().inflate(
				R.layout.dialog_rename_shader, null);
		final EditText nameView = view.findViewById(R.id.name);
		nameView.setText(name);

		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.rename_shader)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					String newName = nameView.getText().toString();
					SoftKeyboard.hide(activity, nameView);
					renameShader(id, newName);
				})
				.setNeutralButton(R.string.reparent_shader, (dialog, which) -> {
					SoftKeyboard.hide(activity, nameView);
					listener.onReparentShader(id);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// NameTokenizer/the tree view assume PascalCase, so text that
	// doesn't start with a capital letter (including starting with a
	// digit) won't group the way the user probably expects - catch that
	// here rather than silently accepting it. onAccepted is invoked with
	// the final text to use - immediately (synchronously) if no warning
	// was needed, otherwise once the user picks a way to resolve it (not
	// at all if they cancel). Shared by the plain rename dialog (checked
	// against the whole name) and the reparent flow (checked against
	// just the postfix, since the parent half is guaranteed capitalized
	// already).
	public void checkNamePrefixAndThen(
			@NonNull String candidate, @NonNull Consumer<String> onAccepted) {
		if (candidate.isEmpty() || Character.isUpperCase(candidate.charAt(0))) {
			onAccepted.accept(candidate);
			return;
		}
		View view = activity.getLayoutInflater().inflate(
				R.layout.dialog_name_prefix_warning, null);
		((TextView) view.findViewById(R.id.message)).setText(
				activity.getString(R.string.name_prefix_warning, candidate));

		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setView(view)
				.show();
		view.findViewById(R.id.capitalize).setOnClickListener(v -> {
			dialog.dismiss();
			onAccepted.accept(Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1));
		});
		view.findViewById(R.id.add_underscore).setOnClickListener(v -> {
			dialog.dismiss();
			onAccepted.accept("_" + candidate);
		});
		view.findViewById(R.id.do_it_anyway).setOnClickListener(v -> {
			dialog.dismiss();
			onAccepted.accept(candidate);
		});
		view.findViewById(R.id.cancel).setOnClickListener(v -> dialog.dismiss());
	}
}
