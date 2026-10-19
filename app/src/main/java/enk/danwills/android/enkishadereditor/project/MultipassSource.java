package enk.danwills.android.enkishadereditor.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// One piece of text holding a whole multipass shader - for the clipboard,
// sharing and opening shared files. The Image pass comes first, then each
// offscreen buffer after a marker line that's just a GLSL comment:
//
//   ...Image pass...
//   //// Buffer A
//   ...Buffer A...
//
// A shader without buffers is just its source, exactly as before.
public final class MultipassSource {
	private static final String MARKER_PREFIX = "//// Buffer ";
	private static final Pattern PATTERN_MARKER = Pattern.compile(
			"^//// (Image|Buffer ([A-" +
					ShaderProjectPaths.getBufferLetter(ShaderProjectPaths.MAX_BUFFERS - 1) +
					"]))[ \\t]*$",
			Pattern.MULTILINE);

	// bufferSources: indexed by buffer, null where there's none - as
	// ShaderProject.getBufferSources().
	public record Parsed(@NonNull String imageSource, @NonNull String[] bufferSources) {
	}

	private MultipassSource() {
	}

	@NonNull
	public static String join(@NonNull String imageSource, @NonNull String[] bufferSources) {
		StringBuilder text = new StringBuilder(imageSource);
		for (int buffer = 0; buffer < bufferSources.length; ++buffer) {
			if (bufferSources[buffer] == null) {
				continue;
			}
			if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
				text.append('\n');
			}
			text.append(MARKER_PREFIX)
					.append(ShaderProjectPaths.getBufferLetter(buffer))
					.append('\n')
					.append(bufferSources[buffer]);
		}
		return text.toString();
	}

	// The passes in `text`, or null if it has no buffer markers - an
	// ordinary single-pass shader.
	@Nullable
	public static Parsed parse(@NonNull String text) {
		Matcher matcher = PATTERN_MARKER.matcher(text);
		String[] buffers = new String[ShaderProjectPaths.MAX_BUFFERS];
		boolean anyBuffer = false;
		String image = null;
		int sectionStart = 0;
		int section = -1;
		boolean leading = true;
		while (matcher.find()) {
			String content = text.substring(sectionStart, matcher.start());
			if (leading) {
				// Text before the first marker is the Image pass, unless
				// it's only whitespace and an explicit Image marker follows.
				if (!content.isBlank() || matcher.group(2) != null) {
					image = content;
				}
				leading = false;
			} else {
				store(section, content, buffers);
				if (section == -1) {
					image = content;
				}
			}
			String letter = matcher.group(2);
			section = letter != null ? letter.charAt(0) - 'A' : -1;
			anyBuffer |= letter != null;
			sectionStart = Math.min(text.length(), matcher.end() + 1);
		}
		if (!anyBuffer) {
			return null;
		}
		String content = text.substring(sectionStart);
		store(section, content, buffers);
		if (section == -1) {
			image = content;
		}
		return new Parsed(image != null ? image : "", buffers);
	}

	private static void store(int section, @NonNull String content, @NonNull String[] buffers) {
		if (section >= 0) {
			buffers[section] = content;
		}
	}
}
