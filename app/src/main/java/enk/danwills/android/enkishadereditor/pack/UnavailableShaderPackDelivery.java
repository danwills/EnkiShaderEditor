package enk.danwills.android.enkishadereditor.pack;

import android.content.Context;

import androidx.annotation.NonNull;

import enk.danwills.android.enkishadereditor.R;

// Delivery until a real one (e.g. Play Asset Delivery) is wired up.
public final class UnavailableShaderPackDelivery implements ShaderPackDelivery {
	@NonNull
	private final Context context;

	public UnavailableShaderPackDelivery(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	@Override
	public void fetch(@NonNull ShaderPackCatalog.Entry entry, @NonNull Callback callback) {
		callback.onDelivered(null, context.getString(R.string.pack_purchases_unavailable));
	}
}
