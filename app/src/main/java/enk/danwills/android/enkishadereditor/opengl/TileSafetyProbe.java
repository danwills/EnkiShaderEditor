package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

// Renders a few sample tiles (bound to the *full* target resolution,
// same as a real capture's tile program - see probe() below - but only
// tileBucketPixels+margin pixels drawn per tile, at the caller's chosen
// positions, since cost can vary hugely across an image) off-screen, on
// whatever thread/process calls this. Used by TileProbeService (see that
// class's own doc for why it runs in an isolated :probe process) to find
// the largest TileBucketSize a given shader can render without tripping a
// GPU driver's hang-detection watchdog - which kills the whole process
// outright, with no catchable Java/native exception anywhere (see
// HighResScreenshotCapture's own doc on exactly this problem, which tiled
// capture already works around by keeping draw calls small; this probe
// exists to find how small "small enough" actually needs to be for a
// specific shader, instead of always assuming the most conservative
// bucket size). Deliberately doesn't attempt anything clever around
// cancellation or recovery: if probe() doesn't survive to return at all,
// that failure *is* the signal callers need - there's nothing to catch,
// and nothing this method could do about it even if there were.
public final class TileSafetyProbe {
	// Matches ShaderRenderer.MAX_TEXTURES/HeadlessShaderCompiler's own
	// copy of the same constant - how many samplers ShaderSourcePreparer
	// will discover before it stops looking.
	private static final int MAX_TEXTURES = 32;

	private TileSafetyProbe() {
	}

	public enum Result {
		// Rendered, glFinish()'d, and every pixel of the tile was
		// genuinely overwritten by the shader - this tile size looks safe
		// for this shader (on this device).
		SAFE,
		// Not a tile-size question at all - the shader itself doesn't
		// compile. Every bucket size would report this identically, so
		// callers should treat it as "stop probing, something else is
		// wrong" rather than keep searching.
		COMPILE_FAILED,
		// glFinish() returned without killing the process, but the draw
		// demonstrably didn't complete: either GL itself reported an
		// error, or some of the tile still holds the sentinel colour it
		// was cleared to beforehand (see SENTINEL_* below). Some drivers
		// silently abort an over-long draw at a fixed deadline (~2s seen
		// on the dev device) instead of killing the process - the probe
		// survives, but the real capture would get a partly-unrendered
		// tile, or a GPU reset that takes the main process down with it.
		// Treated as unsafe, same as a crash.
		RENDER_INCOMPLETE,
		// Couldn't even get a GL context - inconclusive, not a verdict
		// on the shader at all.
		NO_GL_CONTEXT
	}

	// What probe() found, plus how long the slowest sample tile's draw
	// took (draw call through glFinish(), not EGL/compile setup) -
	// TileSizeProber uses that to catch a silently-truncated draw the
	// sentinel check alone can't (see its own plateau check). -1 when no
	// draw happened.
	public record Outcome(@NonNull Result result, long drawMs) {
	}

	private static final String TAG = "TileSafetyProbe";

	// Cleared into the tile before drawing and looked for afterward: any
	// pixel still holding exactly this value never got written by the
	// shader. An odd, non-opaque alpha makes a genuine match from shader
	// output vanishingly unlikely (blending is off, so whatever the
	// shader writes replaces it outright).
	private static final int SENTINEL_R = 255;
	private static final int SENTINEL_G = 0;
	private static final int SENTINEL_B = 255;
	private static final int SENTINEL_A = 77;

	@NonNull
	// sampleOffsets: x0, y0 pairs - each sample tile's content origin in
	// the target image (GL's bottom-up pixels), one draw per pair.
	public static Outcome probe(@NonNull String fragmentSource, int targetWidth, int targetHeight,
			int tileBucketPixels, @NonNull int[] sampleOffsets) {
		long startNanos = System.nanoTime();
		Log.d(TAG, "probe: starting pid=" + android.os.Process.myPid() +
				" tileBucketPixels=" + tileBucketPixels +
				" targetWidth=" + targetWidth + " targetHeight=" + targetHeight +
				" sourceLength=" + fragmentSource.length());
		HeadlessEglSession egl = HeadlessEglSession.create();
		if (egl == null) {
			Log.e(TAG, "probe: NO_GL_CONTEXT - HeadlessEglSession.create() returned null");
			return new Outcome(Result.NO_GL_CONTEXT, -1);
		}
		try {
			Log.d(TAG, "probe: got EGL context, version=" + egl.version);
			GlDevice device = new GlDevice(MAX_TEXTURES);
			var prepared = ShaderSourcePreparer.prepare(fragmentSource, egl.version, MAX_TEXTURES);
			var fragment = prepared.getFragmentShader();
			if (fragment == null) {
				Log.e(TAG, "probe: COMPILE_FAILED - ShaderSourcePreparer returned no fragment " +
						"shader at all (source null/blank?)");
				return new Outcome(Result.COMPILE_FAILED, -1);
			}
			String vertexSource = prepared.getVertexShader(
					RendererProgramManager.FULL_SCREEN_VERTEX_SHADER,
					RendererProgramManager.FULL_SCREEN_VERTEX_SHADER_3, egl.version);
			var tiledFragment = HighResScreenshotCapture.insertAfterShaderHeader(
					fragment, HighResScreenshotCapture.TILE_OFFSET_PRELUDE);
			var buildResult = device.createProgram(
					vertexSource, tiledFragment.getSource(), tiledFragment.getLineMapping());
			if (!buildResult.succeeded() || buildResult.getProgram() == null) {
				Log.e(TAG, "probe: COMPILE_FAILED - " + buildResult.getInfoLog());
				return new Outcome(Result.COMPILE_FAILED, -1);
			}
			GlProgram program = buildResult.getProgram();
			try {
				Outcome outcome = renderSampleTiles(device, program, targetWidth, targetHeight,
						tileBucketPixels, sampleOffsets);
				long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
				Log.d(TAG, "probe: result=" + outcome.result() + " drawMs=" + outcome.drawMs() +
						" totalMs=" + elapsedMs);
				return outcome;
			} finally {
				device.deleteProgram(program);
			}
		} finally {
			egl.destroy();
		}
	}

	@NonNull
	private static Outcome renderSampleTiles(@NonNull GlDevice device, @NonNull GlProgram program,
			int targetWidth, int targetHeight, int tileBucketPixels, @NonNull int[] sampleOffsets) {
		// Never bigger than the frame itself - a real capture's single
		// tile can't exceed it either (tileContentSize clamps to the
		// target the same way), so anything past that would only test
		// pixels no capture will ever draw.
		int contentSize = Math.min(tileBucketPixels, Math.max(targetWidth, targetHeight));
		int renderSize = Math.min(
				contentSize + HighResScreenshotCapture.TILE_MARGIN * 2,
				device.getMaxTextureSize());
		GlTexture2D texture = device.createTexture2D();
		device.allocateTexture2D(texture, renderSize, renderSize);
		device.applyTextureParameters(texture, new TextureParameters(
				GLES20.GL_NEAREST, GLES20.GL_NEAREST,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		GlFramebuffer framebuffer = device.createFramebuffer();
		device.attachColor(framebuffer, texture);
		GlTexture2D blank = bindBlankTextures(device);
		Log.d(TAG, "renderSampleTiles: renderSize=" + renderSize +
				" samples=" + sampleOffsets.length / 2);
		try {
			int fbStatus = device.checkFramebufferStatus(framebuffer);
			if (fbStatus != GLES20.GL_FRAMEBUFFER_COMPLETE) {
				Log.e(TAG, "renderSampleTiles: framebuffer incomplete, status=0x" +
						Integer.toHexString(fbStatus));
				return new Outcome(Result.COMPILE_FAILED, -1);
			}
			ProgramBindings bindings = new ProgramBindings(program);
			// The *full* target resolution, not this tile's own render
			// size - a tile's real-world cost often scales with what the
			// shader believes the overall image resolution to be (more
			// raymarch/antialiasing steps at higher resolution), not
			// just how many of its own pixels get drawn, so this has to
			// match a real capture's own `resolution` binding exactly to
			// be a meaningful test of that capture's actual risk.
			bindings.setFloat2(ShaderRenderer.UNIFORM_RESOLUTION,
					new float[]{targetWidth, targetHeight});
			device.bindFramebuffer(framebuffer);
			device.setViewport(0, 0, renderSize, renderSize);
			long slowestMs = 0;
			int samples = Math.max(1, sampleOffsets.length / 2);
			for (int i = 0; i < samples; ++i) {
				// Placed like a real capture's scratch-buffer tile: its
				// render origin is the content origin less the margin.
				float x0 = sampleOffsets.length >= 2 * (i + 1)
						? Math.max(0, sampleOffsets[2 * i] - HighResScreenshotCapture.TILE_MARGIN)
						: 0;
				float y0 = sampleOffsets.length >= 2 * (i + 1)
						? Math.max(0, sampleOffsets[2 * i + 1] - HighResScreenshotCapture.TILE_MARGIN)
						: 0;
				bindings.setFloat2("_tileOffset", new float[]{x0, y0});
				GLES20.glClearColor(SENTINEL_R / 255f, SENTINEL_G / 255f,
						SENTINEL_B / 255f, SENTINEL_A / 255f);
				device.clear(GLES20.GL_COLOR_BUFFER_BIT);
				GLES20.glClearColor(0f, 0f, 0f, 0f);
				// Flushed separately so the clear's own cost isn't counted
				// in drawMs below.
				GLES20.glFinish();
				device.applyBindings(bindings);
				long drawStart = System.nanoTime();
				device.draw(Mesh.fullScreenQuad(), program);
				// Block until the GPU has genuinely finished this draw (or
				// the driver's watchdog kills this process first) -
				// glDrawArrays() alone can return long before the queued
				// work completes.
				GLES20.glFinish();
				long drawMs = (System.nanoTime() - drawStart) / 1_000_000;
				int glError = GLES20.glGetError();
				int untouched = countSentinelPixels(renderSize);
				Log.d(TAG, "renderSampleTiles: sample " + (i + 1) + "/" + samples + " at " +
						(int) x0 + "," + (int) y0 + " drawMs=" + drawMs + " glGetError=0x" +
						Integer.toHexString(glError) + " untouchedPixels=" + untouched + "/" +
						(renderSize * renderSize));
				slowestMs = Math.max(slowestMs, drawMs);
				if (glError != GLES20.GL_NO_ERROR || untouched != 0) {
					return new Outcome(Result.RENDER_INCOMPLETE, slowestMs);
				}
			}
			return new Outcome(Result.SAFE, slowestMs);
		} finally {
			device.deleteFramebuffer(framebuffer);
			device.deleteTexture(texture);
			device.deleteTexture(blank);
		}
	}

	// Binds a transparent black 1x1 texture to every texture unit, so the
	// shader's samplers (backbuffer, saved textures - none are bound here)
	// don't read the tile itself: setting up the tile's texture leaves it
	// bound to unit 0, where every unbound sampler points. A shader that
	// copies its backbuffer through (state kept in a row of it) would then
	// write back the sentinel colour and look like a draw that never
	// finished.
	@NonNull
	private static GlTexture2D bindBlankTextures(@NonNull GlDevice device) {
		GlTexture2D blank = device.createTexture2D();
		device.allocateTexture2D(blank, 1, 1);
		GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, 1, 1,
				GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4));
		device.applyTextureParameters(blank, new TextureParameters(
				GLES20.GL_NEAREST, GLES20.GL_NEAREST,
				GLES20.GL_CLAMP_TO_EDGE, GLES20.GL_CLAMP_TO_EDGE));
		int[] units = new int[1];
		GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, units, 0);
		for (int unit = 0; unit < Math.min(units[0], MAX_TEXTURES); ++unit) {
			device.bindTexture(unit, blank);
		}
		return blank;
	}

	// Reads back the whole tile (still bound) and counts pixels holding
	// the exact sentinel value - i.e. ones the draw never reached.
	private static int countSentinelPixels(int size) {
		ByteBuffer pixels = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder());
		GLES20.glReadPixels(0, 0, size, size, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
		int count = 0;
		for (int i = 0; i < size * size; ++i) {
			int base = i * 4;
			if ((pixels.get(base) & 0xff) == SENTINEL_R &&
					(pixels.get(base + 1) & 0xff) == SENTINEL_G &&
					(pixels.get(base + 2) & 0xff) == SENTINEL_B &&
					(pixels.get(base + 3) & 0xff) == SENTINEL_A) {
				++count;
			}
		}
		return count;
	}
}
