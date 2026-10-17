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
		// Return false to abort the capture after this tile - checked
		// between every tile, so cancelling takes effect within at most
		// one tile's render+readback time. The caller's already-allocated
		// result Bitmap and GL resources are discarded the same way as
		// any other capture failure - see capture()'s cleanup, which runs
		// unconditionally regardless of why renderTiles() returned null.
		boolean onProgress(int tilesDone, int totalTiles);
	}

	// Reports one tile's rendered pixels right after ProgressListener's
	// bare tile-count progress above - purely additive/optional, no
	// return value, can't cancel anything. argb/contentX0/contentY0/
	// contentW/contentH are exactly the values renderTiles() already
	// computed for its own Bitmap.setPixels() call below (so already in
	// top-down, whole-image coordinates matching the final result
	// Bitmap, not GL's bottom-up convention) - this just hands the caller
	// that same data before it's consumed.
	interface TilePreviewListener {
		void onTile(int contentX0, int contentY0, int contentW, int contentH,
				int targetWidth, int targetHeight, @NonNull int[] argb);
	}

	// A high-res frame sequence's own persistent, evolving backbuffer -
	// two full-resolution textures ping-ponged frame to frame across the
	// *whole sequence* (created once before it starts, destroyed once
	// after), used only for the singlePass case (see capture() below).
	// Seeded exactly once - on the sequence's first frame - from the
	// live (lower-resolution) backbuffer, the same exact-row-0-copy way
	// a single capture always has; every frame after that feeds forward
	// entirely from the sequence's *own* prior output instead, same as
	// how the live renderer's own ping-pong targets work. Without this,
	// every captured frame independently re-derived its backbuffer
	// input from the live simulation's own (lower-res) state each time -
	// internally consistent for one frame, but unable to show any
	// feedback detail that only exists at the capture's own resolution,
	// and never actually accumulating anything across the sequence.
	static final class SequenceFeedback {
		private final GlTexture2D[] textures = new GlTexture2D[2];
		private final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
		private int frontIndex;
		private boolean seeded;

		private SequenceFeedback() {
		}

		@Nullable
		static SequenceFeedback create(@NonNull GlDevice device, int width, int height) {
			SequenceFeedback feedback = new SequenceFeedback();
			for (int i = 0; i < 2; ++i) {
				GlTexture2D texture = device.createTexture2D();
				device.allocateTexture2D(texture, width, height);
				device.applyTextureParameters(texture, new TextureParameters(
						GLES20.GL_NEAREST, GLES20.GL_NEAREST,
						GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
				GlFramebuffer framebuffer = device.createFramebuffer();
				device.attachColor(framebuffer, texture);
				boolean ok = device.checkFramebufferStatus(framebuffer) == GLES20.GL_FRAMEBUFFER_COMPLETE;
				feedback.textures[i] = texture;
				feedback.framebuffers[i] = framebuffer;
				if (!ok) {
					Log.e(TAG, "SequenceFeedback.create: framebuffer " + i + " incomplete");
					feedback.destroy(device);
					return null;
				}
			}
			return feedback;
		}

		void destroy(@NonNull GlDevice device) {
			device.bindFramebuffer(null);
			for (int i = 0; i < 2; ++i) {
				if (framebuffers[i] != null) {
					device.deleteFramebuffer(framebuffers[i]);
				}
				if (textures[i] != null) {
					device.deleteTexture(textures[i]);
				}
			}
		}

		boolean isSeeded() {
			return seeded;
		}

		void markSeeded() {
			seeded = true;
		}

		@NonNull
		GlTexture2D getBackTexture() {
			return textures[1 - frontIndex];
		}

		@NonNull
		GlFramebuffer getFrontFramebuffer() {
			return framebuffers[frontIndex];
		}

		@NonNull
		GlTexture2D getFrontTexture() {
			return textures[frontIndex];
		}

		// Called once this frame's render + readback are both done, so
		// the *next* call sees this frame's output as its "back"
		// (input) texture and starts writing into the other one -
		// exactly mirroring ShaderRenderPipeline.swapTargets().
		void swap() {
			frontIndex = 1 - frontIndex;
		}
	}

	private static final String TAG = "HighResCapture";

	// Per-tile *content* size (excluding the overlap margin below) comes
	// from the caller's chosen TileBucketSize now, not a fixed constant -
	// kept small by default (TileBucketSize.NORMAL = 512) not just for
	// GPU memory (it's well under typical GL_MAX_TEXTURE_SIZE
	// regardless), but because a single draw call covering too many
	// fragments of an expensive shader (heavy raymarching, many octaves/
	// samples) can take long enough to trip a mobile GPU driver's
	// hang-detection watchdog (TDR) and reset the whole EGL context
	// mid-capture - every tile after that renders/reads back garbage.
	// The live preview never hits this because it runs at a `quality`
	// scale tuned to stay within a frame budget; a one-off capture has
	// no such governor, so the tile size itself is the only lever to
	// keep any single draw's worst-case cost bounded (barring
	// `singlePass` in capture() below, which opts out of that bound
	// entirely). 512 was chosen empirically after 2048 reliably tripped
	// the watchdog on a heavy shader partway through a capture - see
	// TileBucketSize's own doc for why MAX (2048) risks exactly that
	// again, deliberately, as a user-chosen trade.
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

	// Rebuilds an exact (nearest-pixel, never blended) copy of the *real*
	// backbuffer at the capture's target resolution, so anything the
	// shader stores at specific pixels - a counter or other state
	// stashed in row 0 is the common pattern - reads back the same value
	// it would at normal resolution instead of a bilinear blend between
	// neighbours. Plain UV sampling of the original (small) backbuffer
	// texture isn't enough on its own: the shader's own addressing math
	// (texel-index / resolution) assumes backbuffer and `resolution` are
	// the same size, which is true normally but not once `resolution` is
	// bound to the inflated capture target - without this, that math
	// would land on the wrong texel entirely, not just a blurred one.
	// texelFetch() (GLES3) reads a single texel by *integer* coordinate,
	// bypassing the texture's own filter setting entirely, so this needs
	// no filter-state save/restore on the live backbuffer texture
	// either. GLES2-only devices fall back to plain (bilinear,
	// resolution-mismatched) sampling - a known limitation there, not
	// fixable without texelFetch.
	//
	// The blit output is deliberately sized targetWidth x (one tile's
	// own height), not tile-sized in both dimensions: a shader reading
	// backbuffer via a position-*independent* UV (a fixed small
	// constant like (idx+0.5)/resolution, not derived from gl_FragCoord
	// at all - the "persistent state packed at pixel index idx, row 0"
	// pattern) computes that UV against the *bound texture's own size* -
	// if that's merely tile-sized, the same tiny UV that was meant to
	// select "pixel idx" instead lands on whatever texel idx maps to
	// once rescaled by (tileWidth / targetWidth), silently reading a
	// *different* packed state field than the one asked for. Matching
	// the blit output's width to the shader's own `resolution.x` keeps
	// that arithmetic honest without needing the full *height* too,
	// since every such read's Y component is equally tiny (near 0) -
	// i.e. "row 0" - regardless of which tile is rendering, so only the
	// bottommost (GL-native row 0) tile's own blit pass ever satisfies
	// the row-band condition below, and that's the one tile that
	// actually has true row-0 data to offer. The band spans the *entire*
	// target width, not just some fixed-size corner of it - any
	// column could be where a shader's "state packed at pixel index
	// idx, row 0" pattern stashes its data, not just the first few - so
	// restricting the exact copy to a narrow corner would silently
	// corrupt every index beyond that corner's width back to blurred
	// resampling, which is exactly the bug this was fixed from (a
	// 64x64-square version of this band let most of row 0 fall through
	// to proportional mapping instead). Within the row band the copy is
	// a direct, *unscaled* 1:1 mapping (not proportional to the
	// capture's resolution multiplier) - precisely because discrete
	// packed state at a specific index must not be resampled, unlike
	// ordinary visual content. Outside the row band (any
	// gl_FragCoord-relative visual feedback read, not the index-based
	// state pattern) falls back to a proportional mapping against the
	// true global Y - correct for any tile, since this blit already
	// covers the full target width and only needs the tile's own Y
	// offset.
	//
	// Separately: when the capture's target resolution is *exactly* the
	// live backbuffer's own size (e.g. capturing at the same resolution
	// the shader's already running at live), the whole image gets this
	// same unscaled 1:1 treatment, not just the row band - not merely an
	// optimization, but a correctness fix: the "proportional" branch's
	// uv = globalPixel / targetResolution; srcCoord = floor(uv * srcSize)
	// round-trip is only guaranteed to recover the original integer
	// pixel exactly when it's genuinely resampling across a size
	// mismatch - at a 1:1 size match it's representable in principle,
	// but float division followed by multiplication by the same value
	// doesn't always associate back to the identity exactly in IEEE 754,
	// occasionally landing one pixel off right at the boundary between
	// floor() buckets. For *visual* feedback content a stray off-by-one
	// pixel at a 1:1 size match is invisible; for packed state read via
	// a position-independent UV (the row-0 pattern this whole file
	// exists for) it's exactly the kind of single-frame seed corruption
	// that can derail a shader's own backbuffer-persisted state machine
	// for the rest of a sequence (e.g. flipping a stored "is this my own
	// prior state" sentinel), so it's worth skipping that arithmetic
	// entirely whenever it's avoidable.
	private static final int ROW_BAND_HEIGHT = 1;

	private static final String BACKBUFFER_BLIT_VERTEX_3 =
			"#version 300 es\n" + RendererProgramManager.FULL_SCREEN_VERTEX_SHADER_3;
	private static final String BACKBUFFER_BLIT_FRAGMENT_3 = """
			#version 300 es
			precision highp float;
			uniform sampler2D srcBackbuffer;
			uniform float _tileOffsetY;
			uniform vec2 targetResolution;
			uniform float _rowBandHeight;
			out vec4 fragColor;
			void main() {
				ivec2 srcSize = textureSize(srcBackbuffer, 0);
				ivec2 srcCoord;
				if (srcSize == ivec2(targetResolution)) {
					srcCoord = ivec2(gl_FragCoord.x, gl_FragCoord.y + _tileOffsetY);
				} else if (gl_FragCoord.y < _rowBandHeight) {
					srcCoord = ivec2(gl_FragCoord.xy);
				} else {
					vec2 globalPixel = vec2(gl_FragCoord.x, gl_FragCoord.y + _tileOffsetY);
					vec2 uv = globalPixel / targetResolution;
					srcCoord = ivec2(floor(uv * vec2(srcSize)));
				}
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

	// Exposed separately (not just computed inline in capture() below) so
	// ShaderRenderer.captureHighResScreenshotSequence() can decide, before
	// the frame loop even starts, whether it's worth allocating a
	// SequenceFeedback at all - allocating one for a sequence that's
	// never actually going to render singlePass would just be dead
	// weight (and the wrong size, since singlePass is what guarantees
	// targetWidth/targetHeight themselves fit in one texture).
	static boolean isSinglePassEligible(@NonNull GlDevice device, int targetWidth, int targetHeight,
			@NonNull TileBucketSize bucketSize, boolean allowSinglePass) {
		return allowSinglePass && bucketSize == TileBucketSize.MAX &&
				targetWidth <= device.getMaxTextureSize() && targetHeight <= device.getMaxTextureSize();
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
			@NonNull TileBucketSize bucketSize,
			boolean allowSinglePass,
			@NonNull TileRenderOrder tileOrder,
			@Nullable ProgressListener onProgress,
			@Nullable TilePreviewListener onTilePreview,
			@Nullable SequenceFeedback feedback) {
		// At 1x oversample (allowSinglePass - see TileBucketSize's own
		// doc) with MAX bucket size, and a resolution that still fits in
		// one texture, skip tiling - and the tile-offset-macro rewrite/
		// recompile it needs - entirely: reuse the live main program
		// directly, with a single "tile" spanning the whole image and no
		// margin, rather than compiling a throwaway copy of it. Never
		// deleted below (ownsTileProgram), since it's the same GlProgram
		// the live renderer still needs for its next ordinary frame.
		boolean singlePass = isSinglePassEligible(device, targetWidth, targetHeight, bucketSize, allowSinglePass);
		GlProgram tileProgram;
		boolean ownsTileProgram;
		if (singlePass) {
			tileProgram = programManager.getMainProgram();
			if (tileProgram == null) {
				return null;
			}
			ownsTileProgram = false;
		} else {
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
			tileProgram = buildResult.getProgram();
			ownsTileProgram = true;
		}

		// A sequence frame that already has its own seeded feedback
		// texture needs no blit at all - see renderTiles()'s own use of
		// `feedback`, which binds feedback.getBackTexture() (its own
		// prior output, already at the exact target resolution) directly
		// instead. Only the sequence's first frame (feedback == null, or
		// not yet seeded) still needs it, to seed that texture from the
		// live backbuffer in the first place.
		boolean needsBlit = feedback == null || !feedback.isSeeded();
		GlProgram blitProgram = null;
		// Still wanted even for a single pass: the capture resolution
		// can (and typically does) differ from the live backbuffer's
		// own resolution regardless of tiling, and plain UV sampling of
		// the small original texture across that mismatch is only fine
		// for *visual* content (GL's own bilinear filtering is exactly
		// what you want when upscaling a feedback trail) - an
		// index-packed state value (the "counter stashed at row 0"
		// pattern) gets blurred across neighbours instead of read back
		// exactly, same failure either way whether there's 1 tile or
		// many. So this respects preserveBackbufferEdges here exactly
		// like the tiled path does, not a reason singlePass gets to skip
		// it - see BACKBUFFER_BLIT_FRAGMENT_3's own doc for the row-band
		// mechanics that actually do the exact copy. The blit
		// output needs to be targetWidth wide (see that same comment) -
		// skip it entirely if that alone wouldn't fit as a single
		// texture, rather than silently falling back to a tile-sized one
		// that would mis-map any index-based state read.
		if (needsBlit && glVersion >= 3 && preserveBackbufferEdges &&
				targetWidth <= device.getMaxTextureSize()) {
			var blitResult = device.createProgram(
					BACKBUFFER_BLIT_VERTEX_3, BACKBUFFER_BLIT_FRAGMENT_3, ShaderLineMapping.identity());
			if (blitResult.succeeded() && blitResult.getProgram() != null) {
				blitProgram = blitResult.getProgram();
			} else {
				Log.e(TAG, "capture: backbuffer blit program compile failed (falling back to " +
						"plain UV sampling): " + blitResult.getInfoLog());
			}
		}

		// A single pass needs no margin (there's no tile seam to protect
		// against) and gets its tile/render buffer sized exactly to the
		// target - not a reusable square big enough for *any* tile's
		// position, since there's only ever the one - avoiding the
		// memory a square max(targetWidth, targetHeight)^2 buffer would
		// otherwise waste for a non-square capture.
		int tileContentSize;
		int margin;
		int renderBufferWidth;
		int renderBufferHeight;
		if (singlePass) {
			tileContentSize = Math.max(targetWidth, targetHeight);
			margin = 0;
			renderBufferWidth = targetWidth;
			renderBufferHeight = targetHeight;
		} else {
			tileContentSize = Math.min(bucketSize.pixels, device.getMaxTextureSize());
			if (tileContentSize <= TILE_MARGIN * 2) {
				Log.e(TAG, "capture: tileContentSize too small, aborting");
				if (ownsTileProgram) {
					device.deleteProgram(tileProgram);
				}
				if (blitProgram != null) {
					device.deleteProgram(blitProgram);
				}
				return null;
			}
			margin = TILE_MARGIN;
			renderBufferWidth = tileContentSize + margin * 2;
			renderBufferHeight = renderBufferWidth;
		}

		// A sequence frame with its own feedback texture renders straight
		// into that (already validated, already the exact right size -
		// see SequenceFeedback.create()) instead of a fresh scratch
		// one, so its output persists as next frame's input rather than
		// being discarded the moment this call returns.
		GlTexture2D tileTexture = feedback != null ? feedback.getFrontTexture() : device.createTexture2D();
		GlFramebuffer tileFramebuffer =
				feedback != null ? feedback.getFrontFramebuffer() : device.createFramebuffer();
		boolean framebufferOk;
		if (feedback != null) {
			framebufferOk = true;
		} else {
			device.allocateTexture2D(tileTexture, renderBufferWidth, renderBufferHeight);
			device.applyTextureParameters(tileTexture, new TextureParameters(
					GLES20.GL_NEAREST, GLES20.GL_NEAREST,
					GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
			device.attachColor(tileFramebuffer, tileTexture);
			framebufferOk = device.checkFramebufferStatus(tileFramebuffer) == GLES20.GL_FRAMEBUFFER_COMPLETE;
			if (!framebufferOk) {
				Log.e(TAG, "capture: tile framebuffer incomplete");
			}
		}

		GlTexture2D backbufferTileTexture = null;
		GlFramebuffer backbufferTileFramebuffer = null;
		if (blitProgram != null && framebufferOk) {
			// targetWidth wide (not renderBufferWidth - see the comment
			// on BACKBUFFER_BLIT_FRAGMENT_3), one tile's own height tall
			// (the whole image's height, for a single pass).
			backbufferTileTexture = device.createTexture2D();
			device.allocateTexture2D(backbufferTileTexture, targetWidth, renderBufferHeight);
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
					targetWidth, targetHeight, tileContentSize, margin, tileOrder, onProgress,
					onTilePreview, feedback);
		}

		device.bindFramebuffer(null);
		// feedback (when supplied) owns tileTexture/tileFramebuffer for
		// the whole sequence, not just this one call - the caller
		// destroys it once the sequence itself is done, not here.
		if (feedback == null) {
			device.deleteFramebuffer(tileFramebuffer);
			device.deleteTexture(tileTexture);
		}
		if (ownsTileProgram) {
			device.deleteProgram(tileProgram);
		}
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
			int margin,
			@NonNull TileRenderOrder tileOrder,
			@Nullable ProgressListener onProgress,
			@Nullable TilePreviewListener onTilePreview,
			@Nullable SequenceFeedback feedback) {
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

		// Already seeded - bind straight to our own exact-resolution
		// prior output, no resampling of any kind needed (it's already
		// the right size), and no live-backbuffer blit either (that's
		// only for seeding this in the first place - see capture()'s
		// `needsBlit`). This is what actually makes feedback detail at
		// the capture's own resolution persist and accumulate frame to
		// frame instead of being re-derived from the live (lower-res)
		// simulation state every time.
		boolean usingOwnFeedback = feedback != null && feedback.isSeeded();
		if (usingOwnFeedback) {
			bindings.setTexture(ShaderRenderer.UNIFORM_BACKBUFFER, feedback.getBackTexture());
		}

		boolean canBlitBackbuffer = !usingOwnFeedback && blitProgram != null &&
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
		boolean cancelled = false;
		float[] tileOffset = new float[2];
		Mesh fullScreenQuad = Mesh.fullScreenQuad();
		// Only redone when the tile about to render is in a different
		// row than the last one that used it - still a one-draw-per-row
		// saving for SCANLINE (whose tiles visit each row contiguously,
		// same as before this field existed), just no longer assuming
		// contiguous rows for the other, deliberately non-scanline
		// orders below.
		int blitRow = -1;

		// tileRow/tileCol follow GL's own bottom-up row convention (row 0
		// = bottom of the image) throughout, matching how resolution/
		// gl_FragCoord already work for every shader here - only
		// converted to the Bitmap's top-down rows at the very end,
		// exactly like ShaderRenderPipeline.readPixelsAsPng() does for a
		// normal (untiled) capture. Visited in whichever order
		// tileOrder.order() lays out, rather than a fixed nested loop -
		// purely cosmetic (see TileRenderOrder's own doc), so every
		// tile's own math here is otherwise identical to before.
		for (int[] tile : tileOrder.order(tilesX, tilesY)) {
			int tileRow = tile[0];
			int tileCol = tile[1];
			int contentY0 = tileRow * tileContentSize;
			int contentH = Math.min(tileContentSize, targetHeight - contentY0);
			int renderY0 = Math.max(0, contentY0 - margin);
			int renderY1 = Math.min(targetHeight, contentY0 + contentH + margin);
			int renderH = renderY1 - renderY0;

			// The blit output spans the full target width (see the
			// comment on BACKBUFFER_BLIT_FRAGMENT_3), so it only depends
			// on this tile's row.
			if (canBlitBackbuffer && tileRow != blitRow) {
				blitRow = tileRow;
				blitBindings.clear();
				blitBindings.setTexture("srcBackbuffer", originalBackbuffer);
				blitBindings.setFloat("_tileOffsetY", renderY0);
				blitBindings.setFloat2("targetResolution",
						new float[]{targetWidth, targetHeight});
				blitBindings.setFloat("_rowBandHeight", ROW_BAND_HEIGHT);
				device.bindFramebuffer(backbufferTileFramebuffer);
				device.setViewport(0, 0, targetWidth, renderH);
				device.applyBindings(blitBindings);
				device.draw(fullScreenQuad, blitProgram);
				bindings.setTexture(ShaderRenderer.UNIFORM_BACKBUFFER, backbufferTileTexture);
			}

			int contentX0 = tileCol * tileContentSize;
			int contentW = Math.min(tileContentSize, targetWidth - contentX0);

			int renderX0 = Math.max(0, contentX0 - margin);
			int renderX1 = Math.min(targetWidth, contentX0 + contentW + margin);
			int renderW = renderX1 - renderX0;

			tileOffset[0] = renderX0;
			tileOffset[1] = renderY0;

			bindings.setFloat2("_tileOffset", tileOffset);

			device.bindFramebuffer(tileFramebuffer);
			device.setViewport(0, 0, renderW, renderH);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT);
			device.applyBindings(bindings);
			device.draw(fullScreenQuad, tileProgram);
			if (tilesDone == 0) {
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
			if (onTilePreview != null) {
				onTilePreview.onTile(contentX0, targetHeight - contentY0 - contentH,
						contentW, contentH, targetWidth, targetHeight, argb);
			}

			++tilesDone;
			if (onProgress != null && !onProgress.onProgress(tilesDone, totalTiles)) {
				cancelled = true;
				break;
			}
		}
		if (feedback != null) {
			// Marks the seed as done (a no-op if this call was already
			// past that) and flips which texture is "front" - so next
			// call's getBackTexture() sees what was just rendered here,
			// not what was there before it. Done regardless of
			// `cancelled`: either way this frame's render already
			// physically happened, and the whole SequenceFeedback gets
			// torn down right after a cancelled sequence anyway.
			feedback.markSeeded();
			feedback.swap();
		}
		return cancelled ? null : result;
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
