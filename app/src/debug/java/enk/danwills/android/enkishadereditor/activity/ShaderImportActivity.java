package enk.danwills.android.enkishadereditor.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import enk.danwills.android.enkishadereditor.receiver.ShaderImportReceiver;

// Debug builds only: a shader arrived from the test tooling while the app
// was on screen (see ShaderImportReceiver). A see-through activity over
// whatever was showing asks whether to replace the library's version -
// and so drop any edits to it still open - or ignore the import; either
// way it then goes, leaving things as they were. Replacing the open shader
// reloads it in the editor.
public class ShaderImportActivity extends AppCompatActivity {
	private static final String TAG = "ShaderImport";
	private static final String NAME = "name";
	private static final String FILE = "file";
	private static final String QUALITY = "quality";
	private static final String EDITOR_ON_TOP = "editor_on_top";

	public static void start(@NonNull Context context, @NonNull String name,
			@NonNull String file, float quality, boolean editorOnTop) {
		context.startActivity(new Intent(context, ShaderImportActivity.class)
				.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				.putExtra(NAME, name)
				.putExtra(FILE, file)
				.putExtra(QUALITY, quality)
				.putExtra(EDITOR_ON_TOP, editorOnTop));
	}

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (state != null) {
			// Recreated (rotation): the dialog went with the old one.
			finish();
			return;
		}
		Intent intent = getIntent();
		String name = intent.getStringExtra(NAME);
		String file = intent.getStringExtra(FILE);
		float quality = intent.getFloatExtra(QUALITY, 0.25f);
		boolean editorOnTop = intent.getBooleanExtra(EDITOR_ON_TOP, false);
		if (name == null || file == null) {
			finish();
			return;
		}
		new MaterialAlertDialogBuilder(this)
				.setTitle("Shader arrived: " + name)
				.setMessage("Replace the library's version with it (dropping any edits to " +
						"that shader still open), or ignore it?")
				.setPositiveButton("Replace", (d, w) -> replace(name, file, quality,
						editorOnTop))
				.setNegativeButton("Ignore", (d, w) -> ignore(name, file))
				.setOnCancelListener(d -> ignore(name, file))
				.show();
	}

	private void replace(@NonNull String name, @NonNull String file, float quality,
			boolean editorOnTop) {
		Context app = getApplicationContext();
		ShaderImportReceiver.importAsync(app, name, file, quality, result -> {
			finish();
			if (result.id() > 0 && editorOnTop) {
				// Back to the editor (already under this), reloading.
				startActivity(new Intent(app, MainActivity.class)
						.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
						.putExtra(MainActivity.EXTRA_RELOAD_SHADER, result.id()));
			}
		});
	}

	private void ignore(@NonNull String name, @NonNull String file) {
		//noinspection ResultOfMethodCallIgnored
		ShaderImportReceiver.importFile(getApplicationContext(), file).delete();
		Log.i(TAG, "Ignored " + name);
		finish();
	}

	@Override
	public void finish() {
		super.finish();
		overridePendingTransition(0, 0);
	}
}
