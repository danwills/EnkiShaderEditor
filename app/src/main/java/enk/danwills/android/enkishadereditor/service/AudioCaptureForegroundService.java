package enk.danwills.android.enkishadereditor.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import enk.danwills.android.enkishadereditor.R;

// Since Android 14 (API 34), creating a MediaProjection-based capture
// (including AudioPlaybackCaptureConfiguration, used for the audioSpectrum
// uniform's system-audio mode) requires a running foreground service
// declared with foregroundServiceType="mediaProjection", or the system
// rejects the capture outright. This service exists purely to satisfy
// that requirement; the actual capture still happens via AudioRecord on
// its own thread, not here.
public class AudioCaptureForegroundService extends Service {
	// Lets a caller find out once startForeground() has actually
	// succeeded (or failed), since starting the service and it actually
	// reaching that point are two different events in time -
	// MediaProjectionManager.getMediaProjection() requires the latter to
	// have already happened on API 34+, or it throws SecurityException.
	public interface Listener {
		void onForegroundServiceStarted();

		void onForegroundServiceFailed();
	}

	private static final String TAG = "AudioCapture";
	private static final String CHANNEL_ID = "audio_capture";
	private static final int NOTIFICATION_ID = 1;

	@Nullable
	private static volatile Listener listener;

	public static void setListener(@Nullable Listener listener) {
		AudioCaptureForegroundService.listener = listener;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager manager = getSystemService(NotificationManager.class);
			if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
				NotificationChannel channel = new NotificationChannel(
						CHANNEL_ID,
						getString(R.string.audio_capture_channel_name),
						NotificationManager.IMPORTANCE_MIN);
				manager.createNotificationChannel(channel);
			}
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(R.string.audio_capture_notification_title))
				.setSmallIcon(R.drawable.ic_launcher_monochrome)
				.setPriority(NotificationCompat.PRIORITY_MIN)
				.setOngoing(true)
				.build();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(
						NOTIFICATION_ID,
						notification,
						ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
			} else {
				startForeground(NOTIFICATION_ID, notification);
			}
			Listener currentListener = listener;
			if (currentListener != null) {
				currentListener.onForegroundServiceStarted();
			}
		} catch (RuntimeException e) {
			// e.g. ForegroundServiceStartNotAllowedException (API 31+) if
			// Android doesn't consider the app sufficiently foreground at
			// this exact moment. Stop ourselves rather than let this crash
			// the whole app; the caller falls back to the microphone.
			Log.e(TAG, "startForeground failed", e);
			Listener currentListener = listener;
			if (currentListener != null) {
				currentListener.onForegroundServiceFailed();
			}
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
