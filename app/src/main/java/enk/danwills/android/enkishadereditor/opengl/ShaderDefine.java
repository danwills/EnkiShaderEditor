package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

/**
 * One #define line from a shader's source, as parsed by
 * {@link ShaderDefineParser} for the "Adjust Defines" tool
 * (see MainActivity.showAdjustDefinesDialog()). Mutable - value/comment
 * are edited in place as the user adjusts a slider/text field, and
 * instances are reordered directly in whatever list holds them; name,
 * type and the slider range are fixed at parse time.
 */
public final class ShaderDefine {
	public enum Type {FLOAT, INT, BOOL, VEC2, VEC3, VEC4, OTHER}

	// The macro's name - for a function-like macro (e.g. "SQR(x)") this
	// is the whole name+parameter-list token, left untouched; only
	// object-like macros get slider/typed editing (see type below).
	@NonNull
	public final String name;
	@NonNull
	public final Type type;
	// Where this line originally was in the source (0-based), and its
	// exact leading whitespace - both needed by
	// ShaderDefineParser.reconstruct() to put edited content back in
	// place without disturbing indentation or anything around it.
	public final int lineIndex;
	@NonNull
	public final String indent;

	@NonNull
	public final String originalValue;
	@Nullable
	public final String originalComment;

	@NonNull
	public String value;
	@Nullable
	public String comment;

	// Only meaningful for FLOAT/INT - a heuristic exploration range
	// around the original value (see ShaderDefineParser.inferSliderRange),
	// not a hard limit; the paired text field still accepts anything.
	public final float sliderMin;
	public final float sliderMax;

	// Only non-null for VEC2/VEC3/VEC4 - the vector's own constructor
	// name ("vec3", "vec4"...) and its N components, editable in place
	// per-component (see syncVectorValue(), which rebuilds `value` from
	// these after any of them changes).
	@Nullable
	public final String vectorConstructor;
	@Nullable
	public final float[] vectorComponents;
	// ShaderDefineParser.looksLikeColor()'s own heuristic guess, fixed
	// at parse time - kept around only so showColorPicker below can be
	// reset back to it (see DefineAdapter's "treat as color" toggle).
	public final boolean looksLikeColor;
	// What DefineAdapter actually goes by when deciding whether to show
	// the color swatch/picker for a VEC3/VEC4 - starts equal to
	// looksLikeColor, but the user can flip it per-define (the heuristic
	// above is a guess, not a guarantee, and some colors - HDR ones
	// especially - won't obviously look like one from name/range alone).
	public boolean showColorPicker;

	public ShaderDefine(@NonNull String name, @NonNull Type type, int lineIndex,
			@NonNull String indent, @NonNull String value, @Nullable String comment,
			float sliderMin, float sliderMax,
			@Nullable String vectorConstructor, @Nullable float[] vectorComponents,
			boolean looksLikeColor) {
		this.name = name;
		this.type = type;
		this.lineIndex = lineIndex;
		this.indent = indent;
		this.originalValue = value;
		this.originalComment = comment;
		this.value = value;
		this.comment = comment;
		this.sliderMin = sliderMin;
		this.sliderMax = sliderMax;
		this.vectorConstructor = vectorConstructor;
		this.vectorComponents = vectorComponents;
		this.looksLikeColor = looksLikeColor;
		this.showColorPicker = looksLikeColor;
	}

	public boolean isValueChanged() {
		return !value.equals(originalValue);
	}

	public boolean isCommentChanged() {
		String current = comment == null ? "" : comment;
		String original = originalComment == null ? "" : originalComment;
		return !current.equals(original);
	}

	// Rebuilds `value` ("vec3(1.0, 0.5, 0.2)") from vectorComponents -
	// call after mutating any component in place (slider, per-component
	// text box, or the color picker writing all of them at once).
	public void syncVectorValue() {
		if (vectorComponents == null || vectorConstructor == null) {
			return;
		}
		StringBuilder sb = new StringBuilder(vectorConstructor).append('(');
		for (int i = 0; i < vectorComponents.length; i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(formatFloat(vectorComponents[i]));
		}
		sb.append(')');
		value = sb.toString();
	}

	// Rebuilds the exact source line this define should now occupy -
	// same indentation as the original, current (possibly edited) value
	// and comment.
	@NonNull
	public String toLine() {
		StringBuilder line = new StringBuilder(indent).append("#define ").append(name);
		if (!value.isEmpty()) {
			line.append(' ').append(value);
		}
		if (comment != null && !comment.isEmpty()) {
			line.append(" // ").append(comment);
		}
		return line.toString();
	}

	// Shared by syncVectorValue() above and DefineAdapter's own scalar
	// FLOAT editing - trims to a reasonably short decimal, never losing
	// the ability to round-trip back to the same float.
	@NonNull
	public static String formatFloat(float value) {
		if (value == Math.rint(value) && Math.abs(value) < 1e9f) {
			return (long) value + ".0";
		}
		String s = String.format(Locale.US, "%.6f", value);
		s = s.replaceAll("0+$", "");
		if (s.endsWith(".")) {
			s += "0";
		}
		return s;
	}
}
