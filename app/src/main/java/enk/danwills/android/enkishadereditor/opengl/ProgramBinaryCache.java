package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Lets the renderer read/write a persisted compiled-program cache without
// depending on the database layer directly. get() is called from the GL
// thread and expected to return promptly (a single indexed row lookup);
// put() is also called from the GL thread but must not block it - an
// implementation should hand the actual write off to a background thread.
public interface ProgramBinaryCache {
	@Nullable
	ProgramBinary get(long shaderId, @NonNull String sourceHash, @NonNull String driverSignature);

	void put(long shaderId, @NonNull ProgramBinary binary,
			@NonNull String sourceHash, @NonNull String driverSignature);
}
