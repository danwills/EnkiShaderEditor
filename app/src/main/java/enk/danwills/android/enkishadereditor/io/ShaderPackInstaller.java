package enk.danwills.android.enkishadereditor.io;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.database.DatabaseContract;
import enk.danwills.android.enkishadereditor.database.dao.CursorHelpers;

// Installs a shader pack file (see ShaderPackWriter) into the library:
// merges its shaders, buffers and textures in like a database import,
// tags every one of its shaders with the pack's id, and records the pack
// as installed. Blocking - call off the UI thread.
public final class ShaderPackInstaller {
	public record Result(boolean success, @NonNull String message,
			@Nullable DataRecords.ShaderPack pack) {
	}

	private static final String TEMP_FILE_NAME = "pack-import.db";

	private ShaderPackInstaller() {
	}

	// From anywhere the system file picker or another app can hand over.
	@NonNull
	public static Result install(@NonNull Context context, @NonNull Uri uri) {
		try (var in = context.getContentResolver().openInputStream(uri)) {
			if (in == null) {
				return failure(context.getString(R.string.cant_find_db));
			}
			return install(context, in, uri);
		} catch (IOException | SecurityException e) {
			return failure(context.getString(R.string.import_failed, e.getMessage()));
		}
	}

	// A pack file already on the device, e.g. one a store delivered.
	@NonNull
	public static Result install(@NonNull Context context, @NonNull File file) {
		try (var in = new FileInputStream(file)) {
			return install(context, in, Uri.fromFile(file));
		} catch (IOException e) {
			return failure(context.getString(R.string.import_failed, e.getMessage()));
		}
	}

	@NonNull
	private static Result install(@NonNull Context context, @NonNull InputStream in,
			@NonNull Uri uri) throws IOException {
		try (var out = context.openFileOutput(TEMP_FILE_NAME, Context.MODE_PRIVATE)) {
			byte[] buffer = new byte[8192];
			int len;
			while ((len = in.read(buffer)) != -1) {
				out.write(buffer, 0, len);
			}
		}
		File copy = new File(context.getFilesDir(), TEMP_FILE_NAME);
		try {
			DataRecords.ShaderPack pack = readPackInfo(copy);
			if (pack == null) {
				return failure(context.getString(R.string.not_a_shader_pack));
			}
			int shaders = countShaders(copy);
			String error = Database.getInstance(context).importDatabase(TEMP_FILE_NAME, uri,
					pack.packId());
			if (error != null) {
				return failure(error);
			}
			Database.getInstance(context).getDataSource().pack.putPack(pack,
					DatabaseContract.PackColumns.SOURCE_FILE);
			return new Result(true, context.getResources().getQuantityString(
					R.plurals.shader_pack_installed, shaders, pack.title(), shaders), pack);
		} finally {
			context.deleteFile(TEMP_FILE_NAME);
		}
	}

	// The pack described by a pack file's pack_info row, or null if it
	// isn't a pack file (e.g. an ordinary database backup).
	@Nullable
	public static DataRecords.ShaderPack readPackInfo(@NonNull File file) {
		try (var db = SQLiteDatabase.openDatabase(file.getPath(), null,
				SQLiteDatabase.OPEN_READONLY);
				Cursor cursor = db.query(DatabaseContract.PackInfoColumns.TABLE_NAME, null,
						null, null, null, null, null, "1")) {
			if (!cursor.moveToFirst()) {
				return null;
			}
			String packId = CursorHelpers.getString(cursor,
					DatabaseContract.PackInfoColumns.PACK_ID);
			String title = CursorHelpers.getString(cursor,
					DatabaseContract.PackInfoColumns.TITLE);
			if (packId == null || packId.isEmpty() || title == null || title.isEmpty()) {
				return null;
			}
			return new DataRecords.ShaderPack(
					packId,
					title,
					CursorHelpers.getString(cursor, DatabaseContract.PackInfoColumns.DESCRIPTION),
					CursorHelpers.getString(cursor, DatabaseContract.PackInfoColumns.AUTHOR),
					CursorHelpers.getInt(cursor, DatabaseContract.PackInfoColumns.VERSION),
					null,
					null);
		} catch (SQLiteException e) {
			// Not a database, or no pack_info table.
			return null;
		}
	}

	private static int countShaders(@NonNull File file) {
		try (var db = SQLiteDatabase.openDatabase(file.getPath(), null,
				SQLiteDatabase.OPEN_READONLY);
				Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " +
						DatabaseContract.ShaderColumns.TABLE_NAME, null)) {
			return cursor.moveToFirst() ? cursor.getInt(0) : 0;
		} catch (SQLiteException e) {
			return 0;
		}
	}

	@NonNull
	private static Result failure(@NonNull String message) {
		return new Result(false, message, null);
	}
}
