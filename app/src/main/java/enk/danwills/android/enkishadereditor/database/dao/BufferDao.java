package enk.danwills.android.enkishadereditor.database.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import enk.danwills.android.enkishadereditor.database.DatabaseContract;
import enk.danwills.android.enkishadereditor.database.DatabaseTable;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinary;

// The offscreen buffer passes (Buffer A-D) of multipass shaders - see
// DatabaseContract.BufferColumns. Sources are exchanged as a fixed-size
// array indexed by buffer (0 = A ... MAX_BUFFERS - 1 = D), with null for
// a buffer the shader doesn't have. A shader's Common section (code every
// pass starts with - see ShaderSourcePreparer) is a row of its own, at
// buffer COMMON, read and written apart from the buffers.
public class BufferDao {
	public static final int MAX_BUFFERS = 4;
	public static final int COMMON = -1;

	@NonNull
	private final SQLiteOpenHelper dbHelper;

	public BufferDao(@NonNull SQLiteOpenHelper dbHelper) {
		this.dbHelper = dbHelper;
	}

	// region Public API
	@NonNull
	public String[] getBufferSources(long shaderId) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		return getBufferSources(db, shaderId);
	}

	// Makes shaderId's buffers exactly `sources` (see the class comment):
	// a null entry deletes that buffer, a non-null one inserts or updates
	// it. An unchanged source keeps its cached binary. Any change counts
	// as a source edit of the shader (its SOURCE_MODIFIED), like one to
	// the Image pass - see ShaderDao.updateShader().
	public void setBufferSources(long shaderId, @NonNull String[] sources) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		db.beginTransaction();
		try {
			String[] stored = getBufferSources(db, shaderId);
			boolean changed = false;
			for (int buffer = 0; buffer < MAX_BUFFERS; ++buffer) {
				String source = buffer < sources.length ? sources[buffer] : null;
				if (source == null) {
					if (stored[buffer] != null) {
						db.delete(DatabaseContract.BufferColumns.TABLE_NAME,
								whereBuffer(),
								whereBufferArgs(shaderId, buffer));
						changed = true;
					}
				} else if (stored[buffer] == null) {
					insertBuffer(db, shaderId, buffer, source);
					changed = true;
				} else if (!source.equals(stored[buffer])) {
					var cv = new ContentValues();
					cv.put(DatabaseContract.BufferColumns.SOURCE, source);
					db.update(DatabaseContract.BufferColumns.TABLE_NAME, cv,
							whereBuffer(),
							whereBufferArgs(shaderId, buffer));
					changed = true;
				}
			}
			if (changed) {
				// Same format as ShaderDao's own timestamps.
				db.execSQL("UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" SET " + DatabaseContract.ShaderColumns.SOURCE_MODIFIED +
						" = datetime('now', 'localtime')" +
						" WHERE " + DatabaseContract.ShaderColumns._ID + " = ?",
						new Object[]{shaderId});
			}
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	public void copyBuffers(long fromShaderId, long toShaderId) {
		setBufferSources(toShaderId, getBufferSources(fromShaderId));
		setCommonSource(toShaderId, getCommonSource(fromShaderId));
	}

	// The shader's Common section, or null if it has none.
	@Nullable
	public String getCommonSource(long shaderId) {
		return getCommonSource(dbHelper.getReadableDatabase(), shaderId);
	}

	// Makes the shader's Common section `source`, or removes it for null;
	// a change counts as a source edit, as setBufferSources()'s do.
	public void setCommonSource(long shaderId, @Nullable String source) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		db.beginTransaction();
		try {
			String stored = getCommonSource(db, shaderId);
			if (source == null ? stored == null : source.equals(stored)) {
				db.setTransactionSuccessful();
				return;
			}
			if (source == null) {
				db.delete(DatabaseContract.BufferColumns.TABLE_NAME, whereBuffer(),
						whereBufferArgs(shaderId, COMMON));
			} else if (stored == null) {
				insertBuffer(db, shaderId, COMMON, source);
			} else {
				var cv = new ContentValues();
				cv.put(DatabaseContract.BufferColumns.SOURCE, source);
				db.update(DatabaseContract.BufferColumns.TABLE_NAME, cv, whereBuffer(),
						whereBufferArgs(shaderId, COMMON));
			}
			db.execSQL("UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME +
					" SET " + DatabaseContract.ShaderColumns.SOURCE_MODIFIED +
					" = datetime('now', 'localtime')" +
					" WHERE " + DatabaseContract.ShaderColumns._ID + " = ?",
					new Object[]{shaderId});
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	// Same contract as ShaderDao.getCompiledBinary(), for one buffer's
	// program.
	@Nullable
	public ProgramBinary getCompiledBinary(long shaderId, int buffer,
			@NonNull String sourceHash, @NonNull String driverSignature) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.query(
				DatabaseContract.BufferColumns.TABLE_NAME,
				new String[]{
						DatabaseContract.BufferColumns.COMPILED_BINARY,
						DatabaseContract.BufferColumns.COMPILED_BINARY_FORMAT},
				whereBuffer() + " AND " +
						DatabaseContract.BufferColumns.COMPILED_BINARY_SOURCE_HASH + " = ? AND " +
						DatabaseContract.BufferColumns.COMPILED_BINARY_DRIVER_SIG + " = ?",
				new String[]{String.valueOf(shaderId), String.valueOf(buffer),
						sourceHash, driverSignature},
				null, null, null)) {
			if (cursor.moveToFirst()) {
				byte[] bytes = CursorHelpers.getBlob(
						cursor, DatabaseContract.BufferColumns.COMPILED_BINARY);
				if (bytes != null && bytes.length > 0) {
					return new ProgramBinary(
							CursorHelpers.getInt(
									cursor, DatabaseContract.BufferColumns.COMPILED_BINARY_FORMAT),
							bytes);
				}
			}
		}
		return null;
	}

	public void saveCompiledBinary(long shaderId, int buffer, @NonNull ProgramBinary binary,
			@NonNull String sourceHash, @NonNull String driverSignature) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		var cv = new ContentValues();
		cv.put(DatabaseContract.BufferColumns.COMPILED_BINARY, binary.bytes());
		cv.put(DatabaseContract.BufferColumns.COMPILED_BINARY_FORMAT, binary.format());
		cv.put(DatabaseContract.BufferColumns.COMPILED_BINARY_SOURCE_HASH, sourceHash);
		cv.put(DatabaseContract.BufferColumns.COMPILED_BINARY_DRIVER_SIG, driverSignature);
		db.update(DatabaseContract.BufferColumns.TABLE_NAME, cv,
				whereBuffer(),
				whereBufferArgs(shaderId, buffer));
	}
	// endregion

	// region Package-private static helpers
	static void deleteBuffers(@NonNull SQLiteDatabase db, long shaderId) {
		db.delete(DatabaseContract.BufferColumns.TABLE_NAME,
				DatabaseContract.BufferColumns.SHADER_ID + " = ?",
				new String[]{String.valueOf(shaderId)});
	}

	// Called by ShaderDao's import for each shader it copies, since only
	// it knows the new id the imported shader got. A backup made before
	// buffers existed simply has no such table - nothing to copy.
	static boolean importBuffers(@NonNull SQLiteDatabase dst, @NonNull SQLiteDatabase src,
			long fromShaderId, long toShaderId) {
		if (!hasBuffersTable(src)) {
			return true;
		}
		String[] sources = getBufferSources(src, fromShaderId);
		for (int buffer = 0; buffer < MAX_BUFFERS; ++buffer) {
			if (sources[buffer] != null &&
					insertBuffer(dst, toShaderId, buffer, sources[buffer]) < 1) {
				return false;
			}
		}
		String common = getCommonSource(src, fromShaderId);
		return common == null || insertBuffer(dst, toShaderId, COMMON, common) >= 1;
	}
	// endregion

	// region Private helpers
	@NonNull
	private static String[] getBufferSources(@NonNull SQLiteDatabase db, long shaderId) {
		String[] sources = new String[MAX_BUFFERS];
		try (var cursor = db.query(
				DatabaseContract.BufferColumns.TABLE_NAME,
				new String[]{
						DatabaseContract.BufferColumns.BUFFER,
						DatabaseContract.BufferColumns.SOURCE},
				DatabaseContract.BufferColumns.SHADER_ID + " = ?",
				new String[]{String.valueOf(shaderId)},
				null, null, null)) {
			while (cursor.moveToNext()) {
				int buffer = CursorHelpers.getInt(cursor, DatabaseContract.BufferColumns.BUFFER);
				if (buffer >= 0 && buffer < MAX_BUFFERS) {
					sources[buffer] = CursorHelpers.getString(
							cursor, DatabaseContract.BufferColumns.SOURCE);
				}
			}
		}
		return sources;
	}

	@Nullable
	private static String getCommonSource(@NonNull SQLiteDatabase db, long shaderId) {
		try (var cursor = db.query(
				DatabaseContract.BufferColumns.TABLE_NAME,
				new String[]{DatabaseContract.BufferColumns.SOURCE},
				whereBuffer(),
				whereBufferArgs(shaderId, COMMON),
				null, null, null)) {
			return cursor.moveToFirst()
					? CursorHelpers.getString(cursor, DatabaseContract.BufferColumns.SOURCE)
					: null;
		}
	}

	private static long insertBuffer(@NonNull SQLiteDatabase db, long shaderId, int buffer,
			@NonNull String source) {
		var cv = new ContentValues();
		cv.put(DatabaseContract.BufferColumns.SHADER_ID, shaderId);
		cv.put(DatabaseContract.BufferColumns.BUFFER, buffer);
		cv.put(DatabaseContract.BufferColumns.SOURCE, source);
		return db.insert(DatabaseContract.BufferColumns.TABLE_NAME, null, cv);
	}

	private static boolean hasBuffersTable(@NonNull SQLiteDatabase db) {
		try (Cursor cursor = db.rawQuery(
				"SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?",
				new String[]{DatabaseContract.BufferColumns.TABLE_NAME})) {
			return cursor != null && cursor.moveToFirst();
		}
	}

	@NonNull
	private static String whereBuffer() {
		return DatabaseContract.BufferColumns.SHADER_ID + " = ? AND " +
				DatabaseContract.BufferColumns.BUFFER + " = ?";
	}

	@NonNull
	private static String[] whereBufferArgs(long shaderId, int buffer) {
		return new String[]{String.valueOf(shaderId), String.valueOf(buffer)};
	}

	private static void createBuffersTable(@NonNull SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + DatabaseContract.BufferColumns.TABLE_NAME + " (" +
				DatabaseContract.BufferColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT," +
				DatabaseContract.BufferColumns.SHADER_ID + " INTEGER NOT NULL," +
				DatabaseContract.BufferColumns.BUFFER + " INTEGER NOT NULL," +
				DatabaseContract.BufferColumns.SOURCE + " TEXT NOT NULL," +
				DatabaseContract.BufferColumns.COMPILED_BINARY + " BLOB," +
				DatabaseContract.BufferColumns.COMPILED_BINARY_FORMAT + " INTEGER," +
				DatabaseContract.BufferColumns.COMPILED_BINARY_SOURCE_HASH + " TEXT," +
				DatabaseContract.BufferColumns.COMPILED_BINARY_DRIVER_SIG + " TEXT," +
				"UNIQUE(" + DatabaseContract.BufferColumns.SHADER_ID + "," +
				DatabaseContract.BufferColumns.BUFFER + "));");
	}
	// endregion

	@NonNull
	public static DatabaseTable buildSchema() {
		return new DatabaseTable() {
			@Override
			public void onCreate(@NonNull SQLiteDatabase db) {
				createBuffersTable(db);
			}

			@Override
			public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
				if (oldVersion < 10) {
					createBuffersTable(db);
				}
			}

			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb) {
				// Done per shader by ShaderDao's import - see importBuffers().
				return true;
			}
		};
	}
}
