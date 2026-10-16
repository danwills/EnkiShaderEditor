package enk.danwills.android.enkishadereditor.database.dao;

import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class CursorHelpers {
	public static int getInt(@NonNull Cursor c, @NonNull String col) {
		int i = c.getColumnIndex(col);
		return i > -1 ? c.getInt(i) : 0;
	}

	public static long getLong(@NonNull Cursor c, @NonNull String col) {
		int i = c.getColumnIndex(col);
		return i > -1 ? c.getLong(i) : 0L;
	}

	// Unlike getLong()/getInt() above, distinguishes "column is NULL"
	// (no value ever recorded) from a genuine 0 - needed for columns
	// where that distinction matters, like COMPILE_TIME_MS.
	@Nullable
	public static Long getLongOrNull(@NonNull Cursor c, @NonNull String col) {
		int i = c.getColumnIndex(col);
		return (i > -1 && !c.isNull(i)) ? c.getLong(i) : null;
	}

	@Nullable
	public static Integer getIntOrNull(@NonNull Cursor c, @NonNull String col) {
		int i = c.getColumnIndex(col);
		return (i > -1 && !c.isNull(i)) ? c.getInt(i) : null;
	}

	public static float getFloat(@NonNull Cursor c, @NonNull String col) {
		int i = c.getColumnIndex(col);
		return i > -1 ? c.getFloat(i) : 0f;
	}

	@Nullable
	public static String getString(@NonNull Cursor c, @NonNull String col) {
		int i = c.getColumnIndex(col);
		return i > -1 ? c.getString(i) : "";
	}

	@Nullable
	public static byte[] getBlob(@NonNull Cursor c, @NonNull String col) {
		int i = c.getColumnIndex(col);
		return i > -1 ? c.getBlob(i) : null;
	}
}
