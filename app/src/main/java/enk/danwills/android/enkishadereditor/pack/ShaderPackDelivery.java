package enk.danwills.android.enkishadereditor.pack;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;

// Getting an owned shader pack's file onto the device (e.g. a Play Asset
// Delivery on-demand pack), for ShaderPackInstaller to install. Callbacks
// arrive on the main thread.
public interface ShaderPackDelivery {
	interface Callback {
		// The delivered pack file, or null with a message for the user.
		void onDelivered(@Nullable File packFile, @Nullable String message);
	}

	void fetch(@NonNull ShaderPackCatalog.Entry entry, @NonNull Callback callback);
}
