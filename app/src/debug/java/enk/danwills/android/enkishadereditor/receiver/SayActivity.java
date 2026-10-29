package enk.danwills.android.enkishadereditor.receiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
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
//   "clarify:<text>" on Send; Cancel puts the thumbs back. If `rate` has
//   given up waiting by then (the file's gone), it's sent as a message
//   instead, so it still reaches Claude.
// What's typed is never lost: it's kept in files/claude-draft as it's
// typed, until it's sent, so Cancel, Back, the activity being recreated
// or the app being killed all leave it there for next time (a tap outside
// the box doesn't close it). Leaving the box (Home, another app) closes
// it, words kept, and the panel's 💬 says so (✏️) - it can't be found
// again otherwise, being in a task of its own. While it's up, the panel's
// out of its way.
// A see-through activity in a task of its own, so closing it leaves the
// screen as it was.
public class SayActivity extends Activity {
	private static final String TAG = "SayActivity";
	private static final String DRAFT = "claude-draft";
	@Nullable
	private String rate;
	private boolean sent;
	@Nullable
	private AlertDialog dialog;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		rate = getIntent().getStringExtra("rate");
		if (rate != null && !rate.matches("[0-9A-Za-z_-]{1,64}")) {
			finish();
			return;
		}
		String question = getIntent().getStringExtra("question");
		if (rate != null && state == null) {
			write("claude-rate-" + rate, "typing", false);
		}
		boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
				== Configuration.UI_MODE_NIGHT_YES;
		var edit = new EditText(this);
		edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE |
				InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		edit.setMinLines(2);
		edit.setHint(rate != null ? "What should Claude know?" : "Say something to Claude");
		String draft = read(DRAFT);
		if (!draft.isEmpty()) {
			edit.setText(draft);
			edit.setSelection(draft.length());
		}
		edit.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				if (!sent) {
					write(DRAFT, s.toString(), false);
				}
			}
		});
		var frame = new FrameLayout(this);
		int pad = Math.round(20 * getResources().getDisplayMetrics().density);
		frame.setPadding(pad, pad / 2, pad, 0);
		frame.addView(edit);
		dialog = new AlertDialog.Builder(this, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
				: android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
				.setTitle(rate != null ? "💬 Let me clarify" : "💬 Tell Claude")
				.setMessage(rate != null && question != null ? question : null)
				.setView(frame)
				.setPositiveButton("Send", (d, w) -> send(edit.getText().toString().trim()))
				.setNegativeButton("Cancel", (d, w) -> cancel())
				.setOnCancelListener(d -> cancel())
				.setOnDismissListener(d -> finish())
				.create();
		dialog.setCanceledOnTouchOutside(false);
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
		boolean waited = rate != null && new File(getFilesDir(), "claude-rate-" + rate).exists();
		if (waited) {
			write("claude-rate-" + rate, "clarify:" + line, false);
		} else {
			String question = getIntent().getStringExtra("question");
			write("claude-messages", (rate != null && question != null
					? "(about \"" + question.replace('\n', ' ') + "\") " : "") + line + "\n", true);
		}
		new File(getFilesDir(), DRAFT).delete();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			StatusOverlay.get(getApplicationContext()).said(line, waited);
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

	@NonNull
	private String read(@NonNull String name) {
		try (var in = new FileInputStream(new File(getFilesDir(), name))) {
			var out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			for (int n; (n = in.read(buffer)) > 0; ) {
				out.write(buffer, 0, n);
			}
			return out.toString("UTF-8");
		} catch (IOException e) {
			return "";
		}
	}

	private void write(@NonNull String name, @NonNull String content, boolean append) {
		try (var out = new FileOutputStream(new File(getFilesDir(), name), append)) {
			out.write(content.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Log.w(TAG, "Couldn't write " + name, e);
		}
	}

	// Recreated (the app killed while away, say): the new one shows the box
	// again, with the draft, so this one's goes quietly.
	@Override
	protected void onDestroy() {
		if (dialog != null && dialog.isShowing() && !isFinishing()) {
			dialog.setOnDismissListener(null);
			dialog.dismiss();
		}
		super.onDestroy();
	}

	@Override
	protected void onResume() {
		super.onResume();
		// While the box is up, every status.sh command waits, so Claude's
		// taps can't land on it.
		write("claude-typing", "", false);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			StatusOverlay.get(getApplicationContext()).typing(true, false);
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (isChangingConfigurations()) {
			return;
		}
		new File(getFilesDir(), "claude-typing").delete();
		// Left (Home, another app) with the box still up: closed, the
		// words kept, and the thumbs back if Claude's still asking.
		if (!sent) {
			cancel();
			if (dialog != null && dialog.isShowing()) {
				dialog.setOnDismissListener(null);
				dialog.dismiss();
			}
			finish();
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			StatusOverlay.get(getApplicationContext()).typing(false, !read(DRAFT).trim().isEmpty());
		}
	}

	@Override
	public void finish() {
		super.finish();
		overridePendingTransition(0, 0);
	}
}
