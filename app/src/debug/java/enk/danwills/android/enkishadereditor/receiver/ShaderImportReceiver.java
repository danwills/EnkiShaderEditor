package enk.danwills.android.enkishadereditor.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.project.MultipassSource;

// Debug builds only: puts a shader into the library from the test
// tooling, no UI involved (tools/phone/add_shader.sh). The shader's text -
// one pass, or multipass text with `//// Buffer` and `//// Text` sections -
// is a file in the app's own files/import/ (written there with run-as),
// named by the `file` extra; `name` is the shader's name. A shader of that
// name already in the library is replaced in place, keeping its id (so
// its place, thumbnail and settings); otherwise it's added. `quality`
// (default 1/4) is for a new one. The file is deleted afterwards.
public class ShaderImportReceiver extends BroadcastReceiver {
	private static final String TAG = "ShaderImport";

	@Override
	public void onReceive(Context context, Intent intent) {
		String name = intent.getStringExtra("name");
		String file = intent.getStringExtra("file");
		float quality = intent.getFloatExtra("quality", 0.25f);
		if (name == null || name.isEmpty() || file == null || file.contains("/")) {
			Log.w(TAG, "Needs `name` and a bare `file`");
			return;
		}
		Context app = context.getApplicationContext();
		PendingResult pending = goAsync();
		new Thread(() -> {
			String message;
			try {
				message = importShader(app, name, new File(new File(app.getFilesDir(), "import"),
						file), quality);
			} catch (IOException e) {
				message = "Can't import " + name + ": " + e.getMessage();
			}
			Log.i(TAG, message);
			String toast = message;
			new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
				Toast.makeText(app, toast, Toast.LENGTH_LONG).show();
				pending.finish();
			});
		}, "ShaderImport").start();
	}

	@NonNull
	private static String importShader(@NonNull Context context, @NonNull String name,
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
		data.text.setTexts(id, parsed != null ? parsed.texts() : java.util.Map.of());
		return (replaced ? "Replaced " : "Added ") + name + " (" + id + ")";
	}
}
