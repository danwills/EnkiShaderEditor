package enk.danwills.android.enkishadereditor.shaderorg;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

// Splits a camelCase/PascalCase shader name into its sub-tokens, e.g.
// "ExampleTastyC" -> ["Example", "Tasty", "C"].
public final class NameTokenizer {
	private static final Pattern CAMEL_BOUNDARY = Pattern.compile(
			"(?<=[a-z0-9])(?=[A-Z])" +
					"|(?<=[A-Z])(?=[A-Z][a-z])" +
					"|(?<=[A-Za-z])(?=[0-9])" +
					"|(?<=[0-9])(?=[A-Za-z])");

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
