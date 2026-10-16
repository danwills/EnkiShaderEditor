package enk.danwills.android.enkishadereditor.database;

import android.provider.BaseColumns;

/**
 * Defines the database schema, including table and column names.
 * Using a contract class ensures that constants are defined in one place,
 * preventing typos and making schema changes easier to manage.
 */
public final class DatabaseContract {
	public static final String FILE_NAME = "shaders.db";

	// To prevent someone from accidentally instantiating the contract class,
	// make the constructor private.
	private DatabaseContract() {
	}

	/**
	 * Defines the contents of the 'shaders' table.
	 * Implementing BaseColumns adds the standard _ID and _COUNT columns.
	 */
	public interface ShaderColumns extends BaseColumns {
		String TABLE_NAME = "shaders";
		String FRAGMENT_SHADER = "shader";
		String THUMB = "thumb";
		String NAME = "name";
		String CREATED = "created";
		String MODIFIED = "modified";
		// Unlike MODIFIED (bumped on every save, including ones that only
		// touch the thumbnail/quality), this only changes when the GLSL
		// source itself actually differs from what's stored - see
		// ShaderDao.updateShader().
		String SOURCE_MODIFIED = "source_modified";
		String QUALITY = "quality";
		String COMPILED_BINARY = "compiled_binary";
		String COMPILED_BINARY_FORMAT = "compiled_binary_format";
		String COMPILED_BINARY_SOURCE_HASH = "compiled_binary_source_hash";
		String COMPILED_BINARY_DRIVER_SIG = "compiled_binary_driver_sig";
		// Set together, only on a genuine fresh compile (never on a cache
		// hit) - see ShaderDao.saveCompiledBinary(). COMPILE_TIME_SOURCE_LENGTH/
		// _MODIFIED are a snapshot of FRAGMENT_SHADER's length and
		// SOURCE_MODIFIED at that moment, letting a later caller tell
		// (without needing GL/driver access) whether the source has
		// changed since - and if so, roughly how much - without re-deriving
		// the prepared-source hash the binary cache itself keys on.
		String COMPILE_TIME_MS = "compile_time_ms";
		String COMPILE_TIME_SOURCE_LENGTH = "compile_time_source_length";
		String COMPILE_TIME_SOURCE_MODIFIED = "compile_time_source_modified";
	}

	/**
	 * Defines the contents of the 'textures' table.
	 */
	public interface TextureColumns extends BaseColumns {
		String TABLE_NAME = "textures";
		String NAME = "name";
		String WIDTH = "width";
		String HEIGHT = "height";
		String RATIO = "ratio";
		String THUMB = "thumb";
		String MATRIX = "matrix";
	}
}
