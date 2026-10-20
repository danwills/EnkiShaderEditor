package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Reads text back out of an offscreen buffer that holds it the way
// `sourceText` does (see SourceText and TextDirective): texel row n is
// line n, and each texel's R is a character code (as code / 255) - 0
// for no character. A tab is code 9, and the 0s after it, up to the
// next character, are the room it takes. The buffer's top row is left
// for the shader's own use (a cursor, say) and never read.
final class BufferText {
	private static final String TAG = "BufferText";

	private BufferText() {
	}

	// GL thread only. Null if the buffer can't be read. endWithNewline:
	// whether the text should end with a line break, as the source it
	// replaces did - a buffer can't tell an empty last line from none.
	@Nullable
	static String read(@NonNull GlDevice device, @NonNull BufferPass pass,
			boolean endWithNewline) {
		GlFramebuffer framebuffer = pass.getCurrentFramebuffer();
		int width = Math.min(pass.getWidth(), SourceText.MAX_COLUMNS);
		int height = Math.min(pass.getHeight() - 1, SourceText.MAX_LINES);
		if (framebuffer == null || width <= 0 || height <= 0) {
			return null;
		}
		device.bindFramebuffer(framebuffer);
		int[] codes = pass.isHalfFloat()
				? readFloatCodes(width, height)
				: readByteCodes(device, width, height);
		device.bindFramebuffer(null);
		return codes != null ? decode(codes, width, height, endWithNewline) : null;
	}

	@NonNull
	private static int[] readByteCodes(@NonNull GlDevice device, int width, int height) {
		ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4)
				.order(ByteOrder.nativeOrder());
		device.readPixels(0, 0, width, height, pixels);
		int[] codes = new int[width * height];
		for (int i = 0; i < codes.length; ++i) {
			codes[i] = pixels.get(i * 4) & 0xff;
		}
		return codes;
	}

	// A half-float buffer: read as floats where the GPU allows, else in
	// whatever format it says it reads that buffer in.
	@Nullable
	private static int[] readFloatCodes(int width, int height) {
		int[] value = new int[1];
		GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_TYPE, value, 0);
		int type = value[0];
		GLES20.glGetIntegerv(GLES20.GL_IMPLEMENTATION_COLOR_READ_FORMAT, value, 0);
		boolean rgba = value[0] == GLES20.GL_RGBA;
		int[] codes = new int[width * height];
		if (rgba && type == GLES30.GL_HALF_FLOAT) {
			ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 8)
					.order(ByteOrder.nativeOrder());
			GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, pixels);
			for (int i = 0; i < codes.length; ++i) {
				codes[i] = toCode(halfToFloat(pixels.getShort(i * 8)));
			}
		} else {
			ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 16)
					.order(ByteOrder.nativeOrder());
			GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, pixels);
			if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
				Log.w(TAG, "Can't read this buffer back (read type 0x" +
						Integer.toHexString(type) + ")");
				return null;
			}
			for (int i = 0; i < codes.length; ++i) {
				codes[i] = toCode(pixels.getFloat(i * 16));
			}
		}
		return codes;
	}

	private static int toCode(float value) {
		return Math.max(0, Math.min(255, Math.round(value * 255f)));
	}

	private static float halfToFloat(short half) {
		int h = half & 0xffff;
		int sign = (h >>> 15) << 31;
		int exponent = (h >>> 10) & 0x1f;
		int mantissa = h & 0x3ff;
		if (exponent == 0) {
			return (sign != 0 ? -1f : 1f) * mantissa / 1024f / 16384f;
		}
		if (exponent == 31) {
			return Float.intBitsToFloat(sign | 0x7f800000 | (mantissa << 13));
		}
		return Float.intBitsToFloat(sign | ((exponent + 112) << 23) | (mantissa << 13));
	}

	@NonNull
	static String decode(@NonNull int[] codes, int width, int height, boolean endWithNewline) {
		var text = new StringBuilder();
		int lastLine = -1;
		for (int y = 0; y < height; ++y) {
			for (int x = 0; x < width; ++x) {
				if (codes[y * width + x] != 0) {
					lastLine = y;
					break;
				}
			}
		}
		for (int y = 0; y <= lastLine; ++y) {
			int row = y * width;
			int end = width;
			while (end > 0 && codes[row + end - 1] == 0) {
				--end;
			}
			boolean afterTab = false;
			for (int x = 0; x < end; ++x) {
				int code = codes[row + x];
				if (code == 0) {
					// A tab's room - or, anywhere else, a gap: a space.
					if (!afterTab) {
						text.append(' ');
					}
					continue;
				}
				afterTab = code == '\t';
				text.append(code == '\t' || (code >= 32 && code <= 126) ? (char) code : '?');
			}
			if (y < lastLine || endWithNewline) {
				text.append('\n');
			}
		}
		return text.toString();
	}
}
