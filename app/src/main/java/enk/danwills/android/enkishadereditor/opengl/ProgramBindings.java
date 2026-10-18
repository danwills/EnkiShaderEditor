package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

final class ProgramBindings {
	static final int TYPE_INT = 1;
	static final int TYPE_FLOAT = 2;
	static final int TYPE_FLOAT2 = 3;
	static final int TYPE_FLOAT3 = 4;
	static final int TYPE_FLOAT4 = 5;
	static final int TYPE_MATRIX2 = 6;
	static final int TYPE_MATRIX3 = 7;

	static final class UniformValue {
		final String name;
		int type;
		boolean active;
		boolean transpose;
		int count = 1;
		int intValue;
		float floatValue;
		@Nullable
		float[] values;

		UniformValue(String name, int type) {
			this.name = name;
			this.type = type;
		}
	}

	static final class TextureValue {
		final String name;
		boolean active;
		@Nullable
		GlTexture texture;

		TextureValue(String name) {
			this.name = name;
		}
	}

	@NonNull
	private GlProgram program;
	@NonNull
	private final Map<String, UniformValue> uniforms = new LinkedHashMap<>();
	@NonNull
	private final Map<String, TextureValue> textures = new LinkedHashMap<>();

	ProgramBindings(@NonNull GlProgram program) {
		this.program = program;
	}

	// Lets an already-populated set of uniform/texture values (e.g. a
	// normal frame's live bindings) be applied against a *different*
	// compiled program instead - GlDevice.applyBindings() re-resolves
	// every uniform's location by name against getProgram() on each
	// call, so this is safe as long as the other program declares the
	// same uniform names (true for the high-res tile capture's program,
	// which only prepends a macro to the same prepared source).
	void retarget(@NonNull GlProgram program) {
		this.program = program;
	}

	@NonNull
	GlProgram getProgram() {
		return program;
	}

	@NonNull
	Iterable<UniformValue> getUniforms() {
		return uniforms.values();
	}

	@NonNull
	Iterable<TextureValue> getTextures() {
		return textures.values();
	}

	// Replaces every active uniform's values array with its own private
	// copy. The setters store the caller's array by reference, and several
	// callers pass live arrays that keep changing (touch, mouse, pointers,
	// sensors) - harmless for a frame drawn in one go, but a progressive
	// frame is drawn as bands across several refreshes, and without this
	// each band saw whatever those arrays held at *its* draw (e.g. an
	// orbit drag landing mid-frame tore the image into bands rendered from
	// different camera positions). The next beginFrame() re-binds fresh
	// references anyway, so this only ever affects the frame it's called
	// for.
	void freezeValues() {
		for (var uniform : uniforms.values()) {
			if (uniform.active && uniform.values != null) {
				uniform.values = uniform.values.clone();
			}
		}
	}

	void clear() {
		for (var uniform : uniforms.values()) {
			uniform.active = false;
		}
		for (var texture : textures.values()) {
			texture.active = false;
			texture.texture = null;
		}
	}

	void setInt(@NonNull String name, int value) {
		var uniform = getOrCreateUniform(name, TYPE_INT);
		uniform.count = 1;
		uniform.intValue = value;
	}

	void setFloat(@NonNull String name, float value) {
		var uniform = getOrCreateUniform(name, TYPE_FLOAT);
		uniform.count = 1;
		uniform.floatValue = value;
	}

	void setFloat2(@NonNull String name, @NonNull float[] values) {
		setFloat2(name, 1, values);
	}

	void setFloat2(@NonNull String name, int count, @NonNull float[] values) {
		var uniform = getOrCreateUniform(name, TYPE_FLOAT2);
		uniform.count = count;
		uniform.values = values;
	}

	void setFloat3(@NonNull String name, @NonNull float[] values) {
		setFloat3(name, 1, values);
	}

	void setFloat3(@NonNull String name, int count, @NonNull float[] values) {
		var uniform = getOrCreateUniform(name, TYPE_FLOAT3);
		uniform.count = count;
		uniform.values = values;
	}

	void setFloat4(@NonNull String name, @NonNull float[] values) {
		setFloat4(name, 1, values);
	}

	void setFloat4(@NonNull String name, int count, @NonNull float[] values) {
		var uniform = getOrCreateUniform(name, TYPE_FLOAT4);
		uniform.count = count;
		uniform.values = values;
	}

	void setMatrix2(@NonNull String name,
			boolean transpose,
			@NonNull float[] values) {
		var uniform = getOrCreateUniform(name, TYPE_MATRIX2);
		uniform.transpose = transpose;
		uniform.count = 1;
		uniform.values = values;
	}

	void setMatrix3(@NonNull String name,
			boolean transpose,
			@NonNull float[] values) {
		var uniform = getOrCreateUniform(name, TYPE_MATRIX3);
		uniform.transpose = transpose;
		uniform.count = 1;
		uniform.values = values;
	}

	void setTexture(@NonNull String name, @Nullable GlTexture texture) {
		var value = textures.get(name);
		if (value == null) {
			value = new TextureValue(name);
			textures.put(name, value);
		}
		value.active = texture != null;
		value.texture = texture;
	}

	@NonNull
	private UniformValue getOrCreateUniform(@NonNull String name, int type) {
		var uniform = uniforms.get(name);
		if (uniform == null) {
			uniform = new UniformValue(name, type);
			uniforms.put(name, uniform);
		}
		uniform.type = type;
		uniform.active = true;
		return uniform;
	}
}
