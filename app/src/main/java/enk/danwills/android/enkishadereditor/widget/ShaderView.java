package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;

import androidx.annotation.Nullable;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;

import enk.danwills.android.enkishadereditor.app.ShaderEditorApp;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.preference.Preferences;

public class ShaderView extends GLSurfaceView {
	private static final String TAG = "ShaderView";
	private ShaderRenderer renderer;

	public ShaderView(Context context, int renderMode) {
		super(context);
		init(context, renderMode);
	}

	public ShaderView(Context context) {
		super(context);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY);
	}

	public ShaderView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY);
	}

	@Override
	public void onPause() {
		super.onPause();
		renderer.unregisterListeners();
	}

	// unregisterListeners() above clears the renderer's per-program
	// sensor/camera/audio configuration on every pause; with the EGL
	// context now preserved across pauses (see init()) nothing else would
	// restore it, so the renderer re-applies it on its next frame.
	@Override
	public void onResume() {
		renderer.onViewResumed();
		// Re-read every resume so a change made in Settings (which always
		// pauses/resumes this view on the way back) takes effect at once.
		renderer.setBreathingRoom(ShaderEditorApp.preferences.getBreathingRoom());
		renderer.setLiveRenderMode(ShaderEditorApp.preferences.getLiveRenderMode());
		renderer.setStateRowMigration(ShaderEditorApp.preferences.getStateRows());
		super.onResume();
	}

	@Override
	public void setRenderMode(int renderMode) {
		super.setRenderMode(renderMode);
		renderer.setContinuousRendering(renderMode == RENDERMODE_CONTINUOUSLY);
	}

	// Click handling is implemented in renderer.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		renderer.touchAt(event);
		return true;
	}

	public void setFragmentShader(String src, float quality, long shaderId) {
		setFragmentShader(src, null, quality, shaderId);
	}

	// bufferSources: see ShaderRenderer.setFragmentShader().
	public void setFragmentShader(String src, @Nullable String[] bufferSources,
			float quality, long shaderId) {
		onPause();
		// When pasting text from other apps, e.g. Gmail, the
		// text is sometimes tainted with useless non-ascii
		// characters that can raise an exception in the shader
		// compiler. To still allow UTF-8 characters in comments,
		// the source is cleaned up here.
		String[] cleanBuffers = null;
		if (bufferSources != null) {
			cleanBuffers = new String[bufferSources.length];
			for (int i = 0; i < bufferSources.length; ++i) {
				cleanBuffers[i] = removeNonAscii(bufferSources[i]);
			}
		}
		renderer.setFragmentShader(removeNonAscii(src), cleanBuffers, quality, shaderId);
		onResume();
	}

	public ShaderRenderer getRenderer() {
		return renderer;
	}

	private void init(Context context, int renderMode) {
		renderer = new ShaderRenderer(context);

		// On some devices it's important to setEGLContextClientVersion()
		// even if the docs say it's not used when setEGLContextFactory()
		// is called. Not doing so will crash the app (e.g. on the FP1).
		setEGLContextClientVersion(2);
		setEGLContextFactory(new ContextFactory(renderer));
		setRenderer(renderer);
		setRenderMode(renderMode);
		// Keeps the compiled shader (and every other GL resource) alive
		// across backgrounding, so returning to the app doesn't have to
		// recompile anything before the first frame - only honoured when
		// the driver/system can spare it; onSurfaceCreated() still does a
		// full rebuild whenever the context really was discarded. Shader
		// switches no longer depend on context recreation either - see
		// ShaderRenderer.setFragmentShader()'s pendingReload.
		setPreserveEGLContextOnPause(true);
	}

	private static String removeNonAscii(String text) {
		return text == null
				? null
				: text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", "");
	}

	private static class ContextFactory
			implements GLSurfaceView.EGLContextFactory {
		private final ShaderRenderer renderer;

		private ContextFactory(ShaderRenderer renderer) {
			this.renderer = renderer;
		}

		@Override
		public EGLContext createContext(EGL10 egl, EGLDisplay display,
				EGLConfig eglConfig) {
			int priority = requestedPriority(egl, display);
			EGLContext context = createWithFallback(egl, display, eglConfig, 3, priority);
			if (context != null && context != EGL10.EGL_NO_CONTEXT &&
					context.getGL() != null) {
				renderer.setVersion(3);
				return context;
			}
			return createWithFallback(egl, display, eglConfig, 2, priority);
		}

		// EGL_IMG_context_priority (despite the name, widely supported
		// beyond Imagination GPUs - Adreno and Mali drivers commonly
		// advertise it too) - a LOW-priority context lets drivers that
		// honour it schedule the app's own UI and the system's drawing
		// ahead of a heavy shader instead of behind it. 0 means "don't
		// ask" (the Default setting, or the driver doesn't support it).
		private static final int EGL_CONTEXT_PRIORITY_LEVEL_IMG = 0x3100;
		private static final int EGL_CONTEXT_PRIORITY_HIGH_IMG = 0x3101;
		private static final int EGL_CONTEXT_PRIORITY_MEDIUM_IMG = 0x3102;
		private static final int EGL_CONTEXT_PRIORITY_LOW_IMG = 0x3103;

		private static int requestedPriority(EGL10 egl, EGLDisplay display) {
			int priority = switch (ShaderEditorApp.preferences.getGpuPriorityLevel()) {
				case Preferences.GPU_PRIORITY_LOW -> EGL_CONTEXT_PRIORITY_LOW_IMG;
				case Preferences.GPU_PRIORITY_MEDIUM -> EGL_CONTEXT_PRIORITY_MEDIUM_IMG;
				case Preferences.GPU_PRIORITY_HIGH -> EGL_CONTEXT_PRIORITY_HIGH_IMG;
				default -> 0;
			};
			if (priority == 0) {
				return 0;
			}
			String extensions = egl.eglQueryString(display, EGL10.EGL_EXTENSIONS);
			if (extensions == null || !extensions.contains("EGL_IMG_context_priority")) {
				Log.i(TAG, "GPU priority requested but EGL_IMG_context_priority isn't supported");
				return 0;
			}
			return priority;
		}

		// A driver that advertises the priority extension may still refuse
		// a particular level (e.g. HIGH without special permission) - fall
		// back to a plain context rather than failing outright.
		private static EGLContext createWithFallback(EGL10 egl, EGLDisplay display,
				EGLConfig eglConfig, int version, int priority) {
			int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
			if (priority != 0) {
				EGLContext context = egl.eglCreateContext(display, eglConfig,
						EGL10.EGL_NO_CONTEXT, new int[]{
								EGL_CONTEXT_CLIENT_VERSION, version,
								EGL_CONTEXT_PRIORITY_LEVEL_IMG, priority,
								EGL10.EGL_NONE
						});
				if (context != null && context != EGL10.EGL_NO_CONTEXT) {
					Log.i(TAG, "created ES" + version + " context with priority 0x" +
							Integer.toHexString(priority));
					return context;
				}
				Log.w(TAG, "ES" + version + " context with priority 0x" +
						Integer.toHexString(priority) + " refused, retrying without");
			}
			return egl.eglCreateContext(display, eglConfig,
					EGL10.EGL_NO_CONTEXT, new int[]{
							EGL_CONTEXT_CLIENT_VERSION, version,
							EGL10.EGL_NONE
					});
		}

		@Override
		public void destroyContext(EGL10 egl, EGLDisplay display,
				EGLContext context) {
			egl.eglDestroyContext(display, context);
		}
	}
}
