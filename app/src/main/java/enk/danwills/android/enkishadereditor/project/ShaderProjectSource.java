package enk.danwills.android.enkishadereditor.project;

import androidx.annotation.NonNull;

public interface ShaderProjectSource {
	@NonNull
	ShaderProjectOrigin getOrigin();

	@NonNull
	ShaderProjectSession openSession();
}
