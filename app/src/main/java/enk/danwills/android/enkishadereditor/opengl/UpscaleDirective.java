package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// How a frame rendered below the screen's resolution is scaled up to it:
// a `///upscale:filter[,edge[,amount]]` line in any pass, e.g.
// `///upscale:sinc`, `///upscale:defocus,mirror,2.5`. Without one the frame
// is sampled as its backbuffer's filtering says (nearest, unless a
// `///p:` preset says otherwise). Only the on-screen view is filtered -
// thumbnails, captures and recordings are of the frame itself. See the
// surface shader in RendererProgramManager.
public final class UpscaleDirective {
	private static final Pattern PATTERN = Pattern.compile(
			"^[ \\t]*///upscale:([a-z]+)(?:,([a-z]+))?(?:,([0-9]*\\.?[0-9]+))?[ \\t]*$",
			Pattern.MULTILINE);
	// For finding any line to replace, valid or not.
	public static final Pattern ANY_LINE = Pattern.compile(
			"(?m)^[ \\t]*///upscale:.*\\n?");

	// The surface shader's upscaleFilter values are the ordinals.
	public enum Filter {
		// No line: the backbuffer's own filtering.
		DEFAULT(null, 0f),
		NEAREST("nearest", 0f),
		// Nearest, but each texel's edge blended across a screen pixel
		// or so, so its squares stay crisp without jaggies.
		SHARP("sharp", 0f),
		LINEAR("linear", 0f),
		// Cubic B-spline: smooth, a little soft, no ringing.
		BSPLINE("bspline", 0f),
		// Catmull-Rom: sharper, a faint halo at hard edges.
		CATMULL("catmull", 0f),
		// Lanczos-windowed sinc: the sharpest, ringing at hard edges -
		// the more lobes (amount, 2-6), the more.
		SINC("sinc", 3f),
		// A round, out-of-focus blur; amount is its radius in texels.
		DEFOCUS("defocus", 1.5f),
		// Nearest under a CRT's scanlines and phosphor stripes; amount is
		// how strongly (0-1).
		CRT("crt", 0.8f);

		@Nullable
		public final String name;
		public final float defaultAmount;

		Filter(@Nullable String name, float defaultAmount) {
			this.name = name;
			this.defaultAmount = defaultAmount;
		}

		public boolean hasAmount() {
			return this == SINC || this == DEFOCUS || this == CRT;
		}

		public float minAmount() {
			return this == SINC ? 2f : this == DEFOCUS ? 0.5f : 0f;
		}

		public float maxAmount() {
			return this == SINC ? 6f : this == DEFOCUS ? 3.5f : 1f;
		}

		public float amountStep() {
			return this == SINC ? 1f : this == DEFOCUS ? 0.25f : 0.05f;
		}

		@Nullable
		static Filter named(@NonNull String name) {
			for (Filter filter : values()) {
				if (name.equals(filter.name)) {
					return filter;
				}
			}
			return null;
		}
	}

	// What filter taps beyond the frame's edge read. The surface shader's
	// upscaleEdge values are the ordinals.
	public enum Edge {
		CLAMP("clamp"),
		PERIODIC("periodic"),
		MIRROR("mirror"),
		BLACK("black");

		@NonNull
		public final String name;

		Edge(@NonNull String name) {
			this.name = name;
		}

		@Nullable
		static Edge named(@NonNull String name) {
			for (Edge edge : values()) {
				if (name.equals(edge.name)) {
					return edge;
				}
			}
			return null;
		}
	}

	public record Upscale(@NonNull Filter filter, @NonNull Edge edge, float amount) {
		public static final Upscale DEFAULT = new Upscale(Filter.DEFAULT, Edge.CLAMP, 0f);

		// How many texels out from the centre the filter reads, which its
		// program is compiled for: sinc's lobes, the defocus disc's
		// radius rounded up.
		public int reach() {
			if (filter == Filter.SINC) {
				return Math.round(amount);
			}
			if (filter == Filter.DEFOCUS) {
				return Math.min(4, (int) Math.ceil(amount + 0.5f));
			}
			return 1;
		}

		// The line that asks for this, or null for DEFAULT (no line).
		@Nullable
		public String line() {
			if (filter.name == null) {
				return null;
			}
			var line = new StringBuilder("///upscale:").append(filter.name);
			boolean amount = filter.hasAmount() && amount() != filter.defaultAmount;
			if (edge != Edge.CLAMP || amount) {
				line.append(',').append(edge.name);
			}
			if (amount) {
				line.append(',').append(String.format(Locale.ROOT, "%.2f", amount())
						.replaceAll("0+$", "").replaceAll("\\.$", ""));
			}
			return line.toString();
		}
	}

	private UpscaleDirective() {
	}

	// The first of `sources`' upscale lines, or DEFAULT.
	@NonNull
	public static Upscale find(@Nullable String... sources) {
		if (sources == null) {
			return Upscale.DEFAULT;
		}
		for (String source : sources) {
			if (source == null) {
				continue;
			}
			Matcher matcher = PATTERN.matcher(source);
			if (!matcher.find()) {
				continue;
			}
			Filter filter = Filter.named(matcher.group(1));
			if (filter == null) {
				return Upscale.DEFAULT;
			}
			Edge edge = matcher.group(2) != null ? Edge.named(matcher.group(2)) : Edge.CLAMP;
			float amount = filter.defaultAmount;
			if (matcher.group(3) != null) {
				amount = Math.max(filter.minAmount(), Math.min(filter.maxAmount(),
						Float.parseFloat(matcher.group(3))));
				if (filter == Filter.SINC) {
					amount = Math.round(amount);
				}
			}
			return new Upscale(filter, edge != null ? edge : Edge.CLAMP, amount);
		}
		return Upscale.DEFAULT;
	}

	// `source` with its upscale line (if any) replaced by `upscale`'s, at
	// the top - or removed, for DEFAULT.
	@NonNull
	public static String apply(@NonNull String source, @NonNull Upscale upscale) {
		String without = ANY_LINE.matcher(source).replaceAll("");
		String line = upscale.line();
		return line != null ? line + "\n" + without : without;
	}
}
