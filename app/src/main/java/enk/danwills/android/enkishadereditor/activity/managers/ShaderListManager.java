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
import android.widget.PopupMenu;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
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
import enk.danwills.android.enkishadereditor.shaderorg.NameMatch;
import enk.danwills.android.enkishadereditor.shaderorg.ShaderNameNode;
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

		// Long-pressing a group in the name tree: build a shader pack
		// starting from every shader in it, titled after the group.
		void onBuildShaderPack(@NonNull List<Long> shaderIds, @NonNull String title);

		// Tapping a presentation pack's row: play it - see ShowActivity.
		void onPlayShow(@NonNull String packId);

		// Long-pressing a pack's row: Edit Pack, or Update Version.
		void onEditPack(@NonNull String packId);

		void onUpdatePack(@NonNull String packId);
	}

	private final Activity activity;
	private final DataSource dataSource;
	private final Listener listener;
	private final ListView listView;
	private final ShaderAdapter shaderAdapter;
	private final ShaderTreeAdapter shaderTreeAdapter;
	private final ShaderSimilarity similarityIndex = new ShaderSimilarity();
	private final Random random = new Random();
	private static final String PREF_TREE_NEWEST = "sidebar_tree_newest_first";

	@Nullable
	private final Button treeToggleButton;
	@Nullable
	private final Button showSimilarButton;
	@Nullable
	private final CheckBox newestFirstBox;
	@Nullable
	private final ColorStateList treeToggleDefaultTint;
	@Nullable
	private final ColorStateList showSimilarDefaultTint;

	private List<ShaderInfo> lastLoadedShaders = new ArrayList<>();
	// The name tree is the default view; the tree button toggles it off.
	private boolean treeMode = true;
	private boolean similarFilterMode = false;
	private long selectedShaderId = 0;
	private long similarityReferenceId = 0;
	private double similarityThreshold = ShaderSimilarity.DEFAULT_THRESHOLD;
	private boolean textFilterEnabled = false;
	// Only shaders installed from shader packs - see refreshDisplayedAdapters().
	private boolean packsOnly;
	@NonNull
	private String textFilterQuery = "";

	// Use an ExecutorService for background tasks and a Handler for the main thread.
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CoalescingReloadGate reloadGate = new CoalescingReloadGate();

	private static void collectShaderIds(@NonNull ShaderNameNode node, @NonNull List<Long> ids) {
		if (node.shader != null) {
			ids.add(node.shader.id());
		}
		for (var child : node.children.values()) {
			collectShaderIds(child, ids);
		}
	}

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
		listView.setAdapter(treeMode ? shaderTreeAdapter : shaderAdapter);

		listView.setEmptyView(activity.findViewById(R.id.no_shaders));
		listView.setOnItemClickListener((parent, view, position, id) -> {
			if (treeMode) {
				var row = shaderTreeAdapter.getRow(position);
				if (row.node().show && row.node().packId != null) {
					listener.onPlayShow(row.node().packId);
				} else if (row.isSelectable()) {
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
				var ids = new ArrayList<Long>();
				collectShaderIds(row.node(), ids);
				Runnable buildPack = () -> listener.onBuildShaderPack(ids, row.node().getLabel());
				String packId = row.node().packId;
				if (shader == null && packId != null) {
					// A pack: play it (a show), or edit it.
					var menu = new PopupMenu(activity, view);
					if (row.node().show) {
						menu.getMenu().add(0, 1, 0, R.string.play_show);
					}
					menu.getMenu().add(0, 2, 1, R.string.edit_pack);
					menu.getMenu().add(0, 3, 2, R.string.update_pack_version);
					menu.setOnMenuItemClickListener(item -> {
						if (item.getItemId() == 1) {
							listener.onPlayShow(packId);
						} else if (item.getItemId() == 2) {
							listener.onEditPack(packId);
						} else {
							listener.onUpdatePack(packId);
						}
						return true;
					});
					menu.show();
					return true;
				}
				if (shader == null) {
					// A group only: building a pack is all there is to do.
					if (!ids.isEmpty()) {
						buildPack.run();
					}
					return true;
				}
				var title = shader.getTitle();
				Runnable rename = () -> editShaderName(shader.id(), title != null ? title : "");
				if (!row.hasChildren()) {
					rename.run();
					return true;
				}
				// A shader that's also a group: rename it, or build a pack
				// from it and everything under it.
				var menu = new PopupMenu(activity, view);
				menu.getMenu().add(0, 1, 0, R.string.rename_shader);
				menu.getMenu().add(0, 2, 1, R.string.build_shader_pack_from_group);
				menu.setOnMenuItemClickListener(item -> {
					if (item.getItemId() == 1) {
						rename.run();
					} else {
						buildPack.run();
					}
					return true;
				});
				menu.show();
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
		// The tree button starts lit, as tree view is the default.
		updateToggleButtonStates();

		CheckBox packsOnlyBox = activity.findViewById(R.id.filter_shaders_packs);
		if (packsOnlyBox != null) {
			packsOnlyBox.setOnCheckedChangeListener((box, checked) -> {
				packsOnly = checked;
				refreshDisplayedAdapters();
			});
		}

		// Remembered, unlike the filters above and below.
		newestFirstBox = activity.findViewById(R.id.sort_tree_newest);
		if (newestFirstBox != null) {
			var prefs = ShaderEditorApp.preferences.getSharedPreferences();
			boolean newestFirst = prefs.getBoolean(PREF_TREE_NEWEST, false);
			newestFirstBox.setChecked(newestFirst);
			shaderTreeAdapter.setNewestFirst(newestFirst);
			newestFirstBox.setVisibility(treeMode ? View.VISIBLE : View.GONE);
			newestFirstBox.setOnCheckedChangeListener((box, checked) -> {
				prefs.edit().putBoolean(PREF_TREE_NEWEST, checked).apply();
				shaderTreeAdapter.setNewestFirst(checked);
			});
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
				Set<String> shows = dataSource.pack.getShowPackIds();
				handler.post(() -> {
					shaderTreeAdapter.setShowPackIds(shows);
					onShadersLoaded(shaders);
				});
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
			List<ShaderInfo> similar = similarityIndex.findSimilar(
					similarityReferenceId, lastLoadedShaders, similarityThreshold);
			if (similar.isEmpty() && !lastLoadedShaders.isEmpty()) {
				Toast.makeText(activity, R.string.no_similar_shaders, Toast.LENGTH_SHORT).show();
			}
			// Keep the reference shader itself in the list too - it's
			// already highlighted/selected, and dropping it made "similar
			// to this" look like it was missing the one shader the whole
			// comparison is actually anchored to.
			ShaderInfo reference = findById(lastLoadedShaders, similarityReferenceId);
			if (reference != null) {
				List<ShaderInfo> withReference = new ArrayList<>(similar.size() + 1);
				withReference.add(reference);
				withReference.addAll(similar);
				data = withReference;
			} else {
				data = similar;
			}
		}
		if (packsOnly) {
			List<ShaderInfo> packShaders = new ArrayList<>();
			for (ShaderInfo shader : data) {
				if (shader.isFromPack()) {
					packShaders.add(shader);
				}
			}
			data = packShaders;
		}
		Map<Long, Double> scores = null;
		if (textFilterEnabled && !textFilterQuery.isEmpty()) {
			// Best matches first - see NameMatch.
			scores = new HashMap<>();
			List<ShaderInfo> filtered = new ArrayList<>();
			for (ShaderInfo shader : data) {
				String title = shader.getTitle();
				double score = title != null ? NameMatch.score(textFilterQuery, title, false) : 0;
				if (score > 0) {
					scores.put(shader.id(), score);
					filtered.add(shader);
				}
			}
			sortByScore(filtered, scores);
			data = filtered;
		}
		shaderAdapter.setData(data);
		shaderTreeAdapter.setMatchScores(scores);
		shaderTreeAdapter.setData(data);
	}

	// Best first; equal scores keep their order (List.sort is stable).
	private static void sortByScore(@NonNull List<ShaderInfo> shaders,
			@NonNull Map<Long, Double> scores) {
		shaders.sort((a, b) -> Double.compare(
				scores.getOrDefault(b.id(), 0.0), scores.getOrDefault(a.id(), 0.0)));
	}

	@Nullable
	private static ShaderInfo findById(@NonNull List<ShaderInfo> shaders, long id) {
		for (ShaderInfo shader : shaders) {
			if (shader.id() == id) {
				return shader;
			}
		}
		return null;
	}

	private void toggleTreeMode() {
		treeMode = !treeMode;
		listView.setAdapter(treeMode ? shaderTreeAdapter : shaderAdapter);
		if (newestFirstBox != null) {
			newestFirstBox.setVisibility(treeMode ? View.VISIBLE : View.GONE);
		}
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

	// The first shader in the currently displayed list - follows
	// whatever filtering is active, same as getAdjacentShader() above.
	@Nullable
	public ShaderInfo getFirstShader() {
		List<ShaderInfo> shaders = shaderAdapter.getShaders();
		return shaders.isEmpty() ? null : shaders.get(0);
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

	// Returns matching shaders best first (see NameMatch): a match in the
	// name beats one only in the source, and equal matches keep the order
	// they're displayed in.
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
		Map<Long, Double> scores = new HashMap<>();
		for (ShaderInfo shader : shaderAdapter.getShaders()) {
			String title = shader.getTitle();
			double score = title != null ? NameMatch.score(query, title, fuzzy) : 0;
			if (score == 0 && searchSource) {
				String source = sources.get(shader.id());
				if (source != null && matches(query, source, fuzzy)) {
					score = 0.5;
				}
			}
			if (score > 0) {
				scores.put(shader.id(), score);
				matches.add(shader);
			}
		}
		sortByScore(matches, scores);
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
		return fuzzy ? NameMatch.fuzzyMatches(q, t) : t.contains(q);
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
	// starts with a lowercase letter (or a symbol) won't group the way
	// the user probably expects - catch that here rather than silently
	// accepting it. A digit is fine: a number after a lowercase letter
	// starts a token of its own (see NameTokenizer). onAccepted is invoked with
	// the final text to use - immediately (synchronously) if no warning
	// was needed, otherwise once the user picks a way to resolve it (not
	// at all if they cancel). Shared by the plain rename dialog (checked
	// against the whole name) and the reparent flow (checked against
	// just the postfix, since the parent half is guaranteed capitalized
	// already).
	public void checkNamePrefixAndThen(
			@NonNull String candidate, @NonNull Consumer<String> onAccepted) {
		if (candidate.isEmpty() || Character.isUpperCase(candidate.charAt(0))
				|| Character.isDigit(candidate.charAt(0))) {
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
