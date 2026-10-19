package enk.danwills.android.enkishadereditor.database;

import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.List;

import enk.danwills.android.enkishadereditor.database.dao.BufferDao;
import enk.danwills.android.enkishadereditor.database.dao.PackDao;
import enk.danwills.android.enkishadereditor.database.dao.ShaderDao;
import enk.danwills.android.enkishadereditor.database.dao.TextureDao;

public class DataSource {
	public final ShaderDao shader;
	public final TextureDao texture;
	public final BufferDao buffer;
	public final PackDao pack;

	@NonNull
	@Contract("_ -> new")
	public static List<DatabaseTable> buildSchema(@NonNull Context context) {
		return List.of(ShaderDao.buildSchema(context), TextureDao.buildSchema(context),
				BufferDao.buildSchema(), PackDao.buildSchema());
	}

	public DataSource(SQLiteOpenHelper dbHelper, @NonNull Context context) {
		this.shader = new ShaderDao(dbHelper, context);
		this.texture = new TextureDao(dbHelper, context);
		this.buffer = new BufferDao(dbHelper);
		this.pack = new PackDao(dbHelper);
	}
}
