package enk.danwills.android.enkishadereditor.database;

import androidx.annotation.Nullable;

/**
 * Contains immutable data records representing database entities.
 */
public final class DataRecords {
	interface WithTitle {
		@Nullable
		String name();

		// The fallback title (and list sort order) deliberately uses the
		// source-only timestamp rather than the general "anything
		// changed" one, so it reflects actual code edits.
		@Nullable
		String sourceModified();

		@Nullable
		default String getTitle() {
			var name = name();
			return name == null || name.isEmpty() ? sourceModified() : name;
		}
	}

	/**
	 * Holds summary data for a shader, suitable for list displays.
	 */
	public record ShaderInfo(
			long id,
			@Nullable
			String name,
			@Nullable
			String modified,
			@Nullable
			String sourceModified,
			@Nullable
			byte[] thumb,
			// The shader pack it was installed from, or null for the
			// user's own.
			@Nullable
			String packId
	) implements WithTitle {
		// Pack shaders are named "<Pack Title>: <name>" - see ShaderDao's
		// import.
		public static final String PACK_NAME_SEPARATOR = ": ";

		public boolean isFromPack() {
			return packId != null && !packId.isEmpty();
		}
	}

	/**
	 * Holds the complete data for a single shader.
	 */
	public record Shader(
			long id,
			String fragmentShader,
			@Nullable
			String name,
			@Nullable
			String modified,
			@Nullable
			String sourceModified,
			float quality,
			// The three below are null until a genuine fresh compile (not
			// a cache hit) has ever been timed for this shader - see
			// ShaderDao.saveCompiledBinary(). compileTimeSourceLength/
			// compileTimeSourceModified are a snapshot of fragmentShader's
			// length and sourceModified as they were at that moment, so a
			// caller can tell whether/how much the source has grown since
			// without needing GL/driver access to re-derive the binary
			// cache's own prepared-source hash.
			@Nullable
			Long compileTimeMs,
			@Nullable
			Integer compileTimeSourceLength,
			@Nullable
			String compileTimeSourceModified
	) implements WithTitle {
	}

	/**
	 * Holds summary data for a texture, suitable for list displays.
	 */
	public record TextureInfo(
			long id,
			String name,
			int width,
			int height,
			byte[] thumb
	) {
	}

	/**
	 * A shader pack installed into the library - see ShaderPackInstaller.
	 */
	public record ShaderPack(
			String packId,
			String title,
			@Nullable
			String description,
			@Nullable
			String author,
			int version,
			@Nullable
			String installed,
			@Nullable
			String source,
			// See DatabaseContract.PackColumns.SHOW and PackShow.
			@Nullable
			String show
	) {
		public boolean isShow() {
			return show != null && !show.isEmpty();
		}
	}
}
