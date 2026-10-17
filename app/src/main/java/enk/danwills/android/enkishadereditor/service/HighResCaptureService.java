package enk.danwills.android.enkishadereditor.service;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.preference.Preferences;

// A tiled high-res screenshot/sequence/video capture (see
// ShaderRenderer.captureHighResScreenshotOnSharedContext() and its
// sequence counterpart) runs on its own thread against a
// SharedCaptureContext, independent of the live GLSurfaceView's own
// context - so unlike that one, it keeps working fine if the app gets
// backgrounded mid-capture. The one thing backgrounding still
// threatens is the *process* itself getting killed outright before the
// capture finishes, same as any other backgrounded app. This service
// exists purely to prevent that, the same way AudioCaptureForegroundService
// does for audio capture - start it for the duration of the capture,
// then stop it; the actual work happens on MainActivity's own capture
// thread, never here.
//
// Also hosts two notification action buttons letting the frame-sequence
// capture thread's OS thread priority (Preferences.CAPTURE_THREAD_PRIORITY,
// via MainActivity.runFrameSequenceCapture()) be changed without
// reopening the app - see setCaptureThreadId()/onStartCommand() below.
public class HighResCaptureService extends Service {
	private static final String TAG = "HighResCapture";
	private static final String CHANNEL_ID = "high_res_capture";
	private static final int NOTIFICATION_ID = 2;
	private static final String ACTION_SET_PRIORITY_NORMAL =
			"enk.danwills.android.enkishadereditor.action.CAPTURE_PRIORITY_NORMAL";
	private static final String ACTION_SET_PRIORITY_HIGH =
			"enk.danwills.android.enkishadereditor.action.CAPTURE_PRIORITY_HIGH";

	// The capture thread's OS tid (android.os.Process.myTid(), not
	// java.lang.Thread's own id - Process.setThreadPriority() below
	// needs the former), recorded by MainActivity.runFrameSequenceCapture()
	// right after it starts, so the notification's priority buttons can
	// re-prioritize that *specific, currently running* thread live rather
	// than only affecting the next capture. -1 means no capture is
	// currently running (or its tid hasn't been recorded yet). Volatile:
	// set from the capture thread, read from whichever thread dispatches
	// a notification action (the main thread, same as any other
	// Service callback).
	private static volatile int captureThreadTid = -1;
	private static volatile int lastPercent = 0;

	public static void start(@NonNull Context context) {
		lastPercent = 0;
		try {
			ContextCompat.startForegroundService(
					context, new Intent(context, HighResCaptureService.class));
		} catch (RuntimeException e) {
			// e.g. ForegroundServiceStartNotAllowedException (API 31+) -
			// the capture itself still runs fine on its own thread either
			// way, it just loses the extra process-survival guarantee
			// this service exists to provide.
			Log.e(TAG, "startForegroundService failed", e);
		}
	}

	public static void stop(@NonNull Context context) {
		context.stopService(new Intent(context, HighResCaptureService.class));
	}

	// Called by MainActivity.runFrameSequenceCapture() right after it
	// sets this thread's own priority, and again with -1 once that
	// thread is done (success, failure, or cancellation) - a tid can be
	// reused by the OS for an unrelated thread once its original owner
	// exits, so leaving a stale one recorded could re-prioritize the
	// wrong thread entirely.
	public static void setCaptureThreadId(int tid) {
		captureThreadTid = tid;
	}

	public static void clearCaptureThreadId() {
		captureThreadTid = -1;
	}

	// Re-posts the same ongoing notification with a determinate progress
	// bar/percentage - safe to call even if the service was never started
	// (e.g. startForegroundService() above failed), since notifying an ID
	// that was never shown just... shows it, still tied to this service's
	// lifecycle (cleared by stop() above via stopService()). Callers
	// should only call this while a capture they themselves started the
	// service for is still running, same as start()/stop().
	// POST_NOTIFICATIONS (API 33+) isn't checked here - this only ever
	// updates the *same* ongoing notification startForeground() already
	// showed (exempt from that permission, same as any foreground
	// service's own notification), and if it were somehow revoked
	// between calls, NotificationManagerCompat.notify() just silently
	// drops the update rather than throwing - nothing to crash-guard.
	@SuppressLint("MissingPermission")
	public static void updateProgress(@NonNull Context context, int percent) {
		lastPercent = percent;
		NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, buildNotification(context, percent));
	}

	// Shared by onStartCommand() (initial notification, and the two
	// priority-action branches) and updateProgress() above, so every
	// notification shown for the life of one capture looks the same
	// (percent + progress bar + the same two priority buttons) regardless
	// of which of those triggered the rebuild. The priority buttons'
	// labels mark whichever level is currently selected with a leading
	// checkmark - Android gives action buttons no other way to show a
	// "selected" state.
	@NonNull
	private static Notification buildNotification(@NonNull Context context, int percent) {
		int currentLevel = ShaderEditorApp.preferences.getCaptureThreadPriorityLevel();
		NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
				.setContentTitle(context.getString(R.string.high_res_capture_notification_title))
				.setContentText(percent + "%")
				.setSmallIcon(R.drawable.ic_launcher_monochrome)
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setOngoing(true)
				.setProgress(100, percent, false)
				.addAction(0,
						(currentLevel == Preferences.CAPTURE_PRIORITY_NORMAL ? "✓ " : "") +
								context.getString(R.string.capture_priority_action_normal),
						priorityActionIntent(context, ACTION_SET_PRIORITY_NORMAL))
				.addAction(0,
						(currentLevel == Preferences.CAPTURE_PRIORITY_HIGH ? "✓ " : "") +
								context.getString(R.string.capture_priority_action_high),
						priorityActionIntent(context, ACTION_SET_PRIORITY_HIGH));
		return builder.build();
	}

	@NonNull
	private static PendingIntent priorityActionIntent(@NonNull Context context, @NonNull String action) {
		Intent intent = new Intent(context, HighResCaptureService.class).setAction(action);
		return PendingIntent.getService(context, action.hashCode(), intent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager manager = getSystemService(NotificationManager.class);
			if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
				NotificationChannel channel = new NotificationChannel(
						CHANNEL_ID,
						getString(R.string.high_res_capture_channel_name),
						NotificationManager.IMPORTANCE_MIN);
				manager.createNotificationChannel(channel);
			}
		}
	}

	// Same MissingPermission non-issue as updateProgress() above for the
	// priority-action branch's own notify() call.
	@SuppressLint("MissingPermission")
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		String action = intent == null ? null : intent.getAction();
		if (ACTION_SET_PRIORITY_NORMAL.equals(action) || ACTION_SET_PRIORITY_HIGH.equals(action)) {
			int level = ACTION_SET_PRIORITY_HIGH.equals(action)
					? Preferences.CAPTURE_PRIORITY_HIGH
					: Preferences.CAPTURE_PRIORITY_NORMAL;
			ShaderEditorApp.preferences.setCaptureThreadPriorityLevel(level);
			int tid = captureThreadTid;
			if (tid >= 0) {
				try {
					Process.setThreadPriority(tid, ShaderEditorApp.preferences.getCaptureThreadPriority());
				} catch (IllegalArgumentException | SecurityException e) {
					// The capture thread finished between reading
					// captureThreadTid and this call - the preference
					// is still updated above for next time, which is
					// all that's left to do.
					Log.e(TAG, "setThreadPriority(" + tid + ") failed", e);
				}
			}
			NotificationManagerCompat.from(this).notify(
					NOTIFICATION_ID, buildNotification(this, lastPercent));
			return START_NOT_STICKY;
		}

		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(NOTIFICATION_ID, buildNotification(this, lastPercent),
						ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(NOTIFICATION_ID, buildNotification(this, lastPercent));
			}
		} catch (RuntimeException e) {
			Log.e(TAG, "startForeground failed", e);
			stopSelf();
		}
		return START_NOT_STICKY;
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
