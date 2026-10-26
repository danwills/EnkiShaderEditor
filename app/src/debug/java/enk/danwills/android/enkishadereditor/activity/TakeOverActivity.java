package enk.danwills.android.enkishadereditor.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Debug builds only: Claude asking before it drives the phone
// (tools/phone/status.sh ask, which starts this with `am start`). A
// see-through activity over whatever's showing, in a task of its own, so
// answering leaves things as they were. Unanswered for `wait` seconds, it
// says Claude will go ahead in `countdown` seconds, and then does (Dan's
// choice: never stuck waiting on a click). Hang on a sec gets it out of
// the way for HOLD_SECONDS (the notification's Hang on, another lot) -
// for when it's nearly OK but Dan needs a moment to finish what he's
// doing - and then status.sh brings it back (an app in the background
// can't bring itself forward), counting down again.
// Only the platform dialog and AndroidX Core, so it drops into any app,
// Compose-only ones without AppCompat or Material Components included.
// A tap outside doesn't close it;
// left unanswered (another app opened over it, say), it posts a
// notification with the same answers, which also brings it back. The
// answer - yes or no, Back being no - goes to files/takeover-<id> in the
// app's storage, which status.sh reads with run-as; a hold writes
// "hold:<holds so far>:<seconds away>" there first, so status.sh knows
// when to bring it back.
public class TakeOverActivity extends Activity {
	private static final String TAG = "TakeOver";
	private static final String CHANNEL = "claude_takeover";
	private static final int NOTIFICATION = 7301;
	private static final String ANSWER = "answer";
	private static final String HOLD = "hold";
	private static final int HOLD_SECONDS = 30;
	// Buttons ignore taps this long after the dialog appears, so a tap
	// meant for the app underneath (it can pop up mid-typing) doesn't
	// answer it.
	private static final long ARM_MS = 1500;
	private boolean answered;
	@Nullable
	private String id;
	private boolean warned;
	private final Handler handler = new Handler(Looper.getMainLooper());
	@Nullable
	private AlertDialog dialog;
	@Nullable
	private String message;
	private int countdown;
	// Seconds of hold left, while held, and how many holds - see hold().
	private int held;
	private int holds;
	// A hold's over but it's not back on screen yet: the countdown waits
	// for it (onStart()).
	private boolean due;
	private boolean started;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (state != null) {
			// Recreated (rotation): the dialog went with the old one.
			finish();
			return;
		}
		id = getIntent().getStringExtra("id");
		String text = getIntent().getStringExtra("text");
		if (id == null || !id.matches("[0-9A-Za-z_-]{1,64}")) {
			finish();
			return;
		}
		message = text != null && !text.isEmpty() ? text
				: "Claude would like to drive the phone for a test.";
		int wait = getIntent().getIntExtra("wait", 15);
		countdown = getIntent().getIntExtra("countdown", 15);
		boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
				== Configuration.UI_MODE_NIGHT_YES;
		dialog = new AlertDialog.Builder(this, night ? android.R.style.Theme_DeviceDefault_Dialog_Alert
				: android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
				.setTitle("🤖 Can Claude use your phone?")
				.setMessage(message)
				.setPositiveButton("Go ahead", (d, w) -> answer(id, "yes"))
				.setNegativeButton("Not now", (d, w) -> answer(id, "no"))
				.setNeutralButton("Hang on a sec", null)
				.setOnCancelListener(d -> answer(id, "no"))
				.setOnDismissListener(d -> {
					if (answered) {
						finish();
					}
				})
				.create();
		dialog.setCanceledOnTouchOutside(false);
		dialog.show();
		// Set after show() so it doesn't close the dialog.
		dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> hold());
		arm();
		String shown = message;
		handler.postDelayed(() -> count(id, shown, countdown), wait * 1000L);
	}

	// Greys the buttons (and ignores Back) for ARM_MS: see ARM_MS.
	private final Runnable armed = () -> enable(true);

	private void arm() {
		enable(false);
		handler.removeCallbacks(armed);
		handler.postDelayed(armed, ARM_MS);
	}

	private void enable(boolean on) {
		if (dialog == null) {
			return;
		}
		dialog.setCancelable(on);
		dialog.setCanceledOnTouchOutside(false);
		for (int which : new int[] {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
				AlertDialog.BUTTON_NEUTRAL}) {
			var button = dialog.getButton(which);
			if (button != null) {
				button.setEnabled(on);
			}
		}
	}

	// Hang on a sec: out of the way for HOLD_SECONDS more, however far it
	// had got - another lot each tap - and status.sh told when to bring
	// it back.
	private void hold() {
		if (answered || dialog == null || id == null || message == null) {
			return;
		}
		held += HOLD_SECONDS;
		++holds;
		write(id, HOLD + ":" + holds + ":" + held);
		moveTaskToBack(true);
		handler.removeCallbacksAndMessages(null);
		if (warned) {
			warned = false;
			dialog.setIcon(null);
		}
		holding();
	}

	private void holding() {
		if (answered || dialog == null || id == null || message == null) {
			return;
		}
		if (held <= 0) {
			if (started) {
				count(id, message, countdown);
			} else {
				due = true;
			}
			return;
		}
		dialog.setTitle("🤖 Waiting for you - " + held + " s");
		dialog.setMessage(message + "\n\nTake your time: Go ahead when you're ready" +
				" (Hang on a sec for longer).");
		--held;
		handler.postDelayed(this::holding, 1000);
	}

	// No answer yet: Claude goes ahead in `left` seconds unless told not to.
	private void count(@NonNull String id, @NonNull String message, int left) {
		if (answered || dialog == null) {
			return;
		}
		if (left <= 0) {
			answer(id, "yes");
			return;
		}
		// A warning now, so it stands out from the question.
		if (!warned) {
			warned = true;
			Drawable icon = ContextCompat.getDrawable(this, android.R.drawable.ic_dialog_alert);
			if (icon != null) {
				icon = icon.mutate();
				icon.setTint(Color.rgb(255, 200, 40));
				dialog.setIcon(icon);
			}
		}
		dialog.setTitle("Claude will go ahead in " + left + " s");
		dialog.setMessage(message + "\n\nNo answer, so Claude will carry on unless you say not now.");
		handler.postDelayed(() -> count(id, message, left - 1), 1000);
	}

	// The notification's buttons (and its body, which just brings this
	// back) arrive here.
	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		String answer = intent.getStringExtra(ANSWER);
		if (HOLD.equals(answer)) {
			hold();
		} else if (id != null && answer != null) {
			answer(id, answer);
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		started = false;
		if (!answered && !isFinishing()) {
			notifyUnanswered();
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		started = true;
		getSystemService(NotificationManager.class).cancel(NOTIFICATION);
		// Back on screen (after a hold, or from the notification): it's
		// popped up again, so it waits again before taking taps.
		if (!answered && dialog != null) {
			arm();
		}
		if (due && !answered && id != null && message != null) {
			due = false;
			count(id, message, countdown);
		}
	}

	private void notifyUnanswered() {
		var manager = getSystemService(NotificationManager.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
				manager.getNotificationChannel(CHANNEL) == null) {
			manager.createNotificationChannel(new NotificationChannel(CHANNEL,
					"Claude asking to use the phone", NotificationManager.IMPORTANCE_HIGH));
		}
		var notification = new NotificationCompat.Builder(this, CHANNEL)
				.setSmallIcon(android.R.drawable.ic_dialog_alert)
				.setContentTitle("🤖 Claude is waiting to use your phone")
				.setContentText(held > 0 ? "It'll ask again in " + held + " s."
						: "It'll go ahead soon unless you say not now.")
				.setContentIntent(reply(null, 0))
				.addAction(0, "Not now", reply("no", 1))
				.addAction(0, "Hang on", reply(HOLD, 3))
				.addAction(0, "Go ahead", reply("yes", 2))
				.setAutoCancel(true)
				.build();
		try {
			manager.notify(NOTIFICATION, notification);
		} catch (SecurityException e) {
			Log.w(TAG, "Can't post the notification", e);
		}
	}

	@NonNull
	private PendingIntent reply(@Nullable String answer, int code) {
		var intent = new Intent(this, TakeOverActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		if (answer != null) {
			intent.putExtra(ANSWER, answer);
		}
		return PendingIntent.getActivity(this, code, intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	private void answer(@NonNull String id, @NonNull String answer) {
		if (answered) {
			return;
		}
		answered = true;
		handler.removeCallbacksAndMessages(null);
		getSystemService(NotificationManager.class).cancel(NOTIFICATION);
		write(id, answer);
		if (dialog != null && dialog.isShowing()) {
			dialog.dismiss();
		}
		finish();
	}

	// What status.sh reads back: an answer, or a hold.
	private void write(@NonNull String id, @NonNull String answer) {
		File file = new File(getFilesDir(), "takeover-" + id);
		try (var out = new FileOutputStream(file)) {
			out.write(answer.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Log.w(TAG, "Couldn't write the answer", e);
		}
	}

	@Override
	public void finish() {
		super.finish();
		overridePendingTransition(0, 0);
	}
}
