package enk.danwills.android.enkishadereditor.io;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
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
		return save(context, pngBytes, filename, null);
	}

	// Finds a sub-album name under ALBUM that nothing's been saved into
	// yet, starting from baseName and trying baseName1, baseName2, ...
	// A frame sequence re-rendered for the same shader would otherwise
	// silently mix its new frames in among an older run's leftovers (or
	// overwrite them) instead of getting its own folder.
	@NonNull
	@RequiresApi(Build.VERSION_CODES.Q)
	public static String findAvailableSubAlbum(Context context, @NonNull String baseName) {
		ContentResolver resolver = context.getContentResolver();
		String candidate = baseName;
		int suffix = 0;
		while (subAlbumHasContent(resolver, candidate)) {
			++suffix;
			candidate = baseName + suffix;
		}
		return candidate;
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	private static boolean subAlbumHasContent(ContentResolver resolver, String subAlbum) {
		String relativePath = Environment.DIRECTORY_PICTURES + "/" + ALBUM + "/" + subAlbum + "/";
		String[] projection = {MediaStore.Images.Media._ID};
		String selection = MediaStore.Images.Media.RELATIVE_PATH + " = ?";
		String[] selectionArgs = {relativePath};
		try (Cursor cursor = resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
				projection, selection, selectionArgs, null)) {
			return cursor != null && cursor.getCount() > 0;
		}
	}

	// Deletes every image MediaStore has under this sub-album (e.g. a
	// rendered frame sequence's many frames) - used once a video's been
	// successfully assembled from them and the "delete image files"
	// option is on. The now-empty folder itself isn't something this app
	// deletes directly (MediaStore's RELATIVE_PATH "folders" are virtual;
	// the system reclaims the real one once nothing's left in it).
	// Returns how many files were actually deleted.
	@RequiresApi(Build.VERSION_CODES.Q)
	public static int deleteSubAlbum(Context context, @NonNull String subAlbum) {
		String relativePath = Environment.DIRECTORY_PICTURES + "/" + ALBUM + "/" + subAlbum + "/";
		String selection = MediaStore.Images.Media.RELATIVE_PATH + " = ?";
		String[] selectionArgs = {relativePath};
		return context.getContentResolver().delete(
				MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs);
	}

	// subAlbum (may be null/blank): an extra path segment nested under
	// the usual album, e.g. so a rendered frame sequence's many files
	// land together in their own folder instead of flooding the main
	// one alongside ordinary screenshots.
	@Nullable
	@RequiresApi(Build.VERSION_CODES.Q)
	public static SavedScreenshot save(Context context, byte[] pngBytes,
			@NonNull String filename, @Nullable String subAlbum) {
		String relativePath = Environment.DIRECTORY_PICTURES + "/" + ALBUM +
				(subAlbum != null && !subAlbum.isBlank() ? "/" + subAlbum : "");

		ContentValues values = new ContentValues();
		values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
		values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
		values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);

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
