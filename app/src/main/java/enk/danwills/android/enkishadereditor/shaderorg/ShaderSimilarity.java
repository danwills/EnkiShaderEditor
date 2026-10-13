package enk.danwills.android.enkishadereditor.shaderorg;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.database.DataRecords.ShaderInfo;

// A small, deliberately simple text-similarity index: each shader's name
// and source are tokenized into a bag of lowercase words, and similarity
// between two shaders is their Jaccard index (intersection / union of
// those word sets). Good enough to surface "shaders that share a lot of
// identifiers/structure with this one" without any heavier NLP machinery.
public final class ShaderSimilarity {
	// Below this, two shaders are considered unrelated noise rather than
	// a meaningful match.
	public static final double DEFAULT_THRESHOLD = 0.64;

	private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");
	private static final int MIN_WORD_LENGTH = 3;

	private final Map<Long, Set<String>> tokensByShaderId = new HashMap<>();

	public void rebuild(@NonNull List<ShaderInfo> shaders, @NonNull Map<Long, String> sources) {
		tokensByShaderId.clear();
		for (ShaderInfo shader : shaders) {
			Set<String> tokens = new HashSet<>();
			addWords(tokens, shader.getTitle());
			addWords(tokens, sources.get(shader.id()));
			tokensByShaderId.put(shader.id(), tokens);
		}
	}

	public boolean isEmpty() {
		return tokensByShaderId.isEmpty();
	}

	// Returns the other shaders at least `threshold` similar to
	// `referenceId`, most similar first.
	@NonNull
	public List<ShaderInfo> findSimilar(
			long referenceId, @NonNull List<ShaderInfo> candidates, double threshold) {
		Set<String> reference = tokensByShaderId.get(referenceId);
		List<ShaderInfo> matches = new ArrayList<>();
		if (reference == null || reference.isEmpty()) {
			return matches;
		}
		List<double[]> scores = new ArrayList<>();
		for (int i = 0; i < candidates.size(); ++i) {
			ShaderInfo candidate = candidates.get(i);
			if (candidate.id() == referenceId) {
				continue;
			}
			Set<String> tokens = tokensByShaderId.get(candidate.id());
			if (tokens == null || tokens.isEmpty()) {
				continue;
			}
			double score = jaccard(reference, tokens);
			if (score >= threshold) {
				matches.add(candidate);
				scores.add(new double[]{matches.size() - 1, score});
			}
		}
		Collections.sort(scores, (a, b) -> Double.compare(b[1], a[1]));
		List<ShaderInfo> sorted = new ArrayList<>(matches.size());
		for (double[] entry : scores) {
			sorted.add(matches.get((int) entry[0]));
		}
		return sorted;
	}

	private static double jaccard(@NonNull Set<String> a, @NonNull Set<String> b) {
		Set<String> smaller = a.size() <= b.size() ? a : b;
		Set<String> larger = a.size() <= b.size() ? b : a;
		int intersection = 0;
		for (String token : smaller) {
			if (larger.contains(token)) {
				++intersection;
			}
		}
		int union = a.size() + b.size() - intersection;
		return union == 0 ? 0 : (double) intersection / union;
	}

	private static void addWords(@NonNull Set<String> tokens, String text) {
		if (text == null) {
			return;
		}
		var matcher = WORD.matcher(text);
		while (matcher.find()) {
			String word = matcher.group().toLowerCase(Locale.ROOT);
			if (word.length() >= MIN_WORD_LENGTH) {
				tokens.add(word);
			}
		}
	}
}
