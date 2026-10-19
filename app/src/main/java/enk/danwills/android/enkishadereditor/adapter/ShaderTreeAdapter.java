package enk.danwills.android.enkishadereditor.adapter;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords.ShaderInfo;
import enk.danwills.android.enkishadereditor.shaderorg.ShaderNameNode;
import enk.danwills.android.enkishadereditor.shaderorg.ShaderNameTreeBuilder;

// Flattens a ShaderNameNode tree into a list of visible rows for display
// in a plain ListView (Android has no built-in arbitrary-depth tree
// widget), expanding/collapsing on tap of either the row's chevron or,
// for group-only rows, anywhere on the row.
public class ShaderTreeAdapter extends BaseAdapter {
	public record Row(@NonNull ShaderNameNode node, int depth, @NonNull String path,
			boolean hasChildren) {
		public boolean isSelectable() {
			return node.shader != null;
		}
	}

	private final int textColorSelected;
	private final int textColorUnselected;
	private final int textColorPack;
	// Whether pack shaders' names get their own colour - see
	// setHighlightPacks().
	private boolean highlightPacks = true;
	private final int textColorGroup;
	private final Drawable highlightRing;
	private final int indentPx;

	// Keyed by the token path ("Example/Tasty"), not node identity, so
	// expand state survives the tree being rebuilt from fresh data.
	private final Set<String> expandedPaths = new HashSet<>();

	private final String unnamedGroupLabel;

	// Branches whose best contained match is at least this good get
	// auto-expanded when similarity sorting turns on, so the user doesn't
	// have to manually dig into every top-ranked group to see why it
	// ranked there.
	private static final double AUTO_EXPAND_THRESHOLD = 0.8;

	private ShaderNameNode root = new ShaderNameNode("");
	private List<Row> visibleRows = Collections.emptyList();
	private long selectedShaderId = 0;
	private long highlightedShaderId = 0;
	// When set, children at every level are ordered by the best
	// similarity score found anywhere in their subtree (descending)
	// instead of alphabetically, and branches containing a very good
	// match are auto-expanded.
	@Nullable
	private Map<Long, Double> similarityScores;

	public ShaderTreeAdapter(Context context) {
		textColorSelected = ContextCompat.getColor(context, R.color.accent);
		textColorUnselected = ContextCompat.getColor(context, R.color.drawer_text_unselected);
		textColorPack = ContextCompat.getColor(context, R.color.pack_shader);
		// `secondary_text` is a dark color meant for light surfaces; the
		// drawer background here is dark, so group labels need a light
		// color like the other drawer text, just a bit dimmer.
		textColorGroup = ContextCompat.getColor(context, R.color.drawer_text_unselected);
		highlightRing = ContextCompat.getDrawable(context, R.drawable.search_match_ring);
		indentPx = (int) (16 * context.getResources().getDisplayMetrics().density);
		unnamedGroupLabel = context.getString(R.string.unnamed_shaders_group);
	}

	public void setData(@NonNull List<ShaderInfo> shaders) {
		root = ShaderNameTreeBuilder.build(shaders, unnamedGroupLabel);
		rebuildVisibleRows();
		notifyDataSetChanged();
	}

	// Shaders installed from a shader pack are named in their own colour
	// to tell them apart - not needed while only pack shaders are shown.
	public void setHighlightPacks(boolean highlightPacks) {
		this.highlightPacks = highlightPacks;
		notifyDataSetChanged();
	}

	public void setSelectedId(long id) {
		selectedShaderId = id;
		notifyDataSetChanged();
	}

	// Briefly tints the given row's background to mark it as a
	// Find-Shader match being browsed - distinct from (and
	// independent of) selectedShaderId, since the match being looked
	// at isn't necessarily the currently-open shader.
	public void setHighlightedId(long id) {
		highlightedShaderId = id;
		notifyDataSetChanged();
	}

	// Pass null to go back to plain alphabetical order. Scores are keyed
	// by shader id, as returned by ShaderSimilarity.scoreAll(); shaders
	// with no entry are treated as having no match.
	public void setSimilarityScores(@Nullable Map<Long, Double> scores) {
		similarityScores = scores;
		expandedPaths.clear();
		if (scores != null) {
			autoExpandBestMatches(root, "");
		}
		rebuildVisibleRows();
		notifyDataSetChanged();
	}

	private void autoExpandBestMatches(@NonNull ShaderNameNode node, @NonNull String path) {
		for (ShaderNameNode child : node.children.values()) {
			String childPath = path.isEmpty() ? child.token : path + "/" + child.token;
			if (!child.children.isEmpty()) {
				if (bestScore(child) >= AUTO_EXPAND_THRESHOLD) {
					expandedPaths.add(childPath);
				}
				autoExpandBestMatches(child, childPath);
			}
		}
	}

	// The best similarity score found anywhere in this node's subtree
	// (including the node itself, if it's a leaf), or -1 if none of its
	// descendants have a score at all.
	private double bestScore(@NonNull ShaderNameNode node) {
		double best = -1;
		if (node.shader != null && similarityScores != null) {
			Double score = similarityScores.get(node.shader.id());
			if (score != null) {
				best = score;
			}
		}
		for (ShaderNameNode child : node.children.values()) {
			best = Math.max(best, bestScore(child));
		}
		return best;
	}

	@NonNull
	public Row getRow(int position) {
		return visibleRows.get(position);
	}

	public void toggleExpand(int position) {
		Row row = visibleRows.get(position);
		if (!row.hasChildren()) {
			return;
		}
		if (!expandedPaths.remove(row.path())) {
			expandedPaths.add(row.path());
		}
		rebuildVisibleRows();
		notifyDataSetChanged();
	}

	// Expands every ancestor of the given shader's node so it's actually
	// visible, then returns its row position (or -1 if it's not in the
	// tree at all, e.g. filtered out).
	public int revealShader(long shaderId) {
		String path = findPathToShader(root, "", shaderId);
		if (path == null) {
			return -1;
		}
		String[] segments = path.split("/");
		StringBuilder prefix = new StringBuilder();
		for (int i = 0; i < segments.length - 1; ++i) {
			if (i > 0) {
				prefix.append('/');
			}
			prefix.append(segments[i]);
			expandedPaths.add(prefix.toString());
		}
		rebuildVisibleRows();
		notifyDataSetChanged();
		for (int i = 0; i < visibleRows.size(); ++i) {
			if (visibleRows.get(i).path().equals(path)) {
				return i;
			}
		}
		return -1;
	}

	@Nullable
	private String findPathToShader(
			@NonNull ShaderNameNode node, @NonNull String path, long shaderId) {
		for (ShaderNameNode child : node.children.values()) {
			String childPath = path.isEmpty() ? child.token : path + "/" + child.token;
			if (child.shader != null && child.shader.id() == shaderId) {
				return childPath;
			}
			String deeper = findPathToShader(child, childPath, shaderId);
			if (deeper != null) {
				return deeper;
			}
		}
		return null;
	}

	@Override
	public int getCount() {
		return visibleRows.size();
	}

	@Override
	public Row getItem(int position) {
		return visibleRows.get(position);
	}

	@Override
	public long getItemId(int position) {
		ShaderInfo shader = visibleRows.get(position).node().shader;
		return shader != null ? shader.id() : -1;
	}

	@Override
	public View getView(int position, View convertView, ViewGroup parent) {
		ViewHolder holder;
		if (convertView == null) {
			convertView = LayoutInflater.from(parent.getContext()).inflate(
					R.layout.row_shader_tree, parent, false);
			holder = new ViewHolder(convertView);
			convertView.setTag(holder);
		} else {
			holder = (ViewHolder) convertView.getTag();
		}
		holder.bindTo(getItem(position), position);
		return convertView;
	}

	private void rebuildVisibleRows() {
		List<Row> rows = new ArrayList<>();
		flatten(root, 0, "", rows);
		visibleRows = rows;
	}

	private void flatten(@NonNull ShaderNameNode node, int depth, @NonNull String path,
			@NonNull List<Row> out) {
		List<ShaderNameNode> children = new ArrayList<>(node.children.values());
		if (similarityScores != null) {
			children.sort((a, b) -> Double.compare(bestScore(b), bestScore(a)));
		}
		for (ShaderNameNode child : children) {
			String childPath = path.isEmpty() ? child.token : path + "/" + child.token;
			boolean hasChildren = !child.children.isEmpty();
			out.add(new Row(child, depth, childPath, hasChildren));
			if (hasChildren && expandedPaths.contains(childPath)) {
				flatten(child, depth + 1, childPath, out);
			}
		}
	}

	private class ViewHolder {
		private final View root;
		private final ImageView chevron;
		private final ImageView icon;
		private final TextView title;

		ViewHolder(@NonNull View itemView) {
			root = itemView;
			chevron = itemView.findViewById(R.id.tree_chevron);
			icon = itemView.findViewById(R.id.shader_icon);
			title = itemView.findViewById(R.id.shader_title);
		}

		void bindTo(@NonNull Row row, int position) {
			root.setPadding(row.depth() * indentPx, root.getPaddingTop(),
					root.getPaddingRight(), root.getPaddingBottom());

			if (row.hasChildren()) {
				chevron.setVisibility(View.VISIBLE);
				chevron.setRotation(expandedPaths.contains(row.path()) ? 90f : 0f);
				chevron.setOnClickListener(v -> toggleExpand(position));
			} else {
				chevron.setVisibility(View.INVISIBLE);
				chevron.setOnClickListener(null);
			}

			ShaderInfo shader = row.node().shader;
			// Reset unconditionally - a recycled view previously bound
			// to a highlighted leaf row would otherwise keep showing
			// that highlight after being rebound to an unrelated row.
			root.setBackgroundResource(R.drawable.drawer_item_selector);
			root.setForeground(null);
			if (shader != null) {
				byte[] bytes = shader.thumb();
				if (bytes != null && bytes.length > 0) {
					icon.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
				} else {
					icon.setImageResource(R.drawable.thumbnail_new_shader);
				}
				title.setText(row.node().getLabel());
				if (shader.id() == highlightedShaderId) {
					root.setForeground(highlightRing);
				}
				title.setTextColor(shader.id() == selectedShaderId
						? textColorSelected
						: highlightPacks && shader.isFromPack()
								? textColorPack
								: textColorUnselected);
			} else {
				icon.setImageResource(row.node().isPack()
						? R.drawable.ic_pack
						: R.drawable.ic_quick_reference_all);
				title.setText(row.node().getLabel());
				title.setTextColor(highlightPacks && row.node().isPack()
						? textColorPack
						: textColorGroup);
			}
		}
	}
}
