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
	//
	// Shaders from a shader pack ("<Pack Title>: <name>") go under a
	// top-level node of their pack's own - keyed by the pack's id, never
	// by name, so it can't merge with one of the user's groups that
	// happens to be called the same - with the rest of their name
	// grouped inside it as usual. Those nodes sort before everything else.
	private static final String PACK_KEY_PREFIX = "\u0001pack:";

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
			ShaderNameNode node = root;
			if (shader.isFromPack()) {
				String packTitle = title;
				int separator = title.indexOf(ShaderInfo.PACK_NAME_SEPARATOR);
				if (separator >= 0) {
					packTitle = title.substring(0, separator);
					title = title.substring(separator + ShaderInfo.PACK_NAME_SEPARATOR.length());
				}
				String key = PACK_KEY_PREFIX + shader.packId();
				ShaderNameNode pack = root.children.get(key);
				if (pack == null) {
					pack = new ShaderNameNode(key);
					pack.label = packTitle;
					root.children.put(key, pack);
				}
				node = pack;
				isUnnamed = false;
			}
			List<String> tokens = isUnnamed
					? List.of(unnamedGroupLabel, title)
					: NameTokenizer.tokenize(title);
			if (tokens.isEmpty()) {
				tokens = List.of(title);
			}
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
