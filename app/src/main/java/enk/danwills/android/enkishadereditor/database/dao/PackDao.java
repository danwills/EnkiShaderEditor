package enk.danwills.android.enkishadereditor.database.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DatabaseContract;
import enk.danwills.android.enkishadereditor.database.DatabaseTable;

// The shader packs installed into this library (see
// DatabaseContract.PackColumns) - which shaders came from which is the
// shaders table's own PACK_ID column.
public class PackDao {
	@NonNull
	private final SQLiteOpenHelper dbHelper;

	public PackDao(@NonNull SQLiteOpenHelper dbHelper) {
		this.dbHelper = dbHelper;
	}

	// region Public API
	@NonNull
	public List<DataRecords.ShaderPack> getPacks() {
		var packs = new ArrayList<DataRecords.ShaderPack>();
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.query(DatabaseContract.PackColumns.TABLE_NAME, null,
				null, null, null, null, DatabaseContract.PackColumns.TITLE)) {
			while (cursor.moveToNext()) {
				packs.add(read(cursor));
			}
		}
		return packs;
	}

	@Nullable
	public DataRecords.ShaderPack getPack(@NonNull String packId) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.query(DatabaseContract.PackColumns.TABLE_NAME, null,
				DatabaseContract.PackColumns.PACK_ID + " = ?", new String[]{packId},
				null, null, null)) {
			return cursor.moveToFirst() ? read(cursor) : null;
		}
	}

	// Records `pack` as installed (now, from `source`), replacing any
	// earlier version's row.
	public void putPack(@NonNull DataRecords.ShaderPack pack, @NonNull String source) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		var cv = new ContentValues();
		cv.put(DatabaseContract.PackColumns.PACK_ID, pack.packId());
		cv.put(DatabaseContract.PackColumns.TITLE, pack.title());
		cv.put(DatabaseContract.PackColumns.DESCRIPTION, pack.description());
		cv.put(DatabaseContract.PackColumns.AUTHOR, pack.author());
		cv.put(DatabaseContract.PackColumns.VERSION, pack.version());
		cv.put(DatabaseContract.PackColumns.INSTALLED,
				new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
		cv.put(DatabaseContract.PackColumns.SOURCE, source);
		db.insertWithOnConflict(DatabaseContract.PackColumns.TABLE_NAME, null, cv,
				SQLiteDatabase.CONFLICT_REPLACE);
	}

	// Thumbnails of up to `limit` of this pack's installed shaders.
	@NonNull
	public List<byte[]> getThumbnails(@NonNull String packId, int limit) {
		var thumbs = new ArrayList<byte[]>();
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.query(DatabaseContract.ShaderColumns.TABLE_NAME,
				new String[]{DatabaseContract.ShaderColumns.THUMB},
				DatabaseContract.ShaderColumns.PACK_ID + " = ? AND " +
						DatabaseContract.ShaderColumns.THUMB + " IS NOT NULL",
				new String[]{packId}, null, null, DatabaseContract.ShaderColumns.NAME,
				String.valueOf(limit))) {
			while (cursor.moveToNext()) {
				thumbs.add(cursor.getBlob(0));
			}
		}
		return thumbs;
	}

	// How many of the library's shaders came from this pack.
	public int countShaders(@NonNull String packId) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.rawQuery(
				"SELECT COUNT(*) FROM " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" WHERE " + DatabaseContract.ShaderColumns.PACK_ID + " = ?",
				new String[]{packId})) {
			return cursor.moveToFirst() ? cursor.getInt(0) : 0;
		}
	}
	// Writes a shader pack file: a standalone database with the same
	// tables as this one, holding only `shaderIds`, their buffers and the
	// textures named, plus a pack_info row describing it (`info`) and a
	// pack_media table of preview images (the shaders' thumbnails).
	// Device-specific columns (compiled binaries, timings, fps) are left
	// out. Replaces `file` if it exists.
	public void writePackFile(@NonNull File file, @NonNull List<Long> shaderIds,
			@NonNull Collection<String> textureNames, @NonNull DataRecords.ShaderPack info) {
		SQLiteDatabase main = dbHelper.getReadableDatabase();
		if (file.exists() && !file.delete()) {
			throw new SQLiteException("Can't replace " + file);
		}
		var ids = new StringBuilder();
		for (long id : shaderIds) {
			ids.append(ids.length() > 0 ? "," : "").append(id);
		}
		try (var pack = SQLiteDatabase.openOrCreateDatabase(file, null)) {
			pack.beginTransaction();
			try {
				copySchema(main, pack, DatabaseContract.ShaderColumns.TABLE_NAME);
				copySchema(main, pack, DatabaseContract.BufferColumns.TABLE_NAME);
				copySchema(main, pack, DatabaseContract.TextureColumns.TABLE_NAME);
				copyRows(main, pack, DatabaseContract.ShaderColumns.TABLE_NAME,
						DatabaseContract.ShaderColumns._ID + " IN (" + ids + ")", null);
				copyRows(main, pack, DatabaseContract.BufferColumns.TABLE_NAME,
						DatabaseContract.BufferColumns.SHADER_ID + " IN (" + ids + ")", null);
				if (!textureNames.isEmpty()) {
					String placeholders = String.join(",",
							Collections.nCopies(textureNames.size(), "?"));
					copyRows(main, pack, DatabaseContract.TextureColumns.TABLE_NAME,
							DatabaseContract.TextureColumns.NAME + " IN (" + placeholders + ")",
							textureNames.toArray(new String[0]));
				}
				pack.execSQL("UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME + " SET " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY + " = NULL, " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_FORMAT + " = NULL, " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_SOURCE_HASH + " = NULL, " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_DRIVER_SIG + " = NULL, " +
						DatabaseContract.ShaderColumns.COMPILE_TIME_MS + " = NULL, " +
						DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH + " = NULL, " +
						DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED + " = NULL, " +
						DatabaseContract.ShaderColumns.LAST_FPS + " = NULL, " +
						DatabaseContract.ShaderColumns.LAST_FPS_QUALITY + " = NULL, " +
						DatabaseContract.ShaderColumns.PACK_ID + " = NULL");
				pack.execSQL("UPDATE " + DatabaseContract.BufferColumns.TABLE_NAME + " SET " +
						DatabaseContract.BufferColumns.COMPILED_BINARY + " = NULL, " +
						DatabaseContract.BufferColumns.COMPILED_BINARY_FORMAT + " = NULL, " +
						DatabaseContract.BufferColumns.COMPILED_BINARY_SOURCE_HASH + " = NULL, " +
						DatabaseContract.BufferColumns.COMPILED_BINARY_DRIVER_SIG + " = NULL");
				writePackInfo(pack, info);
				pack.setVersion(main.getVersion());
				pack.setTransactionSuccessful();
			} finally {
				pack.endTransaction();
			}
		}
	}
	// endregion

	// region Private helpers
	private static void writePackInfo(@NonNull SQLiteDatabase pack,
			@NonNull DataRecords.ShaderPack info) {
		pack.execSQL("CREATE TABLE " + DatabaseContract.PackInfoColumns.TABLE_NAME + " (" +
				DatabaseContract.PackInfoColumns.PACK_ID + " TEXT NOT NULL," +
				DatabaseContract.PackInfoColumns.TITLE + " TEXT NOT NULL," +
				DatabaseContract.PackInfoColumns.DESCRIPTION + " TEXT," +
				DatabaseContract.PackInfoColumns.AUTHOR + " TEXT," +
				DatabaseContract.PackInfoColumns.VERSION + " INTEGER," +
				DatabaseContract.PackInfoColumns.CREATED + " DATETIME," +
				DatabaseContract.PackInfoColumns.FORMAT + " INTEGER);");
		var cv = new ContentValues();
		cv.put(DatabaseContract.PackInfoColumns.PACK_ID, info.packId());
		cv.put(DatabaseContract.PackInfoColumns.TITLE, info.title());
		cv.put(DatabaseContract.PackInfoColumns.DESCRIPTION, info.description());
		cv.put(DatabaseContract.PackInfoColumns.AUTHOR, info.author());
		cv.put(DatabaseContract.PackInfoColumns.VERSION, info.version());
		cv.put(DatabaseContract.PackInfoColumns.CREATED,
				new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
		cv.put(DatabaseContract.PackInfoColumns.FORMAT,
				DatabaseContract.PackInfoColumns.CURRENT_FORMAT);
		pack.insert(DatabaseContract.PackInfoColumns.TABLE_NAME, null, cv);

		pack.execSQL("CREATE TABLE " + DatabaseContract.PackMediaColumns.TABLE_NAME + " (" +
				DatabaseContract.PackMediaColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
				DatabaseContract.PackMediaColumns.KIND + " TEXT NOT NULL," +
				DatabaseContract.PackMediaColumns.SHADER_CREATED + " TEXT," +
				DatabaseContract.PackMediaColumns.IMAGE + " BLOB," +
				DatabaseContract.PackMediaColumns.CAPTION + " TEXT);");
		pack.execSQL("INSERT INTO " + DatabaseContract.PackMediaColumns.TABLE_NAME + " (" +
				DatabaseContract.PackMediaColumns.KIND + ", " +
				DatabaseContract.PackMediaColumns.SHADER_CREATED + ", " +
				DatabaseContract.PackMediaColumns.IMAGE + ", " +
				DatabaseContract.PackMediaColumns.CAPTION + ") SELECT '" +
				DatabaseContract.PackMediaColumns.KIND_THUMB + "', " +
				DatabaseContract.ShaderColumns.CREATED + ", " +
				DatabaseContract.ShaderColumns.THUMB + ", " +
				DatabaseContract.ShaderColumns.NAME + " FROM " +
				DatabaseContract.ShaderColumns.TABLE_NAME + " WHERE " +
				DatabaseContract.ShaderColumns.THUMB + " IS NOT NULL");
	}

	// Creates `table` in `to` exactly as it's declared in `from`.
	private static void copySchema(@NonNull SQLiteDatabase from, @NonNull SQLiteDatabase to,
			@NonNull String table) {
		try (var cursor = from.rawQuery(
				"SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
				new String[]{table})) {
			if (!cursor.moveToFirst()) {
				throw new SQLiteException("No table " + table);
			}
			to.execSQL(cursor.getString(0));
		}
	}

	private static void copyRows(@NonNull SQLiteDatabase from, @NonNull SQLiteDatabase to,
			@NonNull String table, @NonNull String where, @Nullable String[] args) {
		try (var cursor = from.query(table, null, where, args, null, null, null)) {
			while (cursor.moveToNext()) {
				var cv = new ContentValues();
				for (int i = 0; i < cursor.getColumnCount(); ++i) {
					String column = cursor.getColumnName(i);
					switch (cursor.getType(i)) {
						case Cursor.FIELD_TYPE_INTEGER -> cv.put(column, cursor.getLong(i));
						case Cursor.FIELD_TYPE_FLOAT -> cv.put(column, cursor.getDouble(i));
						case Cursor.FIELD_TYPE_BLOB -> cv.put(column, cursor.getBlob(i));
						case Cursor.FIELD_TYPE_NULL -> cv.putNull(column);
						default -> cv.put(column, cursor.getString(i));
					}
				}
				if (to.insert(table, null, cv) < 0) {
					throw new SQLiteException("Couldn't copy a row of " + table);
				}
			}
		}
	}

	@NonNull
	private static DataRecords.ShaderPack read(@NonNull Cursor cursor) {
		return new DataRecords.ShaderPack(
				CursorHelpers.getString(cursor, DatabaseContract.PackColumns.PACK_ID),
				CursorHelpers.getString(cursor, DatabaseContract.PackColumns.TITLE),
				CursorHelpers.getString(cursor, DatabaseContract.PackColumns.DESCRIPTION),
				CursorHelpers.getString(cursor, DatabaseContract.PackColumns.AUTHOR),
				CursorHelpers.getInt(cursor, DatabaseContract.PackColumns.VERSION),
				CursorHelpers.getString(cursor, DatabaseContract.PackColumns.INSTALLED),
				CursorHelpers.getString(cursor, DatabaseContract.PackColumns.SOURCE));
	}

	private static void createPacksTable(@NonNull SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + DatabaseContract.PackColumns.TABLE_NAME + " (" +
				DatabaseContract.PackColumns.PACK_ID + " TEXT PRIMARY KEY," +
				DatabaseContract.PackColumns.TITLE + " TEXT NOT NULL," +
				DatabaseContract.PackColumns.DESCRIPTION + " TEXT," +
				DatabaseContract.PackColumns.AUTHOR + " TEXT," +
				DatabaseContract.PackColumns.VERSION + " INTEGER," +
				DatabaseContract.PackColumns.INSTALLED + " DATETIME," +
				DatabaseContract.PackColumns.SOURCE + " TEXT);");
	}

	private static boolean hasPacksTable(@NonNull SQLiteDatabase db) {
		try (Cursor cursor = db.rawQuery(
				"SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
				new String[]{DatabaseContract.PackColumns.TABLE_NAME})) {
			return cursor != null && cursor.moveToFirst();
		}
	}

	// A backup's installed packs come along with its shaders (which keep
	// their PACK_ID - see ShaderDao's import). Packs already known here
	// are left as they are.
	private static boolean importPacks(@NonNull SQLiteDatabase dst,
			@NonNull SQLiteDatabase src) {
		if (!hasPacksTable(src)) {
			return true;
		}
		try (var cursor = src.query(DatabaseContract.PackColumns.TABLE_NAME, null,
				null, null, null, null, null)) {
			while (cursor.moveToNext()) {
				var cv = new ContentValues();
				for (int i = 0; i < cursor.getColumnCount(); ++i) {
					switch (cursor.getType(i)) {
						case Cursor.FIELD_TYPE_INTEGER ->
								cv.put(cursor.getColumnName(i), cursor.getLong(i));
						case Cursor.FIELD_TYPE_NULL -> cv.putNull(cursor.getColumnName(i));
						default -> cv.put(cursor.getColumnName(i), cursor.getString(i));
					}
				}
				dst.insertWithOnConflict(DatabaseContract.PackColumns.TABLE_NAME, null, cv,
						SQLiteDatabase.CONFLICT_IGNORE);
			}
		}
		return true;
	}
	// endregion

	@NonNull
	public static DatabaseTable buildSchema() {
		return new DatabaseTable() {
			@Override
			public void onCreate(@NonNull SQLiteDatabase db) {
				createPacksTable(db);
			}

			@Override
			public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
				if (oldVersion < 11) {
					createPacksTable(db);
				}
			}

			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb) {
				return importPacks(intoDb, fromDb);
			}

			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb, @Nullable String packId) {
				// A pack file has no packs table - ShaderPackInstaller
				// records the pack itself.
				return packId != null || importPacks(intoDb, fromDb);
			}
		};
	}
}
