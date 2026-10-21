package enk.danwills.android.enkishadereditor.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;
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
// A shader's named texts (see ShaderTextDao) follow, after `//// Text
// <name>` lines - so a shared shader keeps them, and a sample can ship
// one. A shader with neither is just its source, exactly as before.
// ShaderToy's `//// Sound` section is read too, into a free buffer slot -
// its mainSound() makes it a Sound pass (see SoundOutput).
public final class MultipassSource {
	private static final String MARKER_PREFIX = "//// Buffer ";
	private static final String TEXT_MARKER_PREFIX = "//// Text ";
	private static final int TEXT_SECTION = -3;
	private static final String SOUND = "Sound";
	private static final int SOUND_SECTION = -2;
	private static final Pattern PATTERN_MARKER = Pattern.compile(
			"^//// (Image|Sound|Buffer ([A-" +
					ShaderProjectPaths.getBufferLetter(ShaderProjectPaths.MAX_BUFFERS - 1) +
					"])|Text ([A-Za-z_][A-Za-z0-9_]*))[ \\t]*$",
			Pattern.MULTILINE);

	// bufferSources: indexed by buffer, null where there's none - as
	// ShaderProject.getBufferSources(). texts: name -> text, in order.
	public record Parsed(@NonNull String imageSource, @NonNull String[] bufferSources,
			@NonNull Map<String, String> texts) {
	}

	private MultipassSource() {
	}

	@NonNull
	public static String join(@NonNull String imageSource, @NonNull String[] bufferSources) {
		return join(imageSource, bufferSources, Map.of());
	}

	@NonNull
	public static String join(@NonNull String imageSource, @NonNull String[] bufferSources,
			@NonNull Map<String, String> texts) {
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
		for (var entry : texts.entrySet()) {
			if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
				text.append('\n');
			}
			text.append(TEXT_MARKER_PREFIX)
					.append(entry.getKey())
					.append('\n')
					.append(entry.getValue());
		}
		return text.toString();
	}

	// The passes in `text`, or null if it has no buffer markers - an
	// ordinary single-pass shader.
	@Nullable
	public static Parsed parse(@NonNull String text) {
		Matcher matcher = PATTERN_MARKER.matcher(text);
		String[] buffers = new String[ShaderProjectPaths.MAX_BUFFERS];
		// The `//// Sound` section, if any - see placeSound().
		String[] sound = new String[1];
		var texts = new LinkedHashMap<String, String>();
		String textName = null;
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
				store(section, content, buffers, sound, texts, textName);
				if (section == -1) {
					image = content;
				}
			}
			String letter = matcher.group(2);
			textName = matcher.group(3);
			section = letter != null
					? letter.charAt(0) - 'A'
					: textName != null
							? TEXT_SECTION
							: SOUND.equals(matcher.group(1)) ? SOUND_SECTION : -1;
			anyBuffer |= section != -1;
			sectionStart = Math.min(text.length(), matcher.end() + 1);
		}
		if (!anyBuffer) {
			return null;
		}
		String content = text.substring(sectionStart);
		store(section, content, buffers, sound, texts, textName);
		if (section == -1) {
			image = content;
		}
		if (sound[0] != null) {
			placeSound(sound[0], buffers);
		}
		return new Parsed(image != null ? image : "", buffers, texts);
	}

	// ShaderToy's Sound tab (`//// Sound`) becomes a buffer whose
	// mainSound() makes it a Sound pass - in the first slot the shader
	// doesn't already use (or the last, if it uses them all).
	private static void placeSound(@NonNull String sound, @NonNull String[] buffers) {
		for (int buffer = 0; buffer < buffers.length; ++buffer) {
			if (buffers[buffer] == null) {
				buffers[buffer] = sound;
				return;
			}
		}
		buffers[buffers.length - 1] = sound;
	}

	private static void store(int section, @NonNull String content, @NonNull String[] buffers,
			@NonNull String[] sound, @NonNull Map<String, String> texts,
			@Nullable String textName) {
		if (section >= 0) {
			buffers[section] = content;
		} else if (section == SOUND_SECTION) {
			sound[0] = content;
		} else if (section == TEXT_SECTION && textName != null) {
			// The line break before the next marker is the format's, not
			// the text's.
			texts.put(textName, content.endsWith("\n")
					? content.substring(0, content.length() - 1)
					: content);
		}
	}
}
