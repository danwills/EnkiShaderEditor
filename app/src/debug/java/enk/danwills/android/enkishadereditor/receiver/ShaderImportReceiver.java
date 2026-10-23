package enk.danwills.android.enkishadereditor.receiver;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import enk.danwills.android.enkishadereditor.activity.MainActivity;
import enk.danwills.android.enkishadereditor.activity.ShaderImportActivity;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.io.ShaderPackInstaller;
import enk.danwills.android.enkishadereditor.project.MultipassSource;

// Debug builds only: puts a shader into the library from the test
// tooling (tools/phone/add_shader.sh). The shader's text - one pass, or
// multipass text with `//// Buffer` and `//// Text` sections - is a file in
// the app's own files/import/ (written there with run-as), named by the
// `file` extra; `name` is the shader's name. A shader of that name already
// in the library is replaced in place, keeping its id (so its place,
// thumbnail and settings); otherwise it's added. `quality` (default 1/4)
// is for a new one. The file is deleted afterwards.
//
// With the app on screen, the open shader could be saved over the import,
// so it asks first instead - see ShaderImportActivity.
//
// A `pack` extra instead names a shader pack file in files/import/
// (tools/phone/add_pack.sh), installed as Get Shader Pack -> Install from
// File would; it adds shaders without touching the open one, so it needn't
// ask.
public class ShaderImportReceiver extends BroadcastReceiver {
	private static final String TAG = "ShaderImport";

	public record Result(long id, @NonNull String message) {
	}

	@Override
	public void onReceive(Context context, Intent intent) {
		String pack = intent.getStringExtra("pack");
		if (pack != null) {
			if (pack.isEmpty() || pack.contains("/")) {
				Log.w(TAG, "Needs a bare `pack` file");
				return;
			}
			PendingResult pending = goAsync();
			installPackAsync(context.getApplicationContext(), pack, pending::finish);
			return;
		}
		String name = intent.getStringExtra("name");
		String file = intent.getStringExtra("file");
		float quality = intent.getFloatExtra("quality", 0.25f);
		if (name == null || name.isEmpty() || file == null || file.contains("/")) {
			Log.w(TAG, "Needs `name` and a bare `file`");
			return;
		}
		Context app = context.getApplicationContext();
		if (isOnScreen()) {
			Log.i(TAG, "Asking about " + name);
			ShaderImportActivity.start(app, name, file, quality, isEditorOnTop(app));
			return;
		}
		PendingResult pending = goAsync();
		importAsync(app, name, file, quality, result -> pending.finish());
	}

	public interface Done {
		void onDone(@NonNull Result result);
	}

	// Imports on a thread, then toasts and calls back on the main thread.
	public static void importAsync(@NonNull Context app, @NonNull String name,
			@NonNull String file, float quality, @NonNull Done done) {
		new Thread(() -> {
			Result result;
			try {
				result = importShader(app, name, importFile(app, file), quality);
			} catch (IOException e) {
				result = new Result(0, "Can't import " + name + ": " + e.getMessage());
			}
			Log.i(TAG, result.message());
			Result finished = result;
			new Handler(Looper.getMainLooper()).post(() -> {
				Toast.makeText(app, finished.message(), Toast.LENGTH_LONG).show();
				done.onDone(finished);
			});
		}, "ShaderImport").start();
	}

	private static void installPackAsync(@NonNull Context app, @NonNull String file,
			@NonNull Runnable done) {
		new Thread(() -> {
			File pack = importFile(app, file);
			var result = ShaderPackInstaller.install(app, pack);
			//noinspection ResultOfMethodCallIgnored
			pack.delete();
			Log.i(TAG, result.message());
			new Handler(Looper.getMainLooper()).post(() -> {
				Toast.makeText(app, result.message(), Toast.LENGTH_LONG).show();
				done.run();
			});
		}, "PackImport").start();
	}

	@NonNull
	public static File importFile(@NonNull Context app, @NonNull String file) {
		return new File(new File(app.getFilesDir(), "import"), file);
	}

	private static boolean isOnScreen() {
		var state = new ActivityManager.RunningAppProcessInfo();
		ActivityManager.getMyMemoryState(state);
		return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
	}

	// Whether the editor is the activity in front, so it can be told to
	// reload afterwards without bringing it over anything else.
	private static boolean isEditorOnTop(@NonNull Context app) {
		var manager = app.getSystemService(ActivityManager.class);
		for (var task : manager.getAppTasks()) {
			var top = task.getTaskInfo().topActivity;
			if (top != null && top.getClassName().equals(MainActivity.class.getName())) {
				return true;
			}
		}
		return false;
	}

	@NonNull
	private static Result importShader(@NonNull Context context, @NonNull String name,
			@NonNull File file, float quality) throws IOException {
		var bytes = new ByteArrayOutputStream();
		try (var in = new FileInputStream(file)) {
			byte[] chunk = new byte[16384];
			for (int n; (n = in.read(chunk)) > 0; ) {
				bytes.write(chunk, 0, n);
			}
		}
		String text = bytes.toString(StandardCharsets.UTF_8.name());
		//noinspection ResultOfMethodCallIgnored
		file.delete();
		DataSource data = Database.getInstance(context).getDataSource();
		var parsed = MultipassSource.parse(text);
		String source = parsed != null ? parsed.imageSource() : text;
		long id = 0;
		for (DataRecords.ShaderInfo shader : data.shader.getShaders(false)) {
			if (name.equals(shader.name())) {
				id = shader.id();
				break;
			}
		}
		boolean replaced = id > 0;
		if (replaced) {
			var old = data.shader.getShader(id);
			data.shader.updateShader(id, source, null, old != null ? old.quality() : quality);
		} else {
			id = data.shader.insertShader(source, name, null, quality);
			if (id < 1) {
				throw new IOException("insert failed");
			}
		}
		data.buffer.setBufferSources(id, parsed != null ? parsed.bufferSources() : new String[0]);
		data.text.setTexts(id, parsed != null ? parsed.texts() : Map.of());
		return new Result(id, (replaced ? "Replaced " : "Added ") + name + " (" + id + ")");
	}
}
