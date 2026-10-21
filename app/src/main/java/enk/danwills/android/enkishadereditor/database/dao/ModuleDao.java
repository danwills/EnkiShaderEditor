package enk.danwills.android.enkishadereditor.database.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import enk.danwills.android.enkishadereditor.database.DatabaseContract;
import enk.danwills.android.enkishadereditor.database.DatabaseTable;

// Tracker modules (MOD, S3M, XM) imported into the library, by name - a
// shader plays one with a `///mod:name` line (see ModulePlayer). Kept as
// the file's own bytes. Backups and shader packs bring theirs along, as
// they do textures.
public class ModuleDao {
	@NonNull
	private final SQLiteOpenHelper dbHelper;

	public ModuleDao(@NonNull SQLiteOpenHelper dbHelper) {
		this.dbHelper = dbHelper;
	}

	// The new row's id, or -1 if the name is taken.
	public long insertModule(@NonNull String name, @NonNull byte[] data) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		if (getModuleId(db, name) >= 0) {
			return -1;
		}
		var cv = new ContentValues();
		cv.put(DatabaseContract.ModuleColumns.NAME, name);
		cv.put(DatabaseContract.ModuleColumns.DATA, data);
		return db.insert(DatabaseContract.ModuleColumns.TABLE_NAME, null, cv);
	}

	@Nullable
	public byte[] getModuleData(@NonNull String name) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (Cursor cursor = db.query(DatabaseContract.ModuleColumns.TABLE_NAME,
				new String[]{DatabaseContract.ModuleColumns.DATA},
				DatabaseContract.ModuleColumns.NAME + " = ?", new String[]{name},
				null, null, null)) {
			return cursor.moveToFirst() ? cursor.getBlob(0) : null;
		}
	}

	@NonNull
	public List<String> getModuleNames() {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		var names = new ArrayList<String>();
		try (Cursor cursor = db.query(DatabaseContract.ModuleColumns.TABLE_NAME,
				new String[]{DatabaseContract.ModuleColumns.NAME}, null, null, null, null,
				DatabaseContract.ModuleColumns.NAME + " COLLATE NOCASE")) {
			while (cursor.moveToNext()) {
				names.add(cursor.getString(0));
			}
		}
		return names;
	}

	public void deleteModule(@NonNull String name) {
		dbHelper.getWritableDatabase().delete(DatabaseContract.ModuleColumns.TABLE_NAME,
				DatabaseContract.ModuleColumns.NAME + " = ?", new String[]{name});
	}

	private static long getModuleId(@NonNull SQLiteDatabase db, @NonNull String name) {
		try (Cursor cursor = db.query(DatabaseContract.ModuleColumns.TABLE_NAME,
				new String[]{DatabaseContract.ModuleColumns._ID},
				DatabaseContract.ModuleColumns.NAME + " = ?", new String[]{name},
				null, null, null)) {
			return cursor.moveToFirst() ? cursor.getLong(0) : -1;
		}
	}

	private static void createModulesTable(@NonNull SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + DatabaseContract.ModuleColumns.TABLE_NAME + " (" +
				DatabaseContract.ModuleColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
				DatabaseContract.ModuleColumns.NAME + " TEXT NOT NULL UNIQUE," +
				DatabaseContract.ModuleColumns.DATA + " BLOB NOT NULL," +
				DatabaseContract.ModuleColumns.CREATED + " DATETIME DEFAULT " +
				"(datetime('now', 'localtime')));");
	}

	@NonNull
	public static DatabaseTable buildSchema() {
		return new DatabaseTable() {
			@Override
			public void onCreate(@NonNull SQLiteDatabase db) {
				createModulesTable(db);
			}

			@Override
			public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
				if (oldVersion < 14) {
					createModulesTable(db);
				}
			}

			// A backup's or pack's modules come along; ones whose name is
			// already here are left as they are, like textures.
			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb) {
				try (Cursor cursor = fromDb.query(DatabaseContract.ModuleColumns.TABLE_NAME,
						new String[]{DatabaseContract.ModuleColumns.NAME,
								DatabaseContract.ModuleColumns.DATA},
						null, null, null, null, null)) {
					while (cursor.moveToNext()) {
						String name = cursor.getString(0);
						if (name == null || getModuleId(intoDb, name) >= 0) {
							continue;
						}
						var cv = new ContentValues();
						cv.put(DatabaseContract.ModuleColumns.NAME, name);
						cv.put(DatabaseContract.ModuleColumns.DATA, cursor.getBlob(1));
						intoDb.insert(DatabaseContract.ModuleColumns.TABLE_NAME, null, cv);
					}
				} catch (SQLiteException e) {
					// Older than modules: nothing to bring.
				}
				return true;
			}
		};
	}
}
