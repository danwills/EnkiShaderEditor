package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

// One offscreen buffer pass (Buffer A-D) of a multipass shader: its
// program, and a ping-pong pair of render targets at the live render
// size. Each frame renders into the target that isn't current(), then
// swap() makes that the current one - so a pass reading itself (or any
// pass reading a buffer that runs after it) sees the previous frame,
// and one reading an earlier buffer sees this frame's, as on ShaderToy.
// Targets are RGBA16F where the GPU can render into that, so a buffer
// can hold real values (negative, > 1) rather than just colours.
final class BufferPass {
	private static final String TAG = "BufferPass";

	final int buffer;
	@NonNull
	final String sourceText;
	@NonNull
	final PreparedShaderSource prepared;
	@Nullable
	private GlProgram program;
	private final GlFramebuffer[] framebuffers = new GlFramebuffer[2];
	private final GlTexture2D[] textures = new GlTexture2D[2];
	private int current;
	@Nullable
	private SourceText sourceTextTexture;
	@Nullable
	private String textSource;
	private boolean halfFloat;

	BufferPass(int buffer, @NonNull String sourceText, @NonNull PreparedShaderSource prepared) {
		this.buffer = buffer;
		this.sourceText = sourceText;
		this.prepared = prepared;
	}

	// What this pass's `sourceText` shows: its own source, or the pass
	// its `///text:` line names - see TextDirective.
	void setTextSource(@Nullable String textSource) {
		this.textSource = textSource;
	}

	// This pass's `sourceText`, made the first time it's drawn with a
	// program that uses it - GL thread only.
	@Nullable
	SourceText getSourceText(@NonNull Context context, @NonNull GlDevice device, int tabWidth) {
		if (sourceTextTexture == null && program != null) {
			sourceTextTexture = SourceText.createFor(context, device, program,
					textSource != null ? textSource : sourceText,
					tabWidth);
		}
		return sourceTextTexture;
	}

	@Nullable
	GlProgram getProgram() {
		return program;
	}

	void setProgram(@Nullable GlProgram program) {
		this.program = program;
	}

	boolean hasTargets() {
		return framebuffers[0] != null && framebuffers[1] != null;
	}

	// The most recently completed frame - what channels reading this
	// buffer sample.
	@Nullable
	GlTexture2D getCurrentTexture() {
		return textures[current];
	}

	// The framebuffer holding getCurrentTexture(), for reading it back.
	@Nullable
	GlFramebuffer getCurrentFramebuffer() {
		return framebuffers[current];
	}

	boolean isHalfFloat() {
		return halfFloat;
	}

	@Nullable
	GlFramebuffer getRenderFramebuffer() {
		return framebuffers[1 - current];
	}

	int getWidth() {
		return textures[current] != null ? textures[current].getWidth() : 0;
	}

	int getHeight() {
		return textures[current] != null ? textures[current].getHeight() : 0;
	}

	void swap() {
		current = 1 - current;
	}

	// Takes over `other`'s render targets (and what's in them), leaving
	// it with none - see RendererProgramManager.adoptBufferTargets().
	void adoptTargets(@NonNull BufferPass other) {
		for (int i = 0; i < 2; ++i) {
			framebuffers[i] = other.framebuffers[i];
			textures[i] = other.textures[i];
			other.framebuffers[i] = null;
			other.textures[i] = null;
		}
		current = other.current;
		halfFloat = other.halfFloat;
		other.current = 0;
	}

	void ensureTargets(@NonNull GlDevice device, int width, int height, boolean tryHalfFloat,
			@NonNull List<ShaderError> errors) {
		if (hasTargets() || width <= 0 || height <= 0) {
			return;
		}
		releaseTargets(device);
		halfFloat = tryHalfFloat;
		for (int i = 0; i < 2; ++i) {
			GlTexture2D texture = device.createTexture2D();
			GlFramebuffer framebuffer = device.createFramebuffer();
			textures[i] = texture;
			framebuffers[i] = framebuffer;
			if (halfFloat) {
				device.allocateTexture2DHalfFloat(texture, width, height);
				device.applyTextureParameters(texture, new ChannelParameters());
				device.attachColor(framebuffer, texture);
				if (device.checkFramebufferStatus(framebuffer) !=
						GLES20.GL_FRAMEBUFFER_COMPLETE) {
					// Advertised but not actually renderable here - fall
					// back to 8 bits for both targets.
					halfFloat = false;
					releaseTargets(device);
					ensureTargets(device, width, height, false, errors);
					return;
				}
			} else {
				device.allocateTexture2D(texture, width, height);
				device.applyTextureParameters(texture, new ChannelParameters());
				device.attachColor(framebuffer, texture);
				int status = device.checkFramebufferStatus(framebuffer);
				if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
					errors.add(ShaderError.createGeneral(
							"Framebuffer incomplete: 0x" + Integer.toHexString(status))
							.withPass(buffer));
				}
			}
		}
		Log.d(TAG, "Buffer " + (char) ('A' + buffer) + ": " + width + "x" + height +
				(halfFloat ? " RGBA16F" : " RGBA8"));
		clearTargets(device);
		current = 0;
	}

	void clearTargets(@NonNull GlDevice device) {
		for (GlFramebuffer framebuffer : framebuffers) {
			if (framebuffer != null) {
				device.bindFramebuffer(framebuffer);
				device.clear(GLES20.GL_COLOR_BUFFER_BIT);
			}
		}
		device.bindFramebuffer(null);
	}

	void releaseTargets(@NonNull GlDevice device) {
		for (int i = 0; i < 2; ++i) {
			device.deleteFramebuffer(framebuffers[i]);
			framebuffers[i] = null;
			device.deleteTexture(textures[i]);
			textures[i] = null;
		}
		current = 0;
	}

	// Deletes this pass's program and `sourceText`, but not its render
	// targets.
	void releaseProgram(@NonNull GlDevice device) {
		device.deleteProgram(program);
		program = null;
		if (sourceTextTexture != null) {
			sourceTextTexture.release(device);
			sourceTextTexture = null;
		}
	}

	// Deletes everything GL this pass owns, on a context that's still
	// alive.
	void release(@NonNull GlDevice device) {
		releaseTargets(device);
		if (sourceTextTexture != null) {
			sourceTextTexture.release(device);
			sourceTextTexture = null;
		}
		device.deleteProgram(program);
		program = null;
	}

	// The context these belonged to is already gone - just drop them.
	void discard() {
		for (int i = 0; i < 2; ++i) {
			framebuffers[i] = null;
			textures[i] = null;
		}
		program = null;
		sourceTextTexture = null;
		current = 0;
	}
}
