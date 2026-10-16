package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
		EglSession egl = EglSession.create();
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

	// A throwaway 1x1 pbuffer-backed EGL context, negotiated ES3-first
	// with an ES2 fallback exactly like ShaderView's own context
	// factory - matching that negotiation matters, since a binary
	// compiled under ES2 isn't what the live ES3 renderer (or vice
	// versa) will be looking for in the cache.
	private static final class EglSession {
		private final EGLDisplay display;
		private final EGLSurface surface;
		private final EGLContext context;
		final int version;

		private EglSession(@NonNull EGLDisplay display, @NonNull EGLSurface surface,
				@NonNull EGLContext context, int version) {
			this.display = display;
			this.surface = surface;
			this.context = context;
			this.version = version;
		}

		@Nullable
		static EglSession create() {
			EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
			if (display == null || display == EGL14.EGL_NO_DISPLAY) {
				return null;
			}
			int[] versionOut = new int[2];
			if (!EGL14.eglInitialize(display, versionOut, 0, versionOut, 1)) {
				return null;
			}
			EglSession session = tryCreate(display, 3);
			if (session == null) {
				session = tryCreate(display, 2);
			}
			if (session == null) {
				EGL14.eglTerminate(display);
			}
			return session;
		}

		@Nullable
		private static EglSession tryCreate(@NonNull EGLDisplay display, int version) {
			int renderableType = version == 3
					? EGLExt.EGL_OPENGL_ES3_BIT_KHR
					: EGL14.EGL_OPENGL_ES2_BIT;
			int[] configAttribs = {
					EGL14.EGL_RENDERABLE_TYPE, renderableType,
					EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
					EGL14.EGL_RED_SIZE, 8,
					EGL14.EGL_GREEN_SIZE, 8,
					EGL14.EGL_BLUE_SIZE, 8,
					EGL14.EGL_ALPHA_SIZE, 8,
					EGL14.EGL_NONE
			};
			EGLConfig[] configs = new EGLConfig[1];
			int[] numConfigs = new int[1];
			if (!EGL14.eglChooseConfig(
					display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
					numConfigs[0] <= 0 || configs[0] == null) {
				return null;
			}
			EGLConfig config = configs[0];

			int[] contextAttribs = {
					EGL14.EGL_CONTEXT_CLIENT_VERSION, version,
					EGL14.EGL_NONE
			};
			EGLContext context = EGL14.eglCreateContext(
					display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
			if (context == null || context == EGL14.EGL_NO_CONTEXT) {
				return null;
			}

			int[] surfaceAttribs = {
					EGL14.EGL_WIDTH, 1,
					EGL14.EGL_HEIGHT, 1,
					EGL14.EGL_NONE
			};
			EGLSurface surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0);
			if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
				EGL14.eglDestroyContext(display, context);
				return null;
			}

			if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
				EGL14.eglDestroySurface(display, surface);
				EGL14.eglDestroyContext(display, context);
				return null;
			}

			return new EglSession(display, surface, context, version);
		}

		void destroy() {
			EGL14.eglMakeCurrent(display,
					EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
			EGL14.eglDestroySurface(display, surface);
			EGL14.eglDestroyContext(display, context);
			EGL14.eglTerminate(display);
		}
	}
}
