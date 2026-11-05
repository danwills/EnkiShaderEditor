package enk.danwills.android.enkishadereditor.service;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.io.ScreenRecorder;

// Records the screen (see ScreenRecorder) for as long as it runs: a
// foreground service of type mediaProjection, as Android 14+ needs one
// running before getMediaProjection(), and microphone too for the voice
// (without it, Android 14+ hands a service's mic capture silence), and
// one that outlives the
// activity being recreated (a rotation) mid-recording. Its notification
// stops it; More Options' Record Screen does too, while isRecording(), as
// does volume-down in MainActivity.
// API 29+ (see ScreenRecorder); started only there.
public class ScreenRecordService extends Service {
	private static final String TAG = "ScreenRecord";
	private static final String CHANNEL_ID = "screen_record";
	private static final int NOTIFICATION_ID = 3;
	private static final int SAVED_NOTIFICATION_ID = 4;
	private static final String ACTION_STOP = "stop";
	private static final String EXTRA_RESULT_CODE = "result_code";
	private static final String EXTRA_DATA = "data";
	private static final String EXTRA_SOUND_LEVEL = "sound_level";
	private static final String EXTRA_VOICE = "voice";
	private static final String EXTRA_DUCK = "duck";

	private static volatile boolean recording;
	// Told when a recording starts, on the main thread (MainActivity, so
	// its audio input can carry on with the recording's projection).
	@Nullable
	private static Runnable onRecordingStarted;

	// (A ScreenRecorder, API 29+.)
	@Nullable
	private ScreenRecorder recorder;
	@Nullable
	private MediaProjection projection;

	public static boolean isRecording() {
		return recording;
	}

	public static void setOnRecordingStarted(@Nullable Runnable onRecordingStarted) {
		ScreenRecordService.onRecordingStarted = onRecordingStarted;
	}

	// Starts recording with a MediaProjection consent's resultCode and
	// data: soundLevel, what's playing (0 for none, 1 as it is); voice,
	// the microphone; duck, lowering what's playing while the voice talks.
	public static void start(@NonNull Context context, int resultCode, @NonNull Intent data,
			float soundLevel, boolean voice, boolean duck) {
		Intent intent = new Intent(context, ScreenRecordService.class)
				.putExtra(EXTRA_RESULT_CODE, resultCode)
				.putExtra(EXTRA_DATA, data)
				.putExtra(EXTRA_SOUND_LEVEL, soundLevel)
				.putExtra(EXTRA_VOICE, voice)
				.putExtra(EXTRA_DUCK, duck);
		recording = true;
		try {
			ContextCompat.startForegroundService(context, intent);
		} catch (RuntimeException e) {
			Log.e(TAG, "couldn't start", e);
			recording = false;
			Toast.makeText(context, R.string.record_screen_failed, Toast.LENGTH_LONG).show();
		}
	}

	public static void stop(@NonNull Context context) {
		context.startService(new Intent(context, ScreenRecordService.class).setAction(ACTION_STOP));
	}

	@Override
	public void onCreate() {
		super.onCreate();
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null &&
				manager.getNotificationChannel(CHANNEL_ID) == null) {
			manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
					getString(R.string.screen_record_channel_name),
					NotificationManager.IMPORTANCE_LOW));
		}
	}

	// ForegroundServiceType: Ultra Rest Area's manifest removes this
	// service - this never runs there, but lint can't tell.
	@SuppressLint("ForegroundServiceType")
	@Override
	public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
		if (intent == null || ACTION_STOP.equals(intent.getAction())) {
			if (recorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				recorder.stop();
			} else {
				finishService();
			}
			return START_NOT_STICKY;
		}
		if (recorder != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			return START_NOT_STICKY;
		}
		PendingIntent stop = PendingIntent.getService(this, 0,
				new Intent(this, ScreenRecordService.class).setAction(ACTION_STOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(R.string.screen_record_notification_title))
				.setContentText(getString(R.string.screen_record_notification_text))
				.setSmallIcon(R.drawable.ic_launcher_monochrome)
				.setOngoing(true)
				.addAction(0, getString(R.string.stop_recording), stop)
				.build();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
				if (intent.getBooleanExtra(EXTRA_VOICE, false) &&
						ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
								PackageManager.PERMISSION_GRANTED) {
					types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
				}
				startForeground(NOTIFICATION_ID, notification, types);
			} else {
				startForeground(NOTIFICATION_ID, notification);
			}
			MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
			Intent data = intent.getParcelableExtra(EXTRA_DATA);
			projection = manager != null && data != null
					? manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
					: null;
		} catch (RuntimeException e) {
			Log.e(TAG, "couldn't start recording", e);
			projection = null;
		}
		if (projection == null) {
			failed();
			return START_NOT_STICKY;
		}
		DisplayMetrics metrics = new DisplayMetrics();
		DisplayManager displays = getSystemService(DisplayManager.class);
		Display display = displays != null ? displays.getDisplay(Display.DEFAULT_DISPLAY) : null;
		if (display != null) {
			display.getRealMetrics(metrics);
		} else {
			metrics = getResources().getDisplayMetrics();
		}
		startRecorder(intent, metrics);
		return START_NOT_STICKY;
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private void startRecorder(@NonNull Intent intent, @NonNull DisplayMetrics metrics) {
		recorder = new ScreenRecorder(this, projection,
				intent.getFloatExtra(EXTRA_SOUND_LEVEL, 1f),
				intent.getBooleanExtra(EXTRA_VOICE, true),
				intent.getBooleanExtra(EXTRA_DUCK, true),
				this::onFinished);
		if (!recorder.start(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)) {
			recorder = null;
			failed();
			return;
		}
		SharedProjection.share(projection);
		Runnable started = onRecordingStarted;
		if (started != null) {
			started.run();
		}
	}

	private void failed() {
		new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(getApplicationContext(),
				R.string.record_screen_failed, Toast.LENGTH_LONG).show());
		finishService();
	}

	// From the recorder's thread.
	private void onFinished(@Nullable Uri uri) {
		new Handler(Looper.getMainLooper()).post(() -> {
			if (uri != null) {
				Toast.makeText(getApplicationContext(), R.string.record_screen_saved,
						Toast.LENGTH_LONG).show();
				notifySaved(uri);
			} else {
				Toast.makeText(getApplicationContext(), R.string.record_screen_failed,
						Toast.LENGTH_LONG).show();
			}
			recorder = null;
			finishService();
		});
	}

	// MissingPermission/NotificationPermission: without
	// POST_NOTIFICATIONS it just doesn't show (and Ultra Rest Area's
	// manifest removes this service along with that permission).
	@SuppressLint({"MissingPermission", "NotificationPermission"})
	private void notifySaved(@NonNull Uri uri) {
		NotificationManager manager = getSystemService(NotificationManager.class);
		if (manager == null) {
			return;
		}
		Intent view = new Intent(Intent.ACTION_VIEW)
				.setDataAndType(uri, "video/mp4")
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
		manager.notify(SAVED_NOTIFICATION_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
				.setContentTitle(getString(R.string.record_screen_saved))
				.setSmallIcon(R.drawable.ic_launcher_monochrome)
				.setAutoCancel(true)
				.setContentIntent(PendingIntent.getActivity(this, 0, view,
						PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
				.build());
	}

	private void finishService() {
		recording = false;
		if (projection != null) {
			// (Enki's audio input may be using it still.)
			SharedProjection.release(projection);
			projection = null;
		}
		stopForeground(STOP_FOREGROUND_REMOVE);
		stopSelf();
	}

	@Override
	public void onDestroy() {
		if (recorder != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			recorder.stop();
		}
		recording = false;
		super.onDestroy();
	}

	@Nullable
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}
