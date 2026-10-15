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
			byte[] thumb
	) implements WithTitle {
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
			float quality
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
}