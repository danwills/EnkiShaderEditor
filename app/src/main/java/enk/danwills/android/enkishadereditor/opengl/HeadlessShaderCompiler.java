package enk.danwills.android.enkishadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

// Compiles shaders off-screen - no visible GLSurfaceView involved - so
// the whole library's program-binary cache can be warmed in bulk without
// disturbing whatever shader is currently on screen (see
// ProgramBinaryCache). Owns its own throwaway EGL display/context/pbuffer
// surface; EGL contexts are thread-affined, so every call into a given
// instance must happen on the same thread for the run's whole duration -
// callers should run compileAll() on a single dedicated background
// thread, never the GL thread a live ShaderView is using.
public final class HeadlessShaderCompiler {
	// A minimal view of a DB shader row - kept independent of
	// DataRecords.Shader so this package doesn't need to depend on the
	// database package; callers adapt their own records to this.
	public interface ShaderSource {
		long id();

		@NonNull
		String source();
	}

	public record Result(int compiled, int skipped, int failed, int total) {
	}

	// One shader's passes for warm(), as the renderer will be given them.
	public record Passes(long id, @NonNull String source, @Nullable String[] buffers) {
	}

	// Matches ShaderRenderer.MAX_TEXTURES - how many samplers
	// ShaderSourcePreparer will discover before it stops looking.
	private static final int MAX_TEXTURES = 32;

	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	public void cancel() {
		cancelled.set(true);
	}

	// Blocks for the whole run. onProgress is invoked (on the calling
	// thread) after every shader, compiled or not, with the number done
	// so far. forceRecompile: skip the "already cached" check entirely
	// and recompile every shader regardless - a developer/debugging
	// option (e.g. after a change to ShaderSourcePreparer that doesn't
	// show up in the source hash) rather than something most users need.
	@NonNull
	public Result compileAll(
			@NonNull Iterable<? extends ShaderSource> shaders,
			int total,
			@NonNull ProgramBinaryCache cache,
			boolean forceRecompile,
			@NonNull IntConsumer onProgress) {
		HeadlessEglSession egl = HeadlessEglSession.create();
		if (egl == null) {
			// No usable EGL context at all - nothing to warm, but not
			// really a "failure" of any individual shader either.
			return new Result(0, 0, 0, total);
		}
		try {
			GlDevice device = new GlDevice(MAX_TEXTURES);
			String driverSignature = ProgramBinaryKey.driverSignature();
			int compiled = 0;
			int skipped = 0;
			int failed = 0;
			int done = 0;
			for (ShaderSource shader : shaders) {
				if (cancelled.get()) {
					break;
				}
				String sourceHash = ProgramBinaryKey.sourceHash(shader.source(), egl.version);
				if (!forceRecompile &&
						cache.get(shader.id(), sourceHash, driverSignature) != null) {
					++skipped;
				} else if (compileOne(device, shader.id(), shader.source(), egl.version,
						sourceHash, driverSignature, cache)) {
					++compiled;
				} else {
					++failed;
				}
				++done;
				onProgress.accept(done);
			}
			return new Result(compiled, skipped, failed, total);
		} finally {
			egl.destroy();
		}
	}

	// Every pass of each shader in turn - the Image pass and its buffer
	// passes - into `cache`, skipping what it already holds: e.g. a
	// show's next shaders, so each loads from a binary when its turn
	// comes. Blocks; one background thread, as compileAll().
	public void warm(@NonNull List<Passes> shaders, @NonNull ProgramBinaryCache cache) {
		HeadlessEglSession egl = HeadlessEglSession.create();
		if (egl == null) {
			return;
		}
		try {
			GlDevice device = new GlDevice(MAX_TEXTURES);
			String driverSignature = ProgramBinaryKey.driverSignature();
			for (Passes shader : shaders) {
				if (cancelled.get()) {
					return;
				}
				String sourceHash = ProgramBinaryKey.sourceHash(shader.source(), egl.version);
				if (cache.get(shader.id(), sourceHash, driverSignature) == null) {
					compileOne(device, shader.id(), shader.source(), egl.version, sourceHash,
							driverSignature, cache);
				}
				String[] buffers = shader.buffers();
				for (int buffer = 0; buffers != null && buffer < buffers.length; ++buffer) {
					String text = buffers[buffer];
					if (text == null || cancelled.get()) {
						continue;
					}
					String bufferHash = ProgramBinaryKey.sourceHash(text, egl.version);
					if (cache.getBuffer(shader.id(), buffer, bufferHash, driverSignature) ==
							null) {
						ProgramBinary binary = compile(device, text, egl.version);
						if (binary != null) {
							cache.putBuffer(shader.id(), buffer, binary, bufferHash,
									driverSignature);
						}
					}
				}
			}
		} finally {
			egl.destroy();
		}
	}

	// A pass's program binary, as the renderer would build it, or null.
	@Nullable
	private static ProgramBinary compile(@NonNull GlDevice device, @NonNull String source,
			int version) {
		var prepared = ShaderSourcePreparer.prepare(source, version, MAX_TEXTURES);
		var fragment = prepared.getFragmentShader();
		if (fragment == null) {
			return null;
		}
		var result = device.createProgram(
				prepared.getVertexShader(
						RendererProgramManager.FULL_SCREEN_VERTEX_SHADER,
						RendererProgramManager.FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragment.getSource(),
				fragment.getLineMapping());
		if (!result.succeeded() || result.getProgram() == null) {
			return null;
		}
		try {
			return device.getProgramBinary(result.getProgram());
		} finally {
			device.deleteProgram(result.getProgram());
		}
	}

	private boolean compileOne(@NonNull GlDevice device, long shaderId,
			@NonNull String source, int version,
			@NonNull String sourceHash, @NonNull String driverSignature,
			@NonNull ProgramBinaryCache cache) {
		var prepared = ShaderSourcePreparer.prepare(source, version, MAX_TEXTURES);
		var fragment = prepared.getFragmentShader();
		if (fragment == null) {
			return false;
		}
		long compileStart = System.nanoTime();
		var result = device.createProgram(
				prepared.getVertexShader(
						RendererProgramManager.FULL_SCREEN_VERTEX_SHADER,
						RendererProgramManager.FULL_SCREEN_VERTEX_SHADER_3,
						version),
				fragment.getSource(),
				fragment.getLineMapping());
		long compileTimeMs = (System.nanoTime() - compileStart) / 1_000_000;
		if (!result.succeeded() || result.getProgram() == null) {
			return false;
		}
		try {
			ProgramBinary binary = device.getProgramBinary(result.getProgram());
			if (binary == null) {
				return false;
			}
			cache.put(shaderId, binary, sourceHash, driverSignature, compileTimeMs);
			return true;
		} finally {
			device.deleteProgram(result.getProgram());
		}
	}
}
