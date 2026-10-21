package enk.danwills.android.enkishadereditor.shaderorg;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import enk.danwills.android.enkishadereditor.database.DataRecords.ShaderInfo;

// How well a query matches a shader's name, for ranking Find Shader's
// matches and the sidebar filter: higher is better, 0 is no match. In
// order, best first:
//   7 the whole name ("Slime" for "Slime");
//   6 whole words at the start ("slime mold" or "slimemold" for
//     "SlimeMoldTwo" - words as NameTokenizer splits them);
//   5 whole words elsewhere ("mold" for "SlimeMoldTwo");
//   4 the start of the name ("sli");
//   3 the start of a word ("mo");
//   2 anywhere ("ime");
//   1 fuzzy only (its letters in order, see fuzzyMatches()).
// Within a tier, the closer the query is to the name's whole length, the
// higher (less than 1 more), so shorter names come first. A pack
// shader's name is matched without its "<Pack Title>: " too, and spaces
// in the query are optional ("slime mold" finds "SlimeMold").
public final class NameMatch {
	private NameMatch() {
	}

	public static double score(@NonNull String query, @NonNull String name, boolean fuzzy) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (q.isEmpty()) {
			return 0;
		}
		double best = scoreNames(q, name, fuzzy);
		String joined = q.replaceAll("\\s+", "");
		if (!joined.equals(q)) {
			best = Math.max(best, scoreNames(joined, name, fuzzy));
		}
		return best;
	}

	private static double scoreNames(@NonNull String q, @NonNull String name, boolean fuzzy) {
		double best = scoreOne(q, name, fuzzy);
		int separator = name.indexOf(ShaderInfo.PACK_NAME_SEPARATOR);
		if (separator >= 0) {
			best = Math.max(best, scoreOne(q,
					name.substring(separator + ShaderInfo.PACK_NAME_SEPARATOR.length()), fuzzy));
		}
		return best;
	}

	// Every character of `query` appears in `target` in order, though not
	// necessarily next to each other. Both lower case.
	public static boolean fuzzyMatches(@NonNull String query, @NonNull String target) {
		int qi = 0;
		for (int ti = 0; ti < target.length() && qi < query.length(); ++ti) {
			if (target.charAt(ti) == query.charAt(qi)) {
				++qi;
			}
		}
		return qi == query.length();
	}

	private static double scoreOne(@NonNull String q, @NonNull String name, boolean fuzzy) {
		String t = name.toLowerCase(Locale.ROOT);
		int tier = tier(q, name, t, fuzzy);
		return tier == 0 ? 0 : tier + 0.5 * Math.min(1.0, (double) q.length() / t.length());
	}

	private static int tier(@NonNull String q, @NonNull String name, @NonNull String t,
			boolean fuzzy) {
		if (t.equals(q)) {
			return 7;
		}
		// Where NameTokenizer's words start and end in the name.
		Set<Integer> starts = new HashSet<>();
		Set<Integer> ends = new HashSet<>();
		List<String> tokens = NameTokenizer.tokenize(name);
		int from = 0;
		for (String token : tokens) {
			int at = name.indexOf(token, from);
			if (at < 0) {
				break;
			}
			starts.add(at);
			ends.add(at + token.length());
			from = at + token.length();
		}
		int tier = 0;
		for (int at = t.indexOf(q); at >= 0; at = t.indexOf(q, at + 1)) {
			boolean wordStart = at == 0 || starts.contains(at);
			boolean wholeWords = wordStart && ends.contains(at + q.length());
			int here = wholeWords
					? at == 0 ? 6 : 5
					: at == 0 ? 4 : wordStart ? 3 : 2;
			tier = Math.max(tier, here);
		}
		if (tier == 0 && fuzzy && fuzzyMatches(q, t)) {
			tier = 1;
		}
		return tier;
	}
}
