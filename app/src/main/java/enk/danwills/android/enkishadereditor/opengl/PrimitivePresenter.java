package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Upscaling's Accumulate Points and 2D Gaussian Splats: instead of scaling
// the frame up, draw primitives that the shader's last offscreen buffer
// lists - one texel a point, two a splat - added up on screen, over the
// frame (the Image pass) as a background. A fragment pass can only write its
// own pixel; this is the scatter it can't do.
//
// Points: a texel is (x, y, intensity, colour) - x and y across the frame,
// 0 to 1; colour 0xRRGGBB as a number (0 or less: white), times intensity
// (0 or less: not drawn). Splats: texels 2i and 2i+1 (in reading order) are
// (x, y, ux, uy) - the centre, and the major semi-axis (one sigma) as a
// vector in frame heights - and (r, g, b, minor) - the colour at the
// centre, and the minor semi-axis' length. Both add up, so the order
// doesn't matter.
//
// Texel (0, 0) can instead be a control texel, marked by a negative z, and
// then isn't drawn (in splat mode, neither is splat 0): x 1 keeps adding
// frame after frame (0: each frame starts afresh, as without one), y
// scales the exposure (0: 1), w tone-maps (1: 1 - e^-v; 2: log density, the
// average colour brightened by how much landed, as flames are shown; 0:
// clamps), and z
// is -1 less the points' radius - a Gaussian's sigma, in frame pixels
// (-1: single pixels). For a size per point, use splats. Kept
// frames are averaged, so the picture keeps its brightness as it builds.
// The Upscaling's amount is a further exposure.
//
// Each drawn frame adds the buffer's latest step once - a paused or redrawn
// frame adds nothing. Starts over on a new size, buffer or mode.
final class PrimitivePresenter {
	private static final String TAG = "PrimitivePresenter";

	private static final String POINT_VERTEX = """
			#version 300 es
			precision highp float;
			uniform highp sampler2D points;
			uniform vec2 drawSize;
			uniform float pointSigma;
			uniform float firstPoint;
			// The splats xform: (offset, in frame heights; zoom; turn) about
			// the frame's centre - see setXform().
			uniform vec4 xform;

			vec2 xformed(vec2 uv, float aspect) {
				vec2 q = (uv - 0.5) * vec2(aspect, 1.0);
				float c = cos(xform.w), s = sin(xform.w);
				q = mat2(c, s, -s, c) * q * xform.z + xform.xy;
				return q / vec2(aspect, 1.0) + 0.5;
			}

			out vec4 colour; // rgb, and its weight (intensity)
			flat out float sigma;

			vec3 unpackColour(float packed) {
				if (packed <= 0.0) {
					return vec3(1.0);
				}
				// Rounded only where halves exist: above 2^23, x.5 rounds an odd
				// value up to the next even one, losing blue.
				int v = int(packed < 8388608.0 ? packed + 0.5 : packed);
				return vec3(float(v & 255), float((v >> 8) & 255), float((v >> 16) & 255)) / 255.0;
			}

			void main() {
				ivec2 size = textureSize(points, 0);
				int i = gl_VertexID + int(firstPoint);
				vec4 p = texelFetch(points, ivec2(i % size.x, i / size.x), 0);
				// Grown with the xform's zoom; a single pixel when under half one.
				sigma = (pointSigma > 0.0 ? pointSigma : 0.4) * xform.z;
				if (sigma < 0.5) {
					sigma = 0.0;
				}
				// Out to 3 sigma, or a single pixel.
				gl_PointSize = sigma > 0.0 ? 2.0 * ceil(3.0 * sigma) + 1.0 : 1.0;
				if (!(p.z > 0.0) || any(isnan(p.xy))) {
					gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
					colour = vec4(0.0);
					return;
				}
				// Packed as 0xRRGGBB - blue in the low byte.
				colour = vec4(unpackColour(p.w).bgr, 1.0) * p.z;
				gl_Position = vec4(xformed(p.xy, drawSize.x / drawSize.y) * 2.0 - 1.0, 0.0, 1.0);
			}
			""";
	private static final String POINT_FRAGMENT = """
			#version 300 es
			precision highp float;
			in vec4 colour;
			flat in float sigma;
			out vec4 fragColor;

			void main() {
				if (sigma <= 0.0) {
					fragColor = colour;
					return;
				}
				// A Gaussian dot whose pixels add up to the point's colour.
				float size = 2.0 * ceil(3.0 * sigma) + 1.0;
				vec2 d = (gl_PointCoord - 0.5) * size;
				float s2 = sigma * sigma;
				fragColor = colour * exp(-0.5 * dot(d, d) / s2) / (6.2831853 * s2);
			}
			""";
	private static final String SPLAT_VERTEX = """
			#version 300 es
			precision highp float;
			uniform highp sampler2D points;
			uniform vec2 drawSize;
			uniform float skipFirst;
			// The splats xform: (offset, in frame heights; zoom; turn) about
			// the frame's centre - see setXform().
			uniform vec4 xform;

			vec2 xformed(vec2 uv, float aspect) {
				vec2 q = (uv - 0.5) * vec2(aspect, 1.0);
				float c = cos(xform.w), s = sin(xform.w);
				q = mat2(c, s, -s, c) * q * xform.z + xform.xy;
				return q / vec2(aspect, 1.0) + 0.5;
			}

			out vec3 colour;
			out vec2 local;

			void main() {
				ivec2 size = textureSize(points, 0);
				int splat = gl_VertexID / 6;
				int corner = gl_VertexID % 6;
				int i = 2 * splat;
				vec4 a = texelFetch(points, ivec2(i % size.x, i / size.x), 0);
				vec4 b = texelFetch(points, ivec2((i + 1) % size.x, (i + 1) / size.x), 0);
				// Two triangles, corners at +-3 sigma.
				vec2 c = vec2(corner == 1 || corner == 2 || corner == 4 ? 3.0 : -3.0,
						corner == 2 || corner == 4 || corner == 5 ? 3.0 : -3.0);
				float major = length(a.zw);
				if ((splat == 0 && skipFirst > 0.5) || !(major > 0.0) || !(b.w > 0.0) ||
						all(lessThanEqual(b.rgb, vec3(0.0)))) {
					gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
					colour = vec3(0.0);
					local = vec2(0.0);
					return;
				}
				// Turned and scaled with the xform; at least half a pixel each
				// way, so small ones don't vanish between pixels.
				float cs = cos(xform.w), sn = sin(xform.w);
				vec2 axis = mat2(cs, sn, -sn, cs) * a.zw / major;
				float minPx = 0.5 / drawSize.y;
				vec2 u = axis * max(major * xform.z, minPx);
				vec2 v = vec2(-axis.y, axis.x) * max(b.w * xform.z, minPx);
				vec2 offset = c.x * u + c.y * v; // frame heights
				vec2 centre = xformed(a.xy, drawSize.x / drawSize.y);
				gl_Position = vec4(centre * 2.0 - 1.0 + offset * 2.0 * vec2(drawSize.y / drawSize.x, 1.0),
						0.0, 1.0);
				colour = b.rgb;
				local = c;
			}
			""";
	private static final String SPLAT_FRAGMENT = """
			#version 300 es
			precision highp float;
			in vec3 colour;
			in vec2 local;
			out vec4 fragColor;

			void main() {
				float r2 = dot(local, local);
				if (r2 > 9.0) {
					discard;
				}
				// Alpha adds up how much landed here, for the log tone map.
				float g = exp(-0.5 * r2);
				fragColor = vec4(colour, 1.0) * g;
			}
			""";
	private static final String QUAD_VERTEX = """
			#version 300 es
			in vec2 position;
			void main() {
				gl_Position = vec4(position, 0.0, 1.0);
			}
			""";
	// running sum + this frame's primitives
	private static final String ADD_FRAGMENT = """
			#version 300 es
			precision highp float;
			uniform highp sampler2D sum;
			uniform highp sampler2D added;
			uniform float keep;
			out vec4 fragColor;

			void main() {
				ivec2 p = ivec2(gl_FragCoord.xy);
				vec4 a = texelFetch(added, p, 0);
				fragColor = keep > 0.5 ? texelFetch(sum, p, 0) + a : a;
			}
			""";
	private static final String PRESENT_FRAGMENT = """
			#version 300 es
			precision highp float;
			uniform highp sampler2D sum;
			uniform sampler2D frame;
			uniform vec2 drawSize;
			uniform float scale;
			uniform float toneMap;
			out vec4 fragColor;

			void main() {
				vec2 uv = gl_FragCoord.xy / drawSize;
				vec4 total = texture(sum, uv);
				vec3 added;
				if (toneMap > 1.5) {
					// Log density, as flame renderers do: the average colour
					// here, brightened by how much landed - compressed, so
					// sparse wisps and dense cores both show.
					vec3 average = total.rgb / max(total.a, 1e-6);
					added = average * (1.0 - pow(1.0 + max(total.a * scale, 0.0), -0.4));
				} else {
					vec3 v = total.rgb * scale;
					added = toneMap > 0.5 ? 1.0 - exp(-max(v, 0.0)) : v;
				}
				fragColor = vec4(clamp(texture(frame, uv).rgb + added, 0.0, 1.0), 1.0);
			}
			""";

	private GlProgram pointProgram;
	private GlProgram splatProgram;
	private GlProgram addProgram;
	private GlProgram presentProgram;
	private ProgramBindings pointBindings;
	private ProgramBindings splatBindings;
	private ProgramBindings addBindings;
	private ProgramBindings presentBindings;
	private boolean broken;

	// This frame's primitives (half floats, blended), and the running sum
	// (ping-ponged, full floats where the GPU can).
	private GlTexture2D addedTexture;
	private GlFramebuffer addedFramebuffer;
	private final GlTexture2D[] sumTextures = new GlTexture2D[2];
	private final GlFramebuffer[] sumFramebuffers = new GlFramebuffer[2];
	private int sumCurrent;
	// Full floats that can be blended into (EXT_float_blend): primitives go
	// straight into the sum, skipping a full-screen read and write of it a
	// frame - most of the cost at screen size.
	private boolean direct;
	private int width;
	private int height;

	// What was last added - to add each frame once, and start over on a
	// change.
	private int lastSourceWidth;
	private int lastSourceHeight;
	private long lastStamp = -1;
	private Upscaling.Filter lastFilter;
	private int keptFrames;
	private final float[] control = new float[4];
	// See setXform().
	private final float[] xform = {0f, 0f, 1f, 0f};
	private boolean xformChanged;
	private final ByteBuffer pixel = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());
	private boolean hasControl;

	// Draws `source`'s primitives over `frame` into the bound (on-screen)
	// framebuffer at drawWidth x drawHeight - adding them first if `stamp`
	// (the source's completed-frame count) is new. False if it can't (no
	// GLES3 support, say), to fall back to the plain surface.
	// sourceFramebuffer: the one holding `source`, to read the control
	// texel back through.
	boolean draw(@NonNull GlDevice device, @NonNull Mesh quad, @NonNull GlTexture2D frame,
			@NonNull GlTexture2D source, @NonNull GlFramebuffer sourceFramebuffer, long stamp,
			@NonNull Upscaling.Upscale upscale, int drawWidth, int drawHeight) {
		// Added up at the frame's size (the quality), then scaled up
		// smoothly: far fewer pixels to fill than the screen's, and each
		// point covers a frame pixel.
		int frameWidth = frame.getWidth();
		int frameHeight = frame.getHeight();
		if (!ensurePrograms(device) || !ensureTargets(device, frameWidth, frameHeight)) {
			return false;
		}
		boolean splats = upscale.filter() == Upscaling.Filter.SPLATS;
		// A buffer ping-pongs between two textures, so it's told apart by
		// its size and step count: a new shader's starts again from 0.
		if (source.getWidth() != lastSourceWidth || source.getHeight() != lastSourceHeight ||
				stamp < lastStamp || upscale.filter() != lastFilter) {
			lastSourceWidth = source.getWidth();
			lastSourceHeight = source.getHeight();
			lastFilter = upscale.filter();
			lastStamp = -1;
			keptFrames = 0;
		}
		device.disableScissor();
		// A new xform redraws the latest step at once, even paused.
		if (stamp != lastStamp || xformChanged) {
			lastStamp = stamp;
			readControl(device, sourceFramebuffer);
			boolean keep = hasControl && control[0] > 0.5f && keptFrames > 0 && !xformChanged;
			xformChanged = false;
			keptFrames = keep ? keptFrames + 1 : 1;
			addPrimitives(device, quad, source, splats, keep, frameWidth, frameHeight);
		}

		float exposure = hasControl && control[1] > 0f ? control[1] : 1f;
		device.bindFramebuffer(null);
		device.setViewport(0, 0, drawWidth, drawHeight);
		presentBindings.clear();
		presentBindings.setTexture("sum", sumTextures[sumCurrent]);
		presentBindings.setTexture("frame", frame);
		presentBindings.setFloat2("drawSize", new float[]{drawWidth, drawHeight});
		presentBindings.setFloat("scale", exposure * upscale.amount() / keptFrames);
		presentBindings.setFloat("toneMap", hasControl ? control[3] : 0f);
		device.applyBindings(presentBindings);
		device.draw(quad, presentProgram);
		return true;
	}

	// The splats xform: primitives drawn offset (x, y, in frame heights),
	// zoomed and turned about the frame's centre, without touching the
	// shader - a change starts the sum again. Points grow with the zoom.
	void setXform(float x, float y, float zoom, float angle) {
		if (x != xform[0] || y != xform[1] || zoom != xform[2] || angle != xform[3]) {
			xform[0] = x;
			xform[1] = y;
			xform[2] = zoom;
			xform[3] = angle;
			xformChanged = true;
		}
	}

	// Into addedTexture, then summed into the other sum texture.
	private void addPrimitives(@NonNull GlDevice device, @NonNull Mesh quad,
			@NonNull GlTexture2D source, boolean splats, boolean keep, int drawWidth,
			int drawHeight) {
		device.bindFramebuffer(direct ? sumFramebuffers[sumCurrent] : addedFramebuffer);
		device.setViewport(0, 0, drawWidth, drawHeight);
		if (!direct || !keep) {
			device.setClearColor(0f, 0f, 0f, 0f);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		}
		ProgramBindings bindings = splats ? splatBindings : pointBindings;
		bindings.clear();
		bindings.setTexture("points", source);
		bindings.setFloat2("drawSize", new float[]{drawWidth, drawHeight});
		bindings.setFloat4("xform", xform);
		if (splats) {
			bindings.setFloat("skipFirst", hasControl ? 1f : 0f);
		} else {
			bindings.setFloat("pointSigma", hasControl ? Math.max(0f, -control[2] - 1f) : 0f);
			bindings.setFloat("firstPoint", hasControl ? 1f : 0f);
		}
		device.applyBindings(bindings);
		// Attribute-less: positions come from the texture. Arrays left
		// enabled by quad draws would otherwise be read past their end.
		for (int i = 0; i < 4; ++i) {
			GLES20.glDisableVertexAttribArray(i);
		}
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
		int texels = source.getWidth() * source.getHeight();
		if (splats) {
			GLES30.glDrawArrays(GLES20.GL_TRIANGLES, 0, texels / 2 * 6);
		} else {
			// The control texel is skipped by firstPoint.
			GLES30.glDrawArrays(GLES20.GL_POINTS, 0, hasControl ? texels - 1 : texels);
		}
		GLES20.glDisable(GLES20.GL_BLEND);
		if (direct) {
			return;
		}

		int next = 1 - sumCurrent;
		device.bindFramebuffer(sumFramebuffers[next]);
		addBindings.clear();
		addBindings.setTexture("sum", sumTextures[sumCurrent]);
		addBindings.setTexture("added", addedTexture);
		addBindings.setFloat("keep", keep ? 1f : 0f);
		device.applyBindings(addBindings);
		device.draw(quad, addProgram);
		sumCurrent = next;
	}

	// Texel (0, 0), if it's a control texel (see the class's doc).
	// An 8-bit buffer can't hold one (its read fails): no control.
	private void readControl(@NonNull GlDevice device, @NonNull GlFramebuffer framebuffer) {
		hasControl = false;
		device.bindFramebuffer(framebuffer);
		while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
			// Earlier errors aren't this read's.
		}
		pixel.clear();
		GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, pixel);
		if (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
			return;
		}
		pixel.asFloatBuffer().get(control);
		hasControl = control[2] < 0f;
	}

	private boolean ensurePrograms(@NonNull GlDevice device) {
		if (broken) {
			return false;
		}
		if (presentProgram != null) {
			return true;
		}
		pointProgram = build(device, POINT_VERTEX, POINT_FRAGMENT);
		splatProgram = build(device, SPLAT_VERTEX, SPLAT_FRAGMENT);
		addProgram = build(device, QUAD_VERTEX, ADD_FRAGMENT);
		presentProgram = build(device, QUAD_VERTEX, PRESENT_FRAGMENT);
		if (pointProgram == null || splatProgram == null || addProgram == null ||
				presentProgram == null) {
			release(device);
			broken = true;
			return false;
		}
		pointBindings = new ProgramBindings(pointProgram);
		splatBindings = new ProgramBindings(splatProgram);
		addBindings = new ProgramBindings(addProgram);
		presentBindings = new ProgramBindings(presentProgram);
		return true;
	}

	@Nullable
	private static GlProgram build(@NonNull GlDevice device, @NonNull String vertex,
			@NonNull String fragment) {
		var result = device.createProgram(vertex, fragment, ShaderLineMapping.identity());
		if (!result.succeeded() || result.getProgram() == null) {
			Log.w(TAG, "won't compile: " + result.getInfoLog());
			return null;
		}
		return result.getProgram();
	}

	private boolean ensureTargets(@NonNull GlDevice device, int drawWidth, int drawHeight) {
		if (sumTextures[0] != null && width == drawWidth && height == drawHeight) {
			return true;
		}
		releaseTargets(device);
		if (!device.supportsHalfFloatTargets()) {
			broken = true;
			return false;
		}
		boolean full = device.supportsFloatTargets();
		String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
		direct = full && extensions != null && extensions.contains("GL_EXT_float_blend");
		Log.d(TAG, "targets " + drawWidth + "x" + drawHeight + (direct ? ", blending into full floats"
				: full ? ", summing full floats" : ", half floats"));
		sumCurrent = 0;
		if (!direct) {
			addedTexture = floatTexture(device, drawWidth, drawHeight, false);
			addedFramebuffer = framebufferFor(device, addedTexture);
		}
		for (int i = 0; i < (direct ? 1 : 2); ++i) {
			sumTextures[i] = floatTexture(device, drawWidth, drawHeight, full);
			sumFramebuffers[i] = framebufferFor(device, sumTextures[i]);
		}
		if ((!direct && (addedFramebuffer == null || sumFramebuffers[1] == null)) ||
				sumFramebuffers[0] == null) {
			releaseTargets(device);
			broken = true;
			return false;
		}
		for (var framebuffer : sumFramebuffers) {
			if (framebuffer == null) {
				continue;
			}
			device.bindFramebuffer(framebuffer);
			device.setClearColor(0f, 0f, 0f, 0f);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		}
		width = drawWidth;
		height = drawHeight;
		lastStamp = -1;
		keptFrames = 0;
		return true;
	}

	@NonNull
	private static GlTexture2D floatTexture(@NonNull GlDevice device, int w, int h, boolean full) {
		GlTexture2D texture = device.createTexture2D();
		if (full) {
			device.allocateTexture2DFloat(texture, w, h);
		} else {
			device.allocateTexture2DHalfFloat(texture, w, h);
		}
		// Linear, for the smooth scale-up (float linear filtering comes with
		// supportsFloatTargets(); half floats filter in core GLES 3).
		device.applyTextureParameters(texture, new TextureParameters(
				GLES20.GL_LINEAR, GLES20.GL_LINEAR,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		return texture;
	}

	@Nullable
	private static GlFramebuffer framebufferFor(@NonNull GlDevice device,
			@NonNull GlTexture2D texture) {
		GlFramebuffer framebuffer = device.createFramebuffer();
		device.attachColor(framebuffer, texture);
		if (device.checkFramebufferStatus(framebuffer) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			device.deleteFramebuffer(framebuffer);
			return null;
		}
		return framebuffer;
	}

	private void releaseTargets(@NonNull GlDevice device) {
		device.deleteFramebuffer(addedFramebuffer);
		device.deleteTexture(addedTexture);
		addedFramebuffer = null;
		addedTexture = null;
		for (int i = 0; i < 2; ++i) {
			device.deleteFramebuffer(sumFramebuffers[i]);
			device.deleteTexture(sumTextures[i]);
			sumFramebuffers[i] = null;
			sumTextures[i] = null;
		}
		width = 0;
		height = 0;
	}

	// Everything, e.g. when the context goes - GL thread only.
	void release(@NonNull GlDevice device) {
		releaseTargets(device);
		device.deleteProgram(pointProgram);
		device.deleteProgram(splatProgram);
		device.deleteProgram(addProgram);
		device.deleteProgram(presentProgram);
		pointProgram = null;
		splatProgram = null;
		addProgram = null;
		presentProgram = null;
		lastSourceWidth = 0;
	}

	// Forgets GL objects without deleting them - for a context that's
	// already gone.
	void forget() {
		addedTexture = null;
		addedFramebuffer = null;
		for (int i = 0; i < 2; ++i) {
			sumTextures[i] = null;
			sumFramebuffers[i] = null;
		}
		pointProgram = null;
		splatProgram = null;
		addProgram = null;
		presentProgram = null;
		lastSourceWidth = 0;
		width = 0;
		height = 0;
		broken = false;
	}
}
