package enk.danwills.android.enkishadereditor.database.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.database.DatabaseContract;
import enk.danwills.android.enkishadereditor.database.DatabaseTable;

// A shader's named texts - see DatabaseContract.TextColumns. Exchanged
// as a name -> text map, in the order they were added.
public class ShaderTextDao {
	// A text's name is how a pass reads it (`uniform sampler2D <name>;`),
	// so it has to be a GLSL identifier.
	private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,31}");

	@NonNull
	private final SQLiteOpenHelper dbHelper;

	public ShaderTextDao(@NonNull SQLiteOpenHelper dbHelper) {
		this.dbHelper = dbHelper;
	}

	public static boolean isValidName(@Nullable String name) {
		return name != null && NAME.matcher(name).matches() && !name.startsWith("gl_");
	}

	// region Public API
	@NonNull
	public Map<String, String> getTexts(long shaderId) {
		return getTexts(dbHelper.getReadableDatabase(), shaderId);
	}

	// Makes shaderId's texts exactly `texts`.
	public void setTexts(long shaderId, @NonNull Map<String, String> texts) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		db.beginTransaction();
		try {
			Map<String, String> stored = getTexts(db, shaderId);
			for (String name : stored.keySet()) {
				if (!texts.containsKey(name)) {
					db.delete(DatabaseContract.TextColumns.TABLE_NAME, whereText(),
							new String[]{String.valueOf(shaderId), name});
				}
			}
			for (var entry : texts.entrySet()) {
				String old = stored.get(entry.getKey());
				if (old == null) {
					insertText(db, shaderId, entry.getKey(), entry.getValue());
				} else if (!old.equals(entry.getValue())) {
					var cv = new ContentValues();
					cv.put(DatabaseContract.TextColumns.TEXT, entry.getValue());
					db.update(DatabaseContract.TextColumns.TABLE_NAME, cv, whereText(),
							new String[]{String.valueOf(shaderId), entry.getKey()});
				}
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	public void copyTexts(long fromShaderId, long toShaderId) {
		setTexts(toShaderId, getTexts(fromShaderId));
	}
	// endregion

	// region Package-private static helpers
	static void deleteTexts(@NonNull SQLiteDatabase db, long shaderId) {
		db.delete(DatabaseContract.TextColumns.TABLE_NAME,
				DatabaseContract.TextColumns.SHADER_ID + " = ?",
				new String[]{String.valueOf(shaderId)});
	}

	// Called by ShaderDao's import for each shader it copies, as for
	// buffers (BufferDao.importBuffers()). A backup or pack made before
	// texts existed has no such table - nothing to copy.
	static boolean importTexts(@NonNull SQLiteDatabase dst, @NonNull SQLiteDatabase src,
			long fromShaderId, long toShaderId) {
		if (!hasTextsTable(src)) {
			return true;
		}
		for (var entry : getTexts(src, fromShaderId).entrySet()) {
			var cv = new ContentValues();
			cv.put(DatabaseContract.TextColumns.SHADER_ID, toShaderId);
			cv.put(DatabaseContract.TextColumns.NAME, entry.getKey());
			cv.put(DatabaseContract.TextColumns.TEXT, entry.getValue());
			if (dst.insertWithOnConflict(DatabaseContract.TextColumns.TABLE_NAME, null, cv,
					SQLiteDatabase.CONFLICT_REPLACE) < 1) {
				return false;
			}
		}
		return true;
	}
	// endregion

	// region Private helpers
	@NonNull
	private static Map<String, String> getTexts(@NonNull SQLiteDatabase db, long shaderId) {
		var texts = new LinkedHashMap<String, String>();
		try (var cursor = db.query(
				DatabaseContract.TextColumns.TABLE_NAME,
				new String[]{
						DatabaseContract.TextColumns.NAME,
						DatabaseContract.TextColumns.TEXT},
				DatabaseContract.TextColumns.SHADER_ID + " = ?",
				new String[]{String.valueOf(shaderId)},
				null, null, DatabaseContract.TextColumns._ID)) {
			while (cursor.moveToNext()) {
				String name = cursor.getString(0);
				String text = cursor.getString(1);
				if (name != null) {
					texts.put(name, text != null ? text : "");
				}
			}
		}
		return texts;
	}

	private static void insertText(@NonNull SQLiteDatabase db, long shaderId,
			@NonNull String name, @NonNull String text) {
		var cv = new ContentValues();
		cv.put(DatabaseContract.TextColumns.SHADER_ID, shaderId);
		cv.put(DatabaseContract.TextColumns.NAME, name);
		cv.put(DatabaseContract.TextColumns.TEXT, text);
		db.insert(DatabaseContract.TextColumns.TABLE_NAME, null, cv);
	}

	private static boolean hasTextsTable(@NonNull SQLiteDatabase db) {
		try (Cursor cursor = db.rawQuery(
				"SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
				new String[]{DatabaseContract.TextColumns.TABLE_NAME})) {
			return cursor != null && cursor.moveToFirst();
		}
	}

	@NonNull
	private static String whereText() {
		return DatabaseContract.TextColumns.SHADER_ID + " = ? AND " +
				DatabaseContract.TextColumns.NAME + " = ?";
	}

	private static void createTextsTable(@NonNull SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + DatabaseContract.TextColumns.TABLE_NAME + " (" +
				DatabaseContract.TextColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
				DatabaseContract.TextColumns.SHADER_ID + " INTEGER NOT NULL," +
				DatabaseContract.TextColumns.NAME + " TEXT NOT NULL," +
				DatabaseContract.TextColumns.TEXT + " TEXT NOT NULL," +
				"UNIQUE(" + DatabaseContract.TextColumns.SHADER_ID + "," +
				DatabaseContract.TextColumns.NAME + "));");
	}
	// endregion

	@NonNull
	public static DatabaseTable buildSchema() {
		return new DatabaseTable() {
			@Override
			public void onCreate(@NonNull SQLiteDatabase db) {
				createTextsTable(db);
			}

			@Override
			public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
				if (oldVersion < 15) {
					createTextsTable(db);
				}
			}

			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb) {
				// Done per shader by ShaderDao's import - see importTexts().
				return true;
			}
		};
	}
}
