package enk.danwills.android.enkishadereditor.shaderorg;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.TreeMap;

import enk.danwills.android.enkishadereditor.database.DataRecords.ShaderInfo;

// One node in the name tree. A node always has a non-empty token, and
// always has at least one of: a shader (its full token path matches an
// existing shader's name exactly) or children (other shaders continue
// past this token) - otherwise it wouldn't have been created at all.
public final class ShaderNameNode {
	@NonNull
	public final String token;
	@NonNull
	public final Map<String, ShaderNameNode> children = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	@Nullable
	public ShaderInfo shader;

	public ShaderNameNode(@NonNull String token) {
		this.token = token;
	}
}
