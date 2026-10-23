package com.example.phonekit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

// Debug builds only: shows what Claude is doing while it drives Dan's
// phone (tools/phone/status.sh sends it with an explicit `am broadcast`).
// Extras: `text`; `kind` - its icon and colour (see StatusOverlay.icon());
// `eta` - how long its step's likely to take ("?" for no estimate);
// `took` - how long the newest line's step actually took (on its own,
// amending that line); `rate` - an id: thumbs up/down shown for it, its
// answer written to files/claude-rate-<id>; `rate_clear` - thumbs gone;
// `hide` - the panel out of sight, for a clean screenshot; `done` - the
// run's over, the panel gone at once (the notification has the log's end). Shown in StatusOverlay
// over whatever's on screen, or - without the overlay permission, which
// status.sh grants with appops - as a plain toast.
public class TestStatusReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		Context app = context.getApplicationContext();
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Settings.canDrawOverlays(app)) {
			String text = intent.getStringExtra("text");
			if (text != null && !text.isEmpty()) {
				Toast.makeText(app, text, Toast.LENGTH_LONG).show();
			}
			return;
		}
		var overlay = StatusOverlay.get(app);
		if (intent.getBooleanExtra("done", false)) {
			overlay.clear();
			return;
		}
		if (intent.getBooleanExtra("rate_clear", false)) {
			overlay.clearRating();
			return;
		}
		String rate = intent.getStringExtra("rate");
		if (rate != null && rate.matches("[0-9A-Za-z_-]{1,64}")) {
			overlay.askRating(rate);
		}
		String took = intent.getStringExtra("took");
		if (took != null && !took.isEmpty()) {
			overlay.amendLast(took);
			return;
		}
		if (intent.getBooleanExtra("hide", false)) {
			overlay.hide();
			return;
		}
		String text = intent.getStringExtra("text");
		if (text == null || text.isEmpty()) {
			return;
		}
		overlay.add(text, intent.getStringExtra("kind"), intent.getStringExtra("eta"));
	}
}
