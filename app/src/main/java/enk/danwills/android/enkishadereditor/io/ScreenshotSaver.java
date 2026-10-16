package enk.danwills.android.enkishadereditor.io;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

// Saves a rendered shader frame (PNG bytes from ShaderRenderer's
// glReadPixels capture) into the device's own Pictures gallery via
// MediaStore, rather than app-private storage, so it shows up
// immediately in Photos/Gallery/Files alongside normal camera shots.
// Requires API 29 (Q) for MediaStore's RELATIVE_PATH column - older
// devices would instead need a runtime WRITE_EXTERNAL_STORAGE grant and
// the legacy DATA-column insert path, which isn't implemented here.
public final class ScreenshotSaver {
	private static final String ALBUM = "Enki Shader Editor";

	private ScreenshotSaver() {
	}

	public static boolean isSupported() {
		return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
	}

	// filename: for a "saved as ..." message. uri: a content:// URI
	// usable for both opening the image (ACTION_VIEW) and putting it on
	// the clipboard (ClipData.newUri) - clipboard image-copy needs a
	// URI some other app's process can read back from later, which a
	// MediaStore-backed one already is, without a separate FileProvider.
	public record SavedScreenshot(@NonNull String filename, @NonNull Uri uri) {
	}

	@Nullable
	@RequiresApi(Build.VERSION_CODES.Q)
	public static SavedScreenshot save(Context context, byte[] pngBytes) {
		String filename = "EnkiShader_" +
				new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) +
				".png";

		ContentValues values = new ContentValues();
		values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
		values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
		values.put(MediaStore.Images.Media.RELATIVE_PATH,
				Environment.DIRECTORY_PICTURES + "/" + ALBUM);

		ContentResolver resolver = context.getContentResolver();
		Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
		if (uri == null) {
			return null;
		}
		try (OutputStream out = resolver.openOutputStream(uri)) {
			if (out == null) {
				resolver.delete(uri, null, null);
				return null;
			}
			out.write(pngBytes);
			return new SavedScreenshot(filename, uri);
		} catch (IOException e) {
			resolver.delete(uri, null, null);
			return null;
		}
	}
}
