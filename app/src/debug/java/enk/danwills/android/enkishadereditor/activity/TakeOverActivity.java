package enk.danwills.android.enkishadereditor.activity;

import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// Debug builds only: Claude asking before it drives the phone
// (tools/phone/status.sh ask, which starts this with `am start`). A
// see-through activity over whatever's showing, in a task of its own, so
// answering leaves things as they were. The answer - yes or no, Back being
// no - goes to files/takeover-<id> in the app's storage, which status.sh
// reads with run-as.
public class TakeOverActivity extends AppCompatActivity {
	private static final String TAG = "TakeOver";
	private boolean answered;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		if (state != null) {
			// Recreated (rotation): the dialog went with the old one.
			finish();
			return;
		}
		String id = getIntent().getStringExtra("id");
		String text = getIntent().getStringExtra("text");
		if (id == null || !id.matches("[0-9A-Za-z_-]{1,64}")) {
			finish();
			return;
		}
		new MaterialAlertDialogBuilder(this)
				.setTitle("🤖 Can Claude use your phone?")
				.setMessage(text != null && !text.isEmpty() ? text
						: "Claude would like to drive the phone for a test.")
				.setPositiveButton("Go ahead", (d, w) -> answer(id, "yes"))
				.setNegativeButton("Not now", (d, w) -> answer(id, "no"))
				.setOnCancelListener(d -> answer(id, "no"))
				.setOnDismissListener(d -> finish())
				.show();
	}

	private void answer(@NonNull String id, @NonNull String answer) {
		if (answered) {
			return;
		}
		answered = true;
		File file = new File(getFilesDir(), "takeover-" + id);
		try (var out = new FileOutputStream(file)) {
			out.write(answer.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			Log.w(TAG, "Couldn't write the answer", e);
		}
		finish();
	}

	@Override
	public void finish() {
		super.finish();
		overridePendingTransition(0, 0);
	}
}
