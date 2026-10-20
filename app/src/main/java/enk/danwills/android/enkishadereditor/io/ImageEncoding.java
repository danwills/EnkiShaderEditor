package enk.danwills.android.enkishadereditor.io;

import android.graphics.Bitmap;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

// How captured images (screenshots, high-res captures and sequence
// frames) are encoded - picked in Settings (Preferences.IMAGE_FORMAT /
// IMAGE_QUALITY). Everything here is Bitmap.compress(), so no extra
// libraries. Thumbnails and textures stay PNG regardless.
public record ImageEncoding(@NonNull Format format, int quality) {
	public static final ImageEncoding PNG = new ImageEncoding(Format.PNG, 100);

	public enum Format {
		PNG("png", "png", "image/png", false),
		JPEG("jpeg", "jpg", "image/jpeg", true),
		WEBP("webp", "webp", "image/webp", true),
		WEBP_LOSSLESS("webp_lossless", "webp", "image/webp", false);

		// The value stored in preferences.
		@NonNull
		public final String key;
		@NonNull
		public final String extension;
		@NonNull
		public final String mimeType;
		// Whether IMAGE_QUALITY applies.
		public final boolean lossy;

		Format(@NonNull String key, @NonNull String extension, @NonNull String mimeType,
				boolean lossy) {
			this.key = key;
			this.extension = extension;
			this.mimeType = mimeType;
			this.lossy = lossy;
		}

		@NonNull
		public static Format fromKey(@Nullable String key) {
			for (Format format : values()) {
				if (format.key.equals(key)) {
					return format;
				}
			}
			return PNG;
		}
	}

	// Null if the bitmap couldn't be encoded (out of memory, mostly).
	@Nullable
	public byte[] encode(@NonNull Bitmap bitmap) {
		try (var out = new ByteArrayOutputStream()) {
			return bitmap.compress(compressFormat(), compressQuality(), out)
					? out.toByteArray()
					: null;
		} catch (IOException | OutOfMemoryError e) {
			return null;
		}
	}

	// The deprecated WEBP format is lossless at quality 100 from
	// Android 10, which is as far back as saving captures goes (see
	// ScreenshotSaver), so it stands in for WEBP_LOSSLESS/WEBP_LOSSY
	// before Android 11.
	@SuppressWarnings("deprecation")
	@NonNull
	private Bitmap.CompressFormat compressFormat() {
		boolean api30 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
		return switch (format) {
			case PNG -> Bitmap.CompressFormat.PNG;
			case JPEG -> Bitmap.CompressFormat.JPEG;
			case WEBP -> api30 ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
			case WEBP_LOSSLESS -> api30
					? Bitmap.CompressFormat.WEBP_LOSSLESS
					: Bitmap.CompressFormat.WEBP;
		};
	}

	private int compressQuality() {
		if (!format.lossy) {
			return 100;
		}
		int q = Math.max(1, Math.min(100, quality));
		// Keep the deprecated WEBP lossy (see compressFormat()).
		return format == Format.WEBP && Build.VERSION.SDK_INT < Build.VERSION_CODES.R
				? Math.min(99, q)
				: q;
	}

	// Which format some encoded bytes are, from their signature - so
	// whatever saves them names and types the file after what was
	// actually written, not whatever the setting says by then.
	@NonNull
	public static Format sniff(@NonNull byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 &&
				(bytes[2] & 0xff) == 0xff) {
			return Format.JPEG;
		}
		if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' &&
				bytes[3] == 'F' && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' &&
				bytes[11] == 'P') {
			return Format.WEBP;
		}
		return Format.PNG;
	}
}
