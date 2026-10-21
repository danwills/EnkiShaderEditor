package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// A shader's soundtrack: a `///mod:name` line in any pass plays the
// tracker module of that name from the library (see ModuleDao and
// ModulePlayer).
public final class ModuleDirective {
	private static final Pattern PATTERN = Pattern.compile(
			"^[ \\t]*///mod:([A-Za-z0-9_]+)[ \\t]*$", Pattern.MULTILINE);

	private ModuleDirective() {
	}

	// The module the first of `sources` naming one names, or null.
	@Nullable
	public static String find(@Nullable String... sources) {
		if (sources == null) {
			return null;
		}
		for (String source : sources) {
			if (source == null) {
				continue;
			}
			Matcher matcher = PATTERN.matcher(source);
			if (matcher.find()) {
				return matcher.group(1);
			}
		}
		return null;
	}

	// The line that plays `name`.
	public static String line(String name) {
		return "///mod:" + name;
	}
}
