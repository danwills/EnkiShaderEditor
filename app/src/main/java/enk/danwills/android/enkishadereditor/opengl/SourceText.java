package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// A pass's own source as the `sourceText` texture, so a shader can draw
// (or read) its own text: one texel per character, and texel row n is
// line n - so line 0 is at v = 0, and a shader looks a character up
// by (column, line) directly. Each texel:
// - R: the character code, ASCII 32-126; tabs are expanded to spaces
//   and anything else becomes '?';
// - G, B: the character's index in the whole text, in reading order,
//   as G * 256 + B - for type-on reveals in order with one fetch;
// - A: 255 for a character, 0 for padding past the end of its line.
// `sourceTextSize` is (columns, lines) and `sourceTextLength` the number
// of characters - see ShaderRenderer.drawChainRows(), which binds each
// pass its own.
final class SourceText {
	// Long enough for any sensible line or shader, small enough for
	// every GPU's texture size limit.
	static final int MAX_COLUMNS = 512;
	static final int MAX_LINES = 2048;
	private static final int MAX_INDEX = 0xffff;
	// Exact texels: a character code blended with its neighbour's would
	// be a different character.
	private static final TextureParameters NEAREST = new TextureParameters(
			GLES20.GL_NEAREST, GLES20.GL_NEAREST,
			GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE);

	@NonNull
	final GlTexture2D texture;
	final int columns;
	final int lines;
	final int length;
	// (columns, lines), for `sourceTextSize`.
	@NonNull
	final float[] size;

	private SourceText(@NonNull GlTexture2D texture, int columns, int lines, int length) {
		this.texture = texture;
		this.columns = columns;
		this.lines = lines;
		this.length = length;
		size = new float[]{columns, lines};
	}

	// GL thread only. Null for no source.
	@Nullable
	static SourceText create(@NonNull GlDevice device, @Nullable String source, int tabWidth) {
		if (source == null) {
			return null;
		}
		String[] text = expandTabs(source, Math.max(1, tabWidth)).split("\n", -1);
		int lines = Math.max(1, Math.min(MAX_LINES, text.length));
		int columns = 1;
		for (int y = 0; y < lines; ++y) {
			columns = Math.max(columns, Math.min(MAX_COLUMNS, text[y].length()));
		}
		ByteBuffer pixels = ByteBuffer.allocateDirect(columns * lines * 4)
				.order(ByteOrder.nativeOrder());
		int index = 0;
		for (int y = 0; y < lines; ++y) {
			String line = text[y];
			int n = Math.min(columns, line.length());
			for (int x = 0; x < n; ++x) {
				char c = line.charAt(x);
				int at = (y * columns + x) * 4;
				int i = Math.min(index++, MAX_INDEX);
				pixels.put(at, (byte) (c >= 32 && c <= 126 ? c : '?'));
				pixels.put(at + 1, (byte) (i >> 8));
				pixels.put(at + 2, (byte) i);
				pixels.put(at + 3, (byte) 255);
			}
		}
		var texture = device.createTexture2D();
		device.allocateTexture2D(texture, columns, lines);
		device.applyTextureParameters(texture, NEAREST);
		device.updateTexture2D(texture, pixels);
		return new SourceText(texture, columns, lines, index);
	}

	@NonNull
	private static String expandTabs(@NonNull String source, int tabWidth) {
		if (source.indexOf('\t') < 0) {
			return source;
		}
		var out = new StringBuilder(source.length());
		int column = 0;
		for (int i = 0; i < source.length(); ++i) {
			char c = source.charAt(i);
			if (c == '\t') {
				int spaces = tabWidth - column % tabWidth;
				out.append(" ".repeat(spaces));
				column += spaces;
			} else {
				out.append(c);
				column = c == '\n' ? 0 : column + 1;
			}
		}
		return out.toString();
	}

	void release(@NonNull GlDevice device) {
		device.deleteTexture(texture);
	}
}
