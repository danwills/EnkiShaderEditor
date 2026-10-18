package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// A throwaway 1x1 pbuffer-backed EGL context, negotiated ES3-first with an
// ES2 fallback exactly like ShaderView's own context factory - no visible
// GLSurfaceView involved, so this works equally on the main process (see
// HeadlessShaderCompiler, which warms the program-binary cache) or an
// isolated :probe process (see TileSafetyProbe) with nothing else GL-
// related around to share with. EGL contexts are thread-affined, so every
// call against a given instance must happen on the same thread for the
// session's whole duration.
final class HeadlessEglSession {
	private final EGLDisplay display;
	private final EGLSurface surface;
	private final EGLContext context;
	final int version;

	private HeadlessEglSession(@NonNull EGLDisplay display, @NonNull EGLSurface surface,
			@NonNull EGLContext context, int version) {
		this.display = display;
		this.surface = surface;
		this.context = context;
		this.version = version;
	}

	@Nullable
	static HeadlessEglSession create() {
		EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
		if (display == null || display == EGL14.EGL_NO_DISPLAY) {
			return null;
		}
		int[] versionOut = new int[2];
		if (!EGL14.eglInitialize(display, versionOut, 0, versionOut, 1)) {
			return null;
		}
		HeadlessEglSession session = tryCreate(display, 3);
		if (session == null) {
			session = tryCreate(display, 2);
		}
		if (session == null) {
			EGL14.eglTerminate(display);
		}
		return session;
	}

	@Nullable
	private static HeadlessEglSession tryCreate(@NonNull EGLDisplay display, int version) {
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

		return new HeadlessEglSession(display, surface, context, version);
	}

	void destroy() {
		EGL14.eglMakeCurrent(display,
				EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
		EGL14.eglDestroySurface(display, surface);
		EGL14.eglDestroyContext(display, context);
		EGL14.eglTerminate(display);
	}
}
