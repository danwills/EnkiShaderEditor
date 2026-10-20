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

		// Asked once onProgress() has returned false: true keeps what's
		// rendered so far instead of discarding it - the result is
		// cropped to the bounding box of the tiles that finished (see
		// renderTiles()).
		default boolean keepPartialOnStop() {
			return false;
		}
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
	// boundaries for such shaders. Package-visible (not private) so
	// TileSafetyProbe can size its own throwaway single-tile render
	// buffer identically to a real capture's.
	static final int TILE_MARGIN = 4;

	// Each tile is drawn as one or more scissored pieces ("chunks"), each
	// finished and timed on its own - see drawInChunks(). The driver
	// resets the GPU on a draw that runs past ~2s, and tile cost varies
	// enormously - across shaders (one at 1/8 quality can need 30us a
	// pixel), across an image, and with thermal throttling - so no tile
	// size is safe on its own. Instead every piece is sized from how long
	// the last one took, aiming at TARGET_CHUNK_MS, starting from a tiny
	// MIN_CHUNK_SIZE piece so even the very first draw can't run long.
	private static final long TARGET_CHUNK_MS = 125;
	private static final int MIN_CHUNK_SIZE = 32;
	// The most a piece may grow by from one to the next, so one quick
	// (or mis-timed) piece can't leap straight to a huge one.
	private static final int MAX_CHUNK_GROWTH = 4;
	// The most pixels read back at once - see renderTiles(). 16MB of
	// int per array; a full Max tile (2048 + margins) still fits in one.
	private static final int READBACK_BAND_PIXELS = 4 << 20;

	// Package-visible for TileSafetyProbe - see TILE_MARGIN above.
	static final String TILE_OFFSET_PRELUDE = """
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
	// The blit output is a full targetWidth x targetHeight texture (see
	// its allocation in capture() below), written one row-band at a
	// time (one draw per distinct tile row, viewport-positioned at that
	// row's own true global Y - see the `canBlitBackbuffer` block in
	// renderTiles()) rather than tile-sized or row-band-sized: the
	// *tile program* (the user's own shader, compiled separately below)
	// samples this texture back via its own ordinary UV math, always
	// normalized against the full `resolution` uniform regardless of
	// which tile is currently rendering - and GL always resolves a UV
	// against the texture actually bound, not whatever size the shader
	// author had in mind. A texture shorter than the full target height
	// would silently mis-map *every* such read for any row but the
	// first (only row 0's own blit happens to start at global Y 0,
	// matching a short texture's own origin by coincidence) - not just
	// the position-independent "packed state at pixel index idx, row 0"
	// pattern this file was first built to protect, but perfectly
	// ordinary position-dependent visual feedback sampling too. Full
	// width for the same reason an index-packed read might land at any
	// column, not just a narrow corner. Within the true row-0 band the
	// copy is a direct, *unscaled* 1:1 mapping (not proportional to the
	// capture's resolution multiplier) - precisely because discrete
	// packed state at a specific index must not be resampled, unlike
	// ordinary visual content. Outside that band (any gl_FragCoord-
	// relative visual feedback read, not the index-based state pattern)
	// falls back to a proportional mapping against the true global Y.
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
			uniform vec2 targetResolution;
			uniform float _rowBandHeight;
			out vec4 fragColor;
			void main() {
				ivec2 srcSize = textureSize(srcBackbuffer, 0);
				ivec2 srcCoord;
				// Both an exact size match and the true row-0 band want
				// the same unscaled 1:1 copy now that gl_FragCoord is
				// already the global position either way - see this
				// file's own class-level doc for why each needs it.
				if (srcSize == ivec2(targetResolution) || gl_FragCoord.y < _rowBandHeight) {
					srcCoord = ivec2(gl_FragCoord.xy);
				} else {
					vec2 uv = gl_FragCoord.xy / targetResolution;
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
	// Package-visible (not private) so TileSafetyProbe can prepend the
	// same TILE_OFFSET_PRELUDE a real capture's tile program gets.
	@NonNull
	static PreparedShaderInput insertAfterShaderHeader(
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

	// Whether capture() below will skip tiling entirely and render the
	// whole image in one draw (see that method's own doc for why that's
	// only ever offered at 1x oversample with MAX bucket size).
	static boolean isSinglePassEligible(@NonNull GlDevice device, int targetWidth, int targetHeight,
			@NonNull TileBucketSize bucketSize, boolean allowSinglePass) {
		return allowSinglePass && bucketSize == TileBucketSize.MAX &&
				targetWidth <= device.getMaxTextureSize() && targetHeight <= device.getMaxTextureSize();
	}

	// Whether a SequenceFeedback is even possible to allocate at this
	// target size - its one texture has to hold the *whole* image
	// (there's nowhere else for a persistent, whole-image feedback
	// trail to live), so this is purely a texture-size question,
	// independent of whether tiling happens. Deliberately broader than
	// isSinglePassEligible() above: renderTiles() below writes each
	// tile directly into the (shared, persistent) feedback texture at
	// its own true position - via a scissor-clipped viewport, see the
	// `usingOwnFeedback` branch there - whenever one exists, tiled or
	// not, so a merely-tiled-for-TDR-safety sequence (the common case;
	// only a resolution that outright exceeds GL_MAX_TEXTURE_SIZE
	// can't) still gets full persistent high-res feedback accumulation,
	// not just singlePass. Exposed separately (not just computed inline
	// in capture() below) so ShaderRenderer.captureHighResScreenshotSequence()
	// can decide, before the frame loop even starts, whether it's worth
	// allocating a SequenceFeedback at all.
	static boolean isFeedbackEligible(@NonNull GlDevice device, int targetWidth, int targetHeight) {
		return targetWidth <= device.getMaxTextureSize() && targetHeight <= device.getMaxTextureSize();
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
		// mechanics that actually do the exact copy. The blit output is
		// now a full targetWidth x targetHeight texture (see the
		// allocation below) - skip it entirely if that wouldn't fit,
		// rather than silently falling back to a smaller one that would
		// mis-map every UV-based read, not just the index-based ones.
		if (needsBlit && glVersion >= 3 && preserveBackbufferEdges &&
				targetWidth <= device.getMaxTextureSize() &&
				targetHeight <= device.getMaxTextureSize()) {
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
			// Full target size, not just one row-band tall - see
			// BACKBUFFER_BLIT_FRAGMENT_3's own doc for why: the *tile
			// program* (the user's own shader) samples this texture via
			// its own normal UV math, normalized against the full
			// `resolution` uniform (targetWidth x targetHeight) - GL
			// always resolves a UV against the texture it's actually
			// bound to, so anything shorter than the full target height
			// here would silently mis-map every such read regardless of
			// which row is being rendered (only row 0's own blit would
			// ever land right, by coincidence of both starting at 0).
			backbufferTileTexture = device.createTexture2D();
			device.allocateTexture2D(backbufferTileTexture, targetWidth, targetHeight);
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
		// Distinct from usingOwnFeedback above: tileFramebuffer/tileTexture
		// are feedback's own whole-image texture whenever feedback exists
		// at all (see their assignment below), including the sequence's
		// very first (not-yet-seeded) frame - usingOwnFeedback is only
		// about what this frame reads as *input* (its own prior output
		// vs a fresh blit from the live backbuffer), which is a separate
		// question from where this frame's *output* needs to land. Using
		// usingOwnFeedback for the viewport/scissor/readback positioning
		// below instead of this would render every tile of the seeding
		// frame at (0, 0) instead of its real position, silently
		// corrupting the texture every later frame then reads from.
		boolean renderingIntoFeedback = feedback != null;

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
		// The finished tiles' bounding box, in the result's top-down
		// coordinates - what a stopped capture keeps when asked to.
		int doneX0 = targetWidth;
		int doneY0 = targetHeight;
		int doneX1 = 0;
		int doneY1 = 0;
		// See the per-tile fatal-error check below.
		boolean aborted = false;
		// See TARGET_CHUNK_MS.
		int[] chunkSize = {MIN_CHUNK_SIZE};
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
			// on this tile's row. Positioned at this row's own true
			// global Y (renderY0), not (0,0) - backbufferTileTexture is
			// now a full targetWidth x targetHeight texture (see its
			// allocation above), and this is what actually puts each
			// row's content at the same coordinates the tile program's
			// own UV math (normalized against the full `resolution`
			// uniform) will look for it at. gl_FragCoord inside the blit
			// shader is therefore already the true global position for
			// every row, not just row 0 - no `_tileOffsetY` needed
			// there any more to reconstruct it.
			if (canBlitBackbuffer && tileRow != blitRow) {
				blitRow = tileRow;
				blitBindings.clear();
				blitBindings.setTexture("srcBackbuffer", originalBackbuffer);
				blitBindings.setFloat2("targetResolution",
						new float[]{targetWidth, targetHeight});
				blitBindings.setFloat("_rowBandHeight", ROW_BAND_HEIGHT);
				device.bindFramebuffer(backbufferTileFramebuffer);
				device.setViewport(0, renderY0, targetWidth, renderH);
				device.applyBindings(blitBindings);
				device.draw(fullScreenQuad, blitProgram);
				bindings.setTexture(ShaderRenderer.UNIFORM_BACKBUFFER, backbufferTileTexture);
			}

			int contentX0 = tileCol * tileContentSize;
			int contentW = Math.min(tileContentSize, targetWidth - contentX0);

			int renderX0 = Math.max(0, contentX0 - margin);
			int renderX1 = Math.min(targetWidth, contentX0 + contentW + margin);
			int renderW = renderX1 - renderX0;

			// renderingIntoFeedback places the viewport at this tile's
			// true global position (renderX0, renderY0) directly within
			// the shared feedback texture (see below) - gl_FragCoord is
			// therefore *already* the correct global position by the
			// time the shader's own TILE_OFFSET_PRELUDE macro reads it,
			// and adding renderX0/renderY0 again on top would double it
			// for every tile except the one already at (0, 0) - which,
			// not coincidentally, was the only one that ever looked
			// right. The private-scratch-buffer branch (renderingIntoFeedback
			// false) still renders at a *local* (0, 0)-relative viewport
			// (see below), where gl_FragCoord only goes up to renderW/
			// renderH and genuinely needs renderX0/renderY0 added to
			// recover the global position - this is the one case that
			// still needs a real offset.
			tileOffset[0] = renderingIntoFeedback ? 0 : renderX0;
			tileOffset[1] = renderingIntoFeedback ? 0 : renderY0;

			bindings.setFloat2("_tileOffset", tileOffset);

			device.bindFramebuffer(tileFramebuffer);
			// renderingIntoFeedback means tileFramebuffer is feedback's
			// own whole-image texture (see its assignment above), not a
			// small scratch buffer private to this one tile - rendering
			// (and clearing) at the viewport's true global position
			// (renderX0, renderY0) is what puts each tile where it
			// actually belongs in that shared texture, instead of every
			// tile overwriting the same (0, 0) corner. The scissor rect
			// (this tile's own *content* box, smaller than the viewport
			// by `margin` on every side) keeps the margin - rendered
			// only so dFdx/dFdy see real neighbouring fragments, never
			// meant to be kept - from ever reaching the shared texture
			// and corrupting whichever neighbouring tile's content
			// actually owns those pixels; the scratch-buffer path below
			// doesn't need this, since its margin pixels simply never
			// get read back into the result Bitmap at all.
				// Reading back from a shared whole-image texture
			// (renderingIntoFeedback) needs this tile's true global
			// position - unlike the small scratch buffer the other
			// branch uses, there's no per-tile local origin to translate
			// against; the texture's own pixel at (contentX0, contentY0)
			// already *is* this tile's content origin.
			int contentLocalX0 = renderingIntoFeedback ? contentX0 : contentX0 - renderX0;
			int contentLocalY0 = renderingIntoFeedback ? contentY0 : contentY0 - renderY0;
			if (renderingIntoFeedback) {
				device.setViewport(renderX0, renderY0, renderW, renderH);
			} else {
				device.setViewport(0, 0, renderW, renderH);
			}
			// Drawn in scissored pieces - see drawInChunks(). The scratch
			// buffer's whole render rect, margin included (rendered only
			// so dFdx/dFdy see real neighbouring fragments); the shared
			// feedback texture's content rect only, so the margin never
			// overwrites a neighbouring tile's pixels there.
			int drawX0 = renderingIntoFeedback ? contentX0 : 0;
			int drawY0 = renderingIntoFeedback ? contentY0 : 0;
			int drawW = renderingIntoFeedback ? contentW : renderW;
			int drawH = renderingIntoFeedback ? contentH : renderH;
			device.setScissor(drawX0, drawY0, drawW, drawH);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT);
			device.applyBindings(bindings);
			boolean drawn = drawInChunks(device, fullScreenQuad, tileProgram,
					drawX0, drawY0, drawW, drawH, chunkSize, tileContentSize + margin * 2);
			device.disableScissor();
			// Checked before the readback, every tile: a draw that hit
			// the driver's per-draw deadline resets the GPU, after which
			// every further GL call on this context fails
			// (GL_INVALID_FRAMEBUFFER_OPERATION is what the dev device
			// reports) - and reading pixels back from it has crashed the
			// whole process natively (in the driver's own memcpy).
			// Stopping here fails the capture cleanly instead.
			int tileError = drawn ? GLES20.glGetError() : GLES20.GL_INVALID_FRAMEBUFFER_OPERATION;
			if (isFatalGlError(tileError)) {
				Log.e(TAG, "renderTiles: fatal GL error 0x" + Integer.toHexString(tileError) +
						" at tile " + tilesDone + " of " + totalTiles + " - likely a GPU reset from a " +
						"draw that hit the driver's deadline; aborting capture");
				aborted = true;
				break;
			}
			if (tilesDone == 0 && tileError != GLES20.GL_NO_ERROR) {
				Log.e(TAG, "renderTiles: GL error after first tile draw: 0x" +
						Integer.toHexString(tileError));
			}

			// Read back in row bands of at most READBACK_BAND_PIXELS, so a
			// big tile (a single pass is the whole frame) never needs
			// one huge pixel array - 8192x8192 in one go ran the Java
			// heap out of memory. readPixelsFlippedToArgb() flips each
			// band's rows top-down local to itself; a band's row 0 is
			// its highest GL-native row, which belongs at bitmap row
			// (targetHeight - bandY0 - bandH).
			int bandRows = Math.max(1, Math.min(contentH, READBACK_BAND_PIXELS / contentW));
			boolean readOk = true;
			for (int bandOffset = 0; bandOffset < contentH; bandOffset += bandRows) {
				int bandH = Math.min(bandRows, contentH - bandOffset);
				int[] argb;
				try {
					argb = renderPipeline.readPixelsFlippedToArgb(
							contentLocalX0, contentLocalY0 + bandOffset, contentW, bandH);
				} catch (OutOfMemoryError e) {
					Log.e(TAG, "renderTiles: OOM reading back a " + contentW + "x" + bandH + " band");
					readOk = false;
					break;
				}
				int bandBitmapY0 = targetHeight - (contentY0 + bandOffset) - bandH;
				result.setPixels(argb, 0, contentW, contentX0, bandBitmapY0, contentW, bandH);
				if (onTilePreview != null) {
					onTilePreview.onTile(contentX0, bandBitmapY0, contentW, bandH,
							targetWidth, targetHeight, argb);
				}
			}
			if (!readOk) {
				aborted = true;
				break;
			}
			int bitmapY0 = targetHeight - contentY0 - contentH;
			doneX0 = Math.min(doneX0, contentX0);
			doneY0 = Math.min(doneY0, bitmapY0);
			doneX1 = Math.max(doneX1, contentX0 + contentW);
			doneY1 = Math.max(doneY1, bitmapY0 + contentH);

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
		// Pairs with beginFrame() at the top of this method, same as
		// every other render path (the live loop, stepSimulationFrame())
		// already does - without this, UNIFORM_FRAME_NUMBER ("frame")
		// never advanced across a capture, tiled or not, staying stuck
		// at whatever it was when the sequence was paused. Unconditional
		// (not skipped on `cancelled`) for the same reason the feedback
		// swap above is: this frame's render already physically happened.
		builtinUniforms.endFrame();
		if (aborted) {
			return null;
		}
		if (cancelled) {
			if (tilesDone == 0 || onProgress == null || !onProgress.keepPartialOnStop()) {
				return null;
			}
			if (doneX0 == 0 && doneY0 == 0 && doneX1 == targetWidth && doneY1 == targetHeight) {
				return result;
			}
			try {
				return Bitmap.createBitmap(result, doneX0, doneY0, doneX1 - doneX0, doneY1 - doneY0);
			} catch (OutOfMemoryError e) {
				Log.e(TAG, "renderTiles: OOM cropping the partial capture");
				return null;
			}
		}
		return result;
	}

	// Draws one rect of the bound framebuffer (scissor already enabled)
	// in pieces no bigger than chunkSize[0], finishing and timing each -
	// see TARGET_CHUNK_MS. A rect bigger than the current size is split
	// into quarters, recursively, and the size is re-read before every
	// piece, so a slow piece shrinks the very next one. chunkSize[0]
	// carries over between tiles. Returns false if a fatal GL error (a
	// GPU reset) means the capture should stop.
	private static boolean drawInChunks(@NonNull GlDevice device, @NonNull Mesh quad,
			@NonNull GlProgram program, int x0, int y0, int width, int height,
			@NonNull int[] chunkSize, int maxChunkSize) {
		if (width > chunkSize[0] || height > chunkSize[0]) {
			// Split on even pixels, so 2x2 shading quads never straddle
			// two pieces.
			int halfW = width > chunkSize[0] ? (width / 2 + 1) & ~1 : width;
			int halfH = height > chunkSize[0] ? (height / 2 + 1) & ~1 : height;
			return drawInChunks(device, quad, program, x0, y0, halfW, halfH, chunkSize, maxChunkSize) &&
					(halfW >= width || drawInChunks(device, quad, program, x0 + halfW, y0,
							width - halfW, halfH, chunkSize, maxChunkSize)) &&
					(halfH >= height || drawInChunks(device, quad, program, x0, y0 + halfH,
							halfW, height - halfH, chunkSize, maxChunkSize)) &&
					(halfW >= width || halfH >= height || drawInChunks(device, quad, program,
							x0 + halfW, y0 + halfH, width - halfW, height - halfH, chunkSize,
							maxChunkSize));
		}
		device.setScissor(x0, y0, width, height);
		long start = System.nanoTime();
		device.draw(quad, program);
		GLES20.glFinish();
		long nanos = System.nanoTime() - start;
		if (isFatalGlError(GLES20.glGetError())) {
			return false;
		}
		chunkSize[0] = nextChunkSize(chunkSize[0], width, height, nanos, maxChunkSize);
		return true;
	}

	// The size (on a side) for the next piece: whatever would take about
	// TARGET_CHUNK_MS at the rate the last width x height piece drew at -
	// shrinking right away when that was slow, growing at most
	// MAX_CHUNK_GROWTH times over when quick. Shared with
	// ShaderRenderer's banded simulation steps.
	static int nextChunkSize(int size, int width, int height, long nanos, int maxChunkSize) {
		double nanosPerPixel = Math.max(1.0, nanos) / Math.max(1, width * height);
		int ideal = (int) Math.sqrt(TARGET_CHUNK_MS * 1_000_000.0 / nanosPerPixel);
		int next = Math.min(ideal, size * MAX_CHUNK_GROWTH);
		next = Math.max(MIN_CHUNK_SIZE, Math.min(maxChunkSize, next));
		if (next < size / 2) {
			Log.w(TAG, "drawInChunks: a " + width + "x" + height + " draw took " +
					nanos / 1_000_000 + "ms - drawing " + next + "px pieces from here on");
		}
		return next;
	}

	// Errors that mean the context itself is no longer usable (a GPU
	// reset, or out of memory) - unlike e.g. a stray GL_INVALID_VALUE
	// from an unrelated earlier call, which renderTiles() has always
	// tolerated without harm.
	private static boolean isFatalGlError(int error) {
		return error == GLES20.GL_OUT_OF_MEMORY ||
				error == GLES20.GL_INVALID_FRAMEBUFFER_OPERATION ||
				error == GL_CONTEXT_LOST;
	}

	// GLES 3.2 / KHR_robustness's GL_CONTEXT_LOST - not in GLES20's
	// constants, and this file only targets GLES 2/3.0 APIs otherwise.
	private static final int GL_CONTEXT_LOST = 0x0507;

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
