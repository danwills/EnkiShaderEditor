package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses #define lines out of shader source for the "Adjust Defines"
 * tool (see MainActivity.showAdjustDefinesDialog()), and writes edited/
 * reordered {@link ShaderDefine}s back into that same source afterward.
 * Deliberately simple, matching the app's existing (much smaller)
 * define-scanning in MainActivity.findDefineLines(): a #define is any
 * line whose trimmed text starts with "#define", full stop - no
 * awareness of #if/#ifdef guards or defines hidden inside block
 * comments.
 */
public final class ShaderDefineParser {
	private ShaderDefineParser() {
	}

	// Captures: 1 = leading whitespace, 2 = name (incl. a function-like
	// macro's own "(args)", since that's all one token with no space),
	// 3 = everything after the name, untrimmed.
	private static final Pattern DEFINE_LINE =
			Pattern.compile("^(\\s*)#define\\s+(\\S+)(.*)$");

	// A plain vecN(...) constructor call with nothing else around it -
	// e.g. "vec3(1.0, 0.5, 0.2)". Only this exact shape gets per-
	// component slider/color editing (see bindVector() in DefineAdapter);
	// anything with extra arithmetic, nested calls, a swizzle, etc. is
	// left as OTHER, editable only as plain text.
	private static final Pattern VEC_CONSTRUCTOR =
			Pattern.compile("^(vec[234])\\s*\\((.*)\\)$");

	/**
	 * Parses every #define line in `source`, in file order, excluding
	 * any whose name never appears again anywhere else in the file -
	 * those aren't doing anything a shader invocation can see, so
	 * there's nothing meaningful to adjust (see MainActivity's own
	 * confirmation toast if this ends up excluding everything).
	 */
	@NonNull
	public static List<ShaderDefine> parseUsedDefines(@NonNull String source) {
		List<ShaderDefine> all = parseAllDefines(source);
		List<ShaderDefine> used = new ArrayList<>();
		for (ShaderDefine define : all) {
			if (isReferencedElsewhere(source, define)) {
				used.add(define);
			}
		}
		return used;
	}

	// How many #define lines parseUsedDefines() above excluded for not
	// being referenced anywhere else in the file - see
	// MainActivity.showAdjustDefinesDialog()'s own note about those.
	public static int countExcludedDefines(@NonNull String source) {
		return parseAllDefines(source).size() - parseUsedDefines(source).size();
	}

	@NonNull
	private static List<ShaderDefine> parseAllDefines(@NonNull String source) {
		List<ShaderDefine> defines = new ArrayList<>();
		String[] lines = source.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			Matcher m = DEFINE_LINE.matcher(lines[i]);
			if (!m.matches()) {
				continue;
			}
			String indent = m.group(1);
			String name = m.group(2);
			String rest = m.group(3).strip();
			String value;
			String comment = null;
			int commentAt = rest.indexOf("//");
			if (commentAt >= 0) {
				value = rest.substring(0, commentAt).strip();
				comment = rest.substring(commentAt + 2).strip();
				if (comment.isEmpty()) {
					comment = null;
				}
			} else {
				value = rest;
			}
			defines.add(parseDefine(name, i, indent, value, comment));
		}
		return defines;
	}

	@NonNull
	private static ShaderDefine parseDefine(@NonNull String name, int lineIndex,
			@NonNull String indent, @NonNull String value, @Nullable String comment) {
		Matcher vec = VEC_CONSTRUCTOR.matcher(value);
		if (vec.matches()) {
			String constructor = vec.group(1);
			String[] parts = vec.group(2).split(",", -1);
			int n = Integer.parseInt(constructor.substring(3));
			if (parts.length == n) {
				float[] components = new float[n];
				boolean allNumeric = true;
				for (int i = 0; i < n; i++) {
					Float parsed = tryParseFloat(parts[i].strip());
					if (parsed == null) {
						allNumeric = false;
						break;
					}
					components[i] = parsed;
				}
				if (allNumeric) {
					ShaderDefine.Type type = n == 2 ? ShaderDefine.Type.VEC2
							: n == 3 ? ShaderDefine.Type.VEC3 : ShaderDefine.Type.VEC4;
					boolean color = looksLikeColor(name, type, components);
					return new ShaderDefine(name, type, lineIndex, indent, value, comment,
							0, 0, constructor, components, color);
				}
			}
		}
		ShaderDefine.Type type = inferType(value);
		float[] range = inferSliderRange(type, value);
		return new ShaderDefine(name, type, lineIndex, indent, value, comment,
				range[0], range[1], null, null, false);
	}

	// Name fragments that suggest a vector holds a color, beyond the
	// obvious "color"/"colour" - see looksLikeColor() below. Deliberately
	// a plain substring match (same as "color"/"colour" themselves),
	// not whole-word, so e.g. "skyTint" or "baseAlbedo" still match.
	private static final String[] COLOR_NAME_HINTS = {
			"color", "colour", "tint", "albedo", "diffuse", "specular",
			"ambient", "emissive", "emission", "rgb", "hue",
	};

	// A VEC3/VEC4 "looks like" a color if its name says so, or if every
	// component sits in a generous range around normalized [0,1] - not
	// strict [0,1] itself, since HDR/overbright colors routinely exceed
	// 1 on purpose. Any value clearly outside that (very negative, or
	// large) is treated as "probably not a color" without a telling
	// name - e.g. a direction, scale, or world-space offset vector.
	// Never the final word either way - see ShaderDefine.colorOverride,
	// which lets the user flip this per-define from the editor itself.
	private static final float COLOR_RANGE_LO = -0.1f;
	private static final float COLOR_RANGE_HI = 4f;

	private static boolean looksLikeColor(
			@NonNull String name, @NonNull ShaderDefine.Type type, @NonNull float[] components) {
		if (type != ShaderDefine.Type.VEC3 && type != ShaderDefine.Type.VEC4) {
			return false;
		}
		String lower = name.toLowerCase(Locale.ROOT);
		for (String hint : COLOR_NAME_HINTS) {
			if (lower.contains(hint)) {
				return true;
			}
		}
		for (float c : components) {
			if (c < COLOR_RANGE_LO || c > COLOR_RANGE_HI) {
				return false;
			}
		}
		return true;
	}

	@Nullable
	private static Float tryParseFloat(@NonNull String token) {
		String candidate = (token.endsWith("f") || token.endsWith("F"))
				? token.substring(0, token.length() - 1)
				: token;
		if (candidate.isEmpty()) {
			return null;
		}
		try {
			float parsed = Float.parseFloat(candidate);
			return Float.isNaN(parsed) || Float.isInfinite(parsed) ? null : parsed;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// Whether `define`'s name shows up anywhere in `source` other than
	// on its own line - a plain word-boundary search against the bare
	// name (a function-like macro's parameter list is stripped first,
	// since the invocation site never repeats it).
	private static boolean isReferencedElsewhere(@NonNull String source, @NonNull ShaderDefine define) {
		String bareName = define.name.contains("(")
				? define.name.substring(0, define.name.indexOf('('))
				: define.name;
		if (bareName.isEmpty()) {
			return false;
		}
		Pattern wordBoundary = Pattern.compile("\\b" + Pattern.quote(bareName) + "\\b");
		String[] lines = source.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			if (i == define.lineIndex) {
				continue;
			}
			if (wordBoundary.matcher(lines[i]).find()) {
				return true;
			}
		}
		return false;
	}

	@NonNull
	private static ShaderDefine.Type inferType(@NonNull String value) {
		if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
			return ShaderDefine.Type.BOOL;
		}
		if (value.matches("-?\\d+")) {
			return ShaderDefine.Type.INT;
		}
		return tryParseFloat(value) != null ? ShaderDefine.Type.FLOAT : ShaderDefine.Type.OTHER;
	}

	// A generous, symmetric-ish exploration range around the original
	// value, always including both it and zero - not a hard limit (the
	// paired text field still accepts any value), just where the slider
	// starts out useful without the user having to rescale it first.
	@NonNull
	private static float[] inferSliderRange(@NonNull ShaderDefine.Type type, @NonNull String value) {
		if (type != ShaderDefine.Type.FLOAT && type != ShaderDefine.Type.INT) {
			return new float[]{0, 0};
		}
		Float numeric = tryParseFloat(value);
		return numeric == null ? new float[]{-1, 1} : inferSliderRange(numeric);
	}

	// Same heuristic as above, directly against a known value - shared
	// with DefineAdapter's own per-component vector sliders (see
	// bindVector()), which have no surrounding #define text to re-parse.
	@NonNull
	// Sized to the value itself - a small one (a reaction rate of 0.042,
	// say) gets a small range rather than at least -2 to 2, where a touch
	// threw it anywhere - and to -2..2 only for 0.
	public static float[] inferSliderRange(float numeric) {
		float base = numeric != 0f ? Math.abs(numeric) : 1f;
		float span = base * 2f;
		float lo = Math.min(0f, numeric - span);
		float hi = Math.max(0f, numeric + span);
		return new float[]{lo, hi};
	}

	/**
	 * The source live mode runs: every define at its own line, with its
	 * current value, and the ones in `live` declared as uniforms there
	 * instead - see ShaderDefine.toLiveLine(). Reordering doesn't matter
	 * for running, so it isn't applied here.
	 */
	@NonNull
	public static String toLiveSource(@NonNull String originalSource,
			@NonNull List<ShaderDefine> defines, @NonNull Collection<ShaderDefine> live) {
		String[] lines = originalSource.split("\n", -1);
		for (ShaderDefine define : defines) {
			if (define.lineIndex < lines.length) {
				lines[define.lineIndex] = live.contains(define) && define.canBeLive()
						? define.toLiveLine()
						: define.toLine();
			}
		}
		return String.join("\n", lines);
	}

	// Lines testing whether a macro exists or what it is - a uniform
	// can't be either, and turning a define into one would quietly flip
	// every `#ifdef NAME` without any compile error to catch it.
	private static final Pattern CONDITIONAL_DIRECTIVE =
			Pattern.compile("^\\s*#\\s*(if|ifdef|ifndef|elif|undef)\\b.*");

	// Whether `define` is tested by an #if/#ifdef/#ifndef/#elif/#undef
	// anywhere in `source`, so it can't be made live - see
	// CONDITIONAL_DIRECTIVE.
	public static boolean isUsedInConditional(@NonNull String source,
			@NonNull ShaderDefine define) {
		Pattern word = Pattern.compile("\\b" + Pattern.quote(define.name) + "\\b");
		for (String line : source.split("\n", -1)) {
			if (CONDITIONAL_DIRECTIVE.matcher(line).matches() && word.matcher(line).find()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Writes `editedDefines` (in whatever order the user left them -
	 * see MainActivity's drag-reorder adapter) back into `originalSource`:
	 * the N used-define lines keep their N original line *positions*
	 * (so everything else in the file is untouched, including any
	 * excluded/unused #defines), but now hold editedDefines' content in
	 * its new order - position k gets editedDefines.get(k), regardless
	 * of which define used to live there. That's exactly what
	 * "reordering" these means from the file's point of view: the same
	 * N slots, differently filled.
	 */
	@NonNull
	public static String reconstruct(@NonNull String originalSource,
			@NonNull List<ShaderDefine> originalOrderDefines,
			@NonNull List<ShaderDefine> editedDefines) {
		String[] lines = originalSource.split("\n", -1);
		List<Integer> positions = new ArrayList<>(originalOrderDefines.size());
		for (ShaderDefine define : originalOrderDefines) {
			positions.add(define.lineIndex);
		}
		Collections.sort(positions);
		for (int i = 0; i < positions.size(); i++) {
			lines[positions.get(i)] = editedDefines.get(i).toLine();
		}
		return String.join("\n", lines);
	}
}
