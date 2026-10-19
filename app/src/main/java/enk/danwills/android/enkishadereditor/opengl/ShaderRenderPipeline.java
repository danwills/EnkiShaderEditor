package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

final class ShaderRenderPipeline {
	private static final int THUMBNAIL_WIDTH = 144;
	private static final int THUMBNAIL_HEIGHT = 144;
	private static final String SURFACE_FRAME = "frame";
	private static final String SURFACE_FRAME_SIZE = "frameSize";
	private static final String SURFACE_CROP_PIXELS = "cropPixels";

	private final float[] thumbnailResolution =
			new float[]{THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT};
	private final float[] drawResolution = new float[2];
	private final float[] frameSize = new float[2];
	private boolean cropBorderPixel = false;
	private final TextureParameters thumbnailTextureParameters =
			new TextureParameters(
					GLES20.GL_LINEAR,
					GLES20.GL_LINEAR,
					GLES20.GL_CLAMP_TO_EDGE,
					GLES20.GL_CLAMP_TO_EDGE);
	@NonNull
	private final GlDevice device;
	@NonNull
	private final Mesh fullScreenQuadMesh;
	private final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
	private final GlTexture2D[] targetTextures = new GlTexture2D[2];
	private int frontTarget;
	private int backTarget = 1;
	// See releaseTargetsKeepingLastFrame().
	@Nullable
	private GlTexture2D standInFrame;
	@Nullable
	private GlFramebuffer thumbnailFramebuffer;
	@Nullable
	private GlTexture2D thumbnailTexture;

	ShaderRenderPipeline(@NonNull GlDevice device, @NonNull Mesh fullScreenQuadMesh) {
		this.device = device;
		this.fullScreenQuadMesh = fullScreenQuadMesh;
	}

	@NonNull
	List<ShaderError> createContextResources() {
		ArrayList<ShaderError> errors = new ArrayList<>();
		thumbnailTexture = device.createTexture2D();
		device.applyTextureParameters(thumbnailTexture, thumbnailTextureParameters);
		device.allocateTexture2D(
				thumbnailTexture,
				THUMBNAIL_WIDTH,
				THUMBNAIL_HEIGHT);
		thumbnailFramebuffer = device.createFramebuffer();
		device.attachColor(thumbnailFramebuffer, thumbnailTexture);
		int status = device.checkFramebufferStatus(thumbnailFramebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ShaderError.createGeneral(
					"Thumbnail framebuffer incomplete: 0x" +
							Integer.toHexString(status)));
		}
		device.bindFramebuffer(null);
		return errors;
	}

	void discardContextResources() {
		thumbnailFramebuffer = null;
		thumbnailTexture = null;
		standInFrame = null;
		discardTargets();
	}

	void releaseTargets() {
		for (int i = 0; i < framebuffers.length; ++i) {
			device.deleteFramebuffer(framebuffers[i]);
			framebuffers[i] = null;
			device.deleteTexture(targetTextures[i]);
			targetTextures[i] = null;
		}
		frontTarget = 0;
		backTarget = 1;
	}

	// Like releaseTargets(), but holds on to the most recently completed
	// frame's texture (deleting only its framebuffer) as a stand-in that
	// renderCompletedSurfacePass() keeps presenting until a frame at the
	// new size completes - see ShaderRenderer.onSurfaceChanged().
	void releaseTargetsKeepingLastFrame() {
		GlTexture2D lastCompleted = targetTextures[backTarget];
		targetTextures[backTarget] = null;
		releaseStandInFrame();
		releaseTargets();
		standInFrame = lastCompleted;
	}

	// Frees the stand-in from releaseTargetsKeepingLastFrame(), if any -
	// called once a frame at the new size has completed.
	void releaseStandInFrame() {
		if (standInFrame != null) {
			device.deleteTexture(standInFrame);
			standInFrame = null;
		}
	}

	// Most rows a shader's state may span at the bottom of the frame -
	// see detectStateRows().
	static final int MAX_STATE_ROWS = 8;
	// Rows above MAX_STATE_ROWS read to gauge how much an ordinary image's
	// neighbouring rows differ, for comparison.
	private static final int STATE_ROW_BASELINE_ROWS = 6;
	private static final int STATE_ROW_MIN_PREFIX = 8;

	// How many rows, from the bottom up, of the stand-in frame look like
	// state storage rather than picture - 0 if none (or no stand-in).
	// Storage is typically wildly discontinuous with what's next to it
	// (packed positions/velocities rather than image), so row i counts
	// if it differs from row i + 1 far more than ordinary picture rows
	// differ from each other. State conventionally starts at x = 0 and
	// may only fill part of a row (the rest being picture), so this tests
	// growing prefixes of the row (8, 16, 32 ... pixels, then the whole
	// width) - comparing whole rows diluted a 128-pixel state across a
	// 360-pixel row below the threshold. One small glReadPixels, once per
	// resize.
	int detectStateRows() {
		GlTexture2D source = standInFrame;
		if (source == null) {
			return 0;
		}
		int width = source.getWidth();
		int rows = Math.min(source.getHeight(), MAX_STATE_ROWS + 1 + STATE_ROW_BASELINE_ROWS);
		if (width <= 0 || rows < 3) {
			return 0;
		}
		GlFramebuffer framebuffer = device.createFramebuffer();
		device.attachColor(framebuffer, source);
		int[] pixels = new int[width * rows];
		device.readPixels(0, 0, width, rows, IntBuffer.wrap(pixels));
		device.bindFramebuffer(null);
		device.deleteFramebuffer(framebuffer);

		// prefixDiff[i][x] = summed per-channel difference between rows i
		// and i + 1 over pixels [0, x].
		long[][] prefixDiff = new long[rows - 1][width];
		for (int i = 0; i < rows - 1; ++i) {
			long sum = 0;
			int a = i * width;
			int b = a + width;
			for (int x = 0; x < width; ++x) {
				int p = pixels[a + x];
				int q = pixels[b + x];
				for (int shift = 0; shift < 32; shift += 8) {
					sum += Math.abs(((p >>> shift) & 0xff) - ((q >>> shift) & 0xff));
				}
				prefixDiff[i][x] = sum;
			}
		}
		// Rows above any state, for "how different are ordinary rows".
		int baselineStart = Math.min(MAX_STATE_ROWS + 1, rows - 2);
		int stateRows = 0;
		for (int i = 0; i < Math.min(MAX_STATE_ROWS, baselineStart); ++i) {
			if (!rowLooksLikeState(prefixDiff, i, baselineStart, width)) {
				break;
			}
			stateRows = i + 1;
		}
		return stateRows;
	}

	private static boolean rowLooksLikeState(
			@NonNull long[][] prefixDiff, int row, int baselineStart, int width) {
		for (int prefix = Math.min(STATE_ROW_MIN_PREFIX, width); ; prefix = Math.min(prefix * 2, width)) {
			double mean = prefixDiff[row][prefix - 1] / (4.0 * prefix);
			double[] baseline = new double[prefixDiff.length - baselineStart];
			for (int k = 0; k < baseline.length; ++k) {
				baseline[k] = prefixDiff[baselineStart + k][prefix - 1] / (4.0 * prefix);
			}
			java.util.Arrays.sort(baseline);
			double typical = baseline[baseline.length / 2];
			if (mean > typical * 3 + 6) {
				return true;
			}
			if (prefix == width) {
				return false;
			}
		}
	}

	// Copies the stand-in frame's bottom `rows` rows into both freshly
	// created targets as exact pixel values (raw GPU copies, no scaling
	// or filtering), treating them as one long line of pixels - wrapped
	// at the old width, re-laid wrapped at the new one - the usual way a
	// shader lays out more state than fits in one row. So a shader keeping
	// state there carries on across a resolution change instead of
	// restarting from black, even when the change moves where its state
	// wraps. Anything that lands beyond the shader's actual state is
	// just picture it redraws next frame.
	void migrateStateRows(int rows) {
		GlTexture2D source = standInFrame;
		if (source == null || rows <= 0 || !hasTargets()) {
			return;
		}
		int oldWidth = source.getWidth();
		int newWidth = targetTextures[0].getWidth();
		int newHeight = targetTextures[0].getHeight();
		rows = Math.min(rows, source.getHeight());
		long total = Math.min((long) rows * oldWidth, (long) newHeight * newWidth);
		GlFramebuffer framebuffer = device.createFramebuffer();
		device.attachColor(framebuffer, source);
		device.bindFramebuffer(framebuffer);
		for (GlTexture2D target : targetTextures) {
			device.bindTexture(0, target);
			// Walk the linear range in runs that stay within one source
			// row and one destination row.
			for (long i = 0; i < total; ) {
				int srcY = (int) (i / oldWidth);
				int srcX = (int) (i % oldWidth);
				int dstY = (int) (i / newWidth);
				int dstX = (int) (i % newWidth);
				int run = (int) Math.min(total - i,
						Math.min(oldWidth - srcX, newWidth - dstX));
				GLES20.glCopyTexSubImage2D(GLES20.GL_TEXTURE_2D, 0,
						dstX, dstY, srcX, srcY, run, 1);
				i += run;
			}
			device.generateMipmap(target);
		}
		device.bindFramebuffer(null);
		device.deleteFramebuffer(framebuffer);
	}

	boolean hasTargets() {
		return framebuffers[0] != null && targetTextures[0] != null;
	}

	// Clears both ping-pong render targets to black, so a "restart"
	// action can discard whatever a feedback shader had accumulated
	// into the backbuffer, not just reset the time-based uniforms.
	void clearTargets() {
		for (GlFramebuffer framebuffer : framebuffers) {
			if (framebuffer == null) {
				continue;
			}
			device.bindFramebuffer(framebuffer);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
		}
		device.bindFramebuffer(null);
	}

	@NonNull
	List<ShaderError> ensureTargets(
			@NonNull Context context,
			int width,
			int height,
			@NonNull BackBufferParameters parameters) {
		ArrayList<ShaderError> errors = new ArrayList<>();
		if (hasTargets()) {
			return errors;
		}

		releaseTargets();
		createTarget(context, frontTarget, width, height, parameters, errors);
		createTarget(context, backTarget, width, height, parameters, errors);
		device.bindFramebuffer(null);
		return errors;
	}

	void renderMainPass(
			@NonNull ProgramBindings bindings,
			@NonNull GlProgram program) {
		GlFramebuffer framebuffer = framebuffers[frontTarget];
		GlTexture2D targetTexture = targetTextures[frontTarget];
		if (framebuffer == null || targetTexture == null) {
			return;
		}

		device.bindFramebuffer(framebuffer);
		device.setViewport(0, 0, targetTexture.getWidth(), targetTexture.getHeight());
		device.applyBindings(bindings);
		device.draw(fullScreenQuadMesh, program);
	}

	// Progressive live rendering (see ShaderRenderer.drawProgressiveStep()):
	// renders just rows [y0, y0 + rows) of the front target, with the
	// viewport still covering the whole target - so gl_FragCoord and
	// every derivative are exactly what a full-frame pass would give,
	// and a scissor rectangle alone limits which pixels get written. The
	// back target (the previous *completed* frame - what `backbuffer`
	// samples) isn't touched until the whole frame is done and
	// swapTargets() runs, so feedback shaders see exactly what they
	// would in a single full-frame pass.
	void renderMainPassRows(
			@NonNull ProgramBindings bindings,
			@NonNull GlProgram program,
			int y0,
			int rows) {
		GlFramebuffer framebuffer = framebuffers[frontTarget];
		GlTexture2D targetTexture = targetTextures[frontTarget];
		if (framebuffer == null || targetTexture == null) {
			return;
		}
		renderPassRows(bindings, program, framebuffer,
				targetTexture.getWidth(), targetTexture.getHeight(), y0, rows);
	}

	// The same for any full-screen pass and target - an offscreen buffer
	// pass of a multipass shader (see BufferPass), whole or in bands.
	void renderPassRows(
			@NonNull ProgramBindings bindings,
			@NonNull GlProgram program,
			@NonNull GlFramebuffer framebuffer,
			int width,
			int height,
			int y0,
			int rows) {
		device.bindFramebuffer(framebuffer);
		device.setViewport(0, 0, width, height);
		boolean partial = y0 > 0 || rows < height;
		if (partial) {
			device.setScissor(0, y0, width, rows);
		}
		device.applyBindings(bindings);
		device.draw(fullScreenQuadMesh, program);
		if (partial) {
			device.disableScissor();
		}
	}

	// Width of the live render targets, or 0 if there aren't any yet.
	int getTargetWidth() {
		GlTexture2D texture = targetTextures[frontTarget];
		return texture != null ? texture.getWidth() : 0;
	}

	// Height of the live render targets, or 0 if there aren't any yet.
	int getTargetHeight() {
		GlTexture2D texture = targetTextures[frontTarget];
		return texture != null ? texture.getHeight() : 0;
	}

	// Shows the most recently *completed* frame - the back target, after
	// a swap - rather than the front one, which in progressive mode may
	// only be partly rendered.
	void renderCompletedSurfacePass(
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			int surfaceWidth,
			int surfaceHeight) {
		drawSurface(
				standInFrame != null ? standInFrame : targetTextures[backTarget],
				surfaceWidth,
				surfaceHeight,
				null,
				surfaceBindings,
				surfaceProgram,
				cropBorderPixel);
	}

	void renderSurfacePass(
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			int surfaceWidth,
			int surfaceHeight) {
		drawSurface(
				targetTextures[frontTarget],
				surfaceWidth,
				surfaceHeight,
				null,
				surfaceBindings,
				surfaceProgram,
				cropBorderPixel);
	}

	// While on, the on-screen (not thumbnail) surface pass samples one
	// texel in from each edge of the rendered frame instead of the very
	// edge, hiding state that shaders commonly stash in border pixels
	// when ping-ponging through the backbuffer.
	void setCropBorderPixel(boolean cropBorderPixel) {
		this.cropBorderPixel = cropBorderPixel;
	}

	@Nullable
	GlTexture2D getBackTexture() {
		return targetTextures[backTarget];
	}

	void swapTargets() {
		int target = frontTarget;
		frontTarget = backTarget;
		backTarget = target;
	}

	// fromBack: read the last completed frame (progressive mode, where the
	// front target may be mid-render) instead of the front target.
	@Nullable
	byte[] captureThumbnail(
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			boolean fromBack) {
		GlTexture2D source = targetTextures[fromBack ? backTarget : frontTarget];
		if (thumbnailFramebuffer == null || source == null) {
			return null;
		}

		drawSurface(
				source,
				(int) thumbnailResolution[0],
				(int) thumbnailResolution[1],
				thumbnailFramebuffer,
				surfaceBindings,
				surfaceProgram,
				false);

		byte[] png = readPixelsAsPng(THUMBNAIL_WIDTH, THUMBNAIL_HEIGHT);
		device.bindFramebuffer(null);
		return png;
	}

	// Reads back whatever's currently bound (caller's responsibility -
	// the on-screen framebuffer for a full-resolution screenshot, a
	// dedicated target for the thumbnail) and PNG-encodes it. GL's
	// bottom-up row order is flipped to the top-down order Bitmap/PNG
	// expect, and BGRA/RGBA channel order is swapped to Bitmap's ARGB.
	@Nullable
	byte[] readPixelsAsPng(int width, int height) {
		int[] argb = readPixelsFlippedToArgb(0, 0, width, height);
		try (var out = new ByteArrayOutputStream()) {
			Bitmap.createBitmap(
					argb,
					width,
					height,
					Bitmap.Config.ARGB_8888).compress(
					Bitmap.CompressFormat.PNG,
					100,
					out);
			return out.toByteArray();
		} catch (OutOfMemoryError | IllegalArgumentException | IOException e) {
			return null;
		}
	}

	// Reads back a sub-rectangle of whatever's currently bound, top-down
	// row order and ARGB channel order (same conversion readPixelsAsPng()
	// above uses, factored out for callers - like the tiled high-res
	// capture - that need to assemble several such reads, each possibly
	// a sub-rectangle of a larger render, into one bigger Bitmap rather
	// than PNG-encoding each one individually.
	@NonNull
	int[] readPixelsFlippedToArgb(int readX, int readY, int width, int height) {
		final int pixels = width * height;
		final int[] rgba = new int[pixels];
		final IntBuffer buffer = IntBuffer.wrap(rgba);
		device.readPixels(readX, readY, width, height, buffer);

		int[] argb = new int[pixels];
		for (int y = 0; y < height; ++y) {
			for (int x = 0; x < width; ++x) {
				int srcIdx = y * width + x;
				int destIdx = (height - y - 1) * width + x;
				int pixel = rgba[srcIdx];
				argb[destIdx] = 0xff000000
						| ((pixel << 16) & 0x00ff0000)
						| (pixel & 0x0000ff00)
						| ((pixel >> 16) & 0x000000ff);
			}
		}
		return argb;
	}

	private void drawSurface(
			@Nullable GlTexture2D sourceTexture,
			int drawWidth,
			int drawHeight,
			@Nullable GlFramebuffer targetFramebuffer,
			@NonNull ProgramBindings surfaceBindings,
			@NonNull GlProgram surfaceProgram,
			boolean cropBorderPixel) {
		if (sourceTexture == null) {
			return;
		}

		device.bindFramebuffer(targetFramebuffer);
		device.setViewport(0, 0, drawWidth, drawHeight);
		drawResolution[0] = drawWidth;
		drawResolution[1] = drawHeight;
		frameSize[0] = sourceTexture.getWidth();
		frameSize[1] = sourceTexture.getHeight();
		surfaceBindings.clear();
		surfaceBindings.setFloat2(
				ShaderRenderer.UNIFORM_RESOLUTION,
				drawResolution);
		surfaceBindings.setFloat2(SURFACE_FRAME_SIZE, frameSize);
		surfaceBindings.setFloat(SURFACE_CROP_PIXELS, cropBorderPixel ? 1f : 0f);
		surfaceBindings.setTexture(SURFACE_FRAME, sourceTexture);
		device.applyBindings(surfaceBindings);
		device.clear(GLES20.GL_COLOR_BUFFER_BIT);
		device.draw(fullScreenQuadMesh, surfaceProgram);
	}

	private void discardTargets() {
		framebuffers[0] = null;
		framebuffers[1] = null;
		targetTextures[0] = null;
		targetTextures[1] = null;
		frontTarget = 0;
		backTarget = 1;
	}

	private void createTarget(
			@NonNull Context context,
			int index,
			int width,
			int height,
			@NonNull BackBufferParameters parameters,
			@NonNull List<ShaderError> errors) {
		GlTexture2D texture = device.createTexture2D();
		targetTextures[index] = texture;

		Bitmap bitmap = parameters.getPresetBitmap(context, width, height);
		if (bitmap != null) {
			String message = device.uploadTexture2D(texture, bitmap, true);
			if (message != null) {
				errors.add(ShaderError.createGeneral(message));
			}
			bitmap.recycle();
		} else {
			device.allocateTexture2D(texture, width, height);
		}

		device.applyTextureParameters(texture, parameters);
		device.generateMipmap(texture);

		GlFramebuffer framebuffer = device.createFramebuffer();
		framebuffers[index] = framebuffer;
		device.attachColor(framebuffer, texture);
		int status = device.checkFramebufferStatus(framebuffer);
		if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
			errors.add(ShaderError.createGeneral(
					"Framebuffer incomplete: 0x" + Integer.toHexString(status)));
		}

		if (bitmap == null) {
			device.bindFramebuffer(framebuffer);
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
		}
	}
}
