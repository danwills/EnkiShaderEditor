package enk.danwills.android.enkishadereditor.opengl;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// Renders the current shader at a resolution higher than the screen (for
// "Save At Higher Res") and assembles the result into one Bitmap. Runs
// entirely on the GL thread, as a one-off operation distinct from the
// normal per-frame render loop - it must not touch BuiltinUniforms' frame
// counter/runtime clock or ShaderRenderPipeline's ping-pong targets.
//
// Tiled rather than rendered in one pass: the target resolution can
// easily exceed GL_MAX_TEXTURE_SIZE, and even when it wouldn't, a single
// Bitmap big enough for a high multiplier risks OutOfMemoryError anyway
// (handled by the caller gating which multipliers are offered - see
// MainActivity's resolution picker). Every shader here is a plain
// gl_FragCoord/resolution-driven fragment shader (no 3D projection), and
// gl_FragCoord is, by spec, identical to the window/framebuffer
// coordinate a fragment is actually written to - it cannot be made to
// report a "global" coordinate while physically rendering into a small
// per-tile framebuffer; the two are the same number by definition. So
// instead of relying on GL viewport placement, each tile's fragment
// source gets a small macro prepended that adds a per-tile uniform
// offset to every gl_FragCoord read, making the shader's own
// coordinate/resolution math reconstruct the correct global position
// while still physically rendering into a small, reusable framebuffer.
final class HighResScreenshotCapture {
	interface ProgressListener {
		void onProgress(int tilesDone, int totalTiles);
	}

	private static final String TAG = "HighResCapture";

	// Per-tile *content* size (excluding the overlap margin below). Kept
	// small not just for GPU memory (it's well under typical
	// GL_MAX_TEXTURE_SIZE regardless), but because a single draw call
	// covering too many fragments of an expensive shader (heavy
	// raymarching, many octaves/samples) can take long enough to trip a
	// mobile GPU driver's hang-detection watchdog (TDR) and reset the
	// whole EGL context mid-capture - every tile after that renders/reads
	// back garbage. The live preview never hits this because it runs at
	// a `quality` scale tuned to stay within a frame budget; a one-off
	// capture has no such governor, so the tile size itself is the only
	// lever to keep any single draw's worst-case cost bounded. 512 was
	// chosen empirically after 2048 reliably tripped the watchdog on a
	// heavy shader partway through a capture.
	private static final int MAX_TILE_CONTENT_SIZE = 512;
	// Extra border rendered (and cropped off) around each tile so
	// shaders using screen-space derivatives (dFdx/dFdy, commonly via
	// #extension GL_OES_standard_derivatives) have real neighboring
	// fragments near tile edges instead of the tile's own boundary -
	// mitigates (doesn't perfectly eliminate) faint seams at tile
	// boundaries for such shaders.
	private static final int TILE_MARGIN = 4;

	private static final String TILE_OFFSET_PRELUDE = """
			uniform vec2 _tileOffset;
			#define gl_FragCoord vec4(gl_FragCoord.xy + _tileOffset, gl_FragCoord.zw)
			""";

	// Rebuilds, per tile, an exact (nearest-pixel, never blended) copy of
	// the *real* backbuffer at the capture's target resolution, so
	// anything the shader stores at specific pixels - a counter or other
	// state stashed in row 0 is the common pattern - reads back the same
	// value it would at normal resolution instead of a bilinear blend
	// between neighbours. Plain UV sampling of the original (small)
	// backbuffer texture isn't enough on its own: the shader's own
	// addressing math (texel-index / resolution) assumes backbuffer and
	// `resolution` are the same size, which is true normally but not
	// once `resolution` is bound to the inflated capture target -
	// without this, that math would land on the wrong texel entirely,
	// not just a blurred one. texelFetch() (GLES3) reads a single texel
	// by *integer* coordinate, bypassing the texture's own filter
	// setting entirely, so this needs no filter-state save/restore on
	// the live backbuffer texture either. GLES2-only devices fall back
	// to plain (bilinear, resolution-mismatched) sampling - a known
	// limitation there, not fixable without texelFetch.
	// A shader reading backbuffer via a position-*independent* UV (a
	// fixed small constant, not derived from gl_FragCoord at all - the
	// "persistent global state stashed at pixel (0,0)/row 0" pattern) is
	// a second, different problem from the one this blit otherwise
	// fixes: such a read isn't touched by the _tileOffset macro at all
	// (it never mentions gl_FragCoord), so it computes the *same* tiny
	// UV in every tile - which then lands on that tile's own local
	// corner instead of the true global origin, since each tile only
	// has its own small window of the backbuffer to sample from. Fixed
	// pragmatically: for the small corner of *every* tile's blit output
	// nearest its own local origin, force the effective tile offset to
	// (0,0) - i.e. pretend this tile is also the bottom-left-most tile,
	// just for that corner - so fixed/near-zero UV reads always find
	// the true global near-origin data regardless of which tile is
	// actually being rendered. Bounded to a generous but finite corner
	// (CORNER_OVERLAY_SIZE) since this is a correctness/best-effort
	// trade-off, not a free lunch: a gl_FragCoord-relative read that
	// happens to sample within this same corner, on a tile that isn't
	// actually near the global origin, will incorrectly see the
	// overlay's stamped content instead of its own tile-local
	// neighbourhood - acceptable because real-world "nearby-pixel"
	// feedback sampling is almost always a tiny radius, far smaller
	// than this corner.
	private static final int CORNER_OVERLAY_SIZE = 64;

	private static final String BACKBUFFER_BLIT_VERTEX_3 =
			"#version 300 es\n" + RendererProgramManager.FULL_SCREEN_VERTEX_SHADER_3;
	private static final String BACKBUFFER_BLIT_FRAGMENT_3 = """
			#version 300 es
			precision highp float;
			uniform sampler2D srcBackbuffer;
			uniform vec2 _tileOffset;
			uniform vec2 targetResolution;
			uniform float _cornerOverlaySize;
			out vec4 fragColor;
			void main() {
				vec2 effectiveOffset =
						(gl_FragCoord.x < _cornerOverlaySize && gl_FragCoord.y < _cornerOverlaySize)
								? vec2(0.0)
								: _tileOffset;
				vec2 globalPixel = gl_FragCoord.xy + effectiveOffset;
				vec2 uv = globalPixel / targetResolution;
				ivec2 srcSize = textureSize(srcBackbuffer, 0);
				ivec2 srcCoord = ivec2(floor(uv * vec2(srcSize)));
				srcCoord = clamp(srcCoord, ivec2(0), srcSize - ivec2(1));
				fragColor = texelFetch(srcBackbuffer, srcCoord, 0);
			}
			""";

	private HighResScreenshotCapture() {
	}

	// Inserts `directive` right after the shader's leading "header" -
	// any run of #version/#extension/#ifdef/#else/#endif/precision/blank/
	// comment lines at the very top - rather than at line 1. A plain
	// prepend (or even ShaderSourcePreparer's #version-aware insertion,
	// which only protects #version's position) breaks real-world shaders
	// here: GLSL ES requires every #extension directive to have nothing
	// but preprocessor directives and precision statements before it in
	// the whole file, and _tileOffset's `uniform` declaration is a real,
	// non-preprocessor token - inserted before a later #extension line
	// (a common position for it, e.g. after the precision block), that's
	// a guaranteed "Extension directives must occur before any non-
	// preprocessor tokens" compile error. Skipping the whole header
	// first avoids that regardless of where the shader's own #extension
	// line happens to sit.
	@NonNull
	private static PreparedShaderInput insertAfterShaderHeader(
			@NonNull PreparedShaderInput input, @NonNull String directive) {
		String source = input.getSource();
		String[] lines = source.split("\n", -1);
		int headerLines = 0;
		for (String line : lines) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() ||
					trimmed.startsWith("#") ||
					trimmed.startsWith("//") ||
					trimmed.startsWith("precision ")) {
				++headerLines;
			} else {
				break;
			}
		}

		StringBuilder rebuilt = new StringBuilder();
		for (int i = 0; i < headerLines; ++i) {
			rebuilt.append(lines[i]).append('\n');
		}
		rebuilt.append(directive);
		for (int i = headerLines; i < lines.length; ++i) {
			rebuilt.append(lines[i]);
			if (i < lines.length - 1) {
				rebuilt.append('\n');
			}
		}

		int insertedLines = (int) directive.chars().filter(c -> c == '\n').count();
		return new PreparedShaderInput(
				rebuilt.toString(),
				input.getLineMapping().addInsertedLinesAfterSourceLine(headerLines, insertedLines));
	}

	// Null on any failure (no prepared shader, compile failure, OOM,
	// incomplete framebuffer) - callers should treat that as "couldn't
	// capture", same as the normal screenshot path. `onProgress` (may be
	// null) is called after every tile, off the GL thread's own call
	// stack but still synchronously on it - callers needing UI updates
	// must hop to the UI thread themselves.
	@Nullable
	static Bitmap capture(
			@NonNull GlDevice device,
			@NonNull RendererProgramManager programManager,
			@NonNull ShaderRenderPipeline renderPipeline,
			@NonNull BuiltinUniforms builtinUniforms,
			int glVersion,
			int targetWidth,
			int targetHeight,
			boolean preserveBackbufferEdges,
			@Nullable ProgressListener onProgress) {
		var fragmentShader = programManager.getPreparedShaderSource().getFragmentShader();
		if (fragmentShader == null) {
			return null;
		}

		String vertexSource = programManager.getPreparedShaderSource().getVertexShader(
				RendererProgramManager.FULL_SCREEN_VERTEX_SHADER,
				RendererProgramManager.FULL_SCREEN_VERTEX_SHADER_3,
				glVersion);
		var tiledFragment = insertAfterShaderHeader(fragmentShader, TILE_OFFSET_PRELUDE);

		var buildResult = device.createProgram(
				vertexSource, tiledFragment.getSource(), tiledFragment.getLineMapping());
		if (!buildResult.succeeded() || buildResult.getProgram() == null) {
			Log.e(TAG, "capture: tile program compile failed: " + buildResult.getInfoLog());
			Log.d(TAG, "capture: tiled fragment source was:\n" + tiledFragment.getSource());
			return null;
		}
		GlProgram tileProgram = buildResult.getProgram();

		GlProgram blitProgram = null;
		if (glVersion >= 3 && preserveBackbufferEdges) {
			var blitResult = device.createProgram(
					BACKBUFFER_BLIT_VERTEX_3, BACKBUFFER_BLIT_FRAGMENT_3, ShaderLineMapping.identity());
			if (blitResult.succeeded() && blitResult.getProgram() != null) {
				blitProgram = blitResult.getProgram();
			} else {
				Log.e(TAG, "capture: backbuffer blit program compile failed (falling back to " +
						"plain UV sampling): " + blitResult.getInfoLog());
			}
		}

		int tileContentSize = Math.min(MAX_TILE_CONTENT_SIZE, device.getMaxTextureSize());
		if (tileContentSize <= TILE_MARGIN * 2) {
			Log.e(TAG, "capture: tileContentSize too small, aborting");
			device.deleteProgram(tileProgram);
			if (blitProgram != null) {
				device.deleteProgram(blitProgram);
			}
			return null;
		}
		int renderBufferSize = tileContentSize + TILE_MARGIN * 2;

		GlTexture2D tileTexture = device.createTexture2D();
		device.allocateTexture2D(tileTexture, renderBufferSize, renderBufferSize);
		device.applyTextureParameters(tileTexture, new TextureParameters(
				GLES20.GL_NEAREST, GLES20.GL_NEAREST,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		GlFramebuffer tileFramebuffer = device.createFramebuffer();
		device.attachColor(tileFramebuffer, tileTexture);
		boolean framebufferOk =
				device.checkFramebufferStatus(tileFramebuffer) == GLES20.GL_FRAMEBUFFER_COMPLETE;
		if (!framebufferOk) {
			Log.e(TAG, "capture: tile framebuffer incomplete");
		}

		GlTexture2D backbufferTileTexture = null;
		GlFramebuffer backbufferTileFramebuffer = null;
		if (blitProgram != null && framebufferOk) {
			backbufferTileTexture = device.createTexture2D();
			device.allocateTexture2D(backbufferTileTexture, renderBufferSize, renderBufferSize);
			device.applyTextureParameters(backbufferTileTexture, new TextureParameters(
					GLES20.GL_NEAREST, GLES20.GL_NEAREST,
					GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
			backbufferTileFramebuffer = device.createFramebuffer();
			device.attachColor(backbufferTileFramebuffer, backbufferTileTexture);
			if (device.checkFramebufferStatus(backbufferTileFramebuffer) !=
					GLES20.GL_FRAMEBUFFER_COMPLETE) {
				Log.e(TAG, "capture: backbuffer-tile framebuffer incomplete, " +
						"falling back to plain UV sampling");
				device.deleteFramebuffer(backbufferTileFramebuffer);
				device.deleteTexture(backbufferTileTexture);
				backbufferTileFramebuffer = null;
				backbufferTileTexture = null;
			}
		}

		Bitmap result = null;
		if (framebufferOk) {
			result = renderTiles(
					device, renderPipeline, builtinUniforms,
					tileProgram, tileFramebuffer,
					blitProgram, backbufferTileFramebuffer, backbufferTileTexture,
					targetWidth, targetHeight, tileContentSize, onProgress);
		}

		device.bindFramebuffer(null);
		device.deleteFramebuffer(tileFramebuffer);
		device.deleteTexture(tileTexture);
		device.deleteProgram(tileProgram);
		if (blitProgram != null) {
			device.deleteProgram(blitProgram);
		}
		if (backbufferTileFramebuffer != null) {
			device.deleteFramebuffer(backbufferTileFramebuffer);
			device.deleteTexture(backbufferTileTexture);
		}
		return result;
	}

	@Nullable
	private static Bitmap renderTiles(
			@NonNull GlDevice device,
			@NonNull ShaderRenderPipeline renderPipeline,
			@NonNull BuiltinUniforms builtinUniforms,
			@NonNull GlProgram tileProgram,
			@NonNull GlFramebuffer tileFramebuffer,
			@Nullable GlProgram blitProgram,
			@Nullable GlFramebuffer backbufferTileFramebuffer,
			@Nullable GlTexture2D backbufferTileTexture,
			int targetWidth,
			int targetHeight,
			int tileContentSize,
			@Nullable ProgressListener onProgress) {
		// One snapshot of every live uniform (time, mouse, sensors,
		// camera, audio, the existing backbuffer texture) shared by every
		// tile, so the whole image is internally consistent - only
		// resolution and _tileOffset vary per tile. Retargeted onto our
		// capture-only program; GlDevice.applyBindings() re-resolves
		// every uniform's location by name on each call, so this is safe
		// even though `bindings` was built against the live main program.
		GlTexture2D originalBackbuffer = renderPipeline.getBackTexture();
		var frame = builtinUniforms.beginFrame(device, originalBackbuffer);
		if (frame == null) {
			Log.e(TAG, "renderTiles: beginFrame() returned null - no live bindings to reuse");
			return null;
		}
		var bindings = frame.bindings();
		bindings.retarget(tileProgram);
		bindings.setFloat2(ShaderRenderer.UNIFORM_RESOLUTION,
				new float[]{targetWidth, targetHeight});

		boolean canBlitBackbuffer = blitProgram != null &&
				backbufferTileFramebuffer != null &&
				backbufferTileTexture != null &&
				originalBackbuffer != null;
		ProgramBindings blitBindings = canBlitBackbuffer ? new ProgramBindings(blitProgram) : null;

		Bitmap result;
		try {
			result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
		} catch (OutOfMemoryError e) {
			Log.e(TAG, "renderTiles: OOM allocating " + targetWidth + "x" + targetHeight + " bitmap");
			return null;
		}

		int tilesX = (targetWidth + tileContentSize - 1) / tileContentSize;
		int tilesY = (targetHeight + tileContentSize - 1) / tileContentSize;
		int totalTiles = tilesX * tilesY;
		int tilesDone = 0;
		float[] tileOffset = new float[2];
		Mesh fullScreenQuad = Mesh.fullScreenQuad();

		// Iterated in GL's own bottom-up coordinate convention (row 0 =
		// bottom of the image) throughout, matching how resolution/
		// gl_FragCoord already work for every shader here - only
		// converted to the Bitmap's top-down rows at the very end,
		// exactly like ShaderRenderPipeline.readPixelsAsPng() does for a
		// normal (untiled) capture.
		for (int tileRow = 0; tileRow < tilesY; ++tileRow) {
			int contentY0 = tileRow * tileContentSize;
			int contentH = Math.min(tileContentSize, targetHeight - contentY0);
			for (int tileCol = 0; tileCol < tilesX; ++tileCol) {
				int contentX0 = tileCol * tileContentSize;
				int contentW = Math.min(tileContentSize, targetWidth - contentX0);

				int renderX0 = Math.max(0, contentX0 - TILE_MARGIN);
				int renderY0 = Math.max(0, contentY0 - TILE_MARGIN);
				int renderX1 = Math.min(targetWidth, contentX0 + contentW + TILE_MARGIN);
				int renderY1 = Math.min(targetHeight, contentY0 + contentH + TILE_MARGIN);
				int renderW = renderX1 - renderX0;
				int renderH = renderY1 - renderY0;

				tileOffset[0] = renderX0;
				tileOffset[1] = renderY0;

				if (canBlitBackbuffer) {
					blitBindings.clear();
					blitBindings.setTexture("srcBackbuffer", originalBackbuffer);
					blitBindings.setFloat2("_tileOffset", tileOffset);
					blitBindings.setFloat2("targetResolution",
							new float[]{targetWidth, targetHeight});
					blitBindings.setFloat("_cornerOverlaySize", CORNER_OVERLAY_SIZE);
					device.bindFramebuffer(backbufferTileFramebuffer);
					device.setViewport(0, 0, renderW, renderH);
					device.applyBindings(blitBindings);
					device.draw(fullScreenQuad, blitProgram);
					bindings.setTexture(ShaderRenderer.UNIFORM_BACKBUFFER, backbufferTileTexture);
				}

				bindings.setFloat2("_tileOffset", tileOffset);

				device.bindFramebuffer(tileFramebuffer);
				device.setViewport(0, 0, renderW, renderH);
				device.clear(GLES20.GL_COLOR_BUFFER_BIT);
				device.applyBindings(bindings);
				device.draw(fullScreenQuad, tileProgram);
				if (tileRow == 0 && tileCol == 0) {
					int glError = GLES20.glGetError();
					if (glError != GLES20.GL_NO_ERROR) {
						Log.e(TAG, "renderTiles: GL error after first tile draw: 0x" +
								Integer.toHexString(glError));
					}
				}

				int contentLocalX0 = contentX0 - renderX0;
				int contentLocalY0 = contentY0 - renderY0;
				int[] argb = renderPipeline.readPixelsFlippedToArgb(
						contentLocalX0, contentLocalY0, contentW, contentH);
				// readPixelsFlippedToArgb() already flipped this tile's
				// own rows top-down local to itself; its row 0 is this
				// tile's highest GL-native row, which belongs at bitmap
				// row (targetHeight - contentY0 - contentH).
				result.setPixels(argb, 0, contentW,
						contentX0, targetHeight - contentY0 - contentH,
						contentW, contentH);

				++tilesDone;
				if (onProgress != null) {
					onProgress.onProgress(tilesDone, totalTiles);
				}
			}
		}
		return result;
	}

	// Downsamples `src` to dstWidth x dstHeight by averaging every source
	// pixel that falls under each destination pixel (a true box filter,
	// not a 2x2-neighbourhood bilinear sample) - for oversampling-based
	// antialiasing, where `src` was rendered at some integer multiple of
	// the wanted final size specifically so this can average it back
	// down. Reads `src` one source row-band at a time rather than all at
	// once, so a large oversampled render doesn't need its pixels
	// resident in Java memory twice over at once (the source Bitmap
	// itself, plus a full copy) - only one band's worth at a time. null
	// on OOM (allocating the destination Bitmap, or a row-band buffer
	// for an extreme width).
	@Nullable
	static Bitmap boxDownscale(@NonNull Bitmap src, int dstWidth, int dstHeight) {
		int srcWidth = src.getWidth();
		int srcHeight = src.getHeight();
		Bitmap dst;
		try {
			dst = Bitmap.createBitmap(dstWidth, dstHeight, Bitmap.Config.ARGB_8888);
		} catch (OutOfMemoryError e) {
			Log.e(TAG, "boxDownscale: OOM allocating " + dstWidth + "x" + dstHeight + " output");
			return null;
		}
		try {
			int[] dstRow = new int[dstWidth];
			for (int dy = 0; dy < dstHeight; ++dy) {
				int srcY0 = (int) ((long) dy * srcHeight / dstHeight);
				int srcY1 = Math.min(srcHeight,
						Math.max(srcY0 + 1, (int) ((long) (dy + 1) * srcHeight / dstHeight)));
				int bandHeight = srcY1 - srcY0;
				int[] band = new int[srcWidth * bandHeight];
				src.getPixels(band, 0, srcWidth, 0, srcY0, srcWidth, bandHeight);

				for (int dx = 0; dx < dstWidth; ++dx) {
					int srcX0 = (int) ((long) dx * srcWidth / dstWidth);
					int srcX1 = Math.min(srcWidth,
							Math.max(srcX0 + 1, (int) ((long) (dx + 1) * srcWidth / dstWidth)));

					long a = 0;
					long r = 0;
					long g = 0;
					long b = 0;
					int count = 0;
					for (int by = 0; by < bandHeight; ++by) {
						int rowOffset = by * srcWidth;
						for (int sx = srcX0; sx < srcX1; ++sx) {
							int pixel = band[rowOffset + sx];
							a += (pixel >>> 24) & 0xff;
							r += (pixel >>> 16) & 0xff;
							g += (pixel >>> 8) & 0xff;
							b += pixel & 0xff;
							++count;
						}
					}
					if (count == 0) {
						count = 1;
					}
					dstRow[dx] = ((int) (a / count) << 24) | ((int) (r / count) << 16) |
							((int) (g / count) << 8) | (int) (b / count);
				}
				dst.setPixels(dstRow, 0, dstWidth, 0, dy, dstWidth, 1);
			}
		} catch (OutOfMemoryError e) {
			Log.e(TAG, "boxDownscale: OOM during downscale");
			return null;
		}
		return dst;
	}
}
