package enk.danwills.android.enkishadereditor.io;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.opengl.HeadlessShaderCompiler;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinary;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinaryCache;
import enk.danwills.android.enkishadereditor.receiver.CompileAllCancelReceiver;

// Drives "Compile All Shaders" (see PreferencesFragment): warms the
// persisted compiled-program cache (ProgramBinaryCache) for every shader
// in the library at once, on a single dedicated background thread -
// HeadlessShaderCompiler's EGL context needs one consistent thread for
// its whole run - with a cancellable progress notification. At most one
// run is active at a time; a second request while one is already in
// flight is a no-op.
public final class CompileAllShadersRunner {
	private static final String CHANNEL_ID = "compile_all_shaders";
	private static final int NOTIFICATION_ID = 0xC0471E;

	@NonNull
	private static volatile HeadlessShaderCompiler activeCompiler = new HeadlessShaderCompiler();
	private static volatile boolean running = false;

	private CompileAllShadersRunner() {
	}

	public static void start(@NonNull Context context, @NonNull DataSource dataSource) {
		if (running) {
			return;
		}
		running = true;
		Context appContext = context.getApplicationContext();
		HeadlessShaderCompiler compiler = new HeadlessShaderCompiler();
		activeCompiler = compiler;

		Map<Long, String> sources = dataSource.shader.getAllShaderSources();
		int total = sources.size();
		List<HeadlessShaderCompiler.ShaderSource> shaders = new ArrayList<>(total);
		for (var entry : sources.entrySet()) {
			long id = entry.getKey();
			String source = entry.getValue();
			shaders.add(new HeadlessShaderCompiler.ShaderSource() {
				@Override
				public long id() {
					return id;
				}

				@Override
				@NonNull
				public String source() {
					return source;
				}
			});
		}

		ensureChannel(appContext);
		showProgress(appContext, 0, total, true);

		new Thread(() -> {
			ProgramBinaryCache cache = new ProgramBinaryCache() {
				@Override
				public ProgramBinary get(long shaderId, @NonNull String sourceHash,
						@NonNull String driverSignature) {
					return dataSource.shader.getCompiledBinary(
							shaderId, sourceHash, driverSignature);
				}

				@Override
				public void put(long shaderId, @NonNull ProgramBinary binary,
						@NonNull String sourceHash, @NonNull String driverSignature,
						long compileTimeMs) {
					dataSource.shader.saveCompiledBinary(
							shaderId, binary, sourceHash, driverSignature, compileTimeMs);
				}
			};
			compiler.compileAll(shaders, total, cache,
					done -> showProgress(appContext, done, total, true));
			running = false;
			dismiss(appContext);
		}, "CompileAllShaders").start();
	}

	// Called from CompileAllCancelReceiver (the notification's Cancel
	// action) - targets whichever run is currently active, if any.
	public static void requestCancel() {
		activeCompiler.cancel();
	}

	private static void ensureChannel(@NonNull Context context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return;
		}
		NotificationManager manager = context.getSystemService(NotificationManager.class);
		if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
			NotificationChannel channel = new NotificationChannel(
					CHANNEL_ID,
					context.getString(R.string.compile_all_shaders),
					NotificationManager.IMPORTANCE_LOW);
			manager.createNotificationChannel(channel);
		}
	}

	private static void showProgress(@NonNull Context context, int done, int total,
			boolean ongoing) {
		if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
			return;
		}
		Intent cancelIntent = new Intent(context, CompileAllCancelReceiver.class);
		PendingIntent cancelPendingIntent = PendingIntent.getBroadcast(
				context, 0, cancelIntent,
				PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

		var builder = new NotificationCompat.Builder(context, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_launcher_monochrome)
				.setContentTitle(context.getString(R.string.compile_all_shaders))
				.setContentText(context.getString(R.string.compile_all_progress, done, total))
				.setProgress(Math.max(total, 1), done, false)
				.setOngoing(ongoing)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setAutoCancel(!ongoing);
		if (ongoing) {
			builder.addAction(0, context.getString(android.R.string.cancel), cancelPendingIntent);
		}
		NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
	}

	private static void dismiss(@NonNull Context context) {
		NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
	}
}
