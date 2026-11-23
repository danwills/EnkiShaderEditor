package enk.danwills.android.enkishadereditor.shaderorg;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import enk.danwills.android.enkishadereditor.database.DataRecords.ShaderInfo;

// Versions of one shader, as the sidebar's tree groups them: leaves under
// the same node whose names differ only in their numbers ("Floating Land
// v1" ... "v24", "bench 1" ... "bench 4"). Leaves named otherwise
// ("Regenerating Lizard", "Regenerating Planarian") are series of their
// own, as are ones that are also a group. Unnamed shaders, named by
// their dates, make one series.
public final class ShaderSeries {
	private ShaderSeries() {
	}

	// The newest shader (by code change) of each series.
	@NonNull
	public static Set<Long> newestOfEach(@NonNull List<ShaderInfo> shaders) {
		Set<Long> ids = new HashSet<>();
		collect(ShaderNameTreeBuilder.build(shaders, "\u0000"), ids);
		return ids;
	}

	private static void collect(@NonNull ShaderNameNode node, @NonNull Set<Long> ids) {
		Map<String, ShaderInfo> newest = new HashMap<>();
		for (ShaderNameNode child : node.children.values()) {
			ShaderInfo shader = child.shader;
			if (!child.children.isEmpty()) {
				if (shader != null) {
					ids.add(shader.id());
				}
				collect(child, ids);
				continue;
			}
			if (shader == null) {
				continue;
			}
			String key = child.token.replaceAll("[0-9]+", "#");
			ShaderInfo best = newest.get(key);
			if (best == null || date(shader).compareTo(date(best)) > 0) {
				newest.put(key, shader);
			}
		}
		for (ShaderInfo shader : newest.values()) {
			ids.add(shader.id());
		}
	}

	// Dates are "yyyy-MM-dd HH:mm:ss", so they compare as strings.
	@NonNull
	private static String date(@NonNull ShaderInfo shader) {
		String date = shader.sourceModified() != null
				? shader.sourceModified()
				: shader.modified();
		return date != null ? date : "";
	}
}
