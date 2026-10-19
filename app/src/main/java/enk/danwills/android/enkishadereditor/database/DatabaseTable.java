package enk.danwills.android.enkishadereditor.database;

import android.database.sqlite.SQLiteDatabase;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public interface DatabaseTable {
	void onCreate(@NonNull SQLiteDatabase db);

	void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion);

	boolean onImport(@NonNull SQLiteDatabase intoDb, @NonNull SQLiteDatabase fromDb);

	// Importing a shader pack: like onImport(), with the pack's id to tag
	// what it brings in with - see ShaderPackInstaller.
	default boolean onImport(@NonNull SQLiteDatabase intoDb, @NonNull SQLiteDatabase fromDb,
			@Nullable String packId) {
		return onImport(intoDb, fromDb);
	}
}