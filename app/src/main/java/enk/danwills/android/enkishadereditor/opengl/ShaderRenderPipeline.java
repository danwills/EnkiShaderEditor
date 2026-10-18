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
		int width = targetTexture.getWidth();
		int height = targetTexture.getHeight();
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
				targetTextures[backTarget],
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
