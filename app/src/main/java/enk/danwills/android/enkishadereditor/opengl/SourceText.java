package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.highlighter.Highlight;
import enk.danwills.android.enkishadereditor.highlighter.Lexer;
import enk.danwills.android.enkishadereditor.highlighter.Token;

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
//
// `sourceTextColor` is its syntax highlighting, in the same layout: RGB
// is the colour the editor shows that character in, and A its kind -
// Highlight's ordinal + 1 (so 0 is still padding), for a shader that
// wants its own colours. Only made for a pass that declares it.
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
	// Null unless asked for - see create().
	@Nullable
	final GlTexture2D colorTexture;
	final int columns;
	final int lines;
	final int length;
	// (columns, lines), for `sourceTextSize`.
	@NonNull
	final float[] size;

	private SourceText(@NonNull GlTexture2D texture, @Nullable GlTexture2D colorTexture,
			int columns, int lines, int length) {
		this.texture = texture;
		this.colorTexture = colorTexture;
		this.columns = columns;
		this.lines = lines;
		this.length = length;
		size = new float[]{columns, lines};
	}

	// Whether `program` declares either texture, so needs one made.
	static boolean isUsedBy(@NonNull GlDevice device, @NonNull GlProgram program) {
		return device.hasUniform(program, ShaderRenderer.UNIFORM_SOURCE_TEXT) ||
				device.hasUniform(program, ShaderRenderer.UNIFORM_SOURCE_TEXT_COLOR);
	}

	// For `program`, with `sourceTextColor` only if it declares that.
	@Nullable
	static SourceText createFor(@NonNull Context context, @NonNull GlDevice device,
			@NonNull GlProgram program, @Nullable String source, int tabWidth) {
		return isUsedBy(device, program)
				? create(context, device, source, tabWidth,
						device.hasUniform(program, ShaderRenderer.UNIFORM_SOURCE_TEXT_COLOR))
				: null;
	}

	// GL thread only. Null for no source. withColor also makes
	// `sourceTextColor`, which lexes the source.
	@Nullable
	static SourceText create(@NonNull Context context, @NonNull GlDevice device,
			@Nullable String source, int tabWidth, boolean withColor) {
		if (source == null) {
			return null;
		}
		tabWidth = Math.max(1, tabWidth);
		int[] highlights = withColor ? highlightsOf(source) : null;
		int[] palette = withColor ? palette(context) : null;

		// Line lengths once tabs are expanded, for the texture's size.
		int lines = 1;
		int columns = 1;
		int column = 0;
		for (int i = 0; i < source.length() && lines <= MAX_LINES; ++i) {
			char c = source.charAt(i);
			if (c == '\n') {
				++lines;
				column = 0;
			} else {
				column += c == '\t' ? tabWidth - column % tabWidth : 1;
				columns = Math.max(columns, Math.min(MAX_COLUMNS, column));
			}
		}
		lines = Math.min(lines, MAX_LINES);

		ByteBuffer pixels = newBuffer(columns, lines);
		ByteBuffer colors = withColor ? newBuffer(columns, lines) : null;
		int index = 0;
		int line = 0;
		column = 0;
		for (int i = 0; i < source.length() && line < lines; ++i) {
			char c = source.charAt(i);
			if (c == '\n') {
				++line;
				column = 0;
				continue;
			}
			int count = c == '\t' ? tabWidth - column % tabWidth : 1;
			char code = c == '\t' ? ' ' : c >= 32 && c <= 126 ? c : '?';
			for (int n = 0; n < count; ++n, ++column) {
				if (column >= columns) {
					continue;
				}
				int at = (line * columns + column) * 4;
				int k = Math.min(index++, MAX_INDEX);
				pixels.put(at, (byte) code);
				pixels.put(at + 1, (byte) (k >> 8));
				pixels.put(at + 2, (byte) k);
				pixels.put(at + 3, (byte) 255);
				if (colors != null) {
					int highlight = highlights[i];
					int color = palette[highlight];
					colors.put(at, (byte) (color >> 16));
					colors.put(at + 1, (byte) (color >> 8));
					colors.put(at + 2, (byte) color);
					colors.put(at + 3, (byte) (highlight + 1));
				}
			}
		}
		return new SourceText(upload(device, pixels, columns, lines),
				colors != null ? upload(device, colors, columns, lines) : null,
				columns, lines, index);
	}

	// Each character's Highlight ordinal, as the editor colours it -
	// anything between tokens (white space) as plain text.
	@NonNull
	private static int[] highlightsOf(@NonNull String source) {
		int[] highlights = new int[source.length()];
		Arrays.fill(highlights, Highlight.HIGHLIGHT_IDENTIFIER.ordinal());
		for (Token token : new Lexer(source)) {
			int highlight = Highlight.from(token.type()).ordinal();
			int end = Math.min(token.endOffset(), highlights.length);
			for (int i = Math.max(0, token.startOffset()); i < end; ++i) {
				highlights[i] = highlight;
			}
		}
		return highlights;
	}

	// The editor's colours, by Highlight ordinal - see ShaderEditor.
	@NonNull
	private static int[] palette(@NonNull Context context) {
		Highlight[] values = Highlight.values();
		int[] palette = new int[values.length];
		for (Highlight highlight : values) {
			palette[highlight.ordinal()] = ContextCompat.getColor(context, highlight.id());
		}
		// Plain text in the editor's own text colour.
		palette[Highlight.HIGHLIGHT_IDENTIFIER.ordinal()] =
				ContextCompat.getColor(context, R.color.editor_text);
		return palette;
	}

	@NonNull
	private static ByteBuffer newBuffer(int columns, int lines) {
		return ByteBuffer.allocateDirect(columns * lines * 4).order(ByteOrder.nativeOrder());
	}

	@NonNull
	private static GlTexture2D upload(@NonNull GlDevice device, @NonNull ByteBuffer pixels,
			int columns, int lines) {
		var texture = device.createTexture2D();
		device.allocateTexture2D(texture, columns, lines);
		device.applyTextureParameters(texture, NEAREST);
		device.updateTexture2D(texture, pixels);
		return texture;
	}

	void release(@NonNull GlDevice device) {
		device.deleteTexture(texture);
		device.deleteTexture(colorTexture);
	}
}
