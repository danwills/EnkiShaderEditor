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
// size (or its own - see SIZE). Each frame renders into the target that isn't current(), then
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
// a resize or a rotation, where the rest restarts, as on ShaderToy: the
// state a simulation's interface keeps there. With a `///resize:tile`
// line too, the rest is kept as well: turned with the screen when it's
// rotated (so what was at the top of the phone stays there), and tiled
// over the new size from the bottom left (so a simulation that wraps at
// its edges carries on, its copies meeting seamlessly). A pass can have
// a size of its own, whatever the quality setting (a texture atlas or
// table built at full detail while the picture's drawn coarser): a
// `///size:WxH` line, a fixed size; `///scale:F`, F times the screen's;
// `///minScale:F`, the render's but at least F times the screen's. Its
// targets are then only remade when that size changes, and the pass sees
// it as `resolution`/`iResolution`. Either way it's read back as floats (ES 3,
// float targets) just before a resize (keepForResize()) and when the
// app pauses (ShaderRenderer.saveBuffers()) - a rotation recreates the
// activity and its GL context - and put into the next targets made for
// the same pass source, once.
final class BufferPass {
	private static final String TAG = "BufferPass";
	private static final Pattern FLOAT32 = Pattern.compile(
			"^[ \\t]*///float32[ \\t]*$", Pattern.MULTILINE);
	private static final Pattern RESIZE_TILE = Pattern.compile(
			"^[ \\t]*///resize:tile[ \\t]*$", Pattern.MULTILINE);
	private static final Pattern SIZE = Pattern.compile(
			"^[ \\t]*///size:([0-9]{1,5})x([0-9]{1,5})[ \\t]*$", Pattern.MULTILINE);
	private static final Pattern SCALE = Pattern.compile(
			"^[ \\t]*///(scale|minScale):([0-9]*\\.?[0-9]+)[ \\t]*$", Pattern.MULTILINE);
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
	// What saveForLater() read back, by pass and source, until the next
	// targets made for it take it (restoreSaved()): its size, and its
	// pixels, RGBA floats bottom row first - and when, as it's only for
	// targets made again soon after (SAVED_NANOS): a pause the app comes
	// back from, its context kept, shouldn't put an old frame into the
	// shader opened next time.
	private record Saved(int width, int height, @NonNull FloatBuffer pixels, long nanos,
			int rotation) {
	}

	private static final long SAVED_NANOS = 10_000_000_000L;

	private static final int SAVED_MAX = 8;
	private static final Map<String, Saved> saved = new LinkedHashMap<>(16, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Saved> eldest) {
			return size() > SAVED_MAX;
		}
	};

	// See STATE_ROW: rows kept across a resize (0 none), and whether
	// they're the top ones; and RESIZE_TILE.
	private final int stateRows;
	private final boolean stateRowsAtTop;
	private final boolean resizeTile;
	// See SIZE and SCALE: 0 if it hasn't one.
	private final int fixedWidth;
	private final int fixedHeight;
	private final float scale;
	private final float minScale;

	BufferPass(int buffer, @NonNull String sourceText, @NonNull PreparedShaderSource prepared) {
		this.buffer = buffer;
		this.sourceText = sourceText;
		this.prepared = prepared;
		wantsFloat32 = FLOAT32.matcher(sourceText).find();
		resizeTile = RESIZE_TILE.matcher(sourceText).find();
		Matcher size = SIZE.matcher(sourceText);
		boolean sized = size.find();
		fixedWidth = sized ? Integer.parseInt(size.group(1)) : 0;
		fixedHeight = sized ? Integer.parseInt(size.group(2)) : 0;
		Matcher scaled = SCALE.matcher(sourceText);
		boolean hasScale = !sized && scaled.find();
		float factor = hasScale ? Float.parseFloat(scaled.group(2)) : 0f;
		scale = hasScale && scaled.group(1).equals("scale") ? factor : 0f;
		minScale = hasScale && scaled.group(1).equals("minScale") ? factor : 0f;
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

	// A resize is coming (see ShaderRenderer.onSurfaceChanged()): what's
	// kept is read back for the new targets, then the targets go.
	// rotation: the display's (Surface.ROTATION_*) - see saveForLater().
	void keepForResize(@NonNull GlDevice device, boolean es3, int rotation) {
		if (hasOwnSize()) {
			// (Its own size: ShaderRenderer.ensureBufferTargets() remakes it
			// if that's changed too, else it carries on as it is.)
			return;
		}
		if (es3) {
			saveForLater(device, rotation);
		}
		releaseTargets(device);
	}

	private String savedKey() {
		return buffer + ":" + sourceText.length() + ":" + sourceText.hashCode();
	}

	boolean hasOwnSize() {
		return fixedWidth > 0 || scale > 0f || minScale > 0f;
	}

	// The size a side of its targets should be, with the render's
	// renderSize and the screen's screenSize: see SIZE and SCALE (at most
	// maxSize, at least 1), else the render's.
	int targetWidth(int renderSize, int screenSize, int maxSize) {
		return targetSide(fixedWidth, renderSize, screenSize, maxSize);
	}

	int targetHeight(int renderSize, int screenSize, int maxSize) {
		return targetSide(fixedHeight, renderSize, screenSize, maxSize);
	}

	private int targetSide(int fixed, int renderSize, int screenSize, int maxSize) {
		int side = fixed > 0 ? fixed
				: scale > 0f ? Math.round(screenSize * scale)
				: minScale > 0f ? Math.max(renderSize, Math.round(screenSize * minScale))
				: renderSize;
		return side == renderSize ? side : Math.max(1, Math.min(side, maxSize));
	}

	boolean keepsState() {
		return stateRows > 0;
	}

	// Reads this pass's last frame back (all of it with RESIZE_TILE, else
	// just its state rows) for the next targets made for it - ES 3 and
	// float targets only. GL thread. rotation: the display's now
	// (Surface.ROTATION_*), so restoreSaved() can turn it to match.
	void saveForLater(@NonNull GlDevice device, int rotation) {
		if (stateRows <= 0 || !hasTargets() || !floatTargets || textures[current] == null) {
			return;
		}
		int width = getWidth();
		int height = getHeight();
		int rows = resizeTile ? height : Math.min(stateRows, height);
		int y = stateRowsAtTop ? height - rows : 0;
		FloatBuffer pixels = ByteBuffer.allocateDirect(width * rows * 16)
				.order(ByteOrder.nativeOrder()).asFloatBuffer();
		while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
			// Earlier errors aren't this read's.
		}
		device.bindFramebuffer(framebuffers[current]);
		GLES20.glReadPixels(0, y, width, rows, GLES20.GL_RGBA, GLES20.GL_FLOAT, pixels);
		if (GLES20.glGetError() == GLES20.GL_NO_ERROR) {
			// As if all `height` rows, the state rows at their own end.
			saved.put(savedKey(), new Saved(width, resizeTile ? height : rows, pixels,
					System.nanoTime(), rotation));
		}
	}

	// New targets: what saveForLater() read back for this pass (the same
	// source), if anything, into both - the state rows at their end, and
	// with RESIZE_TILE the rest turned by however far the display's
	// rotation (Surface.ROTATION_*) has gone since, and tiled over the
	// rest - then forgotten.
	void restoreSaved(@NonNull GlDevice device, boolean es3, int rotation) {
		Saved old = saved.remove(savedKey());
		if (old == null || System.nanoTime() - old.nanos() > SAVED_NANOS ||
				!es3 || !floatTargets || !hasTargets() || textures[0] == null) {
			return;
		}
		int width = textures[0].getWidth();
		int height = textures[0].getHeight();
		int keep = Math.min(stateRows, Math.min(height, old.height()));
		FloatBuffer from = old.pixels();
		if (resizeTile && old.height() > stateRows) {
			int oldRest = old.height() - stateRows;
			int oldWidth = old.width();
			// Quarter turns anticlockwise the display's gone: the grid turns
			// as much, so it stays put on the phone.
			int turns = Math.floorMod(rotation - old.rotation(), 4);
			// Bottom row first, as GL has them; the rest's rows start at 0
			// when the state rows are at the top, at stateRows if not.
			int oldBase = stateRowsAtTop ? 0 : stateRows;
			int base = stateRowsAtTop ? 0 : keep;
			float[] row = new float[width * 4];
			float[] source = new float[old.width() * old.height() * 4];
			from.position(0);
			from.get(source);
			FloatBuffer out = ByteBuffer.allocateDirect(width * height * 16)
					.order(ByteOrder.nativeOrder()).asFloatBuffer();
			for (int y = 0; y < height; ++y) {
				int sy;
				boolean state = stateRowsAtTop ? y >= height - keep : y < keep;
				if (state) {
					sy = stateRowsAtTop ? old.height() - (height - y) : y;
				} else {
					sy = 0;
				}
				int gy = y - base;
				for (int x = 0; x < width; ++x) {
					int sx;
					if (state) {
						sx = Math.min(x, oldWidth - 1);
					} else {
						// Where (x, gy) of the new grid was in the old one.
						int ox;
						int oy;
						switch (turns) {
							case 1 -> {
								ox = gy;
								oy = oldRest - 1 - x;
							}
							case 2 -> {
								ox = oldWidth - 1 - x;
								oy = oldRest - 1 - gy;
							}
							case 3 -> {
								ox = oldWidth - 1 - gy;
								oy = x;
							}
							default -> {
								ox = x;
								oy = gy;
							}
						}
						sx = Math.floorMod(ox, oldWidth);
						sy = oldBase + Math.floorMod(oy, oldRest);
					}
					boolean beyond = state && x >= oldWidth;
					for (int c = 0; c < 4; ++c) {
						row[x * 4 + c] = beyond ? (c == 3 ? 1f : 0f)
								: source[(sy * oldWidth + sx) * 4 + c];
					}
				}
				out.put(row);
			}
			out.position(0);
			for (GlTexture2D texture : textures) {
				device.bindTexture(0, texture);
				GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height,
						GLES20.GL_RGBA, GLES20.GL_FLOAT, out);
				out.position(0);
			}
			return;
		}
		// Just the state rows, as much of each as fits.
		int copyWidth = Math.min(width, old.width());
		int y = stateRowsAtTop ? height - keep : 0;
		for (GlTexture2D texture : textures) {
			device.bindTexture(0, texture);
			for (int r = 0; r < keep; ++r) {
				int sy = stateRowsAtTop ? old.height() - keep + r : r;
				from.position(sy * old.width() * 4);
				GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, y + r, copyWidth, 1,
						GLES20.GL_RGBA, GLES20.GL_FLOAT, from.slice());
			}
		}
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
