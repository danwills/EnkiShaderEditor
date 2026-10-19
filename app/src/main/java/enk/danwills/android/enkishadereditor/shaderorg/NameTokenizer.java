package enk.danwills.android.enkishadereditor.shaderorg;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Splits a camelCase/PascalCase shader name into its sub-tokens, e.g.
// "ExampleTastyC" -> ["Example", "Tasty", "C"]. Runs of capitals-or-numerals
// are kept as a single token - e.g. "thingABCFancy" -> ["thing", "ABC",
// "Fancy"], "scene3DView" -> ["scene", "3D", "View"] - except the run's
// last character splits off onto the next token when a lowercase letter
// immediately follows it, since that's almost always the start of a new
// word rather than part of an acronym/number. Whitespace and dots also
// split tokens and are elided entirely rather than kept as tokens - so a
// pack's "SlimeStudies.Physarum" groups under Slime > Studies.
public final class NameTokenizer {
	private static final Pattern CAMEL_BOUNDARY = Pattern.compile(
			"[\\s.]+" +
					"|(?<=[a-z])(?=[A-Z0-9])" +
					"|(?<=[A-Z0-9])(?=[A-Z0-9][a-z])");

	private NameTokenizer() {
	}

	@NonNull
	public static List<String> tokenize(@NonNull String name) {
		List<String> tokens = new ArrayList<>();
		for (String part : CAMEL_BOUNDARY.split(name)) {
			if (!part.isEmpty()) {
				tokens.add(part);
			}
		}
		return tokens;
	}
}
