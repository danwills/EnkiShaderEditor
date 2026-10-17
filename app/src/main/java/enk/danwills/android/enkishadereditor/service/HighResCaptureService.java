package enk.danwills.android.enkishadereditor.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import enk.danwills.android.enkishadereditor.R;

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
public class HighResCaptureService extends Service {
	private static final String TAG = "HighResCapture";
	private static final String CHANNEL_ID = "high_res_capture";
	private static final int NOTIFICATION_ID = 2;

	public static void start(@NonNull Context context) {
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

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(R.string.high_res_capture_notification_title))
				.setSmallIcon(R.drawable.ic_launcher_monochrome)
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setOngoing(true)
				.build();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
			} else {
				startForeground(NOTIFICATION_ID, notification);
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
