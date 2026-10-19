package enk.danwills.android.enkishadereditor.database.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
	// endregion

	// region Private helpers
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
