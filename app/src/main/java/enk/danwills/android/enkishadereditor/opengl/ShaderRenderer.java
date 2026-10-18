package enk.danwills.android.enkishadereditor.opengl;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class ShaderRenderer implements GLSurfaceView.Renderer {
	public interface OnRendererListener {
		void onInfoLog(@NonNull List<ShaderError> error);

		void onFramesPerSecond(int fps);

		// Called when a shader declares `audioSpectrum` and the "use
		// system playback audio" preference is on, but no MediaProjection
		// consent has been granted yet. Invoked on the GL thread.
		void onRequestAudioCapture();
	}

	// Reports tile-by-tile progress during captureHighResScreenshot()
	// below - called synchronously on the GL thread between tiles, same
	// as every other renderer callback; implementations needing to touch
	// UI must hop to the UI thread themselves.
	public interface HighResProgressListener {
		// Return false to cancel the capture - see
		// HighResScreenshotCapture.ProgressListener, which this gets
		// forwarded to one-for-one.
		boolean onProgress(int tilesDone, int totalTiles);
	}

	// Reports one tile's actual rendered pixels as soon as it's read
	// back - alongside (right after) HighResProgressListener's bare
	// tile-count progress above, for a live pixel preview on a progress
	// UI. argb is exactly what HighResScreenshotCapture already computed
	// for this tile (top-down rows, local to this tile, not the whole
	// image); contentX0/contentY0 are already in the *same* top-down,
	// whole-image coordinate space as the final result Bitmap (i.e. (0,0)
	// is the top-left of the whole targetWidth x targetHeight image) -
	// callers can draw argb straight into a same-orientation destination
	// at (contentX0, contentY0) with no further flipping. Purely
	// additive/optional - unlike HighResProgressListener, has no return
	// value and cannot cancel anything. Called synchronously on the GL
	// thread, same hand-off expectations as every other listener here.
	public interface TilePreviewListener {
		void onTilePreview(int contentX0, int contentY0, int contentW, int contentH,
				int targetWidth, int targetHeight, @NonNull int[] argb);
	}

	// Polled once per frame by captureHighResScreenshotSequence() below,
	// right before that frame's own tiled capture starts - rather than
	// taking a single fixed TileRenderOrder for the whole sequence, this
	// lets a caller change the order live (e.g. a Spinner on the
	// progress dialog) and have it take effect starting with the next
	// frame, without disturbing whichever frame is already mid-render
	// (same "finish what's in flight" spirit as interrupting a sequence -
	// see MainActivity.runFrameSequenceCapture()). Called synchronously
	// on the GL thread, same as every other listener here.
	public interface TileOrderSupplier {
		@NonNull
		TileRenderOrder getTileOrder();
	}

	// Reports one completed frame of captureHighResScreenshotSequence()
	// below - called synchronously on the GL thread, same as
	// HighResProgressListener; implementations needing to touch UI or do
	// slower work (saving to disk) must either hop to another thread or
	// accept blocking the GL thread briefly, same trade-off callers of
	// the single-frame capture already make for PNG encoding.
	public interface FrameListener {
		void onFrame(int frameIndex, int totalFrames, @NonNull byte[] png);
	}

	// Reports overall progress through captureHighResScreenshotSequence()
	// below, one step at a time - either stepping through the initial
	// (unsaved) skip frames, or capturing one of the saved output frames
	// - alongside HighResProgressListener's finer per-tile detail within
	// whichever output frame is currently capturing. Called synchronously
	// on the GL thread, same hand-off expectations as the other
	// listeners here.
	public interface SequenceProgressListener {
		// Return false to cancel the rest of the sequence - checked
		// between every skip step and every saved frame, same contract
		// as HighResProgressListener above.
		boolean onSequenceProgress(boolean skipping, int stepIndex, int totalSteps);
	}

	public static final String UNIFORM_AUDIO_SPECTRUM = "audioSpectrum";
	public static final String UNIFORM_BACKBUFFER = "backbuffer";
	public static final String UNIFORM_BATTERY = "battery";
	public static final String UNIFORM_CAMERA_ADDENT = "cameraAddent";
	public static final String UNIFORM_CAMERA_BACK = "cameraBack";
	public static final String UNIFORM_CAMERA_FRONT = "cameraFront";
	public static final String UNIFORM_CAMERA_ORIENTATION = "cameraOrientation";
	public static final String UNIFORM_DATE = "date";
	public static final String UNIFORM_DAYTIME = "daytime";
	public static final String UNIFORM_FRAME_NUMBER = "frame";
	public static final String UNIFORM_FTIME = "ftime";
	public static final String UNIFORM_GRAVITY = "gravity";
	public static final String UNIFORM_GYROSCOPE = "gyroscope";
	public static final String UNIFORM_LAST_NOTIFICATION_TIME = "lastNotificationTime";
	public static final String UNIFORM_LIGHT = "light";
	public static final String UNIFORM_LINEAR = "linear";
	public static final String UNIFORM_MAGNETIC = "magnetic";
	public static final String UNIFORM_MOUSE = "mouse";
	public static final String UNIFORM_NIGHT_MODE = "nightMode";
	public static final String UNIFORM_NOTIFICATION_COUNT = "notificationCount";
	public static final String UNIFORM_OFFSET = "offset";
	public static final String UNIFORM_ORIENTATION = "orientation";
	public static final String UNIFORM_INCLINATION = "inclination";
	public static final String UNIFORM_INCLINATION_MATRIX = "inclinationMatrix";
	public static final String UNIFORM_POINTERS = "pointers";
	public static final String UNIFORM_POINTER_COUNT = "pointerCount";
	public static final String UNIFORM_POSITION = "position";
	public static final String UNIFORM_POWER_CONNECTED = "powerConnected";
	public static final String UNIFORM_PRESSURE = "pressure";
	public static final String UNIFORM_PROXIMITY = "proximity";
	public static final String UNIFORM_RESOLUTION = "resolution";
	public static final String UNIFORM_ROTATION_MATRIX = "rotationMatrix";
	public static final String UNIFORM_ROTATION_VECTOR = "rotationVector";
	public static final String UNIFORM_RUNTIME = "runtime";
	public static final String UNIFORM_SECOND = "second";
	public static final String UNIFORM_START_RANDOM = "startRandom";
	public static final String UNIFORM_SUB_SECOND = "subsecond";
	public static final String UNIFORM_TIME = "time";
	public static final String UNIFORM_MEDIA_VOLUME = "mediaVolume";
	public static final String UNIFORM_MIC_AMPLITUDE = "micAmplitude";
	public static final String UNIFORM_TOUCH = "touch";
	public static final String UNIFORM_TOUCH_START = "touchStart";

	private static final int MAX_TEXTURES = 32;
	private static final long FPS_UPDATE_FREQUENCY_NS = 200000000L;
	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_REFRESH_RATE = 60f;

	private final GlDevice device = new GlDevice(MAX_TEXTURES);
	private final RendererProgramManager programManager =
			new RendererProgramManager(MAX_TEXTURES);
	private final ShaderRenderPipeline renderPipeline =
			new ShaderRenderPipeline(device, Mesh.fullScreenQuad());
	private final BuiltinUniforms builtinUniforms;
	private final Context context;
	private final Object thumbnailLock = new Object();
	private final Object fullImageLock = new Object();

	@Nullable
	private OnRendererListener onRendererListener;
	@NonNull
	private BuiltinUniforms.SurfaceState surfaceState =
			BuiltinUniforms.SurfaceState.empty();
	private long lastRender;
	private byte[] thumbnail = new byte[1];
	private boolean captureThumbnail;
	@Nullable
	private byte[] fullImage;
	private boolean captureFullImage;
	private volatile boolean blankFrameRequested;
	private volatile long nextFpsUpdate;
	private volatile float sum;
	private volatile float samples;
	private volatile int lastFps;
	private volatile float refreshRate = DEFAULT_REFRESH_RATE;
	private volatile long lastCompileNanos = -1;
	private volatile boolean lastCompileSucceeded = false;
	private volatile long lastProgramBinaryBytes = -1;
	private int glVersion = 2;
	// 0 means "no backing DB row" - see ShaderProjectSession.getShaderId().
	// Caching is simply skipped for such sessions (nothing stable to key
	// the cache on).
	private volatile long shaderId = 0;
	@Nullable
	private ProgramBinaryCache programBinaryCache;

	public ShaderRenderer(Context context) {
		this.context = context;
		builtinUniforms = new BuiltinUniforms(context);
	}

	public void setVersion(int version) {
		glVersion = version;
		programManager.setVersion(version);
	}

	public void setFragmentShader(String source, float quality, long shaderId) {
		builtinUniforms.setRuntimeNanos(0);
		setQuality(quality);
		this.shaderId = shaderId;
		programManager.setFragmentShader(source);
		resetFps();
	}

	// Supplies the persisted compiled-program cache - see
	// ProgramBinaryCache. Not required; caching is simply skipped if
	// this is never called (or passed null).
	public void setProgramBinaryCache(@Nullable ProgramBinaryCache cache) {
		programBinaryCache = cache;
	}

	public long getRuntimeNanos() {
		return builtinUniforms.getRuntimeNanos();
	}

	public void setRuntimeNanos(long nanos) {
		builtinUniforms.setRuntimeNanos(nanos);
	}

	// Restarts every time-based uniform (`time`, `runtime`, `frame`,
	// `startRandom`), not just `runtime` - see BuiltinUniforms.restart() -
	// and clears the backbuffer ping-pong targets to black, so a
	// feedback shader's accumulated image is discarded too.
	public void restartTime() {
		builtinUniforms.restart(System.nanoTime());
		renderPipeline.clearTargets();
	}

	public void pauseClock() {
		builtinUniforms.pauseClock();
	}

	// Clears the view to solid black on the very next onDrawFrame() call
	// and otherwise skips that frame entirely (no main/surface pass), so
	// a manually-paused shader doesn't keep showing its last live frame
	// frozen on screen - see ShaderViewManager.blankScreen() and
	// MainActivity.togglePauseShader(). Set here (typically from a
	// queueEvent() runnable, i.e. already on the GL thread, but callable
	// from anywhere) and read back in onDrawFrame() - volatile is enough
	// since it's a plain one-shot flag with no other state to keep in
	// sync with it.
	public void requestBlankFrame() {
		blankFrameRequested = true;
	}

	public void resumeClock() {
		builtinUniforms.resumeClock();
	}

	public void setQuality(float quality) {
		builtinUniforms.setQuality(quality);
	}

	public void setOnRendererListener(OnRendererListener listener) {
		onRendererListener = listener;
	}

	// Time taken by the most recent shader compile+link, or -1 if none
	// has happened yet.
	public long getLastCompileNanos() {
		return lastCompileNanos;
	}

	public boolean wasLastCompileSuccessful() {
		return lastCompileSucceeded;
	}

	// Size in bytes of the linked main program's GPU binary, queried
	// via GLES30's glGetProgramBinary machinery, or -1 if unavailable -
	// either because the context is GLES2-only, or (common on many
	// GPU drivers) the device reports zero supported binary formats,
	// meaning program binary retrieval simply isn't supported here.
	// The size itself is a vendor/driver-specific blob, not a portable
	// or comparable metric across devices.
	public long getLastProgramBinaryBytes() {
		return lastProgramBinaryBytes;
	}

	public int getLastFps() {
		return lastFps;
	}

	// The live render target's actual current size - the surface's own
	// pixel dimensions (from the most recent onSurfaceChanged(), not
	// necessarily DisplayMetrics' widthPixels/heightPixels, which can
	// differ by system bar/inset pixels the GLSurfaceView itself never
	// gets) scaled by this shader's own quality setting (see
	// BuiltinUniforms.updateSurface()). 0 before the surface has been
	// sized at all.
	public int getRenderWidth() {
		return surfaceState.renderWidth();
	}

	public int getRenderHeight() {
		return surfaceState.renderHeight();
	}

	public void setMediaProjection(@Nullable MediaProjection mediaProjection) {
		builtinUniforms.setMediaProjection(mediaProjection);
	}

	// Clears the "already asked for system audio consent" guard. Safe to
	// call from any thread (see BuiltinAudioUniforms.resetConsentGuard).
	// Called ahead of refreshAudioCaptureSource(), separately and not
	// queued onto the GL thread, so that two preference changes made in
	// quick succession (e.g. toggling the preference off then back on
	// before the GL thread next runs) can't both reset the guard after
	// the first one already re-asked - only the first queued refresh
	// should actually trigger a new prompt.
	public void resetAudioCaptureConsent() {
		builtinUniforms.resetAudioCaptureConsent();
	}

	// Re-evaluates which audio source to use against the current
	// preferences, e.g. after "use system playback audio" changes,
	// without requiring a shader recompile to trigger it.
	public void refreshAudioCaptureSource() {
		builtinUniforms.refreshAudioCaptureSource();
		if (builtinUniforms.consumeNeedsAudioCaptureConsent() && onRendererListener != null) {
			onRendererListener.onRequestAudioCapture();
		}
	}

	// See BuiltinAudioUniforms.releaseMediaProjection().
	public void releaseMediaProjection() {
		builtinUniforms.releaseMediaProjection();
	}

	// Forwards to BuiltinUniforms - see its own doc on release() for why
	// this needs to be set before the app can be backgrounded during a
	// HighResCaptureService capture, and cleared once that capture is
	// done. Call via ShaderViewManager.setBackgroundCaptureActive(),
	// never directly - that method also guards the live-context
	// recreate/resume paths this flag doesn't cover.
	public void setBackgroundCaptureActive(boolean active) {
		builtinUniforms.setBackgroundCaptureActive(active);
	}

	// See AudioSpectrumListener.isReceivingAudio().
	public boolean isReceivingAudio() {
		return builtinUniforms.isReceivingAudio();
	}

	public void setCropBorderPixel(boolean cropBorderPixel) {
		renderPipeline.setCropBorderPixel(cropBorderPixel);
	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		device.resetState();
		device.disable(GLES20.GL_CULL_FACE);
		device.disable(GLES20.GL_BLEND);
		device.disable(GLES20.GL_DEPTH_TEST);
		device.setClearColor(0f, 0f, 0f, 1f);

		discardContextResources();
		var thumbnailErrors = renderPipeline.createContextResources();
		if (!thumbnailErrors.isEmpty()) {
			submitErrors(thumbnailErrors);
		}

		if (programManager.hasPreparedShader()) {
			resetFps();

			ProgramBinary cachedBinary = null;
			String sourceHash = null;
			String driverSignature = null;
			if (shaderId > 0 && programBinaryCache != null && glVersion >= 3) {
				String sourceText = programManager.getSourceText();
				if (sourceText != null) {
					sourceHash = ProgramBinaryKey.sourceHash(sourceText, glVersion);
					driverSignature = ProgramBinaryKey.driverSignature();
					cachedBinary = programBinaryCache.get(shaderId, sourceHash, driverSignature);
				}
			}

			long compileStart = System.nanoTime();
			var reloadResult = programManager.reload(context, device, cachedBinary);
			lastCompileNanos = System.nanoTime() - compileStart;
			lastCompileSucceeded = reloadResult.succeeded();
			submitErrors(reloadResult.textureErrors());
			if (reloadResult.succeeded()) {
				submitErrors(Collections.emptyList());
				ProgramBinary freshBinary = reloadResult.freshBinary();
				if (freshBinary != null) {
					lastProgramBinaryBytes = freshBinary.bytes().length;
					if (programBinaryCache != null &&
							sourceHash != null && driverSignature != null) {
						programBinaryCache.put(shaderId, freshBinary, sourceHash, driverSignature,
								lastCompileNanos / 1_000_000);
					}
				} else {
					// Either this came from a cache hit (nothing fresh to
					// report/persist) or caching wasn't attempted above
					// (no shaderId/cache, e.g. an unsaved scratch session) -
					// either way, still worth querying directly for the
					// stats display.
					lastProgramBinaryBytes = queryProgramBinaryBytes(programManager.getMainProgram());
				}
				builtinUniforms.configure(
						device,
						programManager.getMainProgram(),
						programManager.getFTimeMax(),
						programManager.getTextureResources());
				if (builtinUniforms.consumeNeedsAudioCaptureConsent() &&
						onRendererListener != null) {
					onRendererListener.onRequestAudioCapture();
				}
			} else if (!reloadResult.programErrors().isEmpty()) {
				submitErrors(reloadResult.programErrors());
				lastProgramBinaryBytes = -1;
			}
		}
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
		long now = System.nanoTime();
		lastRender = now;
		refreshRate = getRefreshRate(context);
		surfaceState = builtinUniforms.updateSurface(width, height, now);
		if (surfaceState.renderTargetsChanged()) {
			renderPipeline.releaseTargets();
		}

		resetFps();
	}

	@Override
	public void onDrawFrame(GL10 gl) {
		if (blankFrameRequested) {
			blankFrameRequested = false;
			device.clear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			cancelCaptureFullImage();
			return;
		}

		var surfaceProgram = programManager.getSurfaceProgram();
		var mainProgram = programManager.getMainProgram();
		var surfaceBindings = programManager.getSurfaceBindings();
		if (surfaceProgram == null ||
				mainProgram == null ||
				surfaceBindings == null) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			cancelCaptureFullImage();
			return;
		}

		if (!renderPipeline.hasTargets()) {
			var targetErrors = renderPipeline.ensureTargets(
					context,
					surfaceState.renderWidth(),
					surfaceState.renderHeight(),
					programManager.getBackBufferParameters());
			if (!targetErrors.isEmpty()) {
				submitErrors(targetErrors);
			}
			if (!renderPipeline.hasTargets()) {
				cancelCaptureThumbnail();
				cancelCaptureFullImage();
				return;
			}
		}

		var frame = builtinUniforms.beginFrame(device, renderPipeline.getBackTexture());
		if (frame == null) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			cancelCaptureFullImage();
			return;
		}

		renderPipeline.renderMainPass(frame.bindings(), mainProgram);
		renderPipeline.renderSurfacePass(
				surfaceBindings,
				surfaceProgram,
				frame.surfaceWidth(),
				frame.surfaceHeight());
		captureFullImage(frame.surfaceWidth(), frame.surfaceHeight());
		renderPipeline.swapTargets();
		captureThumbnail();

		if (onRendererListener != null) {
			updateFps(frame.now());
		}

		builtinUniforms.endFrame();
	}

	public void unregisterListeners() {
		builtinUniforms.release();
	}

	public void touchAt(MotionEvent e) {
		builtinUniforms.updateTouch(e);
	}

	public void setOffset(float x, float y) {
		builtinUniforms.updateOffset(x, y);
	}

	public byte[] getThumbnail() {
		synchronized (thumbnailLock) {
			captureThumbnail = true;
			try {
				thumbnailLock.wait(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				captureThumbnail = false;
				return null;
			}
			return thumbnail;
		}
	}

	// Blocks (off the GL thread - callers should invoke this from a
	// background thread) until the next frame renders, then returns a
	// full-resolution PNG of exactly what's on screen - no toolbar,
	// editor, or other app UI, since it reads straight from the
	// GLSurfaceView's own framebuffer. Null on timeout/failure.
	@Nullable
	public byte[] captureFullScreenshot() {
		synchronized (fullImageLock) {
			captureFullImage = true;
			try {
				fullImageLock.wait(3000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				captureFullImage = false;
				return null;
			}
			return fullImage;
		}
	}

	// Renders the current shader at `width`x`height` times `oversample`
	// (independent of the screen's actual resolution and the live
	// `quality` setting), then - if oversample > 1 - box-filter
	// downsamples back to `width`x`height` for antialiasing, and returns
	// an encoded PNG, or null on any failure. Unlike
	// captureFullScreenshot() above, this doesn't hook into the normal
	// per-frame render loop at all - callers (see
	// ShaderViewManager.takeHighResScreenshot()) must invoke this
	// directly on the GL thread (e.g. via GLSurfaceView.queueEvent()),
	// not from onDrawFrame(). See HighResScreenshotCapture for why this
	// needs to be tiled rather than one single large render.
	@Nullable
	public byte[] captureHighResScreenshot(int width, int height, int oversample,
			boolean preserveBackbufferEdges, @NonNull TileBucketSize bucketSize,
			@NonNull TileRenderOrder tileOrder, @Nullable HighResProgressListener progressListener,
			@Nullable TilePreviewListener tilePreviewListener) {
		return captureHighResScreenshot(device, width, height, oversample, preserveBackbufferEdges,
				bucketSize, tileOrder, progressListener, tilePreviewListener);
	}

	// Same as above, but against a caller-supplied SharedCaptureContext
	// instead of this renderer's own live context - see
	// HighResCaptureService, which uses this to keep a capture running
	// even after the app's backgrounded and the live GLSurfaceView's own
	// context has been torn down. Must be called on whichever thread the
	// caller is keeping that context current on - never the live GL
	// thread (and never concurrently with any live-context rendering,
	// even though the two contexts are otherwise independent, since
	// `programManager`/`renderPipeline`/`builtinUniforms` are the SAME
	// instances the live renderer itself uses, same as the point of
	// sharing in the first place - see SharedCaptureContext's own doc).
	//
	// Reuses the live `device` field rather than a fresh GlDevice:
	// captureHighResScreenshotSequenceOnSharedContext() below needs
	// stepSimulationFrame()'s renderPipeline.renderMainPass() call, which
	// is hard-wired to this same `device` instance (renderPipeline owns
	// it from construction, there's no per-call override), so a single
	// capture run via the shared context has to use it too for the two
	// to behave consistently. GlDevice itself doesn't own any GL objects
	// by identity - it's just a state-cache wrapper around whichever
	// context is actually current on the calling thread - so the only
	// thing that needs resetting when handing it to a different context
	// is that cache, via resetState().
	@Nullable
	public byte[] captureHighResScreenshotOnSharedContext(@NonNull SharedCaptureContext sharedContext,
			int width, int height, int oversample, boolean preserveBackbufferEdges,
			@NonNull TileBucketSize bucketSize, @NonNull TileRenderOrder tileOrder,
			@Nullable HighResProgressListener progressListener,
			@Nullable TilePreviewListener tilePreviewListener) {
		if (!sharedContext.makeCurrent()) {
			return null;
		}
		device.resetState();
		return captureHighResScreenshot(device, width, height, oversample, preserveBackbufferEdges,
				bucketSize, tileOrder, progressListener, tilePreviewListener);
	}

	@Nullable
	private byte[] captureHighResScreenshot(@NonNull GlDevice useDevice, int width, int height,
			int oversample, boolean preserveBackbufferEdges, @NonNull TileBucketSize bucketSize,
			@NonNull TileRenderOrder tileOrder, @Nullable HighResProgressListener progressListener,
			@Nullable TilePreviewListener tilePreviewListener) {
		// See BuiltinUniforms.clearTouchState()'s own doc - called here
		// (the entry point every single, non-sequence high-res capture
		// funnels through) rather than relying on pauseClock() alone,
		// since the shader may already have been paused before this
		// capture started.
		builtinUniforms.clearTouchState();
		return captureHighResScreenshot(useDevice, width, height, oversample, preserveBackbufferEdges,
				bucketSize, tileOrder, progressListener, tilePreviewListener, null);
	}

	// Same as above, but for one frame of a sequence that's maintaining
	// its own persistent SequenceFeedback across the whole sequence (see
	// that class's own doc) - feedback is null for every other caller
	// (the single-capture overload just above, which always passes
	// null), in which case this behaves identically to before.
	@Nullable
	private byte[] captureHighResScreenshot(@NonNull GlDevice useDevice, int width, int height,
			int oversample, boolean preserveBackbufferEdges, @NonNull TileBucketSize bucketSize,
			@NonNull TileRenderOrder tileOrder, @Nullable HighResProgressListener progressListener,
			@Nullable TilePreviewListener tilePreviewListener,
			@Nullable HighResScreenshotCapture.SequenceFeedback feedback) {
		if (!programManager.hasPreparedShader()) {
			return null;
		}
		HighResScreenshotCapture.ProgressListener innerListener = progressListener == null
				? null
				: progressListener::onProgress;
		HighResScreenshotCapture.TilePreviewListener innerTilePreviewListener =
				tilePreviewListener == null ? null : tilePreviewListener::onTilePreview;
		// Single-pass eligibility (see TileBucketSize's own doc) is
		// gated on oversample==1 specifically because oversampling
		// renders at a size *larger* than the final image on purpose,
		// for later box-downscaling (see boxDownscale() below) - that
		// inflated render is exactly the kind of oversized single draw
		// tiling exists to avoid, so it's never offered a shortcut
		// around tiling regardless of bucket size.
		var bitmap = HighResScreenshotCapture.capture(
				useDevice, programManager, renderPipeline, builtinUniforms,
				glVersion, width * oversample, height * oversample,
				preserveBackbufferEdges, bucketSize, oversample == 1, tileOrder, innerListener,
				innerTilePreviewListener, feedback);
		if (bitmap == null) {
			return null;
		}
		if (oversample > 1) {
			bitmap = HighResScreenshotCapture.boxDownscale(bitmap, width, height);
			if (bitmap == null) {
				return null;
			}
		}
		try (var out = new ByteArrayOutputStream()) {
			bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
			return out.toByteArray();
		} catch (IOException | OutOfMemoryError e) {
			return null;
		}
	}

	// Must be called on the GL thread (e.g. via
	// GLSurfaceView.queueEvent()) while the live context is current -
	// see SharedCaptureContext's own doc for why. Returns null if this
	// device/moment doesn't support it (an unsupported EGL config,
	// resource exhaustion, etc) - callers should fall back to running
	// the capture on the live context in the usual way.
	@Nullable
	public SharedCaptureContext createSharedCaptureContext() {
		return SharedCaptureContext.createFromCurrentContext(glVersion);
	}

	// Advances a paused simulation forward by exactly deltaSeconds of
	// virtual time, rendering one ordinary (normal-resolution, not
	// tiled) frame into the ping-pong targets so backbuffer-dependent
	// state (spin, feedback trails, anything a shader persists via
	// `backbuffer`) evolves the same way it would during live playback -
	// just driven by a fixed virtual delta instead of wall-clock time.
	// Used between frames of captureHighResScreenshotSequence() below, so
	// how long each frame's own (possibly slow, tiled) capture actually
	// takes has no effect on the simulated motion between frames. No-op
	// if no compiled program/targets exist yet.
	private void stepSimulationFrame(float deltaSeconds) {
		var mainProgram = programManager.getMainProgram();
		if (mainProgram == null || !renderPipeline.hasTargets()) {
			return;
		}
		builtinUniforms.advancePausedTime((long) (deltaSeconds * 1_000_000_000L));
		var frame = builtinUniforms.beginFrame(device, renderPipeline.getBackTexture());
		if (frame == null) {
			return;
		}
		renderPipeline.renderMainPass(frame.bindings(), mainProgram);
		renderPipeline.swapTargets();
		builtinUniforms.endFrame();
	}

	// Captures numFrames frames via captureHighResScreenshot() above,
	// each virtualFps seconds apart in simulated (not wall-clock) time -
	// the first captured frame at whatever state the shader is currently
	// paused at, every later one stepped forward via
	// stepSimulationFrame(). skipFrames steps the simulation forward
	// that many times *first*, without capturing or saving anything -
	// for skipping past an intro/settling period before the saved
	// sequence starts - so the saved output is always exactly numFrames
	// long regardless of skipFrames. frameListener is called once per
	// completed (saved) frame, sequenceProgressListener once per step
	// of either phase - both still on the GL thread (see their own
	// doc). Returns false (and may have already handed some frames to
	// frameListener) if a frame fails to capture partway through; true
	// if every frame succeeded.
	public boolean captureHighResScreenshotSequence(int width, int height, int oversample,
			boolean preserveBackbufferEdges, int skipFrames, int numFrames, float virtualFps,
			@NonNull TileBucketSize bucketSize, @NonNull TileOrderSupplier tileOrderSupplier,
			@Nullable HighResProgressListener tileProgressListener,
			@Nullable TilePreviewListener tilePreviewListener,
			@Nullable SequenceProgressListener sequenceProgressListener,
			@NonNull FrameListener frameListener) {
		if (!programManager.hasPreparedShader()) {
			return false;
		}
		// See BuiltinUniforms.clearTouchState()'s own doc - called once
		// here, before the frame loop starts, rather than relying on
		// pauseClock() alone, since the shader may already have been
		// paused (e.g. the user adjusted the view with a gesture while
		// already paused) before this sequence started.
		builtinUniforms.clearTouchState();
		// Bucket size (unlike tile order) isn't live-changeable mid-
		// sequence, so singlePass eligibility is fixed for the whole
		// run - safe to decide once, up front, whether a
		// SequenceFeedback is worth allocating at all. Null either way
		// this returns false (not eligible, so every frame would just
		// ignore it anyway - see captureHighResScreenshot()'s own
		// feedback-is-null behavior) or if allocation itself failed
		// (e.g. OOM) - callers degrade gracefully to the pre-existing
		// per-frame-from-the-live-backbuffer behavior either way, just
		// without the persistent high-res feedback a healthy allocation
		// would have given them.
		HighResScreenshotCapture.SequenceFeedback feedback =
				HighResScreenshotCapture.isSinglePassEligible(
						device, width * oversample, height * oversample, bucketSize, oversample == 1)
						? HighResScreenshotCapture.SequenceFeedback.create(
								device, width * oversample, height * oversample)
						: null;
		try {
			float deltaSeconds = virtualFps > 0 ? 1f / virtualFps : 0f;
			for (int i = 0; i < skipFrames; ++i) {
				stepSimulationFrame(deltaSeconds);
				if (sequenceProgressListener != null &&
						!sequenceProgressListener.onSequenceProgress(true, i + 1, skipFrames)) {
					return false;
				}
			}
			for (int i = 0; i < numFrames; ++i) {
				if (i > 0 || skipFrames > 0) {
					stepSimulationFrame(deltaSeconds);
				}
				if (sequenceProgressListener != null &&
						!sequenceProgressListener.onSequenceProgress(false, i + 1, numFrames)) {
					return false;
				}
				byte[] png = captureHighResScreenshot(device, width, height, oversample,
						preserveBackbufferEdges, bucketSize, tileOrderSupplier.getTileOrder(),
						tileProgressListener, tilePreviewListener, feedback);
				if (png == null) {
					return false;
				}
				frameListener.onFrame(i, numFrames, png);
			}
			return true;
		} finally {
			if (feedback != null) {
				feedback.destroy(device);
			}
		}
	}

	// Same as above, but against a caller-supplied SharedCaptureContext -
	// see captureHighResScreenshotOnSharedContext() just above for why
	// that means reusing the live `device` field (reset first) rather
	// than a fresh one, and HighResCaptureService for the caller. The
	// plain captureHighResScreenshotSequence() above already resolves
	// back to that same `device` field internally (via the 5-arg
	// captureHighResScreenshot() overload), so once the shared context
	// is current and the state cache is reset, it needs no further
	// special-casing - everything it calls, including
	// stepSimulationFrame()'s use of renderPipeline, already runs
	// against whatever context is current on this thread.
	//
	// Same thread-affinity and no-concurrent-live-rendering requirements
	// as captureHighResScreenshotOnSharedContext() apply here too, for
	// the whole duration of the sequence (not just one call) -
	// stepSimulationFrame() advances this renderer's actual shared
	// simulation state (BuiltinUniforms' paused clock, the ping-pong
	// backbuffer), so it's never safe to interleave with the live GL
	// thread doing its own rendering against the same state, even
	// briefly between frames.
	public boolean captureHighResScreenshotSequenceOnSharedContext(
			@NonNull SharedCaptureContext sharedContext, int width, int height, int oversample,
			boolean preserveBackbufferEdges, int skipFrames, int numFrames, float virtualFps,
			@NonNull TileBucketSize bucketSize, @NonNull TileOrderSupplier tileOrderSupplier,
			@Nullable HighResProgressListener tileProgressListener,
			@Nullable TilePreviewListener tilePreviewListener,
			@Nullable SequenceProgressListener sequenceProgressListener,
			@NonNull FrameListener frameListener) {
		if (!sharedContext.makeCurrent()) {
			return false;
		}
		device.resetState();
		return captureHighResScreenshotSequence(width, height, oversample, preserveBackbufferEdges,
				skipFrames, numFrames, virtualFps, bucketSize, tileOrderSupplier, tileProgressListener,
				tilePreviewListener, sequenceProgressListener, frameListener);
	}

	private void resetFps() {
		sum = 0;
		samples = 0;
		lastFps = 0;
		nextFpsUpdate = 0;
	}

	private void submitErrors(@NonNull List<ShaderError> errors) {
		if (onRendererListener != null) {
			onRendererListener.onInfoLog(errors);
		}
	}

	private void discardContextResources() {
		builtinUniforms.clearConfiguration();
		renderPipeline.discardContextResources();
		programManager.discardContextResources();
	}

	// -1 if binary retrieval isn't supported here: needs an ES3 context
	// and a driver that actually reports at least one supported binary
	// format (plenty of GPUs report zero, meaning the entry points
	// exist but retrieval isn't really supported) - see
	// GlDevice.getProgramBinary().
	private long queryProgramBinaryBytes(@NonNull GlProgram program) {
		if (glVersion < 3) {
			return -1;
		}
		ProgramBinary binary = device.getProgramBinary(program);
		return binary != null ? binary.bytes().length : -1;
	}

	private void captureThumbnail() {
		synchronized (thumbnailLock) {
			var surfaceBindings = programManager.getSurfaceBindings();
			var surfaceProgram = programManager.getSurfaceProgram();
			if (captureThumbnail &&
					surfaceBindings != null &&
					surfaceProgram != null) {
				thumbnail = renderPipeline.captureThumbnail(
						surfaceBindings,
						surfaceProgram);
				captureThumbnail = false;
				thumbnailLock.notifyAll();
			}
		}
	}

	private void cancelCaptureThumbnail() {
		synchronized (thumbnailLock) {
			if (captureThumbnail) {
				captureThumbnail = false;
				thumbnailLock.notifyAll();
			}
		}
	}

	// width/height: the surface's actual current size, matching what
	// renderSurfacePass() just drew into the on-screen framebuffer -
	// still bound at this point, so the read captures that exact frame.
	private void captureFullImage(int width, int height) {
		synchronized (fullImageLock) {
			if (captureFullImage) {
				fullImage = renderPipeline.readPixelsAsPng(width, height);
				captureFullImage = false;
				fullImageLock.notifyAll();
			}
		}
	}

	private void cancelCaptureFullImage() {
		synchronized (fullImageLock) {
			if (captureFullImage) {
				captureFullImage = false;
				fullImageLock.notifyAll();
			}
		}
	}

	private void updateFps(long now) {
		long delta = now - lastRender;

		synchronized (this) {
			sum += Math.min(NS_PER_SECOND / delta, refreshRate);

			if (++samples > 0xffff) {
				sum = sum / samples;
				samples = 1;
			}
		}

		if (now > nextFpsUpdate) {
			int fps = Math.round(sum / samples);
			if (fps != lastFps && onRendererListener != null) {
				onRendererListener.onFramesPerSecond(fps);
				lastFps = fps;
			}
			nextFpsUpdate = now + FPS_UPDATE_FREQUENCY_NS;
		}

		lastRender = now;
	}

	private static float getRefreshRate(Context context) {
		float refreshRate = DEFAULT_REFRESH_RATE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Display display = getDisplay(context);
			if (display != null && display.getMode() != null) {
				refreshRate = display.getMode().getRefreshRate();
			}
		} else {
			WindowManager windowManager = (WindowManager) context
					.getSystemService(Context.WINDOW_SERVICE);
			if (windowManager != null) {
				refreshRate = windowManager.getDefaultDisplay()
						.getRefreshRate();
			}
		}
		return refreshRate > 0f ? refreshRate : DEFAULT_REFRESH_RATE;
	}

	@SuppressLint("UseRequiresApi")
	@TargetApi(Build.VERSION_CODES.R)
	private static Display getDisplay(Context context) {
		try {
			return context.getDisplay();
		} catch (UnsupportedOperationException e) {
			// Fall through. Thrown when the context isn't associated
			// with a display.
		}
		DisplayManager displayManager = context.getSystemService(
				DisplayManager.class);
		if (displayManager == null) {
			return null;
		}
		return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
	}
}
