package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// The values of the #defines Adjust Defines' live mode has turned into
// uniforms (see ShaderDefineParser.toLiveSource()). Set from the UI
// thread as the live sliders move, and bound into every frame by
// BuiltinUniforms.beginFrame() on the GL thread - each value is its own
// fresh array, never changed after it's stored, so the GL thread can't
// see one half-written.
public final class LiveDefines {
	private record Value(@NonNull ShaderDefine.Type type, @NonNull float[] components) {
	}

	private final Map<String, Value> values = new ConcurrentHashMap<>();

	public void set(@NonNull String name, @NonNull ShaderDefine.Type type,
			@NonNull float[] components) {
		values.put(name, new Value(type, components.clone()));
	}

	public void clear() {
		values.clear();
	}

	public boolean isEmpty() {
		return values.isEmpty();
	}

	void apply(@NonNull ProgramBindings bindings) {
		for (var entry : values.entrySet()) {
			String name = entry.getKey();
			float[] c = entry.getValue().components();
			switch (entry.getValue().type()) {
				case INT, BOOL -> bindings.setInt(name, Math.round(c[0]));
				case FLOAT -> bindings.setFloat(name, c[0]);
				case VEC2 -> bindings.setFloat2(name, c);
				case VEC3 -> bindings.setFloat3(name, c);
				case VEC4 -> bindings.setFloat4(name, c);
				default -> {
				}
			}
		}
	}
}
