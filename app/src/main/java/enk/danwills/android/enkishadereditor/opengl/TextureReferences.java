package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Set;
import java.util.TreeSet;

// Which saved textures (see TextureDao) a shader's code uses - e.g. so a
// shader pack can carry exactly the textures its shaders need.
public final class TextureReferences {
	private TextureReferences() {
	}

	@NonNull
	public static Set<String> find(@Nullable String imageSource, @NonNull String[] bufferSources) {
		var names = new TreeSet<String>();
		ShaderSourcePreparer.collectTextureNames(imageSource, names);
		for (String source : bufferSources) {
			ShaderSourcePreparer.collectTextureNames(source, names);
		}
		return names;
	}
}
