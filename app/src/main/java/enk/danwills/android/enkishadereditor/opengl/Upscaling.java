package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Locale;

// How a frame rendered below the screen's resolution is scaled up to it on
// screen: a filter, what its taps past the frame's edges read, and for some
// filters an amount. A property of the view, not the shader - so choosing
// one never touches (or recompiles) the source: ShaderViewManager keeps it
// for the app's session, starting from Settings' default (a spec() string,
// which parse() reads back, falling back to DEFAULT for anything it no
// longer knows). Only the on-screen view is filtered - thumbnails,
// captures and recordings are of the frame itself. See the surface shader
// in RendererProgramManager.
public final class Upscaling {
	// The surface shader's upscaleFilter values are the ordinals.
	public enum Filter {
		// The frame's own (backbuffer) filtering - nearest, unless a
		// `///p:` preset says otherwise.
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
		CRT("crt", 0.8f),
		// Defocus with a lens's character: a bladed iris with a bright
		// rim, colour fringes, and cat's eyes towards the frame's edges;
		// amount is its radius in texels.
		BOKEH("bokeh", 2f),
		// Soft whatever the radius: the frame blurred at its own size by
		// a Gaussian (amount: its sigma, in texels - see the pipeline's
		// preBlur()), then scaled up smoothly (B-spline).
		GAUSSIAN("gaussian", 1.5f),
		// Not a scaling at all: points or 2D Gaussian splats that the
		// shader's last offscreen buffer lists, added up over the frame -
		// see PrimitivePresenter. Amount is the exposure.
		POINTS("points", 1f),
		SPLATS("splats", 1f);

		@Nullable
		public final String name;
		public final float defaultAmount;

		Filter(@Nullable String name, float defaultAmount) {
			this.name = name;
			this.defaultAmount = defaultAmount;
		}

		public boolean hasAmount() {
			return this == SINC || this == DEFOCUS || this == CRT || this == BOKEH ||
					this == GAUSSIAN || isPrimitives();
		}

		// Draws the last buffer's points or splats - see PrimitivePresenter.
		public boolean isPrimitives() {
			return this == POINTS || this == SPLATS;
		}

		// A radius in texels.
		public boolean isRadius() {
			return this == DEFOCUS || this == BOKEH || this == GAUSSIAN;
		}

		public float minAmount() {
			return this == SINC ? 2f : isRadius() || isPrimitives() ? 0.25f : 0f;
		}

		public float maxAmount() {
			return this == SINC ? 6f : this == GAUSSIAN || isPrimitives() ? 8f
					: isRadius() ? 3.5f : 1f;
		}

		public float amountStep() {
			return this == SINC ? 1f : isRadius() || isPrimitives() ? 0.25f : 0.05f;
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
			if (filter == Filter.BOKEH) {
				// Its rim reaches a little past the radius.
				return Math.min(5, (int) Math.ceil(amount * 1.2f + 0.5f));
			}
			return 1;
		}

		// As Preferences keeps it: "filter,edge,amount", e.g. "sinc,clamp,3".
		@NonNull
		public String spec() {
			return (filter.name != null ? filter.name : "default") + "," + edge.name + "," +
					String.format(Locale.ROOT, "%.2f", amount);
		}

		// spec()'s string back - or DEFAULT, for anything not (or no
		// longer) known, the amount kept to its filter's range.
		@NonNull
		public static Upscale parse(@Nullable String spec) {
			if (spec == null) {
				return DEFAULT;
			}
			String[] parts = spec.split(",");
			Filter filter = parts.length > 0 ? Filter.named(parts[0]) : null;
			if (filter == null) {
				return DEFAULT;
			}
			Edge edge = parts.length > 1 ? Edge.named(parts[1]) : null;
			float amount = filter.defaultAmount;
			if (parts.length > 2 && filter.hasAmount()) {
				try {
					amount = Math.max(filter.minAmount(), Math.min(filter.maxAmount(),
							Float.parseFloat(parts[2])));
				} catch (NumberFormatException e) {
					amount = filter.defaultAmount;
				}
				if (filter == Filter.SINC) {
					amount = Math.round(amount);
				}
			}
			return new Upscale(filter, edge != null ? edge : Edge.CLAMP, amount);
		}
	}

	private Upscaling() {
	}
}
