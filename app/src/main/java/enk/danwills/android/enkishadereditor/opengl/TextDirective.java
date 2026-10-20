package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A self-editing shader's marker: a line `///text:Image` (or `///text:A`
// to `///text:D`) in an offscreen buffer's source says that buffer holds
// an edited copy of that pass's text, laid out like `sourceText` (see
// BufferText). So that buffer's `sourceText` is that pass's source
// rather than its own - what it starts its copy from - and Run (see
// MainActivity.runShaderText()) reads the buffer back into that pass.
// Any other shader is unaffected.
public final class TextDirective {
	public static final int NONE = -2;

	private static final Pattern PATTERN = Pattern.compile(
			"^[ \\t]*///text:(Image|[A-D])[ \\t]*$", Pattern.MULTILINE);

	private TextDirective() {
	}

	// The pass `bufferSource` holds the text of - ShaderError.IMAGE_PASS
	// or a buffer index (0 = A) - or NONE.
	public static int findTarget(@Nullable String bufferSource) {
		if (bufferSource == null) {
			return NONE;
		}
		Matcher matcher = PATTERN.matcher(bufferSource);
		if (!matcher.find()) {
			return NONE;
		}
		String target = matcher.group(1);
		return "Image".equals(target) ? ShaderError.IMAGE_PASS : target.charAt(0) - 'A';
	}

	// The first buffer (0 = A) in `bufferSources` with a directive, or -1.
	public static int findTextBuffer(@Nullable String[] bufferSources) {
		if (bufferSources == null) {
			return -1;
		}
		for (int buffer = 0; buffer < bufferSources.length; ++buffer) {
			if (findTarget(bufferSources[buffer]) != NONE) {
				return buffer;
			}
		}
		return -1;
	}
}
