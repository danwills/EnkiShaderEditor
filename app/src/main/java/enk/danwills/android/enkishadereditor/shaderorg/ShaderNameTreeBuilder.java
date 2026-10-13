package enk.danwills.android.enkishadereditor.shaderorg;

import androidx.annotation.NonNull;

import java.util.List;

import enk.danwills.android.enkishadereditor.database.DataRecords.ShaderInfo;

public final class ShaderNameTreeBuilder {
	private ShaderNameTreeBuilder() {
	}

	// Shaders with no name display their last-modified timestamp as a
	// stand-in title (e.g. "2026-09-05 15:27:20"). That string has no
	// camelCase boundaries at all, so each one would otherwise become
	// its own top-level root entry - grouping them under one parent
	// keeps the tree from being flooded with one root per untitled
	// shader.
	@NonNull
	public static ShaderNameNode build(
			@NonNull List<ShaderInfo> shaders, @NonNull String unnamedGroupLabel) {
		ShaderNameNode root = new ShaderNameNode("");
		for (ShaderInfo shader : shaders) {
			String title = shader.getTitle();
			if (title == null || title.isEmpty()) {
				continue;
			}
			boolean isUnnamed = shader.name() == null || shader.name().isEmpty();
			List<String> tokens = isUnnamed
					? List.of(unnamedGroupLabel, title)
					: NameTokenizer.tokenize(title);
			if (tokens.isEmpty()) {
				continue;
			}
			ShaderNameNode node = root;
			for (String token : tokens) {
				ShaderNameNode child = node.children.get(token);
				if (child == null) {
					child = new ShaderNameNode(token);
					node.children.put(token, child);
				}
				node = child;
			}
			// If two shaders share the exact same name, the last one
			// processed wins the leaf; both still appear in the flat list.
			node.shader = shader;
		}
		return root;
	}
}
