package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Reads and rewrites a pass's `uniform sampler2D iChannelN;///...`
// declarations (see ChannelParameters) - what the editor's channel slots
// show and edit. Edits are plain source changes, so they're undoable and
// travel with the code like anything else in it.
public final class ChannelDeclarations {
	// Where a new declaration goes: after the last leading #version,
	// #extension or precision line (and any #endif closing a precision
	// #ifdef), so it never lands before something that has to come first.
	private static final Pattern PATTERN_PREAMBLE = Pattern.compile(
			"^[ \\t]*(#version|#extension|precision)[^\\n]*\\n([ \\t]*#endif[^\\n]*\\n)?",
			Pattern.MULTILINE);

	private ChannelDeclarations() {
	}

	// The channel's parameters as declared in `source`, or null if it
	// doesn't declare that channel.
	@Nullable
	public static ChannelParameters find(@NonNull String source, int channel) {
		Matcher matcher = declaration(channel).matcher(source);
		return matcher.find() ? new ChannelParameters(matcher.group(1)) : null;
	}

	// `source` with the channel declared with `parameters` - replacing an
	// existing declaration, inserting one if there isn't any, or removing
	// it when `parameters` is null.
	@NonNull
	public static String withChannel(@NonNull String source, int channel,
			@Nullable ChannelParameters parameters) {
		Matcher matcher = declaration(channel).matcher(source);
		String line = parameters == null
				? ""
				: "uniform sampler2D " + ChannelParameters.getChannelName(channel) + ";" +
						parameters + "\n";
		if (matcher.find()) {
			return source.substring(0, matcher.start()) + line +
					source.substring(matcher.end());
		}
		if (parameters == null) {
			return source;
		}
		int insertAt = 0;
		for (Matcher preamble = PATTERN_PREAMBLE.matcher(source); preamble.find(); ) {
			// Only the leading block - a precision statement far down
			// inside a function isn't part of it.
			if (!source.substring(insertAt, preamble.start()).trim().isEmpty()) {
				break;
			}
			insertAt = preamble.end();
		}
		return source.substring(0, insertAt) + line + source.substring(insertAt);
	}

	@NonNull
	private static Pattern declaration(int channel) {
		return Pattern.compile(
				"^[ \\t]*uniform[ \\t]+sampler2D[ \\t]+" +
						ChannelParameters.getChannelName(channel) +
						"[ \\t]*;([^\\n]*)(\\n|$)",
				Pattern.MULTILINE);
	}
}
