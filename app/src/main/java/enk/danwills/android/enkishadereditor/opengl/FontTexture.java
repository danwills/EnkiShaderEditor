package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import enk.danwills.android.enkishadereditor.R;

// The built-in `font` texture: a signed-distance-field glyph atlas of
// 16x16 cells, character code n in column n % 16 of row n / 16 counting
// from the top (so in texture coordinates, code n's cell starts at
// u = (n % 16) / 16, v = 1 - (n / 16 + 1) / 16), the same grid as
// ShaderToy's font texture. The value is 0.5 on a glyph's edge, higher
// inside, in all four channels. Glyphs from IBM Plex Mono (SIL Open Font
// License), made by tools/font/make_atlas.py.
final class FontTexture {
	private FontTexture() {
	}

	// GL thread only. Null if the atlas couldn't be read.
	@Nullable
	static GlTexture2D create(@NonNull Context context, @NonNull GlDevice device) {
		var options = new BitmapFactory.Options();
		options.inScaled = false;
		Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.raw.font_atlas,
				options);
		if (bitmap == null) {
			return null;
		}
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		int[] argb = new int[width * height];
		bitmap.getPixels(argb, 0, width, 0, 0, width, height);
		bitmap.recycle();
		// Copied by hand rather than uploaded as a Bitmap, which would
		// premultiply colour by alpha; and flipped, so row 0 (the top of
		// the image) ends up at the top of the texture.
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4)
				.order(ByteOrder.nativeOrder());
		for (int y = 0; y < height; ++y) {
			int from = (height - 1 - y) * width;
			for (int x = 0; x < width; ++x) {
				byte value = (byte) (argb[from + x] >> 16);
				int at = (y * width + x) * 4;
				pixels.put(at, value);
				pixels.put(at + 1, value);
				pixels.put(at + 2, value);
				pixels.put(at + 3, value);
			}
		}
		var texture = device.createTexture2D();
		device.allocateTexture2D(texture, width, height);
		device.applyTextureParameters(texture, new TextureParameters(
				GLES20.GL_LINEAR_MIPMAP_LINEAR, GLES20.GL_LINEAR,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		device.updateTexture2D(texture, pixels);
		device.generateMipmap(texture);
		return texture;
	}
}
