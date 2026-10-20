package enk.danwills.android.enkishadereditor.database.dao;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.Contract;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DatabaseContract;
import enk.danwills.android.enkishadereditor.database.DatabaseTable;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinary;
import enk.danwills.android.enkishadereditor.resource.Resources;

public class ShaderDao {
	@NonNull
	private final SQLiteOpenHelper dbHelper;
	@NonNull
	private final Context appContext;

	public ShaderDao(@NonNull SQLiteOpenHelper dbHelper, @NonNull Context context) {
		this.dbHelper = dbHelper;
		this.appContext = context;
	}

	// region Public API
	@Nullable
	public DataRecords.Shader getShader(long id) {
		String query =
				"SELECT " + DatabaseContract.ShaderColumns._ID + "," + DatabaseContract.ShaderColumns.FRAGMENT_SHADER + "," + DatabaseContract.ShaderColumns.NAME + "," +
						DatabaseContract.ShaderColumns.MODIFIED + "," + DatabaseContract.ShaderColumns.SOURCE_MODIFIED + "," + DatabaseContract.ShaderColumns.QUALITY + "," +
						DatabaseContract.ShaderColumns.COMPILE_TIME_MS + "," + DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH + "," + DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED +
						" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME + " WHERE " + DatabaseContract.ShaderColumns._ID + " = ?";

		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.rawQuery(query, new String[]{String.valueOf(id)})) {
			if (cursor.moveToFirst()) {
				return new DataRecords.Shader(
						CursorHelpers.getLong(cursor, DatabaseContract.ShaderColumns._ID),
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.FRAGMENT_SHADER),
						CursorHelpers.getString(cursor, DatabaseContract.ShaderColumns.NAME),
						CursorHelpers.getString(cursor, DatabaseContract.ShaderColumns.MODIFIED),
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.SOURCE_MODIFIED),
						CursorHelpers.getFloat(cursor, DatabaseContract.ShaderColumns.QUALITY),
						CursorHelpers.getLongOrNull(cursor,
								DatabaseContract.ShaderColumns.COMPILE_TIME_MS),
						CursorHelpers.getIntOrNull(cursor,
								DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH),
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED));
			}
		}
		return null;
	}

	public List<DataRecords.ShaderInfo> getShaders(boolean sortByLastModification) {
		List<DataRecords.ShaderInfo> shaders = new ArrayList<>();
		String query =
				"SELECT " + DatabaseContract.ShaderColumns._ID + "," + DatabaseContract.ShaderColumns.THUMB + "," + DatabaseContract.ShaderColumns.NAME + "," + DatabaseContract.ShaderColumns.MODIFIED + "," + DatabaseContract.ShaderColumns.SOURCE_MODIFIED + "," + DatabaseContract.ShaderColumns.PACK_ID +
						" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME + " ORDER BY " + (sortByLastModification ?
						// Sorts by actual code changes, not any save
						// (autosave can bump MODIFIED with no source edit).
						DatabaseContract.ShaderColumns.SOURCE_MODIFIED + " DESC" :
						DatabaseContract.ShaderColumns._ID + " DESC");

		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.rawQuery(query, null)) {
			if (cursor.moveToFirst()) {
				do {
					shaders.add(new DataRecords.ShaderInfo(
							CursorHelpers.getLong(cursor, DatabaseContract.ShaderColumns._ID),
							CursorHelpers.getString(cursor, DatabaseContract.ShaderColumns.NAME),
							CursorHelpers.getString(cursor,
									DatabaseContract.ShaderColumns.MODIFIED),
							CursorHelpers.getString(cursor,
									DatabaseContract.ShaderColumns.SOURCE_MODIFIED),
							CursorHelpers.getBlob(cursor, DatabaseContract.ShaderColumns.THUMB),
							cursor.getString(cursor.getColumnIndexOrThrow(
									DatabaseContract.ShaderColumns.PACK_ID))));
				} while (cursor.moveToNext());
			}
		}
		return shaders;
	}

	// Used by shader search, which needs every shader's source text to
	// check against (as opposed to the summary-only getShaders() above).
	@NonNull
	public Map<Long, String> getAllShaderSources() {
		Map<Long, String> sources = new HashMap<>();
		String query = "SELECT " + DatabaseContract.ShaderColumns._ID + "," +
				DatabaseContract.ShaderColumns.FRAGMENT_SHADER +
				" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME;

		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.rawQuery(query, null)) {
			if (cursor.moveToFirst()) {
				do {
					sources.put(
							CursorHelpers.getLong(cursor, DatabaseContract.ShaderColumns._ID),
							CursorHelpers.getString(
									cursor, DatabaseContract.ShaderColumns.FRAGMENT_SHADER));
				} while (cursor.moveToNext());
			}
		}
		return sources;
	}

	@Nullable
	public DataRecords.Shader getRandomShader() {
		String query =
				"SELECT " + DatabaseContract.ShaderColumns._ID + "," + DatabaseContract.ShaderColumns.FRAGMENT_SHADER + "," + DatabaseContract.ShaderColumns.NAME + "," +
						DatabaseContract.ShaderColumns.MODIFIED + "," + DatabaseContract.ShaderColumns.SOURCE_MODIFIED + "," + DatabaseContract.ShaderColumns.QUALITY + "," +
						DatabaseContract.ShaderColumns.COMPILE_TIME_MS + "," + DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH + "," + DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED +
						" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" ORDER BY RANDOM() LIMIT 1";

		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.rawQuery(query, null)) {
			if (cursor.moveToFirst()) {
				return new DataRecords.Shader(
						CursorHelpers.getLong(cursor, DatabaseContract.ShaderColumns._ID),
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.FRAGMENT_SHADER),
						CursorHelpers.getString(cursor, DatabaseContract.ShaderColumns.NAME),
						CursorHelpers.getString(cursor, DatabaseContract.ShaderColumns.MODIFIED),
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.SOURCE_MODIFIED),
						CursorHelpers.getFloat(cursor, DatabaseContract.ShaderColumns.QUALITY),
						CursorHelpers.getLongOrNull(cursor,
								DatabaseContract.ShaderColumns.COMPILE_TIME_MS),
						CursorHelpers.getIntOrNull(cursor,
								DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH),
						CursorHelpers.getString(cursor,
								DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED));
			}
		}
		return null;
	}

	@Nullable
	public byte[] getThumbnail(long id) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.query(
				DatabaseContract.ShaderColumns.TABLE_NAME,
				new String[]{DatabaseContract.ShaderColumns.THUMB},
				DatabaseContract.ShaderColumns._ID + " = ?",
				new String[]{String.valueOf(id)},
				null, null, null)) {
			if (cursor != null && cursor.moveToFirst()) {
				return CursorHelpers.getBlob(cursor, DatabaseContract.ShaderColumns.THUMB);
			}
		}
		return null;
	}

	public long getFirstShaderId() {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor =
				db.rawQuery("SELECT " + DatabaseContract.ShaderColumns._ID + " FROM " + DatabaseContract.ShaderColumns.TABLE_NAME + " ORDER BY "
						+ DatabaseContract.ShaderColumns._ID + " LIMIT 1", null)) {
			if (cursor != null && cursor.moveToFirst()) {
				return CursorHelpers.getLong(cursor, DatabaseContract.ShaderColumns._ID);
			}
		}
		return 0;
	}

	public long insertShaderFromResource(@NonNull Context context, @Nullable String name,
			int sourceId, int thumbId,
			float quality) {
		try {
			return insertShader(
					Resources.loadRawResource(context, sourceId),
					name,
					Resources.loadBitmapResource(context, thumbId),
					quality);
		} catch (IOException e) {
			return 0;
		}
	}

	public long insertNewShader() {
		return insertShaderFromResource(
				appContext,
				null,
				R.raw.new_shader,
				R.drawable.thumbnail_new_shader,
				1f);
	}

	public long insertShader(@NonNull String shader, @Nullable String name,
			@Nullable byte[] thumbnail, float quality) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		return insertShader(db, shader, name, thumbnail, quality);
	}

	public void updateShader(long id, @Nullable String shader, @Nullable byte[] thumbnail,
			float quality) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		var cv = new ContentValues();
		cv.put(DatabaseContract.ShaderColumns.MODIFIED, currentTime());
		cv.put(DatabaseContract.ShaderColumns.QUALITY, quality);
		if (shader != null) {
			cv.put(DatabaseContract.ShaderColumns.FRAGMENT_SHADER, shader);
			// SOURCE_MODIFIED only bumps when the GLSL text actually
			// changed, unlike MODIFIED above - a save triggered by
			// something else (autosave on pause, a thumbnail refresh,
			// a quality tweak) shouldn't make the shader look like it
			// was just edited.
			if (!shader.equals(getStoredSource(db, id))) {
				cv.put(DatabaseContract.ShaderColumns.SOURCE_MODIFIED, currentTime());
			}
		}
		if (thumbnail != null) {
			cv.put(DatabaseContract.ShaderColumns.THUMB, thumbnail);
		}
		db.update(DatabaseContract.ShaderColumns.TABLE_NAME, cv,
				DatabaseContract.ShaderColumns._ID + " = ?",
				new String[]{String.valueOf(id)});
	}

	@Nullable
	private static String getStoredSource(@NonNull SQLiteDatabase db, long id) {
		try (var cursor = db.query(
				DatabaseContract.ShaderColumns.TABLE_NAME,
				new String[]{DatabaseContract.ShaderColumns.FRAGMENT_SHADER},
				DatabaseContract.ShaderColumns._ID + " = ?",
				new String[]{String.valueOf(id)},
				null, null, null)) {
			if (cursor.moveToFirst()) {
				return CursorHelpers.getString(
						cursor, DatabaseContract.ShaderColumns.FRAGMENT_SHADER);
			}
		}
		return null;
	}

	public void updateShaderName(long id, @NonNull String name) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		var cv = new ContentValues();
		cv.put(DatabaseContract.ShaderColumns.NAME, name);
		db.update(DatabaseContract.ShaderColumns.TABLE_NAME, cv,
				DatabaseContract.ShaderColumns._ID + " = ?",
				new String[]{String.valueOf(id)});
	}

	// Null unless this exact combination of shaderId+sourceHash+
	// driverSignature was previously saveCompiledBinary()'d - a mismatch
	// on any of the three (the source changed, or the GPU driver did) is
	// treated as a plain cache miss, not an error. See ProgramBinaryCache.
	@Nullable
	public ProgramBinary getCompiledBinary(long id, @NonNull String sourceHash,
			@NonNull String driverSignature) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.query(
				DatabaseContract.ShaderColumns.TABLE_NAME,
				new String[]{
						DatabaseContract.ShaderColumns.COMPILED_BINARY,
						DatabaseContract.ShaderColumns.COMPILED_BINARY_FORMAT},
				DatabaseContract.ShaderColumns._ID + " = ? AND " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_SOURCE_HASH + " = ? AND " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_DRIVER_SIG + " = ?",
				new String[]{String.valueOf(id), sourceHash, driverSignature},
				null, null, null)) {
			if (cursor.moveToFirst()) {
				byte[] bytes = CursorHelpers.getBlob(
						cursor, DatabaseContract.ShaderColumns.COMPILED_BINARY);
				if (bytes != null && bytes.length > 0) {
					return new ProgramBinary(
							CursorHelpers.getInt(
									cursor, DatabaseContract.ShaderColumns.COMPILED_BINARY_FORMAT),
							bytes);
				}
			}
		}
		return null;
	}

	// Pure cache metadata - deliberately doesn't touch MODIFIED or
	// SOURCE_MODIFIED. compileTimeMs should only ever be a genuinely
	// freshly-measured compile duration, never a cache-hit's near-zero
	// load time - see ShaderRenderer/HeadlessShaderCompiler. The two
	// COMPILE_TIME_SOURCE_* columns are copied from this same row's
	// current FRAGMENT_SHADER/SOURCE_MODIFIED via SQL expressions
	// (ContentValues can't express that), letting a later caller judge
	// how stale that timing is without needing GL access - see
	// DataRecords.Shader's field comments.
	public void saveCompiledBinary(long id, @NonNull ProgramBinary binary,
			@NonNull String sourceHash, @NonNull String driverSignature,
			long compileTimeMs) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		db.execSQL(
				"UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME + " SET " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY + " = ?, " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_FORMAT + " = ?, " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_SOURCE_HASH + " = ?, " +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_DRIVER_SIG + " = ?, " +
						DatabaseContract.ShaderColumns.COMPILE_TIME_MS + " = ?, " +
						DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH + " = LENGTH(" +
						DatabaseContract.ShaderColumns.FRAGMENT_SHADER + "), " +
						DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED + " = " +
						DatabaseContract.ShaderColumns.SOURCE_MODIFIED +
						" WHERE " + DatabaseContract.ShaderColumns._ID + " = ?",
				new Object[]{binary.bytes(), binary.format(), sourceHash, driverSignature,
						compileTimeMs, id});
	}

	// Records the most recently observed live fps for this shader at
	// this quality - called on every settled fps reading while the
	// shader is actually live (see ShaderViewManager), so a later
	// caller can know how slow this exact shader was last running
	// without needing it to be the currently-live one. Deliberately
	// doesn't touch MODIFIED - this is pure runtime telemetry, not a
	// user edit.
	public void updateLastFps(long id, float quality, int fps) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		var cv = new ContentValues();
		cv.put(DatabaseContract.ShaderColumns.LAST_FPS, fps);
		cv.put(DatabaseContract.ShaderColumns.LAST_FPS_QUALITY, quality);
		db.update(DatabaseContract.ShaderColumns.TABLE_NAME, cv,
				DatabaseContract.ShaderColumns._ID + " = ?", new String[]{String.valueOf(id)});
	}

	// -1 if this shader has never recorded a live fps at exactly this
	// quality (including a brand new shader, or one only ever seen at a
	// different quality) - callers should treat that as "no data",
	// same as a genuinely unknown fps, not as "fast".
	public int getLastFps(long id, float quality) {
		SQLiteDatabase db = dbHelper.getReadableDatabase();
		try (var cursor = db.rawQuery(
				"SELECT " + DatabaseContract.ShaderColumns.LAST_FPS + ", " +
						DatabaseContract.ShaderColumns.LAST_FPS_QUALITY +
						" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" WHERE " + DatabaseContract.ShaderColumns._ID + " = ?",
				new String[]{String.valueOf(id)})) {
			if (cursor.moveToFirst()) {
				Integer fps = CursorHelpers.getIntOrNull(cursor, DatabaseContract.ShaderColumns.LAST_FPS);
				if (fps != null &&
						CursorHelpers.getFloat(cursor, DatabaseContract.ShaderColumns.LAST_FPS_QUALITY) == quality) {
					return fps;
				}
			}
		}
		return -1;
	}

	// For rename lists made from a copy of this library (see
	// BatchShaderDialog): only renames shader `id` if it was created at
	// `created`, so a list can't hit a different shader that happens to
	// have that id. Like updateShaderName(), leaves every date alone.
	public boolean renameShaderIfCreated(long id, @NonNull String created,
			@NonNull String name) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		var cv = new ContentValues();
		cv.put(DatabaseContract.ShaderColumns.NAME, name);
		return db.update(DatabaseContract.ShaderColumns.TABLE_NAME, cv,
				DatabaseContract.ShaderColumns._ID + " = ? AND " +
						DatabaseContract.ShaderColumns.CREATED + " = ?",
				new String[]{String.valueOf(id), created}) > 0;
	}

	// See renameShaderIfCreated().
	public boolean removeShaderIfCreated(long id, @NonNull String created) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		int removed = db.delete(DatabaseContract.ShaderColumns.TABLE_NAME,
				DatabaseContract.ShaderColumns._ID + " = ? AND " +
						DatabaseContract.ShaderColumns.CREATED + " = ?",
				new String[]{String.valueOf(id), created});
		if (removed > 0) {
			BufferDao.deleteBuffers(db, id);
		}
		return removed > 0;
	}

	public void removeShader(long id) {
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		db.delete(DatabaseContract.ShaderColumns.TABLE_NAME,
				DatabaseContract.ShaderColumns._ID + " = ?",
				new String[]{String.valueOf(id)});
		BufferDao.deleteBuffers(db, id);
	}
	// endregion

	// region Package-private static helpers
	static long insertShader(@NonNull SQLiteDatabase db, @NonNull String shader,
			@Nullable String name,
			@Nullable byte[] thumbnail,
			float quality) {
		String now = currentTime();
		var cv = new ContentValues();
		cv.put(DatabaseContract.ShaderColumns.FRAGMENT_SHADER, shader);
		cv.put(DatabaseContract.ShaderColumns.THUMB, thumbnail);
		cv.put(DatabaseContract.ShaderColumns.NAME, name);
		cv.put(DatabaseContract.ShaderColumns.CREATED, now);
		cv.put(DatabaseContract.ShaderColumns.MODIFIED, now);
		cv.put(DatabaseContract.ShaderColumns.SOURCE_MODIFIED, now);
		cv.put(DatabaseContract.ShaderColumns.QUALITY, quality);
		return db.insert(DatabaseContract.ShaderColumns.TABLE_NAME, null, cv);
	}

	// sourceModified is a separate parameter (rather than always equal to
	// modified) so database import can carry over the imported row's own
	// value instead of regenerating it - see importShaders().
	public static long insertShader(
			@NonNull SQLiteDatabase db,
			@NonNull String shader,
			@Nullable String name,
			@NonNull String created,
			@NonNull String modified,
			@NonNull String sourceModified,
			@Nullable byte[] thumbnail,
			float quality) {
		ContentValues cv = new ContentValues();
		cv.put(DatabaseContract.ShaderColumns.FRAGMENT_SHADER, shader);
		cv.put(DatabaseContract.ShaderColumns.THUMB, thumbnail);
		cv.put(DatabaseContract.ShaderColumns.NAME, name);
		cv.put(DatabaseContract.ShaderColumns.CREATED, created);
		cv.put(DatabaseContract.ShaderColumns.MODIFIED, modified);
		cv.put(DatabaseContract.ShaderColumns.SOURCE_MODIFIED, sourceModified);
		cv.put(DatabaseContract.ShaderColumns.QUALITY, quality);
		return db.insert(DatabaseContract.ShaderColumns.TABLE_NAME, null, cv);
	}

	private static void addShaderNames(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.NAME + " TEXT;");
	}

	private static void addShadersQuality(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.QUALITY + " REAL;");
		db.execSQL("UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME + " SET " + DatabaseContract.ShaderColumns.QUALITY + " = " +
				"1;");
	}

	// SOURCE_MODIFIED: backfilled from the existing MODIFIED value so
	// existing rows get a sane initial date rather than null - see
	// updateShader() for how it diverges from MODIFIED going forward.
	// The COMPILED_BINARY_* columns are pure cache metadata (compiled
	// program binary, its format, and the source/driver signature it's
	// valid for) with nothing to backfill - they start empty and get
	// populated the next time each shader is compiled.
	private static void addSourceModifiedAndCompiledBinaryCache(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.SOURCE_MODIFIED + " DATETIME;");
		db.execSQL("UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME + " SET " + DatabaseContract.ShaderColumns.SOURCE_MODIFIED + " = " + DatabaseContract.ShaderColumns.MODIFIED + ";");
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.COMPILED_BINARY + " BLOB;");
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.COMPILED_BINARY_FORMAT + " INTEGER;");
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.COMPILED_BINARY_SOURCE_HASH + " TEXT;");
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.COMPILED_BINARY_DRIVER_SIG + " TEXT;");
	}

	// Nothing to backfill - these start empty (null) for every existing
	// row and get populated the next time each shader is genuinely
	// freshly compiled (not loaded from the binary cache) - see
	// saveCompiledBinary().
	private static void addCompileTimeTracking(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.COMPILE_TIME_MS + " INTEGER;");
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH + " INTEGER;");
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED + " DATETIME;");
	}

	// Nothing to backfill - every existing shader is the user's own.
	private static void addPackId(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.PACK_ID + " TEXT;");
	}

	// Nothing to backfill - starts null for every existing row and gets
	// populated the next time each shader actually renders a live frame.
	private static void addLastFpsTracking(@NonNull SQLiteDatabase db) {
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.LAST_FPS + " INTEGER;");
		db.execSQL("ALTER TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " ADD COLUMN " + DatabaseContract.ShaderColumns.LAST_FPS_QUALITY + " REAL;");
	}
	// endregion

	// region Private helpers
	@NonNull
	private static String currentTime() {
		return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
	}
	// endregion

	@NonNull
	@Contract("_ -> new")
	public static DatabaseTable buildSchema(@NonNull Context context) {
		return new DatabaseTable() {
			@Override
			public void onCreate(@NonNull SQLiteDatabase db) {
				createShadersTable(db);
			}

			@Override
			public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
				if (oldVersion < 3) {
					addShadersQuality(db);
				}
				if (oldVersion < 5) {
					addShaderNames(db);
				}
				if (oldVersion < 7) {
					addSourceModifiedAndCompiledBinaryCache(db);
				}
				if (oldVersion < 8) {
					addCompileTimeTracking(db);
				}
				if (oldVersion < 9) {
					addLastFpsTracking(db);
				}
				if (oldVersion < 11) {
					addPackId(db);
				}
				if (oldVersion < 12) {
					// v11's pack install tagged the user's own shaders when
					// a pack held the same ones, instead of adding copies -
					// only test packs existed then, so just untag everything.
					db.execSQL("UPDATE " + DatabaseContract.ShaderColumns.TABLE_NAME + " SET " +
							DatabaseContract.ShaderColumns.PACK_ID + " = NULL;");
				}
				if (oldVersion == 12) {
					renamePackShaders(db);
				}
			}

			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb) {
				return importShaders(intoDb, fromDb, null);
			}

			@Override
			public boolean onImport(@NonNull SQLiteDatabase intoDb,
					@NonNull SQLiteDatabase fromDb, @Nullable String packId) {
				return importShaders(intoDb, fromDb, packId);
			}

			private void createShadersTable(@NonNull SQLiteDatabase db) {
				db.execSQL("CREATE TABLE " + DatabaseContract.ShaderColumns.TABLE_NAME + " (" +
						DatabaseContract.ShaderColumns._ID + " INTEGER PRIMARY KEY " +
						"AUTOINCREMENT," +
						DatabaseContract.ShaderColumns.FRAGMENT_SHADER + " TEXT NOT NULL," +
						DatabaseContract.ShaderColumns.THUMB + " BLOB," +
						DatabaseContract.ShaderColumns.NAME + " TEXT," +
						DatabaseContract.ShaderColumns.CREATED + " DATETIME," +
						DatabaseContract.ShaderColumns.MODIFIED + " DATETIME," +
						DatabaseContract.ShaderColumns.SOURCE_MODIFIED + " DATETIME," +
						DatabaseContract.ShaderColumns.QUALITY + " REAL," +
						DatabaseContract.ShaderColumns.COMPILED_BINARY + " BLOB," +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_FORMAT + " INTEGER," +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_SOURCE_HASH + " TEXT," +
						DatabaseContract.ShaderColumns.COMPILED_BINARY_DRIVER_SIG + " TEXT," +
						DatabaseContract.ShaderColumns.COMPILE_TIME_MS + " INTEGER," +
						DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_LENGTH + " INTEGER," +
						DatabaseContract.ShaderColumns.COMPILE_TIME_SOURCE_MODIFIED + " DATETIME," +
						DatabaseContract.ShaderColumns.LAST_FPS + " INTEGER," +
						DatabaseContract.ShaderColumns.LAST_FPS_QUALITY + " REAL," +
						DatabaseContract.ShaderColumns.PACK_ID + " TEXT);");
				insertInitialShaders(db);
			}

			private void insertInitialShaders(SQLiteDatabase db) {
				try {
					String defaultShader = Resources.loadRawResource(context,
							R.raw.default_shader);
					var defaultThumb = Resources.loadBitmapResource(context,
							R.drawable.thumbnail_default);
					insertShader(db,
							defaultShader,
							context.getString(R.string.default_shader),
							defaultThumb,
							1f);
				} catch (IOException e) {
					// This should not happen with packaged resources.
					Toast.makeText(context, "Failed to load initial shaders", Toast.LENGTH_SHORT).show();
				}
			}
		};
	}

	// packId: importing a shader pack - each of its shaders becomes a new
	// shader of its own, tagged with the pack and named
	// "<Pack Title>: <name>" (so it can't be confused with, or grouped among,
	// the user's shaders of the same name), unless this pack already
	// installed that same shader. The user's own shaders are never
	// touched. Otherwise (a backup), shaders already here (same
	// created/modified dates) are skipped and the rest keep their pack_id.
	private static boolean importShaders(
			SQLiteDatabase dst,
			SQLiteDatabase src,
			@Nullable String packId) {
		try (Cursor cursor = src.rawQuery(
				"SELECT *" +
						" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" ORDER BY " + DatabaseContract.ShaderColumns._ID,
				null)) {
			if (cursor == null) {
				return false;
			}
			int shaderIndex =
					cursor.getColumnIndex(DatabaseContract.ShaderColumns.FRAGMENT_SHADER);
			int thumbIndex = cursor.getColumnIndex(DatabaseContract.ShaderColumns.THUMB);
			int nameIndex = cursor.getColumnIndex(DatabaseContract.ShaderColumns.NAME);
			int qualityIndex = cursor.getColumnIndex(DatabaseContract.ShaderColumns.QUALITY);
			String namePrefix = packId != null ? packNamePrefix(src, packId) : null;
			boolean success = true;
			if (cursor.moveToFirst()) {
				do {
					String createdDate = CursorHelpers.getString(cursor,
							DatabaseContract.ShaderColumns.CREATED);
					String modifiedDate = CursorHelpers.getString(cursor,
							DatabaseContract.ShaderColumns.MODIFIED);
					if (createdDate == null || modifiedDate == null) {
						continue;
					}
					if (packId != null
							? packShaderExists(dst, createdDate, modifiedDate, packId)
							: shaderExists(dst, createdDate, modifiedDate)) {
						continue;
					}
					// Falls back to modifiedDate when importing a backup
					// made before SOURCE_MODIFIED existed (an empty,
					// rather than missing, string - see CursorHelpers).
					String sourceModifiedDate = CursorHelpers.getString(cursor,
							DatabaseContract.ShaderColumns.SOURCE_MODIFIED);
					if (sourceModifiedDate == null || sourceModifiedDate.isEmpty()) {
						sourceModifiedDate = modifiedDate;
					}
					String name = cursor.getString(nameIndex);
					if (namePrefix != null) {
						// An unnamed shader goes by its date, as it would here.
						name = namePrefix + DataRecords.ShaderInfo.PACK_NAME_SEPARATOR +
								(name == null || name.trim().isEmpty()
								? createdDate
								: name.trim());
					}
					long shaderId = insertShader(dst,
							cursor.getString(shaderIndex),
							name,
							createdDate,
							modifiedDate,
							sourceModifiedDate,
							cursor.getBlob(thumbIndex),
							cursor.getFloat(qualityIndex));
					if (shaderId < 1 || !BufferDao.importBuffers(dst, src,
							CursorHelpers.getLong(cursor, DatabaseContract.ShaderColumns._ID),
							shaderId)) {
						success = false;
						break;
					}
					// "" when the source predates pack_id - see CursorHelpers.
					String rowPackId = packId != null
							? packId
							: CursorHelpers.getString(cursor,
									DatabaseContract.ShaderColumns.PACK_ID);
					if (rowPackId != null && !rowPackId.isEmpty()) {
						tagShader(dst, shaderId, rowPackId);
					}
				} while (cursor.moveToNext());
			}
			return success;
		}
	}

	private static void tagShader(SQLiteDatabase db, long shaderId, String packId) {
		var cv = new ContentValues();
		cv.put(DatabaseContract.ShaderColumns.PACK_ID, packId);
		db.update(DatabaseContract.ShaderColumns.TABLE_NAME, cv,
				DatabaseContract.ShaderColumns._ID + " = ?",
				new String[]{String.valueOf(shaderId)});
	}

	private static boolean packShaderExists(SQLiteDatabase db, String createdDate,
			String modifiedDate, String packId) {
		try (Cursor cursor = db.rawQuery(
				"SELECT " + DatabaseContract.ShaderColumns._ID +
						" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" WHERE " + DatabaseContract.ShaderColumns.CREATED + " = ?" +
						" AND " + DatabaseContract.ShaderColumns.MODIFIED + " = ?" +
						" AND " + DatabaseContract.ShaderColumns.PACK_ID + " = ?",
				new String[]{createdDate, modifiedDate, packId})) {
			return cursor != null && cursor.moveToFirst();
		}
	}

	// The pack's title, from the pack file's own pack_info - the id if it
	// has no usable title.
	@NonNull
	private static String packNamePrefix(SQLiteDatabase src, String packId) {
		String title = null;
		try (Cursor cursor = src.rawQuery("SELECT " + DatabaseContract.PackInfoColumns.TITLE +
				" FROM " + DatabaseContract.PackInfoColumns.TABLE_NAME + " LIMIT 1", null)) {
			if (cursor != null && cursor.moveToFirst()) {
				title = cursor.getString(0);
			}
		} catch (SQLiteException e) {
			// No pack_info - use the id.
		}
		// Never containing the separator itself, so the name splits back
		// into pack and shader unambiguously.
		String prefix = (title != null ? title : packId)
				.replace(DataRecords.ShaderInfo.PACK_NAME_SEPARATOR.trim(), "").trim();
		return prefix.isEmpty() ? packId : prefix;
	}

	// v12 named pack shaders "<PackTitleInPascalCase>.<name>" - rename
	// them to the current "<Pack Title>: <name>".
	private static void renamePackShaders(@NonNull SQLiteDatabase db) {
		try (Cursor cursor = db.rawQuery("SELECT s." + DatabaseContract.ShaderColumns._ID +
				", s." + DatabaseContract.ShaderColumns.NAME + ", p." +
				DatabaseContract.PackColumns.TITLE + " FROM " +
				DatabaseContract.ShaderColumns.TABLE_NAME + " s JOIN " +
				DatabaseContract.PackColumns.TABLE_NAME + " p ON s." +
				DatabaseContract.ShaderColumns.PACK_ID + " = p." +
				DatabaseContract.PackColumns.PACK_ID, null)) {
			while (cursor.moveToNext()) {
				String name = cursor.getString(1);
				int dot = name != null ? name.indexOf('.') : -1;
				if (dot < 0) {
					continue;
				}
				var cv = new ContentValues();
				cv.put(DatabaseContract.ShaderColumns.NAME, cursor.getString(2) +
						DataRecords.ShaderInfo.PACK_NAME_SEPARATOR + name.substring(dot + 1));
				db.update(DatabaseContract.ShaderColumns.TABLE_NAME, cv,
						DatabaseContract.ShaderColumns._ID + " = ?",
						new String[]{String.valueOf(cursor.getLong(0))});
			}
		}
	}

	private static boolean shaderExists(
			SQLiteDatabase db,
			String createdDate,
			String modifiedDate) {
		try (Cursor cursor = db.rawQuery(
				"SELECT " + DatabaseContract.ShaderColumns._ID +
						" FROM " + DatabaseContract.ShaderColumns.TABLE_NAME +
						" WHERE " + DatabaseContract.ShaderColumns.CREATED + " = ?" +
						" AND " + DatabaseContract.ShaderColumns.MODIFIED + " = ?",
				new String[]{createdDate, modifiedDate})) {
			return cursor != null && cursor.moveToFirst() && cursor.getCount() > 0;
		}
	}
}
