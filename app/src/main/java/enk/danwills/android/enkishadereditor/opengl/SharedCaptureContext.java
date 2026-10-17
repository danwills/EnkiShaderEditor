package enk.danwills.android.enkishadereditor.opengl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.util.Log;

import androidx.annotation.Nullable;

// A headless, pbuffer-backed EGL context that *shares* GL objects
// (textures, programs, buffers, shaders) with the live GLSurfaceView's
// own context. Created while that context is still current (the only
// way to discover its EGLContext/EGLDisplay/EGLConfig to share against -
// see createFromCurrentContext()), it then survives independently of it:
// per EGL's own share-group semantics, an object stays alive as long as
// *any* context in its share group still exists, even after the context
// that originally created it is destroyed. That's what lets a
// long-running capture (see HighResCaptureService) keep using the
// shader's already-compiled program and already-accumulated backbuffer
// texture even after the app is backgrounded and the live GLSurfaceView
// tears its own context down mid-capture - no snapshotting or
// reconstructing that state into a separate, unrelated context needed.
//
// Framebuffers are the one GL object type NOT shared this way (GLES
// requires each context to have its own) - not a problem here, since
// HighResScreenshotCapture already creates its own tile framebuffer
// fresh every capture regardless of which context it's running on.
public final class SharedCaptureContext {
	private static final String TAG = "HighResCapture";

	private final EGLDisplay display;
	private final EGLSurface surface;
	private final EGLContext context;

	private SharedCaptureContext(
			EGLDisplay display, EGLSurface surface, EGLContext context) {
		this.display = display;
		this.surface = surface;
		this.context = context;
	}

	// Must be called on the GL thread that currently has the live
	// context current (e.g. from inside a GLSurfaceView.queueEvent()
	// Runnable) - EGL has no other way to identify what to share
	// against. Returns null if there's no live context current right
	// now, or context/surface creation fails for any reason (an
	// unsupported config, resource exhaustion, etc) - callers should
	// treat that as "this device/moment doesn't support it", not fatal.
	@Nullable
	static SharedCaptureContext createFromCurrentContext(int glVersion) {
		EGLDisplay display = EGL14.eglGetCurrentDisplay();
		EGLContext liveContext = EGL14.eglGetCurrentContext();
		if (display == null || display == EGL14.EGL_NO_DISPLAY ||
				liveContext == null || liveContext == EGL14.EGL_NO_CONTEXT) {
			Log.e(TAG, "createFromCurrentContext: no live context current on this thread");
			return null;
		}

		int renderableType = glVersion >= 3
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
			Log.e(TAG, "createFromCurrentContext: eglChooseConfig failed");
			return null;
		}
		EGLConfig config = configs[0];

		int[] contextAttribs = {
				EGL14.EGL_CONTEXT_CLIENT_VERSION, glVersion,
				EGL14.EGL_NONE
		};
		// liveContext as the share_context parameter - this is the
		// whole point, everything else here matches HeadlessShaderCompiler's
		// own (non-shared) pbuffer context recipe.
		EGLContext newContext = EGL14.eglCreateContext(
				display, config, liveContext, contextAttribs, 0);
		if (newContext == null || newContext == EGL14.EGL_NO_CONTEXT) {
			Log.e(TAG, "createFromCurrentContext: eglCreateContext failed, error 0x"
					+ Integer.toHexString(EGL14.eglGetError()));
			return null;
		}

		int[] surfaceAttribs = {
				EGL14.EGL_WIDTH, 1,
				EGL14.EGL_HEIGHT, 1,
				EGL14.EGL_NONE
		};
		EGLSurface pbuffer = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0);
		if (pbuffer == null || pbuffer == EGL14.EGL_NO_SURFACE) {
			Log.e(TAG, "createFromCurrentContext: eglCreatePbufferSurface failed, error 0x"
					+ Integer.toHexString(EGL14.eglGetError()));
			EGL14.eglDestroyContext(display, newContext);
			return null;
		}

		return new SharedCaptureContext(display, pbuffer, newContext);
	}

	// Makes this context current on the calling thread - call this from
	// a dedicated thread of the caller's own, never the live GL thread
	// (EGL contexts are thread-affined; this one and the live one must
	// never both be current on the same thread at once).
	public boolean makeCurrent() {
		boolean success = EGL14.eglMakeCurrent(display, surface, surface, context);
		if (!success) {
			Log.e(TAG, "makeCurrent: eglMakeCurrent failed, error 0x"
					+ Integer.toHexString(EGL14.eglGetError()));
		}
		return success;
	}

	// Releases this context and its pbuffer surface. Deliberately does
	// NOT call eglTerminate() on the display - createFromCurrentContext()
	// didn't create that display, it's the same one the live context
	// uses (eglGetCurrentDisplay() returns whatever's already current),
	// and terminating it would tear down the live context's own display
	// connection as a side effect.
	public void destroy() {
		EGL14.eglMakeCurrent(display,
				EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
		EGL14.eglDestroySurface(display, surface);
		EGL14.eglDestroyContext(display, context);
	}
}
