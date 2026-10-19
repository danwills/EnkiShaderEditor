package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;

import java.util.List;

// Whether any of a shader's programs declares (and actually uses) a given
// uniform - so the built-in uniform modules can tell which sensors,
// camera and so on to start when a multipass shader uses one only in a
// buffer pass, not the Image pass.
interface UniformPresence {
	boolean has(@NonNull String name);

	@NonNull
	static UniformPresence of(@NonNull GlDevice device, @NonNull List<GlProgram> programs) {
		return name -> {
			for (var program : programs) {
				if (device.hasUniform(program, name)) {
					return true;
				}
			}
			return false;
		};
	}
}
