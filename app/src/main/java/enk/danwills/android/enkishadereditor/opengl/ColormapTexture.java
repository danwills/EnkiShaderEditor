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

// The built-in `colormaps` texture: a colour map per row, 256 steps from
// low (u = 0) to high (u = 1) - Ready's ten, then the CET Perceptually
// Uniform Colour Maps (Peter Kovesi, CC BY 4.0), as tools/colormaps/
// names.txt lists them, row 0 first. Row r is at v = 1 - (r + 0.5) / rows;
// `colormapCount` is the number of rows. Made by
// tools/colormaps/make_atlas.py.
final class ColormapTexture {
	private ColormapTexture() {
	}

	// GL thread only. The texture and its row count, or null if the atlas
	// couldn't be read.
	@Nullable
	static GlTexture2D create(@NonNull Context context, @NonNull GlDevice device) {
		var options = new BitmapFactory.Options();
		options.inScaled = false;
		Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.raw.colormaps,
				options);
		if (bitmap == null) {
			return null;
		}
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		int[] argb = new int[width * height];
		bitmap.getPixels(argb, 0, width, 0, 0, width, height);
		bitmap.recycle();
		// By hand, flipped so row 0 is at the top, as FontTexture does.
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4)
				.order(ByteOrder.nativeOrder());
		for (int y = 0; y < height; ++y) {
			int from = (height - 1 - y) * width;
			for (int x = 0; x < width; ++x) {
				int c = argb[from + x];
				int at = (y * width + x) * 4;
				pixels.put(at, (byte) (c >> 16));
				pixels.put(at + 1, (byte) (c >> 8));
				pixels.put(at + 2, (byte) c);
				pixels.put(at + 3, (byte) 0xff);
			}
		}
		var texture = device.createTexture2D();
		device.allocateTexture2D(texture, width, height);
		// Linear along a map; rows are sampled at their centres, so they
		// don't bleed into each other.
		device.applyTextureParameters(texture, new TextureParameters(
				GLES20.GL_LINEAR, GLES20.GL_LINEAR,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		device.updateTexture2D(texture, pixels);
		return texture;
	}
}
