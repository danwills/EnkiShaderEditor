package enk.danwills.android.enkishadereditor.opengl;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import enk.danwills.android.enkishadereditor.io.ImageEncoding;

public class ShaderRenderer implements GLSurfaceView.Renderer {
	private static final String TAG = "ShaderRenderer";

	public interface OnRendererListener {
		void onInfoLog(@NonNull List<ShaderError> error);

		void onFramesPerSecond(int fps);

		// Called when a shader declares `audioSpectrum` and the "use
		// system playback audio" preference is on, but no MediaProjection
		// consent has been granted yet. Invoked on the GL thread.
		void onRequestAudioCapture();

		// Called once, on the GL thread, right after the first frame
		// following ShaderView.onResume() has been drawn (successfully or
		// not - an error/blank frame still ends the wait), so a caller can
		// tell when the "returned to the app but nothing rendered yet" gap
		// is over. See onViewResumed().
		default void onFirstFrameAfterResume() {
		}
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

		// See HighResScreenshotCapture.ProgressListener.keepPartialOnStop().
		default boolean keepPartialOnStop() {
			return false;
		}
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
	// ShaderToy's names, so its code runs with little or no translation -
	// see BuiltinUniforms.bindShaderToyUniforms() and ShaderSourcePreparer.
	public static final String UNIFORM_ST_TIME = "iTime";
	public static final String UNIFORM_ST_TIME_DELTA = "iTimeDelta";
	public static final String UNIFORM_ST_FRAME_RATE = "iFrameRate";
	public static final String UNIFORM_ST_FRAME = "iFrame";
	public static final String UNIFORM_ST_RESOLUTION = "iResolution";
	public static final String UNIFORM_ST_MOUSE = "iMouse";
	public static final String UNIFORM_ST_DATE = "iDate";
	public static final String UNIFORM_ST_CHANNEL_RESOLUTION = "iChannelResolution";

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
	// How screenshots and captures are encoded - see setCaptureEncoding().
	@NonNull
	private volatile ImageEncoding captureEncoding = ImageEncoding.PNG;
	private volatile boolean blankFrameRequested;
	// See requestRedisplay().
	private volatile boolean redisplayRequested;
	// The view's size, for requestRedisplay().
	private int viewWidth;
	private int viewHeight;
	// Multipass shaders - see drawBufferPasses(). A 1x1 black texture for
	// channels with nothing assigned (ShaderToy reads those as black),
	// whether this context can render into RGBA16F buffers, and which
	// buffers some channel samples with a mipmapped filter.
	@Nullable
	private GlTexture2D blackTexture;
	private boolean halfFloatBuffers;
	private final boolean[] bufferNeedsMipmaps = new boolean[ChannelParameters.MAX_CHANNELS];
	private final float[] channelResolution = new float[ChannelParameters.MAX_CHANNELS * 3];
	private volatile long nextFpsUpdate;
	// See updateFps().
	private volatile double smoothedFrameNanos;
	private static final double FPS_SMOOTHING_NANOS = 1_000_000_000.0;
	private volatile int lastFps;
	// See setFragmentShader()/onViewResumed()/onDrawFrame().
	private volatile boolean pendingReload;
	private volatile boolean pendingReconfigure;
	private volatile boolean pendingFirstFrameNotify;
	// See setBreathingRoom()/applyBreathingRoom().
	private volatile float breathingRoomFactor = 0f;
	private volatile boolean continuousRendering;
	private long lastFrameStartNanos;
	// Auto mode: below 20fps a frame is long enough that the UI and
	// system are visibly waiting on it; above that, leave the shader be.
	private static final long AUTO_BREATHING_SLOW_FRAME_NANOS = 50_000_000L;
	private static final float AUTO_BREATHING_FACTOR = 1.25f;
	private static final long BREATHING_MAX_MEASURED_NANOS = 5_000_000_000L;
	private static final long BREATHING_MAX_SLEEP_NANOS = 2_000_000_000L;
	// See drawProgressiveStep()/adaptProgressiveRows(). Bands grow while
	// draws finish well inside a refresh, shrink once they overrun it -
	// aiming a bit under a full period so the app's own UI and the
	// system still get GPU time within each refresh.
	// NORMAL by default - only ShaderView (the app's own live preview)
	// changes it, from the Live Rendering setting; the wallpaper
	// service's own use of this renderer stays on the full-frame path.
	private volatile int liveRenderMode = LIVE_RENDER_NORMAL;
	// AUTO's own state - see checkAutoSwitchTo*(). GL thread only.
	private volatile boolean autoProgressiveActive;
	private int autoSlowFrames;
	private int autoFastFrames;
	// Slow enough to switch to progressive: below ~2.5fps (frames over
	// 400ms) this many frames running - deliberately well past where the
	// UI starts to feel it, since full-frame rendering is what keeps a
	// shader feeling real-time, and that's worth some UI lag first.
	private static final long AUTO_PROGRESSIVE_SLOW_NANOS = 400_000_000L;
	private static final int AUTO_PROGRESSIVE_SLOW_FRAMES = 3;
	// Fast enough to switch back: whole frames estimated under 350ms this
	// many completed frames running. Optimistic on purpose (full frame is
	// what keeps a shader feeling real-time) - and safe to be: the
	// estimate includes some per-band refresh-wait padding, so the real
	// full-frame cost should come in at or under it, below the 400ms
	// that would send it straight back. autoNormalBackoff below covers
	// any shader where that turns out not to hold.
	private static final long AUTO_NORMAL_FAST_NANOS = 350_000_000L;
	private static final int AUTO_NORMAL_FAST_FRAMES = 3;
	// Loop guard - see checkAutoSwitchToProgressive(). Reset whenever the
	// shader or the mode changes.
	private static final long AUTO_FLAP_WINDOW_NANOS = 10_000_000_000L;
	private static final int AUTO_MAX_BACKOFF = 16;
	private int autoNormalBackoff = 1;
	private long lastAutoSwitchToNormalNanos;
	private long progressiveMeasuredNanos;
	// See primeConservativeStart().
	private volatile long primedFrameNanos;
	private boolean conservativeStart;
	private volatile boolean primeReady;
	// See getProgressive*().
	private int progressiveStepsThisFrame;
	private volatile int progressiveFrameWidth;
	private volatile int progressiveFrameHeight;
	private volatile int progressiveAverageBandRows;
	private long progressiveMeasuredRows;
	private long progressiveMeasuredSteps;
	// See learnStepFloor(). 0 until measured.
	private double stepFloorNanos;
	// "Small band" for floor-learning purposes: at most 1/this of the
	// frame's height (or a single row).
	private static final int PROGRESSIVE_FLOOR_BAND_FRACTION = 50;
	private static final double PROGRESSIVE_FLOOR_RISE_RATE = 0.05;
	private volatile boolean progressiveAbandonRequested;
	@Nullable
	private BuiltinUniforms.PreparedFrame progressiveFrame;
	private int progressiveFrameStamp;
	private int progressiveRowsDone;
	// See setStateRowMigration() - same values as Preferences.STATE_ROWS_*.
	public static final int STATE_ROWS_OFF = 0;
	public static final int STATE_ROWS_AUTO = 1;
	public static final int STATE_ROWS_ALWAYS = 2;
	private volatile int stateRowMigration = STATE_ROWS_AUTO;
	// Set by a resize (onSurfaceChanged()), cleared by a reload - see
	// migrateStateRows().
	private boolean stateRowMigrationPending;
	// See getProgressiveFrameProgress().
	private volatile float progressiveFrameProgress;
	private int progressiveRowsPerStep = Integer.MAX_VALUE;
	private int progressiveLastRows;
	private long lastStepIntervalNanos;
	private static final double PROGRESSIVE_GROW_BELOW = 1.15;
	private static final double PROGRESSIVE_SHRINK_ABOVE = 1.4;
	private static final double PROGRESSIVE_SHRINK_TARGET = 0.7;
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
		setFragmentShader(source, null, quality, shaderId);
	}

	// bufferSources: a multipass shader's offscreen buffer passes, indexed
	// by buffer (0 = A), null entries (or a null array) for none.
	public void setFragmentShader(String source, @Nullable String[] bufferSources,
			float quality, long shaderId) {
		builtinUniforms.setRuntimeNanos(0);
		setQuality(quality);
		this.shaderId = shaderId;
		programManager.setFragmentShader(source, bufferSources);
		resetFps();
		// Compiled on the GL thread's next frame (see onDrawFrame()) rather
		// than relying on ShaderView's onPause()/onResume() to recreate the
		// whole EGL context - ShaderView preserves it across pauses now
		// (see setPreserveEGLContextOnPause() there), so onSurfaceCreated()
		// only still runs when the system genuinely discarded it.
		pendingReload = true;
	}

	// Which offscreen buffer (0 = A) to show on screen instead of the
	// Image pass - the one the editor is on - or -1 for the Image pass.
	// Every pass still renders as usual; only what's shown changes.
	// Thumbnails and high-res captures always take the Image pass, while
	// a plain screenshot takes what's on screen. Set from the UI thread,
	// read on the GL thread.
	private volatile int displayedBuffer = ShaderError.IMAGE_PASS;

	public void setDisplayedBuffer(int buffer) {
		displayedBuffer = buffer;
	}

	// The displayed buffer's latest output, or null to show the Image
	// pass (also when the shader has no such buffer, e.g. while it's
	// being removed).
	@Nullable
	private GlTexture2D getDisplayedBufferTexture() {
		int buffer = displayedBuffer;
		if (buffer == ShaderError.IMAGE_PASS) {
			return null;
		}
		for (var pass : programManager.getBufferPasses()) {
			if (pass.buffer == buffer) {
				return pass.getCurrentTexture();
			}
		}
		return null;
	}

	// Called from ShaderView.onResume() - every pause runs release()
	// (unregistering sensors/camera/audio and clearing BuiltinUniforms'
	// per-program configuration, see unregisterListeners()), which used to
	// be restored for free by the context recreation that always followed.
	// With the context preserved there's no onSurfaceCreated() to do that,
	// so the next frame re-runs just the configure step - no recompile.
	public void onViewResumed() {
		pendingReconfigure = true;
		pendingFirstFrameNotify = true;
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
		for (var pass : programManager.getBufferPasses()) {
			pass.clearTargets(device);
		}
		abandonProgressiveFrame();
	}

	// restartTime() for a capture about to start from the restarted
	// state. A buffer shader's capture reads the last completed frame
	// rather than drawing its own (see captureHighResScreenshot()), and
	// restartTime() just cleared that - so this draws frame 0 for it,
	// at time 0. Any other shader's capture draws frame 0 itself, from
	// the cleared backbuffer.
	public void restartForCapture() {
		restartTime();
		if (hasBufferPasses()) {
			stepSimulationFrame(0f);
		}
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
		redisplayRequested = false;
	}

	// Shows the last completed frame again on the next onDrawFrame(),
	// without rendering a new one - so a paused shader (feedback and
	// buffer shaders included) comes back from requestBlankFrame()
	// exactly as it was, not one step on.
	public void requestRedisplay() {
		redisplayRequested = true;
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

	public void setLocalPlaybackAudioSession(int audioSessionId) {
		builtinUniforms.setLocalPlaybackAudioSession(audioSessionId);
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
		blackTexture = device.createTexture2D();
		device.allocateTexture2D(blackTexture, 1, 1);
		device.updateTexture2D(blackTexture, java.nio.ByteBuffer.allocateDirect(4));
		halfFloatBuffers = glVersion >= 3 && device.supportsHalfFloatTargets();
		var thumbnailErrors = renderPipeline.createContextResources();
		if (!thumbnailErrors.isEmpty()) {
			submitErrors(thumbnailErrors);
		}

		pendingReload = false;
		pendingReconfigure = false;
		abandonProgressiveFrame();
		reloadProgram();
	}

	// Compile (or load from the binary cache) the current prepared shader
	// and configure BuiltinUniforms for it - GL thread only. Shared by
	// onSurfaceCreated() (fresh context) and onDrawFrame()'s pendingReload
	// path (shader changed on a preserved context).
	private void reloadProgram() {
		// A different (or edited) shader - whatever AUTO learnt about the
		// last one's speed no longer applies.
		autoNormalBackoff = 1;
		lastAutoSwitchToNormalNanos = 0;
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

			final String bufferDriverSignature = driverSignature;
			RendererProgramManager.BufferBinaryLookup bufferLookup = (buffer, text) ->
					bufferDriverSignature != null && programBinaryCache != null
							? programBinaryCache.getBuffer(shaderId, buffer,
									ProgramBinaryKey.sourceHash(text, glVersion),
									bufferDriverSignature)
							: null;
			long compileStart = System.nanoTime();
			var reloadResult = programManager.reload(context, device, cachedBinary,
					bufferLookup);
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
				if (programBinaryCache != null && driverSignature != null) {
					for (var fresh : reloadResult.freshBufferBinaries()) {
						programBinaryCache.putBuffer(shaderId, fresh.buffer(), fresh.binary(),
								ProgramBinaryKey.sourceHash(fresh.sourceText(), glVersion),
								driverSignature);
					}
				}
				updateBufferMipmapNeeds();
				configureUniforms(programManager.getMainProgram());
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
		viewWidth = width;
		viewHeight = height;
		if (surfaceState.renderTargetsChanged()) {
			// Keeps the last completed frame on screen (scaled) until the
			// first frame at the new size completes - otherwise a quality
			// change showed black for however long that took, which for a
			// slow shader in progressive mode can be several seconds.
			renderPipeline.releaseTargetsKeepingLastFrame();
			// Buffers simply restart at the new size, as on ShaderToy.
			for (var pass : programManager.getBufferPasses()) {
				pass.releaseTargets(device);
			}
			stateRowMigrationPending = true;
			abandonProgressiveFrame();
			if (primedFrameNanos > 0) {
				primeReady = true;
			}
		}

		resetFps();
	}

	@Override
	public void onDrawFrame(GL10 gl) {
		applyBreathingRoom();
		try {
			drawFrame();
		} finally {
			if (pendingFirstFrameNotify) {
				pendingFirstFrameNotify = false;
				if (onRendererListener != null) {
					onRendererListener.onFirstFrameAfterResume();
				}
			}
		}
	}

	// Sets how much idle time the live preview leaves the GPU between
	// frames, as a multiple of the previous frame's own render time: 1.0
	// adds nothing, 1.1 adds 10% of each frame's time as a gap (so ~9%
	// of the GPU's time is left free), 2.0 halves the frame rate and
	// leaves half the GPU free. A phone GPU largely runs one draw at a
	// time, so the app's own UI and the system's (status bar, task
	// switcher...) can only draw in the gaps a heavy shader leaves -
	// without any, they queue up behind it and everything feels frozen.
	// <= 0 means automatic: no gap at all while frames are quick enough
	// not to be starving anything (see AUTO_BREATHING_SLOW_FRAME_NANOS),
	// AUTO_BREATHING_FACTOR once they aren't. Only applied while
	// rendering continuously (see setContinuousRendering()) - a paused
	// view's frames are rare and on demand, nothing to pace.
	public void setBreathingRoom(float factor) {
		breathingRoomFactor = factor;
	}

	// Kept in sync with GLSurfaceView's own render mode by ShaderView.
	public void setContinuousRendering(boolean continuous) {
		continuousRendering = continuous;
		lastFrameStartNanos = 0;
	}

	// In continuous mode the GL thread loops draw -> swap -> draw, and
	// eglSwapBuffers() blocks once the GPU falls behind, so the gap
	// between one frame starting and the next is (in steady state) how
	// long the GPU actually spent on a frame - which is exactly what the
	// breathing room needs to scale with, without forcing a pipeline-
	// stalling glFinish() of its own. Measured from the end of the
	// previous sleep, so the sleep itself never feeds back into the next
	// frame's estimate.
	private void applyBreathingRoom() {
		long now = System.nanoTime();
		long previous = lastFrameStartNanos;
		lastFrameStartNanos = now;
		if (!continuousRendering || previous == 0) {
			return;
		}
		long workNanos = now - previous;
		lastStepIntervalNanos = workNanos;
		if (workNanos <= 0 || workNanos > BREATHING_MAX_MEASURED_NANOS) {
			// A stall unrelated to rendering (a queued capture, a
			// reload) - not a frame time worth scaling.
			return;
		}
		float factor = breathingRoomFactor > 0f
				? breathingRoomFactor
				: workNanos > AUTO_BREATHING_SLOW_FRAME_NANOS ? AUTO_BREATHING_FACTOR : 1f;
		long sleepNanos = Math.min((long) ((factor - 1f) * workNanos), BREATHING_MAX_SLEEP_NANOS);
		if (sleepNanos < 1_000_000L) {
			return;
		}
		try {
			Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		lastFrameStartNanos = System.nanoTime();
	}

	private void drawFrame() {
		// See setFragmentShader()/onViewResumed() - the preserved-context
		// counterparts of what onSurfaceCreated() does for a fresh one.
		if (pendingReload) {
			pendingReload = false;
			pendingReconfigure = false;
			abandonProgressiveFrame();
			programManager.releaseForReload(device);
			// The new shader's backbuffer parameters (size/filtering) may
			// differ from the old one's - ensureTargets() below rebuilds.
			renderPipeline.releaseTargets();
			// A different (or edited) shader - never hand it the previous
			// one's stashed state.
			stateRowMigrationPending = false;
			reloadProgram();
		} else if (pendingReconfigure) {
			pendingReconfigure = false;
			abandonProgressiveFrame();
			var program = programManager.getMainProgram();
			if (program != null) {
				configureUniforms(program);
			}
		}
		if (blankFrameRequested) {
			blankFrameRequested = false;
			device.clear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			cancelCaptureFullImage();
			return;
		}
		if (redisplayRequested) {
			redisplayRequested = false;
			var surfaceProgram = programManager.getSurfaceProgram();
			var surfaceBindings = programManager.getSurfaceBindings();
			if (surfaceProgram != null && surfaceBindings != null && renderPipeline.hasTargets()) {
				renderPipeline.renderCompletedSurfacePass(surfaceBindings, surfaceProgram,
						viewWidth, viewHeight, getDisplayedBufferTexture());
				return;
			}
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
			if (stateRowMigrationPending) {
				stateRowMigrationPending = false;
				migrateStateRows(mainProgram);
			}
			if (!renderPipeline.hasTargets()) {
				cancelCaptureThumbnail();
				cancelCaptureFullImage();
				return;
			}
		}

		ensureBufferTargets();

		if (progressiveAbandonRequested) {
			progressiveAbandonRequested = false;
			abandonProgressiveFrame();
		}
		if (isProgressive()) {
			drawProgressiveStep(mainProgram, surfaceProgram, surfaceBindings);
			return;
		}
		checkAutoSwitchToProgressive(getChainRows());

		var frame = builtinUniforms.beginFrame(device, renderPipeline.getBackTexture());
		if (frame == null) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			cancelCaptureFullImage();
			return;
		}

		int height = renderPipeline.getTargetHeight();
		drawChainRows(frame.bindings(), mainProgram, 0, getChainRows(), height);
		renderPipeline.renderSurfacePass(
				surfaceBindings,
				surfaceProgram,
				frame.surfaceWidth(),
				frame.surfaceHeight(),
				getDisplayedBufferTexture());
		captureFullImage(frame.surfaceWidth(), frame.surfaceHeight());
		renderPipeline.swapTargets();
		renderPipeline.releaseStandInFrame();
		captureThumbnail(false);

		if (onRendererListener != null) {
			updateFps(frame.now());
		}

		builtinUniforms.endFrame();
	}

	// Progressive live rendering - see setProgressiveRendering(). Renders
	// the next band of rows of the frame in progress (starting a fresh
	// one first if there isn't one, or if anything else rebuilt the
	// shared bindings since - see BuiltinUniforms.getBeginFrameCount()),
	// then shows the most recently *completed* frame. A fast shader's
	// band grows to the whole frame within a few frames and this behaves
	// exactly like the normal path; a slow one's shrinks until each band
	// fits in roughly one display refresh, so no single draw ever ties up
	// the GPU for long and the rest of the screen keeps drawing.
	private void drawProgressiveStep(@NonNull GlProgram mainProgram,
			@NonNull GlProgram surfaceProgram, @NonNull ProgramBindings surfaceBindings) {
		// The rows of every pass, buffers first, count as one tall frame
		// - see getChainRows() - so bands, the countdown dot and AUTO's
		// estimates all cover the whole chain.
		int height = renderPipeline.getTargetHeight();
		int chainRows = getChainRows();
		adaptProgressiveRows(chainRows);
		if (progressiveFrame == null ||
				builtinUniforms.getBeginFrameCount() != progressiveFrameStamp) {
			progressiveFrame = builtinUniforms.beginFrame(device, renderPipeline.getBackTexture());
			if (progressiveFrame == null) {
				device.clear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
				cancelCaptureThumbnail();
				cancelCaptureFullImage();
				return;
			}
			progressiveFrameStamp = builtinUniforms.getBeginFrameCount();
			progressiveFrame.bindings().freezeValues();
			progressiveRowsDone = 0;
			progressiveStepsThisFrame = 0;
		}
		var frame = progressiveFrame;
		// A capture that ran in between may have left the shared bindings
		// pointing at its own (since deleted) tile program.
		frame.bindings().retarget(mainProgram);
		// On-demand frames (paused, RENDERMODE_WHEN_DIRTY) are rare and
		// expected to show a finished result - e.g. a quality change while
		// paused recreates the targets and redraws exactly once - so they
		// always complete the frame in one go rather than leave it part-
		// done until some later request that may never come.
		int remaining = chainRows - progressiveRowsDone;
		// ...except during a conservative start (see
		// primeConservativeStart()): that's exactly when a whole-frame draw
		// is the risk being avoided, and the quality change that triggers
		// it redraws once while still paused.
		int rows = continuousRendering || conservativeStart
				? Math.max(1, Math.min(remaining, progressiveRowsPerStep))
				: remaining;
		drawChainRows(frame.bindings(), mainProgram, progressiveRowsDone, rows, height);
		progressiveRowsDone += rows;
		progressiveLastRows = rows;
		++progressiveStepsThisFrame;
		boolean completed = progressiveRowsDone >= chainRows;
		progressiveFrameProgress = completed ? 1f : progressiveRowsDone / (float) chainRows;
		if (completed) {
			renderPipeline.swapTargets();
			// A real frame at the current size exists now - see
			// releaseTargetsKeepingLastFrame().
			renderPipeline.releaseStandInFrame();
		}
		renderPipeline.renderCompletedSurfacePass(
				surfaceBindings,
				surfaceProgram,
				frame.surfaceWidth(),
				frame.surfaceHeight(),
				getDisplayedBufferTexture());
		captureFullImage(frame.surfaceWidth(), frame.surfaceHeight());
		captureThumbnail(true);
		if (completed) {
			progressiveFrame = null;
			if (onRendererListener != null) {
				updateFps(frame.now());
			}
			builtinUniforms.endFrame();
			conservativeStart = false;
			progressiveFrameWidth = renderPipeline.getTargetWidth();
			progressiveFrameHeight = height;
			progressiveAverageBandRows = chainRows / Math.max(1, progressiveStepsThisFrame);
			// Progressive frames are slow by definition - refresh the
			// listener's display on every one, not just when the rounded
			// fps happens to change, so the band size it shows keeps up
			// with adaptation.
			if (onRendererListener != null && lastFps > 0) {
				onRendererListener.onFramesPerSecond(lastFps);
			}
			checkAutoSwitchToNormal(chainRows);
		}
	}

	// Whether the current shader has offscreen buffer passes - captures
	// that render the Image pass on its own (tiled, high-res, sequences)
	// don't support those yet.
	public boolean hasBufferPasses() {
		return !programManager.getBufferPasses().isEmpty();
	}

	// Rows in one whole frame of the pass chain: each buffer pass, then
	// the Image pass, all at the render size.
	private int getChainRows() {
		return renderPipeline.getTargetHeight() * (programManager.getBufferPasses().size() + 1);
	}

	private void configureUniforms(@NonNull GlProgram mainProgram) {
		builtinUniforms.configure(
				device,
				mainProgram,
				UniformPresence.of(device, programManager.getPassPrograms()),
				programManager.getFTimeMax(),
				programManager.getTextureResources());
	}

	private void ensureBufferTargets() {
		int width = renderPipeline.getTargetWidth();
		int height = renderPipeline.getTargetHeight();
		List<ShaderError> errors = null;
		for (var pass : programManager.getBufferPasses()) {
			if (!pass.hasTargets()) {
				if (errors == null) {
					errors = new ArrayList<>();
				}
				pass.ensureTargets(device, width, height, halfFloatBuffers, errors);
			}
		}
		if (errors != null && !errors.isEmpty()) {
			submitErrors(errors);
		}
	}

	private void updateBufferMipmapNeeds() {
		java.util.Arrays.fill(bufferNeedsMipmaps, false);
		var prepared = new ArrayList<PreparedShaderSource>();
		prepared.add(programManager.getPreparedShaderSource());
		for (var pass : programManager.getBufferPasses()) {
			prepared.add(pass.prepared);
		}
		for (var source : prepared) {
			for (int channel = 0; channel < ChannelParameters.MAX_CHANNELS; ++channel) {
				var parameters = source.getChannel(channel);
				if (parameters != null && parameters.getBuffer() != ChannelParameters.NO_BUFFER &&
						parameters.getBuffer() < bufferNeedsMipmaps.length &&
						usesMipmaps(parameters.getMinFilter())) {
					bufferNeedsMipmaps[parameters.getBuffer()] = true;
				}
			}
		}
	}

	private static boolean usesMipmaps(int minFilter) {
		return minFilter != GLES20.GL_NEAREST && minFilter != GLES20.GL_LINEAR;
	}

	// Draws rows [start, start + rows) of the pass chain (see
	// getChainRows()): buffer passes first, each into its own target,
	// swapped as soon as its last row is done so every later pass reads
	// this frame's result - then the Image pass into the main target,
	// which the caller swaps/presents. The shared bindings are retargeted
	// at each pass's program and get that pass's channels, and are left
	// pointing at the Image pass.
	private void drawChainRows(@NonNull ProgramBindings bindings, @NonNull GlProgram mainProgram,
			int start, int rows, int height) {
		var passes = programManager.getBufferPasses();
		int width = renderPipeline.getTargetWidth();
		GlTexture2D mainBackTexture = renderPipeline.getBackTexture();
		for (int done = start, end = start + rows; done < end; ) {
			int passIndex = done / height;
			int y0 = done % height;
			int n = Math.min(end - done, height - y0);
			if (passIndex < passes.size()) {
				var pass = passes.get(passIndex);
				var program = pass.getProgram();
				var framebuffer = pass.getRenderFramebuffer();
				if (program != null && framebuffer != null) {
					bindings.retarget(program);
					bindChannels(bindings, pass.prepared, passes);
					// A buffer's own `backbuffer` is its previous frame.
					bindings.setTexture(UNIFORM_BACKBUFFER, pass.getCurrentTexture());
					renderPipeline.renderPassRows(bindings, program, framebuffer,
							width, height, y0, n);
				}
				if (y0 + n >= height) {
					pass.swap();
					var texture = pass.getCurrentTexture();
					if (texture != null && pass.buffer < bufferNeedsMipmaps.length &&
							bufferNeedsMipmaps[pass.buffer]) {
						device.generateMipmap(texture);
					}
				}
			} else {
				bindings.retarget(mainProgram);
				bindChannels(bindings, programManager.getPreparedShaderSource(), passes);
				bindings.setTexture(UNIFORM_BACKBUFFER, mainBackTexture);
				renderPipeline.renderMainPassRows(bindings, mainProgram, y0, n);
			}
			done += n;
		}
		bindings.retarget(mainProgram);
	}

	private void bindChannels(@NonNull ProgramBindings bindings,
			@NonNull PreparedShaderSource source, @NonNull List<BufferPass> passes) {
		for (int channel = 0; channel < ChannelParameters.MAX_CHANNELS; ++channel) {
			var parameters = source.getChannel(channel);
			GlTexture2D texture = null;
			if (parameters != null && parameters.getBuffer() != ChannelParameters.NO_BUFFER) {
				for (var pass : passes) {
					if (pass.buffer == parameters.getBuffer()) {
						texture = pass.getCurrentTexture();
						break;
					}
				}
			}
			int offset = channel * 3;
			if (texture != null) {
				// Filtering/wrapping are per texture object (GLES2 has no
				// sampler objects), so set this channel's own right before
				// it's sampled.
				device.applyTextureParameters(texture, parameters);
				channelResolution[offset] = texture.getWidth();
				channelResolution[offset + 1] = texture.getHeight();
				channelResolution[offset + 2] = 1;
			} else {
				texture = blackTexture;
				channelResolution[offset] = 0;
				channelResolution[offset + 1] = 0;
				channelResolution[offset + 2] = 0;
			}
			bindings.setTexture(ChannelParameters.getChannelName(channel), texture);
		}
		bindings.setFloat3(UNIFORM_ST_CHANNEL_RESOLUTION, ChannelParameters.MAX_CHANNELS,
				channelResolution);
	}

	// Sizes the next band from how long the last one actually took, using
	// the same draw-to-draw interval breathing room measures (see
	// applyBreathingRoom() - eglSwapBuffers() blocks once the GPU falls
	// behind, so in continuous mode that interval tracks GPU time, minus
	// any deliberate sleep). Comfortably within a refresh: grow, up to
	// the whole frame. Overrunning: shrink in proportion, so the next
	// band lands near one refresh period.
	private void adaptProgressiveRows(int height) {
		if (height <= 0) {
			return;
		}
		// Only once the targets have actually been rebuilt at the new size
		// (see onSurfaceChanged()) - a frame or two at the old resolution
		// can still land in between, and must not use the prime up.
		long primed = primeReady ? primedFrameNanos : 0;
		if (primed > 0) {
			primedFrameNanos = 0;
			primeReady = false;
			conservativeStart = true;
			double period = NS_PER_SECOND / refreshRate;
			progressiveRowsPerStep = (int) Math.max(1,
					Math.min(height, height * (period / primed) * PROGRESSIVE_SHRINK_TARGET));
			progressiveLastRows = 0;
			lastStepIntervalNanos = 0;
			progressiveMeasuredNanos = 0;
			progressiveMeasuredRows = 0;
			progressiveMeasuredSteps = 0;
			Log.d(TAG, "conservative start: expecting ~" + primed / 1_000_000 + "ms frames, " +
					progressiveRowsPerStep + "/" + height + " rows per band");
			return;
		}
		long interval = lastStepIntervalNanos;
		lastStepIntervalNanos = 0;
		if (!continuousRendering || interval <= 0 || interval > BREATHING_MAX_MEASURED_NANOS ||
				progressiveLastRows <= 0) {
			progressiveRowsPerStep = Math.min(progressiveRowsPerStep, height);
			return;
		}
		// Feeds checkAutoSwitchToNormal()'s whole-frame estimate - this
		// interval is the cost of the *previous* band, progressiveLastRows
		// tall.
		progressiveMeasuredNanos += interval;
		progressiveMeasuredRows += progressiveLastRows;
		++progressiveMeasuredSteps;
		learnStepFloor(interval, height);
		// Every step costs at least the floor regardless of band size
		// (presenting the completed frame, compositing - see
		// learnStepFloor()), so that, not the display's nominal refresh,
		// is what "fits in about a refresh" has to mean. On a 120Hz
		// panel the floor alone can exceed 1.4 refreshes, and judging by
		// the nominal period made every band look overrun - bands only
		// ever shrank, and never grew back once the shader got cheaper.
		double period = Math.max(NS_PER_SECOND / refreshRate, stepFloorNanos);
		int rows;
		if (interval < period * PROGRESSIVE_GROW_BELOW) {
			rows = (int) Math.min(height, progressiveLastRows * 1.5 + 1);
		} else if (interval > period * PROGRESSIVE_SHRINK_ABOVE) {
			rows = (int) Math.max(1, progressiveLastRows * (period / interval) * PROGRESSIVE_SHRINK_TARGET);
		} else {
			rows = progressiveLastRows;
		}
		progressiveRowsPerStep = Math.max(1, Math.min(height, rows));
	}

	// The fixed per-step cost that no band size can get under - learnt
	// only from the smallest bands (where it's essentially all there is),
	// so a slow shader's genuinely long bands can't drag it upward and
	// gradually let big GPU-hogging bands back in. Drops straight to any
	// lower reading; rises only slowly, in case the display/system got
	// slower (e.g. an adaptive refresh rate dropping).
	private void learnStepFloor(long interval, int height) {
		boolean smallBand = progressiveLastRows <= Math.max(1, height / PROGRESSIVE_FLOOR_BAND_FRACTION);
		if (stepFloorNanos <= 0 || interval < stepFloorNanos) {
			if (smallBand || stepFloorNanos <= 0 || interval < stepFloorNanos) {
				stepFloorNanos = stepFloorNanos <= 0
						? Math.min(interval, NS_PER_SECOND / refreshRate)
						: interval;
			}
		} else if (smallBand) {
			stepFloorNanos += (interval - stepFloorNanos) * PROGRESSIVE_FLOOR_RISE_RATE;
		}
	}

	// Live preview render modes - see setLiveRenderMode().
	public static final int LIVE_RENDER_NORMAL = 0;
	public static final int LIVE_RENDER_PROGRESSIVE = 1;
	public static final int LIVE_RENDER_AUTO = 2;

	// NORMAL: every frame in one full-screen draw, exactly as it always
	// has. PROGRESSIVE: always via drawProgressiveStep(). AUTO: normal
	// until frames get slow enough to stall the rest of the screen (see
	// checkAutoSwitchToProgressive()), progressive from then until the
	// bands have grown back to whole frames for a while (see
	// checkAutoSwitchToNormal()). Switching abandons any frame in
	// progress.
	public void setLiveRenderMode(int mode) {
		if (liveRenderMode != mode) {
			liveRenderMode = mode;
			autoProgressiveActive = false;
			autoSlowFrames = 0;
			autoFastFrames = 0;
			autoNormalBackoff = 1;
			lastAutoSwitchToNormalNanos = 0;
			progressiveAbandonRequested = true;
		}
	}

	// One of STATE_ROWS_* - see migrateStateRows().
	public void setStateRowMigration(int mode) {
		stateRowMigration = mode;
	}

	// After a resolution change (not a shader switch/reload - see
	// stateRowMigrationPending), carries the bottom rows of the last
	// completed frame over into the new targets when they look like a
	// backbuffer shader's state storage - or at least row 0 regardless,
	// in ALWAYS mode - so e.g. a physics shader's bodies don't all reset
	// on every quality change. Only for shaders that sample backbuffer.
	private void migrateStateRows(@NonNull GlProgram mainProgram) {
		int mode = stateRowMigration;
		if (mode == STATE_ROWS_OFF || !device.hasUniform(mainProgram, UNIFORM_BACKBUFFER)) {
			return;
		}
		int detected = renderPipeline.detectStateRows();
		int rows = mode == STATE_ROWS_ALWAYS ? Math.max(1, detected) : detected;
		Log.d(TAG, "state rows: detected " + detected + ", migrating " + rows);
		renderPipeline.migrateStateRows(rows);
	}

	// Called (from any thread) just before something expected to make
	// frames much slower - a confirmed risky quality increase - with an
	// estimate of the new full-frame time. Puts AUTO straight into
	// progressive, and (AUTO or PROGRESSIVE) sizes the first bands from
	// the estimate instead of starting from a whole frame, so the first
	// draw at the new cost can't be the one that trips the GPU watchdog;
	// normal adaptation (and AUTO's own switch back to full frame) takes
	// over from there - once the render targets have actually been
	// rebuilt at the new size (see onSurfaceChanged()). Until the first
	// frame at that cost completes,
	// on-demand frames stay banded too (see drawProgressiveStep()). A
	// no-op in NORMAL - whole frames were explicitly asked for.
	public void primeConservativeStart(long estimatedFrameNanos) {
		if (liveRenderMode == LIVE_RENDER_NORMAL || estimatedFrameNanos <= 0) {
			return;
		}
		primedFrameNanos = estimatedFrameNanos;
		if (liveRenderMode == LIVE_RENDER_AUTO) {
			autoProgressiveActive = true;
		}
	}

	// Size of the frame being drawn progressively, and the average band
	// height over the most recently completed one (bands always span the
	// full width) - 0s until a progressive frame has completed.
	public int getProgressiveFrameWidth() {
		return progressiveFrameWidth;
	}

	public int getProgressiveFrameHeight() {
		return progressiveFrameHeight;
	}

	public int getProgressiveAverageBandRows() {
		return progressiveAverageBandRows;
	}

	// 0..1, how much of the progressive frame being built is done, as of
	// the last band drawn - drops back toward 0 as each new frame starts.
	public float getProgressiveFrameProgress() {
		return progressiveFrameProgress;
	}

	// Whether frames are currently being drawn progressively - always
	// true in PROGRESSIVE mode, whatever AUTO last decided otherwise.
	public boolean isProgressiveActive() {
		return isProgressive();
	}

	private boolean isProgressive() {
		return liveRenderMode == LIVE_RENDER_PROGRESSIVE ||
				(liveRenderMode == LIVE_RENDER_AUTO && autoProgressiveActive);
	}

	// AUTO, normal path: switch once a few full frames in a row have each
	// taken long enough that everything else on screen was visibly
	// waiting on them - and size the first band from that measurement,
	// so the very next draw already fits in about a refresh instead of
	// starting from a whole frame and shrinking.
	private void checkAutoSwitchToProgressive(int height) {
		long interval = lastStepIntervalNanos;
		lastStepIntervalNanos = 0;
		if (liveRenderMode != LIVE_RENDER_AUTO || !continuousRendering ||
				interval <= 0 || interval > BREATHING_MAX_MEASURED_NANOS) {
			return;
		}
		double period = NS_PER_SECOND / refreshRate;
		if (interval <= AUTO_PROGRESSIVE_SLOW_NANOS) {
			autoSlowFrames = 0;
			return;
		}
		if (++autoSlowFrames < AUTO_PROGRESSIVE_SLOW_FRAMES || height <= 0) {
			return;
		}
		autoSlowFrames = 0;
		autoFastFrames = 0;
		autoProgressiveActive = true;
		// Back here soon after deciding full frame was fast enough means
		// that decision was too optimistic for this shader - ask for more
		// evidence next time, so the two can't keep flipping each other.
		long sinceNormal = System.nanoTime() - lastAutoSwitchToNormalNanos;
		if (lastAutoSwitchToNormalNanos != 0 && sinceNormal < AUTO_FLAP_WINDOW_NANOS) {
			autoNormalBackoff = Math.min(autoNormalBackoff * 2, AUTO_MAX_BACKOFF);
			Log.d(TAG, "auto live render: re-entered progressive " + sinceNormal / 1_000_000 +
					"ms after leaving it, now needing " + AUTO_NORMAL_FAST_FRAMES * autoNormalBackoff +
					" fast frames to leave again");
		}
		progressiveLastRows = 0;
		progressiveMeasuredNanos = 0;
		progressiveMeasuredRows = 0;
		progressiveMeasuredSteps = 0;
		progressiveRowsPerStep = (int) Math.max(1,
				Math.min(height, height * (period / interval) * PROGRESSIVE_SHRINK_TARGET));
		Log.d(TAG, "auto live render: frames ~" + interval / 1_000_000 +
				"ms, switching to progressive (" + progressiveRowsPerStep + "/" + height + " rows per band)");
	}

	// AUTO, progressive path, on each completed frame: estimates what a
	// whole frame would cost drawn in one go (band time per row, from
	// every band measured since the last check, times the frame height -
	// see adaptProgressiveRows()), and goes back to normal once that's
	// comfortably fast for a few frames running. The gap between this
	// and AUTO_PROGRESSIVE_SLOW_NANOS is what stops a shader hovering
	// near one threshold from flip-flopping between the two modes.
	private void checkAutoSwitchToNormal(int height) {
		long nanos = progressiveMeasuredNanos;
		long rows = progressiveMeasuredRows;
		long steps = progressiveMeasuredSteps;
		progressiveMeasuredNanos = 0;
		progressiveMeasuredRows = 0;
		progressiveMeasuredSteps = 0;
		if (liveRenderMode != LIVE_RENDER_AUTO || rows <= 0 || height <= 0) {
			return;
		}
		// Only the time each band spent above the fixed per-step floor
		// scales with rows (see learnStepFloor()) - a full frame drawn in
		// one go pays that floor once, not once per band.
		long floor = (long) stepFloorNanos;
		long work = Math.max(0, nanos - steps * floor);
		long estimatedFullFrameNanos = work * height / rows + floor;
		if (estimatedFullFrameNanos >= AUTO_NORMAL_FAST_NANOS) {
			autoFastFrames = 0;
			return;
		}
		if (++autoFastFrames >= AUTO_NORMAL_FAST_FRAMES * autoNormalBackoff) {
			autoFastFrames = 0;
			autoProgressiveActive = false;
			lastAutoSwitchToNormalNanos = System.nanoTime();
			Log.d(TAG, "auto live render: whole frame now estimated ~" +
					estimatedFullFrameNanos / 1_000_000 + "ms, switching to normal");
		}
	}

	// GL thread only. Drops a part-rendered progressive frame - used
	// whenever what it was being drawn against no longer holds (targets
	// cleared or recreated, program reloaded, mode switched). Doesn't
	// call endFrame(): it never completed, so `frame` shouldn't advance.
	private void abandonProgressiveFrame() {
		progressiveFrame = null;
		progressiveRowsDone = 0;
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

	// Set by ShaderViewManager as each screenshot or capture starts, so
	// a sequence keeps one format throughout even if Settings changes
	// while it runs in the background.
	public void setCaptureEncoding(@NonNull ImageEncoding encoding) {
		captureEncoding = encoding;
	}

	// Blocks (off the GL thread - callers should invoke this from a
	// background thread) until the next frame renders, then returns a
	// full-resolution image (see setCaptureEncoding()) of exactly what's on screen - no toolbar,
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
		if (hasBufferPasses()) {
			// The tiled path renders the Image pass on its own, without
			// the buffer chain it reads - so a buffer shader captures the
			// live renderer's own completed frame instead, which only
			// exists at the live render size.
			return oversample == 1 && isLiveRenderSize(width, height)
					? renderPipeline.readCompletedFrame(captureEncoding)
					: null;
		}
		HighResScreenshotCapture.ProgressListener innerListener = progressListener == null
				? null
				: new HighResScreenshotCapture.ProgressListener() {
					@Override
					public boolean onProgress(int tilesDone, int totalTiles) {
						return progressListener.onProgress(tilesDone, totalTiles);
					}

					@Override
					public boolean keepPartialOnStop() {
						return progressListener.keepPartialOnStop();
					}
				};
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
			// From the bitmap's own size, not width/height: a stopped
			// capture's partial result is cropped to the finished tiles.
			bitmap = HighResScreenshotCapture.boxDownscale(bitmap,
					Math.max(1, bitmap.getWidth() / oversample),
					Math.max(1, bitmap.getHeight() / oversample));
			if (bitmap == null) {
				return null;
			}
		}
		return captureEncoding.encode(bitmap);
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
		if (hasBufferPasses()) {
			ensureBufferTargets();
		}
		// Every buffer pass, then the Image pass - a whole frame of the
		// chain, exactly as live rendering draws it - in timed bands of
		// rows rather than one draw, so an expensive shader's step can't
		// run past the driver's deadline and reset the GPU (which takes
		// the whole app down).
		int height = renderPipeline.getTargetHeight();
		int chainRows = getChainRows();
		int width = Math.max(1, renderPipeline.getTargetWidth());
		for (int y0 = 0; y0 < chainRows; ) {
			int rows = Math.min(stepBandRows, chainRows - y0);
			long start = System.nanoTime();
			drawChainRows(frame.bindings(), mainProgram, y0, rows, height);
			GLES20.glFinish();
			long nanos = System.nanoTime() - start;
			y0 += rows;
			// The same sizing the tiled capture's pieces use, as an area.
			int side = HighResScreenshotCapture.nextChunkSize(
					(int) Math.sqrt((double) stepBandRows * width), width, rows, nanos,
					Integer.MAX_VALUE);
			stepBandRows = Math.max(1, (int) ((long) side * side / width));
		}
		renderPipeline.swapTargets();
		builtinUniforms.endFrame();
	}

	// Rows per band for stepSimulationFrame() - kept between steps, so a
	// sequence settles on its shader's speed after the first frame.
	private int stepBandRows = 1;

	// Whether width x height is the live render size - the only size a
	// buffer shader can be captured at (see captureHighResScreenshot()).
	public boolean isLiveRenderSize(int width, int height) {
		return width == renderPipeline.getTargetWidth() &&
				height == renderPipeline.getTargetHeight();
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
		// Target size is fixed for the whole run, so safe to decide once,
		// up front, whether a SequenceFeedback is worth allocating at
		// all - see HighResScreenshotCapture.isFeedbackEligible()'s own
		// doc for why this is a texture-size question, not a singlePass-
		// only one: HighResScreenshotCapture.renderTiles() now writes
		// each tile directly into this texture at its own position
		// whenever one exists, tiled or not, so most tiled sequences
		// (anything that isn't tiled specifically because the
		// resolution itself exceeds GL_MAX_TEXTURE_SIZE) still get full
		// persistent high-res feedback accumulation. Null either way
		// this returns false (not eligible, so every frame would just
		// ignore it anyway - see captureHighResScreenshot()'s own
		// feedback-is-null behavior) or if allocation itself failed
		// (e.g. OOM) - callers degrade gracefully to the pre-existing
		// per-frame-from-the-live-backbuffer behavior either way, just
		// without the persistent high-res feedback a healthy allocation
		// would have given them.
		HighResScreenshotCapture.SequenceFeedback feedback = !hasBufferPasses() &&
				HighResScreenshotCapture.isFeedbackEligible(device, width * oversample, height * oversample)
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
				// Deliberately not "i > 0 || skipFrames > 0" - the skip
				// phase above already did exactly skipFrames steps on
				// its own; stepping again here before capturing frame 0
				// would make the first saved frame skipFrames + 1 steps
				// in instead of exactly skipFrames, same off-by-one the
				// no-skip case never had (i == 0 there already means
				// "capture at the current, not-yet-stepped state").
				if (i > 0) {
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
		// stepSimulationFrame() draws into the live targets - through
		// framebuffers of this context's own (see beginSharedContext()).
		renderPipeline.beginSharedContext();
		try {
			return captureHighResScreenshotSequence(width, height, oversample,
					preserveBackbufferEdges, skipFrames, numFrames, virtualFps, bucketSize,
					tileOrderSupplier, tileProgressListener, tilePreviewListener,
					sequenceProgressListener, frameListener);
		} finally {
			renderPipeline.endSharedContext();
		}
	}

	private void resetFps() {
		smoothedFrameNanos = 0;
		lastFps = 0;
		nextFpsUpdate = 0;
	}

	private void submitErrors(@NonNull List<ShaderError> errors) {
		if (onRendererListener != null) {
			onRendererListener.onInfoLog(errors);
		}
	}

	private void discardContextResources() {
		blackTexture = null;
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

	private void captureThumbnail(boolean fromBack) {
		synchronized (thumbnailLock) {
			var surfaceBindings = programManager.getSurfaceBindings();
			var surfaceProgram = programManager.getSurfaceProgram();
			if (captureThumbnail &&
					surfaceBindings != null &&
					surfaceProgram != null) {
				thumbnail = renderPipeline.captureThumbnail(
						surfaceBindings,
						surfaceProgram,
						fromBack);
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
				fullImage = renderPipeline.readPixels(width, height, captureEncoding);
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

	// Exponential moving average of frame *time* (averaging per-frame
	// fps instead skews high whenever frame times vary), weighted by how
	// much real time each frame covered - FPS_SMOOTHING_NANOS is roughly
	// the window it averages over. Used to be a plain running mean since
	// the shader loaded, which took ages (tens of thousands of frames, in
	// the worst case) to reflect a big change - e.g. dropping from 60fps
	// to progressive's 0.5fps. A frame as long as the window, or longer,
	// now moves the reading most or all of the way at once.
	private void updateFps(long now) {
		long delta = now - lastRender;
		lastRender = now;
		if (delta <= 0) {
			return;
		}
		double periodNanos = NS_PER_SECOND / refreshRate;
		double frameNanos = Math.max(delta, periodNanos);
		double previous = smoothedFrameNanos;
		double alpha = 1.0 - Math.exp(-frameNanos / FPS_SMOOTHING_NANOS);
		smoothedFrameNanos = previous <= 0 ? frameNanos : previous + (frameNanos - previous) * alpha;

		if (now > nextFpsUpdate) {
			// At least 1 while frames are completing at all - listeners
			// treat 0 as "no reading yet" (see getSmoothedFps() for the
			// unrounded value).
			int fps = (int) Math.max(1, Math.round(NS_PER_SECOND / smoothedFrameNanos));
			if (fps != lastFps && onRendererListener != null) {
				onRendererListener.onFramesPerSecond(fps);
			}
			lastFps = fps;
			nextFpsUpdate = now + FPS_UPDATE_FREQUENCY_NS;
		}
	}

	// The smoothed frame rate updateFps() reports, unrounded - so a slow
	// shader can show "0.3 fps" instead of an integer that can't go below
	// 1. 0 until a frame has completed.
	public float getSmoothedFps() {
		double nanos = smoothedFrameNanos;
		return nanos > 0 ? (float) (NS_PER_SECOND / nanos) : 0f;
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
