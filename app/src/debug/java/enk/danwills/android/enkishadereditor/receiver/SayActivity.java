package enk.danwills.android.enkishadereditor.receiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Debug builds only: Dan typing (or dictating, with the keyboard's mic) a
// note to Claude while it drives the phone. StatusOverlay opens it from
// two buttons:
// - 💬 beside Pause: a message any time. It's appended to
//   files/claude-messages, one line each, which every status.sh command
//   reads and prints for Claude ("💬 Dan says: ...").
// - Clarify beside the thumbs (`rate` set to the question's id): "let me
//   clarify" instead of a thumb. It writes "typing" to
//   files/claude-rate-<id> at once, so status.sh waits longer, then
//   "clarify:<text>" on Send; Cancel puts the thumbs back.
// A see-through activity in a task of its own, so closing it leaves the
// screen as it was.
public class SayActivity extends Activity {
	private static final String TAG = "SayActivity";
	@Nullable
	private String rate;
	private boolean sent;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (state != null) {
			finish();
			return;
		}
		rate = getIntent().getStringExtra("rate");
		if (rate != null && !rate.matches("[0-9A-Za-z_-]{1,64}")) {
			finish();
			return;
		}
		String question = getIntent().getStringExtra("question");
		if (rate != null) {
			write("claude-rate-" + rate, "typing", false);
		}
		boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
				== Configuration.UI_MODE_NIGHT_YES;
		var edit = new EditText(this);
		edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
				InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		edit.setMinLines(2);
		edit.setHint(rate != null ? "What should Claude know?" : "Say something to Claude");
		var frame = new FrameLayout(this);
		int pad = Math.round(20 * getResources().getDisplayMetrics().density);
		frame.setPadding(pad, pad / 2, pad, 0);
		frame.addView(edit);
		var dialog = new AlertDialog.Builder(this, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
				: android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
				.setTitle(rate != null ? "💬 Let me clarify" : "💬 Tell Claude")
				.setMessage(rate != null && question != null ? question : null)
				.setView(frame)
				.setPositiveButton("Send", (d, w) -> send(edit.getText().toString().trim()))
				.setNegativeButton("Cancel", (d, w) -> cancel())
				.setOnCancelListener(d -> cancel())
				.setOnDismissListener(d -> finish())
				.create();
		if (dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
		}
		dialog.show();
		edit.requestFocus();
	}

	private void send(@NonNull String text) {
		if (sent) {
			return;
		}
		if (text.isEmpty()) {
			cancel();
			return;
		}
		sent = true;
		// One line each in the file.
		String line = text.replace('\n', ' ').replace('\r', ' ');
		if (rate != null) {
			write("claude-rate-" + rate, "clarify:" + line, false);
		} else {
			write("claude-messages", line + "\n", true);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			StatusOverlay.get(getApplicationContext()).said(line, rate != null);
		}
	}

	private void cancel() {
		if (sent) {
			return;
		}
		sent = true;
		if (rate != null) {
			new File(getFilesDir(), "claude-rate-" + rate).delete();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				StatusOverlay.get(getApplicationContext()).askRating(rate, null);
			}
		}
	}

	private void write(@NonNull String name, @NonNull String content, boolean append) {
		try (var out = new FileOutputStream(new File(getFilesDir(), name), append)) {
			out.write(content.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Log.w(TAG, "Couldn't write " + name, e);
		}
	}

	@Override
	public void finish() {
		super.finish();
		overridePendingTransition(0, 0);
	}
}
