package enk.danwills.android.enkishadereditor.opengl;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// One offscreen buffer pass (Buffer A-D) of a multipass shader: its
// program, and a ping-pong pair of render targets at the live render
// size. Each frame renders into the target that isn't current(), then
// swap() makes that the current one - so a pass reading itself (or any
// pass reading a buffer that runs after it) sees the previous frame,
// and one reading an earlier buffer sees this frame's, as on ShaderToy.
// Targets are RGBA16F where the GPU can render into that, so a buffer
// can hold real values (negative, > 1) rather than just colours - or,
// for a buffer whose source has a `///float32` line, RGBA32F where the
// GPU can render into and filter that (a simulation that half floats'
// 11 bits would stall, e.g. reaction-diffusion), falling back to RGBA16F.
// A buffer whose source has a `///stateRow:top` (or `bottom`, and
// optionally a count, `///stateRow:top,2`) line keeps those rows across
// a resize - a rotation or a quality change - where the rest restarts,
// as on ShaderToy: the state a simulation's interface keeps there. They're
// also read back now and then (saveStateRows()), so they come back when
// the GL context itself is gone and made again - a rotation recreates
// the activity - or the shader's opened again this app session.
final class BufferPass {
	private static final String TAG = "BufferPass";
	private static final Pattern FLOAT32 = Pattern.compile(
			"^[ \\t]*///float32[ \\t]*$", Pattern.MULTILINE);
	private static final Pattern STATE_ROW = Pattern.compile(
			"^[ \\t]*///stateRow:(top|bottom)(?:,([0-9]{1,2}))?[ \\t]*$", Pattern.MULTILINE);

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
	// What the targets hold: RGBA8, else half or (float32) full floats.
	private boolean floatTargets;
	private boolean float32;
	private long swaps;
	private final boolean wantsFloat32;
	// The last state rows read back from each pass, by pass and source -
	// see saveStateRows(). A few shaders' worth, for the app session.
	private record SavedRows(int width, int rows, @NonNull FloatBuffer pixels) {
	}

	private static final int SAVED_MAX = 16;
	private static final Map<String, SavedRows> saved = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, SavedRows> eldest) {
			return size() > SAVED_MAX;
		}
	};

	// See STATE_ROW: rows kept across a resize (0 none), and whether
	// they're the top ones.
	private final int stateRows;
	private final boolean stateRowsAtTop;
	// The last frame before a resize, kept until the new targets have
	// the state rows copied in - see keepForResize().
	@Nullable
	private GlFramebuffer keptFramebuffer;
	@Nullable
	private GlTexture2D keptTexture;

	BufferPass(int buffer, @NonNull String sourceText, @NonNull PreparedShaderSource prepared) {
		this.buffer = buffer;
		this.sourceText = sourceText;
		this.prepared = prepared;
		wantsFloat32 = FLOAT32.matcher(sourceText).find();
		Matcher stateRow = STATE_ROW.matcher(sourceText);
		if (stateRow.find()) {
			stateRows = stateRow.group(2) != null
					? Math.max(1, Integer.parseInt(stateRow.group(2)))
					: 1;
			stateRowsAtTop = stateRow.group(1).equals("top");
		} else {
			stateRows = 0;
			stateRowsAtTop = false;
		}
	}

	// A resize is coming (see ShaderRenderer.onSurfaceChanged()): the
	// targets go, but a pass with state rows keeps its last frame until
	// ensureTargets() has made the new ones and copied them in.
	void keepForResize(@NonNull GlDevice device) {
		releaseKept(device);
		if (stateRows > 0 && hasTargets()) {
			keptFramebuffer = framebuffers[current];
			keptTexture = textures[current];
			framebuffers[current] = null;
			textures[current] = null;
		}
		releaseTargets(device);
	}

	private String savedKey() {
		return buffer + ":" + sourceText.length() + ":" + sourceText.hashCode();
	}

	// Reads this pass's state rows back (as floats - ES 3 and float
	// targets only) for restoreSavedRows(), GL thread.
	void saveStateRows(@NonNull GlDevice device) {
		if (stateRows <= 0 || !hasTargets() || !floatTargets || textures[current] == null) {
			return;
		}
		int width = getWidth();
		int rows = Math.min(stateRows, getHeight());
		FloatBuffer pixels = ByteBuffer.allocateDirect(width * rows * 16)
				.order(ByteOrder.nativeOrder()).asFloatBuffer();
		while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
			// Earlier errors aren't this read's.
		}
		device.bindFramebuffer(framebuffers[current]);
		GLES20.glReadPixels(0, stateRowsAtTop ? getHeight() - rows : 0, width, rows,
				GLES20.GL_RGBA, GLES20.GL_FLOAT, pixels);
		if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
			saved.put(savedKey(), new SavedRows(width, rows, pixels));
		}
	}

	boolean hasStateRows() {
		return stateRows > 0;
	}

	// New targets with nothing kept from a resize: the state rows last
	// read back from this pass (the same source), if any, into both.
	private void restoreSavedRows(@NonNull GlDevice device) {
		SavedRows rows = saved.get(savedKey());
		if (rows == null || !floatTargets || textures[0] == null) {
			return;
		}
		int width = Math.min(rows.width(), textures[0].getWidth());
		int count = Math.min(rows.rows(), textures[0].getHeight());
		int y = stateRowsAtTop ? textures[0].getHeight() - count : 0;
		// Row by row: the saved rows are their own width apart.
		for (GlTexture2D texture : textures) {
			device.bindTexture(0, texture);
			for (int r = 0; r < count; ++r) {
				int from = (stateRowsAtTop ? rows.rows() - count + r : r) * rows.width() * 4;
				rows.pixels().position(from);
				GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, y + r, width, 1,
						GLES20.GL_RGBA, GLES20.GL_FLOAT, rows.pixels().slice());
			}
		}
		rows.pixels().position(0);
	}

	// Once the new targets are made: the kept frame's state rows into
	// both, as they are (a blit, so floats stay exact) - as much of each
	// row as fits; then the kept frame goes. Needs ES 3 (es3) and the
	// same kind of target - else the buffer just restarts. With nothing
	// kept, the rows last read back, if any (restoreSavedRows()).
	void copyKeptStateRows(@NonNull GlDevice device, boolean es3) {
		GlTexture2D kept = keptTexture;
		GlFramebuffer from = keptFramebuffer;
		if (stateRows > 0 && kept == null && es3 && hasTargets()) {
			restoreSavedRows(device);
		}
		if (kept != null && from != null && hasTargets() && es3) {
			int width = Math.min(kept.getWidth(), textures[0].getWidth());
			int rows = Math.min(stateRows, Math.min(kept.getHeight(), textures[0].getHeight()));
			int fromY = stateRowsAtTop ? kept.getHeight() - rows : 0;
			int toY = stateRowsAtTop ? textures[0].getHeight() - rows : 0;
			for (GlFramebuffer to : framebuffers) {
				device.blitFramebuffer(from, to, 0, fromY, 0, toY, width, rows);
			}
		}
		releaseKept(device);
	}

	private void releaseKept(@NonNull GlDevice device) {
		device.deleteFramebuffer(keptFramebuffer);
		keptFramebuffer = null;
		device.deleteTexture(keptTexture);
		keptTexture = null;
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

	// Half or full floats, rather than bytes.
	boolean isFloat() {
		return floatTargets;
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
		++swaps;
	}

	// How many steps it's completed - see PrimitivePresenter.
	long getSwaps() {
		return swaps;
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
		floatTargets = other.floatTargets;
		float32 = other.float32;
		other.current = 0;
	}

	// tryFloat32: the GPU can render into and filter RGBA32F - used only
	// if this pass asks for it.
	void ensureTargets(@NonNull GlDevice device, int width, int height, boolean tryHalfFloat,
			boolean tryFloat32, @NonNull List<ShaderError> errors) {
		if (hasTargets() || width <= 0 || height <= 0) {
			return;
		}
		releaseTargets(device);
		float32 = tryFloat32 && wantsFloat32;
		floatTargets = tryHalfFloat || float32;
		for (int i = 0; i < 2; ++i) {
			GlTexture2D texture = device.createTexture2D();
			GlFramebuffer framebuffer = device.createFramebuffer();
			textures[i] = texture;
			framebuffers[i] = framebuffer;
			if (floatTargets) {
				if (float32) {
					device.allocateTexture2DFloat(texture, width, height);
				} else {
					device.allocateTexture2DHalfFloat(texture, width, height);
				}
				device.applyTextureParameters(texture, new ChannelParameters());
				device.attachColor(framebuffer, texture);
				if (device.checkFramebufferStatus(framebuffer) !=
						GLES20.GL_FRAMEBUFFER_COMPLETE) {
					// Advertised but not actually renderable here - fall
					// back a step (full floats to half, half to 8 bits)
					// for both targets.
					releaseTargets(device);
					ensureTargets(device, width, height, float32 && tryHalfFloat, false,
							errors);
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
				(float32 ? " RGBA32F" : floatTargets ? " RGBA16F" : " RGBA8"));
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
		releaseKept(device);
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
