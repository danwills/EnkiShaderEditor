package enk.danwills.android.enkishadereditor.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import enk.danwills.android.enkishadereditor.io.CompileAllShadersRunner;

// The Cancel action on the "Compile All Shaders" progress notification -
// see CompileAllShadersRunner.
public class CompileAllCancelReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(@NonNull Context context, @NonNull Intent intent) {
		CompileAllShadersRunner.requestCancel();
	}
}
