package enk.danwills.android.enkishadereditor.pack;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;

// Buying shader packs - see ShaderPackCatalog for what's on offer and
// ShaderPackDelivery for getting a bought one onto the device. Callbacks
// arrive on the main thread.
public interface ShaderPackStore {
	// What the store says about one pack: its localised price (null if
	// unknown) and whether this user already owns it.
	record Status(@Nullable String price, boolean owned) {
	}

	interface StatusCallback {
		void onStatus(@NonNull Map<String, Status> statusById);
	}

	interface PurchaseCallback {
		// success: the pack is now owned (deliver and install it);
		// otherwise `message` says why not, for the user.
		void onPurchase(boolean success, @Nullable String message);
	}

	// Whether this build can buy anything at all.
	boolean isAvailable();

	void queryStatus(@NonNull List<ShaderPackCatalog.Entry> entries,
			@NonNull StatusCallback callback);

	void purchase(@NonNull Activity activity, @NonNull ShaderPackCatalog.Entry entry,
			@NonNull PurchaseCallback callback);
}
