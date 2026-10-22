package enk.danwills.android.enkishadereditor.opengl;

import android.util.Log;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RendererProgramManager {
	private static final String TAG = "RendererPrograms";
	// A buffer pass's program freshly compiled from source this reload,
	// for the caller to persist - see ReloadResult.freshBinary.
	record FreshBufferBinary(int buffer, @NonNull String sourceText,
			@NonNull ProgramBinary binary) {
	}

	// Looks up a buffer pass's cached program binary, or null.
	interface BufferBinaryLookup {
		@Nullable
		ProgramBinary get(int buffer, @NonNull String sourceText);
	}

	record ReloadResult(
			@NonNull List<ShaderError> textureErrors,
			@NonNull List<ShaderError> programErrors,
			boolean succeeded,
			// Non-null only when the main program was freshly compiled
			// from source this time (as opposed to loaded from a cache
			// hit) - the caller can persist it so next time's load can
			// skip straight to createProgramFromBinary().
			@Nullable ProgramBinary freshBinary,
			@NonNull List<FreshBufferBinary> freshBufferBinaries) {
		@NonNull
		static ReloadResult success(@NonNull List<ShaderError> textureErrors,
				@Nullable ProgramBinary freshBinary,
				@NonNull List<FreshBufferBinary> freshBufferBinaries) {
			return new ReloadResult(List.copyOf(textureErrors), List.of(), true, freshBinary,
					List.copyOf(freshBufferBinaries));
		}

		@NonNull
		static ReloadResult failure(
				@NonNull List<ShaderError> textureErrors,
				@NonNull List<ShaderError> programErrors) {
			return new ReloadResult(
					List.copyOf(textureErrors),
					List.copyOf(programErrors),
					false,
					null,
					List.of());
		}
	}

	// Package-private: also used by HeadlessShaderCompiler, which needs
	// the exact same vertex source to produce cache-compatible binaries.
	static final String FULL_SCREEN_VERTEX_SHADER = """
			attribute vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	static final String FULL_SCREEN_VERTEX_SHADER_3 = """
			in vec2 position;
			void main() {
				gl_Position = vec4(position, 0., 1.);
			}
			""";
	// Scales the rendered frame to the screen, as an Upscaling says:
	// compiled once per filter and edge (see surfaceSource()), their
	// ordinals as upscaleFilter and upscaleEdge - constants, so each
	// program is only the code its filter needs. Every filter but the
	// default reads texel centres itself, so the frame texture's own
	// filtering doesn't matter to it.
	private static final String SURFACE_FRAGMENT_SHADER = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			precision highp float;
			#else
			precision mediump float;
			#endif

			uniform vec2 resolution;
			uniform vec2 frameSize;
			uniform float cropPixels;
			uniform sampler2D frame;
			// UPSCALE
			uniform float upscaleAmount;
			// Seconds, wrapping now and then - for CRT's flicker and roll.
			uniform float upscaleTime;

			// The frame's visible texels (inside any cropped border): the
			// first, and how many.
			vec2 first;
			vec2 count;

			float edgeIndex(float i, float n) {
				if (upscaleEdge == 1) {
					return mod(i, n);
				}
				if (upscaleEdge == 2) {
					float m = mod(i, 2.0 * n);
					return m < n ? m : 2.0 * n - 1.0 - m;
				}
				return clamp(i, 0.0, n - 1.0);
			}

			// Visible texel i (counted from the first), past the edges as
			// upscaleEdge says.
			vec4 texel(vec2 i) {
				if (upscaleEdge == 3 && (any(lessThan(i, vec2(0.0))) ||
						any(greaterThanEqual(i, count)))) {
					return vec4(0.0, 0.0, 0.0, 1.0);
				}
				vec2 j = vec2(edgeIndex(i.x, count.x), edgeIndex(i.y, count.y));
				return texture2D(frame, (first + j + 0.5) / frameSize);
			}

			// s: the position in texels. sharp: blend only across the
			// screen pixel or so at each texel's edge.
			vec4 bilinear(vec2 s, bool sharp) {
				vec2 p = s - 0.5;
				vec2 i = floor(p);
				vec2 f = p - i;
				if (sharp) {
					vec2 scale = resolution / count;
					f = clamp((f - 0.5) * scale + 0.5, 0.0, 1.0);
				}
				return mix(mix(texel(i), texel(i + vec2(1.0, 0.0)), f.x),
						mix(texel(i + vec2(0.0, 1.0)), texel(i + 1.0), f.x), f.y);
			}

			vec4 cubicWeights(float f, bool bspline) {
				float f2 = f * f;
				float f3 = f2 * f;
				if (bspline) {
					return vec4(1.0 - 3.0 * f + 3.0 * f2 - f3, 3.0 * f3 - 6.0 * f2 + 4.0,
							-3.0 * f3 + 3.0 * f2 + 3.0 * f + 1.0, f3) / 6.0;
				}
				return vec4(-f3 + 2.0 * f2 - f, 3.0 * f3 - 5.0 * f2 + 2.0,
						-3.0 * f3 + 4.0 * f2 + f, f3 - f2) / 2.0;
			}

			vec4 cubicRow(vec2 i, float y, vec4 w) {
				return texel(i + vec2(-1.0, y)) * w.x + texel(i + vec2(0.0, y)) * w.y +
						texel(i + vec2(1.0, y)) * w.z + texel(i + vec2(2.0, y)) * w.w;
			}

			vec4 cubic(vec2 s, bool bspline) {
				vec2 p = s - 0.5;
				vec2 i = floor(p);
				vec2 f = p - i;
				vec4 wx = cubicWeights(f.x, bspline);
				vec4 wy = cubicWeights(f.y, bspline);
				return cubicRow(i, -1.0, wx) * wy.x + cubicRow(i, 0.0, wx) * wy.y +
						cubicRow(i, 1.0, wx) * wy.z + cubicRow(i, 2.0, wx) * wy.w;
			}

			float lanczos(float x) {
				float a = float(upscaleReach);
				if (abs(x) < 1e-4) {
					return 1.0;
				}
				if (abs(x) >= a) {
					return 0.0;
				}
				float px = 3.14159265 * x;
				return a * sin(px) * sin(px / a) / (px * px);
			}

			// Visible texel i's texture coordinate along an axis of n
			// texels starting at `start`, past the edges as upscaleEdge
			// says - or -1 for black.
			float coordinate(float i, float n, float start, float size) {
				if (upscaleEdge == 3 && (i < 0.0 || i >= n)) {
					return -1.0;
				}
				return (start + edgeIndex(i, n) + 0.5) / size;
			}

			// Its taps (upscaleReach lobes each side, 2 x upscaleReach a
			// row) - weights and coordinates worked out once a row and
			// column, as the weights are separable.
			vec4 sinc(vec2 s) {
				vec2 p = s - 0.5;
				vec2 i = floor(p);
				vec2 f = p - i;
				float wx[2 * upscaleReach];
				float wy[2 * upscaleReach];
				float ux[2 * upscaleReach];
				float uy[2 * upscaleReach];
				float sx = 0.0;
				float sy = 0.0;
				for (int k = 0; k < 2 * upscaleReach; ++k) {
					float o = float(k - upscaleReach + 1);
					wx[k] = lanczos(o - f.x);
					wy[k] = lanczos(o - f.y);
					ux[k] = coordinate(i.x + o, count.x, first.x, frameSize.x);
					uy[k] = coordinate(i.y + o, count.y, first.y, frameSize.y);
					sx += wx[k];
					sy += wy[k];
				}
				vec4 sum = vec4(0.0);
				for (int y = 0; y < 2 * upscaleReach; ++y) {
					vec4 row = vec4(0.0);
					for (int x = 0; x < 2 * upscaleReach; ++x) {
						vec4 v = ux[x] < 0.0 || uy[y] < 0.0 ? vec4(0.0, 0.0, 0.0, 1.0)
								: texture2D(frame, vec2(ux[x], uy[y]));
						row += v * wx[x];
					}
					sum += row * wy[y];
				}
				return sum / (sx * sy);
			}

			// A lens's out-of-focus disc, upscaleAmount texels across
			// (radius), its rim a little brighter, in roughly linear light.
			vec4 defocus(vec2 s) {
				float r = upscaleAmount;
				vec2 c = floor(s);
				vec4 sum = vec4(0.0);
				float weights = 0.0;
				for (int y = -upscaleReach; y <= upscaleReach; ++y) {
					for (int x = -upscaleReach; x <= upscaleReach; ++x) {
						vec2 t = c + vec2(float(x), float(y));
						float d = length(t + 0.5 - s);
						float w = clamp(r + 0.5 - d, 0.0, 1.0);
						if (w <= 0.0) {
							continue;
						}
						w *= 1.0 + 0.6 * smoothstep(r * 0.5, r + 0.5, d);
						vec4 v = texel(t);
						sum += v * v * w;
						weights += w;
					}
				}
				return sqrt(sum / max(weights, 1e-4));
			}

			float hash(vec2 p) {
				return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
			}

			// A texel row's colour along it at x: nearest, softened a little
			// either side of each texel - l, m, r the texels left of, at and
			// right of floor(x).
			vec3 crtRow(vec3 l, vec3 m, vec3 r, float f) {
				vec3 col = mix(m, l, 0.5 * (1.0 - smoothstep(0.0, 0.18, f)));
				return mix(col, r, 0.5 * smoothstep(0.82, 1.0, f));
			}

			// One line of the tube at x: the frame's row(s) at texel height
			// ty, nearest along it with softened sides, red and blue a touch
			// out of convergence (by conv texels) - in roughly linear light.
			vec3 crtLine(float x, float ty, float conv, inout vec3 glow) {
				float r0 = floor(ty - 0.5);
				float blend = ty - 0.5 - r0;
				float i = floor(x);
				float f = x - i;
				vec3 col = vec3(0.0);
				for (int k = 0; k < 2; ++k) {
					vec2 row = vec2(i, r0 + float(k));
					vec3 l = texel(row - vec2(1.0, 0.0)).rgb;
					vec3 m = texel(row).rgb;
					vec3 r = texel(row + vec2(1.0, 0.0)).rgb;
					vec3 c = vec3(crtRow(l, m, r, clamp(f + conv, 0.0, 1.0)).r, crtRow(l, m, r, f).g,
							crtRow(l, m, r, clamp(f - conv, 0.0, 1.0)).b);
					float w = k == 0 ? 1.0 - blend : blend;
					col += c * c * w;
					glow += (l * l + m * m + r * r) * w;
				}
				return col;
			}

			// Nearest, as an old TV seen up close would show it: lines at
			// least 8 screen pixels apart (one a texel row, at low
			// resolutions), each a beam fatter where brighter between dark
			// scanlines; a slot mask of soft phosphor triads at least 12
			// pixels across and wider than they're tall, every other row of
			// them staggered; and its imperfections - a slight bulge, the
			// corners rounded off, red and blue a touch out of convergence,
			// a faint glow, static, flicker, a slow hum bar rolling up and
			// the odd wobbling line. upscaleAmount is how strongly the
			// scanlines and mask show.
			vec4 crt(vec2 s) {
				vec2 scale = resolution / count;
				float t = upscaleTime;
				// The bulge (subtle - a touch lands near where it's drawn),
				// and the tube's rounded corners.
				vec2 c = s / count * 2.0 - 1.0;
				c *= 1.0 + 0.012 * (c.yx * c.yx);
				vec2 corner = max(abs(c) - (1.0 - 0.03), 0.0);
				float tube = smoothstep(0.03, 0.03 - 3.0 / min(resolution.x, resolution.y),
						length(corner));
				if (tube <= 0.0) {
					return vec4(0.0, 0.0, 0.0, 1.0);
				}
				s = (c * 0.5 + 0.5) * count;
				// The tube's lines, in screen pixels and in lines.
				float lineHeight = max(scale.y, 8.0);
				float ly = s.y * scale.y / lineHeight;
				float line = floor(ly);
				float wobble = step(0.985, hash(vec2(line, floor(t * 20.0))));
				float x = s.x + wobble * (hash(vec2(line, t)) - 0.5) * 3.0 / scale.x;
				float conv = max(0.09, 1.2 / scale.x);
				vec3 beams = vec3(0.0);
				vec3 glow = vec3(0.0);
				for (int k = -1; k <= 1; ++k) {
					float j = line + float(k);
					vec3 col = crtLine(x, (j + 0.5) * lineHeight / scale.y, conv, glow);
					float width = mix(0.12, 0.26, sqrt(dot(col, vec3(0.3, 0.6, 0.1))));
					float dy = ly - j - 0.5;
					beams += col * exp(-0.5 * dy * dy / (width * width));
				}
				vec3 plain = texel(floor(s)).rgb;
				plain *= plain;
				vec3 col = mix(plain, beams * 1.9, upscaleAmount);
				// The slot mask, in screen pixels: soft stripes of red, green
				// and blue, two rows of triads a line, staggered.
				float triad = max(scale.x, 12.0);
				vec2 m = gl_FragCoord.xy / vec2(triad, triad * 0.55);
				float mx = m.x + 0.5 * mod(floor(m.y), 2.0);
				vec3 phase = fract(mx) - vec3(1.0 / 6.0, 0.5, 5.0 / 6.0);
				phase -= floor(phase + 0.5);
				vec3 mask = 0.3 + 0.7 * pow(0.5 + 0.5 * cos(phase * 6.2831853), vec3(1.5));
				float slot = fract(m.y);
				mask *= mix(0.6, 1.0, smoothstep(0.0, 0.2, slot) * smoothstep(1.0, 0.8, slot));
				col *= mix(vec3(1.0), mask * 2.1, upscaleAmount);
				col += glow / 6.0 * 0.12;
				// The tube: darker towards its edges, a faint bar of hum
				// rolling up it, a flicker, and static.
				col *= 1.0 - 0.25 * pow(dot(c, c) * 0.5, 1.5);
				col *= 1.0 - 0.05 * (0.5 + 0.5 * sin((s.y / count.y + t * 0.07) * 6.2831853));
				col *= 1.0 + 0.015 * (hash(vec2(floor(t * 50.0), 1.0)) - 0.5);
				col = sqrt(clamp(col, 0.0, 1.0));
				col += (hash(gl_FragCoord.xy + fract(t) * 91.7) - 0.5) * 0.035;
				return vec4(col * tube, 1.0);
			}

			// One channel's share of a bokeh: where o (from the texel's
			// centre, in radii) falls in a seven-bladed iris, its rim
			// brighter and its edge crisp - clipped, for a texel towards the
			// frame's edges (v, -1 to 1 across it), by the lens barrel into
			// a cat's eye.
			float aperture(vec2 o, vec2 v, float soft) {
				float a = atan(o.y, o.x) + 0.3;
				float blade = 6.2831853 / 7.0;
				float polygon = cos(blade * 0.5) / cos(mod(a, blade) - blade * 0.5);
				float d = length(o) / mix(1.0, polygon, 0.55);
				float inside = smoothstep(1.0 + soft, 1.0 - soft, d);
				float barrel = smoothstep(1.0 + soft, 1.0 - soft, length(o + v * 0.6));
				return inside * barrel * (1.0 + 1.1 * smoothstep(0.55, 1.0, d));
			}

			// A lens's out-of-focus blur, upscaleAmount texels across
			// (radius): each texel's light spread over its bokeh - an
			// iris shape with a bright rim, red's a touch bigger and blue's
			// smaller for colour fringes, and cat's eyes towards the frame's
			// edges - in roughly linear light.
			vec4 bokeh(vec2 s) {
				float r = upscaleAmount;
				float soft = 0.6 / r;
				vec2 c = floor(s);
				vec2 middle = count * 0.5;
				vec3 sum = vec3(0.0);
				vec3 weights = vec3(0.0);
				for (int y = -upscaleReach; y <= upscaleReach; ++y) {
					for (int x = -upscaleReach; x <= upscaleReach; ++x) {
						vec2 t = c + vec2(float(x), float(y));
						vec2 o = (s - t - 0.5) / r;
						if (dot(o, o) > 1.4) {
							continue;
						}
						vec2 v = (t + 0.5 - middle) / middle;
						vec3 w = vec3(aperture(o / 1.05, v, soft), aperture(o, v, soft),
								aperture(o / 0.95, v, soft));
						vec3 col = texel(t).rgb;
						sum += col * col * w;
						weights += w;
					}
				}
				return vec4(sqrt(sum / max(weights, vec3(1e-4))), 1.0);
			}

			void main(void) {
				vec2 uv = gl_FragCoord.xy / resolution.xy;
				if (upscaleFilter == 0) {
					vec2 inset = cropPixels / frameSize;
					gl_FragColor = texture2D(frame, inset + uv * (1.0 - 2.0 * inset));
					return;
				}
				first = vec2(cropPixels);
				count = frameSize - 2.0 * cropPixels;
				vec2 s = uv * count;
				vec4 c;
				if (upscaleFilter == 2) {
					c = bilinear(s, true);
				} else if (upscaleFilter == 3) {
					c = bilinear(s, false);
				} else if (upscaleFilter == 4) {
					c = cubic(s, true);
				} else if (upscaleFilter == 5) {
					c = cubic(s, false);
				} else if (upscaleFilter == 6) {
					c = sinc(s);
				} else if (upscaleFilter == 7) {
					c = defocus(s);
				} else if (upscaleFilter == 8) {
					c = crt(s);
				} else if (upscaleFilter == 9) {
					c = bokeh(s);
				} else if (upscaleFilter == 10) {
					// Gaussian: the frame's already blurred (the pipeline's
					// preBlur()); scaled up smoothly.
					c = cubic(s, true);
				} else {
					c = texel(floor(s));
				}
				gl_FragColor = c;
			}
			""";


	private final int maxTextures;

	@Nullable
	private String sourceText;
	// Indexed by buffer (0 = A), null where there's no such buffer pass.
	@Nullable
	private String[] bufferSourceTexts;
	@NonNull
	private volatile Map<String, String> texts = Map.of();
	@NonNull
	private PreparedShaderSource preparedShaderSource = PreparedShaderSource.empty();
	// In buffer order, only the buffers the shader actually has.
	@NonNull
	private List<BufferPass> bufferPasses = List.of();
	// Replaced by prepareShaderSource() and not yet released on the GL
	// thread - same idea as staleTextureResources.
	@NonNull
	private final List<BufferPass> staleBufferPasses = new ArrayList<>();
	// See getSoundPass().
	@Nullable
	private BufferPass soundPass;
	// See getModuleName().
	@Nullable
	private String moduleName;
	// See getSteps().
	private static final Pattern STEPS = Pattern.compile(
			"^[ \\t]*///steps:([0-9]{1,3})[ \\t]*$", Pattern.MULTILINE);
	private static final int MAX_STEPS = 64;
	private int steps = 1;
	// The backbuffer parameters before the last prepareShaderSource(),
	// as text - see backBufferParametersUnchanged().
	@Nullable
	private String previousBackBufferParameters;
	// See prepareShaderSource()/releaseForReload().
	@NonNull
	private final List<ShaderTextureResources> staleTextureResources = new ArrayList<>();
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int version = 2;
	@Nullable
	private GlProgram surfaceProgram;
	// See getUpscaleBindings().
	@Nullable
	private GlProgram upscaleProgram;
	@Nullable
	private ProgramBindings upscaleBindings;
	private int upscaleKey = -1;
	// See getBlurBindings().
	@Nullable
	private GlProgram blurProgram;
	@Nullable
	private ProgramBindings blurBindings;
	private int blurKey = -1;
	@Nullable
	private GlProgram mainProgram;
	@Nullable
	private ProgramBindings surfaceBindings;
	// The Image pass's `sourceText`, if it uses it - see SourceText.
	@Nullable
	private SourceText mainSourceText;

	RendererProgramManager(int maxTextures) {
		this.maxTextures = maxTextures;
	}

	void setVersion(int version) {
		this.version = version;
		prepareShaderSource();
	}

	// The shader's texts (see ShaderTextDao) - for the next reload, or
	// reloadTexts(). Any thread.
	void setTexts(@NonNull Map<String, String> texts) {
		this.texts = Map.copyOf(texts);
	}

	// GL thread: the texts' textures made again from setTexts()'s.
	void reloadTexts(@NonNull Context context, @NonNull GlDevice device) {
		textureResources.reloadTexts(context, device, texts);
	}

	void setFragmentShader(@Nullable String source, @Nullable String[] bufferSources) {
		sourceText = source;
		bufferSourceTexts = bufferSources != null ? bufferSources.clone() : null;
		prepareShaderSource();
	}

	boolean hasPreparedShader() {
		var fragmentShader = preparedShaderSource.getFragmentShader();
		return fragmentShader != null && !fragmentShader.getSource().isEmpty();
	}

	@NonNull
	ReloadResult reload(@NonNull Context context, @NonNull GlDevice device,
			@Nullable ProgramBinary cachedMainBinary,
			@NonNull BufferBinaryLookup bufferBinaries) {
		clearPrograms();

		var textureErrors = textureResources.load(context, device, texts);
		var surfaceResult = device.createProgram(
				FULL_SCREEN_VERTEX_SHADER,
				surfaceSource(Upscaling.Upscale.DEFAULT),
				ShaderLineMapping.identity());
		if (!surfaceResult.succeeded() || surfaceResult.getProgram() == null) {
			return ReloadResult.failure(textureErrors, surfaceResult.getInfoLog());
		}

		var fragmentShader = preparedShaderSource.getFragmentShader();
		if (fragmentShader == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(textureErrors, List.of());
		}

		// Only the main (user shader) program is worth caching - try a
		// cache hit first, skipping glCompileShader/glLinkProgram
		// entirely. A rejected/missing cache falls straight through to
		// the normal source compile below, exactly as if there were no
		// cache at all.
		GlProgramBuildResult mainResult = null;
		boolean fromCache = false;
		if (cachedMainBinary != null) {
			var binaryResult = device.createProgramFromBinary(
					cachedMainBinary.format(), cachedMainBinary.bytes());
			if (binaryResult.succeeded()) {
				mainResult = binaryResult;
				fromCache = true;
			}
		}
		if (mainResult == null) {
			mainResult = device.createProgram(
					preparedShaderSource.getVertexShader(
							FULL_SCREEN_VERTEX_SHADER,
							FULL_SCREEN_VERTEX_SHADER_3,
							version),
					fragmentShader.getSource(),
					fragmentShader.getLineMapping());
		}
		if (!mainResult.succeeded() || mainResult.getProgram() == null) {
			device.deleteProgram(surfaceResult.getProgram());
			return ReloadResult.failure(textureErrors, mainResult.getInfoLog());
		}

		var freshBufferBinaries = new ArrayList<FreshBufferBinary>();
		var bufferErrors = new ArrayList<ShaderError>();
		for (var pass : allBufferPasses()) {
			GlProgram program = buildBufferProgram(device, pass, bufferBinaries,
					freshBufferBinaries, bufferErrors);
			pass.setProgram(program);
		}
		if (!bufferErrors.isEmpty()) {
			// A buffer that doesn't compile fails the whole shader, like
			// any other compile error - there'd be no sensible picture.
			device.deleteProgram(surfaceResult.getProgram());
			device.deleteProgram(mainResult.getProgram());
			for (var pass : allBufferPasses()) {
				device.deleteProgram(pass.getProgram());
				pass.setProgram(null);
			}
			return ReloadResult.failure(textureErrors, bufferErrors);
		}

		surfaceProgram = surfaceResult.getProgram();
		mainProgram = mainResult.getProgram();
		surfaceBindings = new ProgramBindings(surfaceProgram);
		ProgramBinary freshBinary = fromCache ? null : device.getProgramBinary(mainProgram);
		return ReloadResult.success(textureErrors, freshBinary, freshBufferBinaries);
	}

	@Nullable
	private GlProgram buildBufferProgram(@NonNull GlDevice device, @NonNull BufferPass pass,
			@NonNull BufferBinaryLookup bufferBinaries,
			@NonNull List<FreshBufferBinary> freshBufferBinaries,
			@NonNull List<ShaderError> errors) {
		var fragmentShader = pass.prepared.getFragmentShader();
		if (fragmentShader == null) {
			return null;
		}
		ProgramBinary cached = bufferBinaries.get(pass.buffer, pass.sourceText);
		if (cached != null) {
			var binaryResult = device.createProgramFromBinary(cached.format(), cached.bytes());
			if (binaryResult.succeeded() && binaryResult.getProgram() != null) {
				return binaryResult.getProgram();
			}
		}
		var result = device.createProgram(
				pass.prepared.getVertexShader(
						FULL_SCREEN_VERTEX_SHADER,
						FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragmentShader.getSource(),
				fragmentShader.getLineMapping());
		if (!result.succeeded() || result.getProgram() == null) {
			for (var error : result.getInfoLog()) {
				errors.add(error.withPass(pass.buffer));
			}
			if (result.getInfoLog().isEmpty()) {
				errors.add(ShaderError.createGeneral("Link failed").withPass(pass.buffer));
			}
			return null;
		}
		ProgramBinary fresh = device.getProgramBinary(result.getProgram());
		if (fresh != null) {
			freshBufferBinaries.add(new FreshBufferBinary(pass.buffer, pass.sourceText, fresh));
		}
		return result.getProgram();
	}

	@NonNull
	List<BufferPass> getBufferPasses() {
		return bufferPasses;
	}

	// Every compiled program the current shader draws with - for
	// UniformPresence.
	@NonNull
	List<GlProgram> getPassPrograms() {
		var programs = new ArrayList<GlProgram>(bufferPasses.size() + 1);
		if (mainProgram != null) {
			programs.add(mainProgram);
		}
		for (var pass : allBufferPasses()) {
			if (pass.getProgram() != null) {
				programs.add(pass.getProgram());
			}
		}
		return programs;
	}

	// The Sound pass, if the shader has one - a buffer that makes sound
	// rather than a picture (see SoundOutput). Not in getBufferPasses().
	@Nullable
	BufferPass getSoundPass() {
		return soundPass;
	}

	// The tracker module the shader plays (a `///mod:` line), or null.
	@Nullable
	String getModuleName() {
		return moduleName;
	}

	// How many times the buffer passes run for each frame drawn - a
	// `///steps:N` line in any pass (1-64), else 1. A simulation in its
	// buffers then moves N steps a frame, for one Image pass.
	int getSteps() {
		return steps;
	}


	private static int findSteps(@NonNull String[] sources) {
		for (String source : sources) {
			if (source == null) {
				continue;
			}
			Matcher matcher = STEPS.matcher(source);
			if (matcher.find()) {
				return Math.max(1, Math.min(MAX_STEPS, Integer.parseInt(matcher.group(1))));
			}
		}
		return 1;
	}

	// The drawn buffer passes and the Sound pass - everything with a
	// program to build and release.
	@NonNull
	private List<BufferPass> allBufferPasses() {
		if (soundPass == null) {
			return bufferPasses;
		}
		var all = new ArrayList<>(bufferPasses);
		all.add(soundPass);
		return all;
	}

	// Clean source text of a buffer pass, for the binary cache key.
	@Nullable
	String getBufferSourceText(int buffer) {
		return bufferSourceTexts != null && buffer < bufferSourceTexts.length
				? bufferSourceTexts[buffer]
				: null;
	}

	// The exact source text (already cleaned up, see ShaderView) that
	// prepareShaderSource() was last called with - used to compute the
	// program-binary cache key. null if none has been set yet.
	@Nullable
	String getSourceText() {
		return sourceText;
	}

	void discardContextResources() {
		clearPrograms();
		textureResources.discard();
		for (var pass : allBufferPasses()) {
			pass.discard();
		}
		// The context these belonged to is already gone - nothing left
		// to delete, just references to drop.
		staleTextureResources.clear();
		staleBufferPasses.clear();
	}

	// Deletes (not just drops) the current programs and any texture sets
	// replaced by setFragmentShader() since the last reload, on a context
	// that's still alive - the preserved-context counterpart of
	// discardContextResources(), called right before reload() whenever
	// ShaderRenderer recompiles without onSurfaceCreated() (see its
	// pendingReload). Skipping this would leak one program pair plus every
	// texture the previous shader sampled, on every single shader switch.
	void releaseForReload(@NonNull GlDevice device) {
		device.deleteProgram(surfaceProgram);
		device.deleteProgram(upscaleProgram);
		device.deleteProgram(blurProgram);
		device.deleteProgram(mainProgram);
		if (mainSourceText != null) {
			mainSourceText.release(device);
		}
		clearPrograms();
		for (var stale : staleTextureResources) {
			stale.release(device);
		}
		staleTextureResources.clear();
		// Only programs: these passes were made since the last reload,
		// so any render targets they have are ones they've just adopted
		// to keep (see adoptBufferTargets()).
		for (var pass : allBufferPasses()) {
			pass.releaseProgram(device);
		}
		for (var stale : staleBufferPasses) {
			stale.release(device);
		}
		staleBufferPasses.clear();
	}

	// After a reload - GL thread only.
	void createMainSourceText(@NonNull Context context, @NonNull GlDevice device, int tabWidth) {
		if (mainSourceText != null) {
			mainSourceText.release(device);
			mainSourceText = null;
		}
		if (mainProgram != null) {
			mainSourceText = SourceText.createFor(context, device, mainProgram, sourceText,
					tabWidth);
		}
	}

	@Nullable
	SourceText getMainSourceText() {
		return mainSourceText;
	}

	@Nullable
	GlProgram getSurfaceProgram() {
		return surfaceProgram;
	}

	@NonNull
	private static String surfaceSource(@NonNull Upscaling.Upscale upscale) {
		return SURFACE_FRAGMENT_SHADER.replace("// UPSCALE",
				"#define upscaleFilter " + upscale.filter().ordinal() +
						"\n#define upscaleEdge " + upscale.edge().ordinal() +
						"\n#define upscaleReach " + upscale.reach());
	}

	// The surface program for `upscale`, compiled the first time it's
	// asked for (and kept while it's the one asked for), or null for the
	// default - getSurfaceProgram()'s - or if it won't compile. GL thread
	// only.
	@Nullable
	ProgramBindings getUpscaleBindings(@NonNull GlDevice device,
			@NonNull Upscaling.Upscale upscale) {
		if (upscale.filter() == Upscaling.Filter.DEFAULT || surfaceProgram == null) {
			return null;
		}
		if (upscale.filter().isPrimitives()) {
			// PrimitivePresenter's; where it can't, the frame as linear.
			upscale = new Upscaling.Upscale(Upscaling.Filter.LINEAR, upscale.edge(), 0f);
		}
		int key = (upscale.filter().ordinal() * Upscaling.Edge.values().length +
				upscale.edge().ordinal()) * 16 + upscale.reach();
		if (key == upscaleKey) {
			return upscaleBindings;
		}
		device.deleteProgram(upscaleProgram);
		upscaleProgram = null;
		upscaleBindings = null;
		upscaleKey = key;
		var result = device.createProgram(FULL_SCREEN_VERTEX_SHADER, surfaceSource(upscale),
				ShaderLineMapping.identity());
		if (!result.succeeded() || result.getProgram() == null) {
			Log.w(TAG, "Upscale " + upscale + " won't compile: " + result.getInfoLog());
			return null;
		}
		upscaleProgram = result.getProgram();
		upscaleBindings = new ProgramBindings(upscaleProgram);
		return upscaleBindings;
	}

	@Nullable
	GlProgram getUpscaleProgram() {
		return upscaleProgram;
	}

	// Blurs the frame by a Gaussian, one way (direction: (1, 0) across,
	// (0, 1) up) a pass, at the frame's own size - its edges as
	// upscaleEdge says - for Upscaling's Gaussian. sigma in texels.
	private static final String BLUR_FRAGMENT_SHADER = """
			#ifdef GL_FRAGMENT_PRECISION_HIGH
			precision highp float;
			#else
			precision mediump float;
			#endif

			uniform vec2 frameSize;
			uniform float cropPixels;
			uniform sampler2D frame;
			uniform vec2 direction;
			uniform float sigma;
			// UPSCALE

			vec2 first;
			vec2 count;

			float edgeIndex(float i, float n) {
				if (upscaleEdge == 1) {
					return mod(i, n);
				}
				if (upscaleEdge == 2) {
					float m = mod(i, 2.0 * n);
					return m < n ? m : 2.0 * n - 1.0 - m;
				}
				return clamp(i, 0.0, n - 1.0);
			}

			vec4 texel(vec2 i) {
				if (upscaleEdge == 3 && (any(lessThan(i, vec2(0.0))) ||
						any(greaterThanEqual(i, count)))) {
					return vec4(0.0, 0.0, 0.0, 1.0);
				}
				vec2 j = vec2(edgeIndex(i.x, count.x), edgeIndex(i.y, count.y));
				return texture2D(frame, (first + j + 0.5) / frameSize);
			}

			void main(void) {
				first = vec2(cropPixels);
				count = frameSize - 2.0 * cropPixels;
				vec2 i = floor(gl_FragCoord.xy) - first;
				float reach = ceil(3.0 * sigma);
				vec4 sum = vec4(0.0);
				float weights = 0.0;
				for (int k = -32; k <= 32; ++k) {
					float o = float(k);
					if (abs(o) > reach) {
						continue;
					}
					float w = exp(-0.5 * o * o / (sigma * sigma));
					sum += texel(i + direction * o) * w;
					weights += w;
				}
				gl_FragColor = sum / weights;
			}
			""";

	// The blur program for `upscale`'s edge, compiled the first time it's
	// asked for (and kept while it's the one asked for), or null if
	// `upscale` isn't Gaussian or it won't compile. GL thread only.
	@Nullable
	ProgramBindings getBlurBindings(@NonNull GlDevice device, @NonNull Upscaling.Upscale upscale) {
		if (upscale.filter() != Upscaling.Filter.GAUSSIAN || surfaceProgram == null) {
			return null;
		}
		int key = upscale.edge().ordinal();
		if (key == blurKey) {
			return blurBindings;
		}
		device.deleteProgram(blurProgram);
		blurProgram = null;
		blurBindings = null;
		blurKey = key;
		var result = device.createProgram(FULL_SCREEN_VERTEX_SHADER,
				BLUR_FRAGMENT_SHADER.replace("// UPSCALE", "#define upscaleEdge " + key),
				ShaderLineMapping.identity());
		if (!result.succeeded() || result.getProgram() == null) {
			Log.w(TAG, "Blur won't compile: " + result.getInfoLog());
			return null;
		}
		blurProgram = result.getProgram();
		blurBindings = new ProgramBindings(blurProgram);
		return blurBindings;
	}

	@Nullable
	GlProgram getBlurProgram() {
		return blurProgram;
	}

	@Nullable
	GlProgram getMainProgram() {
		return mainProgram;
	}

	@Nullable
	ProgramBindings getSurfaceBindings() {
		return surfaceBindings;
	}

	@NonNull
	BackBufferParameters getBackBufferParameters() {
		return preparedShaderSource.getBackBufferParameters();
	}

	// For the high-res tile capture, which needs to compile its own
	// variant of the main program (same prepared source, with a tile-
	// offset macro prepended) alongside the normal one reload() above
	// builds - see HighResScreenshotCapture.
	@NonNull
	PreparedShaderSource getPreparedShaderSource() {
		return preparedShaderSource;
	}

	float getFTimeMax() {
		return preparedShaderSource.getFTimeMax();
	}

	@NonNull
	ShaderTextureResources getTextureResources() {
		return textureResources;
	}

	// Compiles `source` and `bufferSources` (as setFragmentShader()
	// takes them) and deletes the programs straight away, changing
	// nothing - so Adjust Defines' live mode can find out whether its
	// converted source compiles before handing it over for real. GL
	// thread only. Empty if every pass compiled.
	@NonNull
	List<ShaderError> testCompile(@NonNull GlDevice device, @NonNull String source,
			@Nullable String[] bufferSources) {
		var errors = new ArrayList<ShaderError>();
		testCompilePass(device, ShaderSourcePreparer.prepare(source, version, maxTextures),
				ShaderError.IMAGE_PASS, errors);
		if (bufferSources != null) {
			for (int buffer = 0; buffer < bufferSources.length; ++buffer) {
				if (bufferSources[buffer] != null) {
					testCompilePass(device, ShaderSourcePreparer.prepare(
							bufferSources[buffer], version, maxTextures), buffer, errors);
				}
			}
		}
		return errors;
	}

	private void testCompilePass(@NonNull GlDevice device, @NonNull PreparedShaderSource prepared,
			int buffer, @NonNull List<ShaderError> errors) {
		var fragmentShader = prepared.getFragmentShader();
		if (fragmentShader == null) {
			return;
		}
		var result = device.createProgram(
				prepared.getVertexShader(
						FULL_SCREEN_VERTEX_SHADER,
						FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragmentShader.getSource(),
				fragmentShader.getLineMapping());
		if (!result.succeeded() || result.getProgram() == null) {
			for (var error : result.getInfoLog()) {
				errors.add(buffer == ShaderError.IMAGE_PASS ? error : error.withPass(buffer));
			}
			if (result.getInfoLog().isEmpty()) {
				var linkFailed = ShaderError.createGeneral("Link failed");
				errors.add(buffer == ShaderError.IMAGE_PASS ? linkFailed : linkFailed.withPass(buffer));
			}
		}
		device.deleteProgram(result.getProgram());
	}

	// For a reload that keeps the shader's state (see ShaderRenderer.
	// setFragmentShader()'s keepState): hands each new buffer pass the
	// render targets - and so the contents - of the replaced pass for
	// the same buffer, before releaseForReload() would delete them. GL
	// thread only.
	void adoptBufferTargets() {
		for (var pass : bufferPasses) {
			for (var stale : staleBufferPasses) {
				if (stale.buffer == pass.buffer && stale.hasTargets()) {
					pass.adoptTargets(stale);
					break;
				}
			}
		}
	}

	// Whether the last setFragmentShader() left the backbuffer's
	// parameters (size, filtering, preset) as they were, so its targets
	// can be kept - see adoptBufferTargets().
	boolean backBufferParametersUnchanged() {
		return previousBackBufferParameters != null && previousBackBufferParameters.equals(
				preparedShaderSource.getBackBufferParameters().toString());
	}

	private void prepareShaderSource() {
		// Kept so releaseForReload() can delete its GL textures later, on
		// the GL thread - this runs on the UI thread, with the GL thread
		// paused, so it can't touch GL itself.
		staleTextureResources.add(textureResources);
		staleBufferPasses.addAll(allBufferPasses());
		previousBackBufferParameters = preparedShaderSource.getFragmentShader() != null
				? preparedShaderSource.getBackBufferParameters().toString()
				: null;
		preparedShaderSource = ShaderSourcePreparer.prepare(
				sourceText,
				version,
				maxTextures);
		// One set of textures (saved images, camera, audio) for the whole
		// shader, shared by name between its passes.
		var samplers = new LinkedHashMap<String, DiscoveredSampler>();
		for (var sampler : preparedShaderSource.getSamplers()) {
			samplers.putIfAbsent(sampler.name(), sampler);
		}
		var passes = new ArrayList<BufferPass>();
		BufferPass sound = null;
		if (bufferSourceTexts != null) {
			for (int buffer = 0; buffer < bufferSourceTexts.length; ++buffer) {
				String bufferSource = bufferSourceTexts[buffer];
				if (bufferSource == null) {
					continue;
				}
				var prepared = ShaderSourcePreparer.prepare(bufferSource, version, maxTextures);
				var pass = new BufferPass(buffer, bufferSource, prepared);
				if (ShaderSourcePreparer.isSoundSource(bufferSource) && sound == null) {
					sound = pass;
				} else {
					passes.add(pass);
				}
				for (var sampler : prepared.getSamplers()) {
					samplers.putIfAbsent(sampler.name(), sampler);
				}
			}
		}
		for (var pass : passes) {
			int target = TextDirective.findTarget(pass.sourceText);
			if (target == ShaderError.IMAGE_PASS) {
				pass.setTextSource(sourceText);
			} else if (target != TextDirective.NONE && bufferSourceTexts != null &&
					target < bufferSourceTexts.length) {
				pass.setTextSource(bufferSourceTexts[target]);
			}
		}
		bufferPasses = List.copyOf(passes);
		soundPass = sound;
		String[] all = new String[1 + (bufferSourceTexts != null ? bufferSourceTexts.length : 0)];
		all[0] = sourceText;
		if (bufferSourceTexts != null) {
			System.arraycopy(bufferSourceTexts, 0, all, 1, bufferSourceTexts.length);
		}
		moduleName = ModuleDirective.find(all);
		steps = findSteps(all);
		textureResources = ShaderTextureResources.create(new ArrayList<>(samplers.values()));
	}

	private void clearPrograms() {
		surfaceProgram = null;
		upscaleProgram = null;
		upscaleBindings = null;
		upscaleKey = -1;
		blurProgram = null;
		blurBindings = null;
		blurKey = -1;
		mainProgram = null;
		surfaceBindings = null;
		mainSourceText = null;
	}
}
