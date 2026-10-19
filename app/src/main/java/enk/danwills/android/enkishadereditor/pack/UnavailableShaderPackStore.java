package enk.danwills.android.enkishadereditor.pack;

import android.app.Activity;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.List;

import enk.danwills.android.enkishadereditor.R;

// The store until a real one (e.g. Play Billing) is wired up: nothing is
// for sale and nothing is owned, and trying to buy says so politely.
public final class UnavailableShaderPackStore implements ShaderPackStore {
	@Override
	public boolean isAvailable() {
		return false;
	}

	@Override
	public void queryStatus(@NonNull List<ShaderPackCatalog.Entry> entries,
			@NonNull StatusCallback callback) {
		var statusById = new HashMap<String, Status>();
		for (var entry : entries) {
			statusById.put(entry.id(), new Status(null, false));
		}
		callback.onStatus(statusById);
	}

	@Override
	public void purchase(@NonNull Activity activity, @NonNull ShaderPackCatalog.Entry entry,
			@NonNull PurchaseCallback callback) {
		callback.onPurchase(false, activity.getString(R.string.pack_purchases_unavailable));
	}
}
