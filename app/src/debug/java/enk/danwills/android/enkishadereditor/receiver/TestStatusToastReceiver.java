package enk.danwills.android.enkishadereditor.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.Deque;

// Debug builds only: shows the `text` extra as a toast, over whatever is
// on screen - so Dan sees what Claude is doing while it drives his phone
// (tools/phone/status.sh sends it with an explicit `am broadcast`).
// A new message replaces the toast showing rather than queueing behind
// it, and stacks onto messages from the last few seconds - two lines
// at most, all a text toast shows. With the `long` extra (the last,
// "done" message) it shows on its own, twice over - about 7 s rather
// than a toast's 3.5.
public class TestStatusToastReceiver extends BroadcastReceiver {
	private static final long STACK_MILLIS = 5000;
	private static final int MAX_LINES = 2;
	// A long toast's time on screen, near enough.
	private static final long LONG_TOAST_MILLIS = 3500;

	private record Line(String text, long at) {
	}

	private static final Deque<Line> recent = new ArrayDeque<>();
	private static Toast showing;

	@Override
	public void onReceive(Context context, Intent intent) {
		String text = intent.getStringExtra("text");
		if (text == null || text.isEmpty()) {
			return;
		}
		if (intent.getBooleanExtra("long", false)) {
			recent.clear();
			show(context, text);
			// Kept alive (goAsync) until the second showing.
			PendingResult pending = goAsync();
			new Handler(Looper.getMainLooper()).postDelayed(() -> {
				show(context, text);
				pending.finish();
			}, LONG_TOAST_MILLIS);
			return;
		}
		long now = SystemClock.elapsedRealtime();
		while (!recent.isEmpty() && now - recent.peekFirst().at() > STACK_MILLIS) {
			recent.pollFirst();
		}
		recent.addLast(new Line(text, now));
		while (recent.size() > MAX_LINES) {
			recent.pollFirst();
		}
		StringBuilder lines = new StringBuilder();
		for (Line line : recent) {
			if (lines.length() > 0) {
				lines.append('\n');
			}
			lines.append(line.text());
		}
		show(context, lines);
	}

	private static void show(Context context, CharSequence text) {
		if (showing != null) {
			showing.cancel();
		}
		showing = Toast.makeText(context.getApplicationContext(), text, Toast.LENGTH_LONG);
		showing.show();
	}
}
