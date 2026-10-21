package enk.danwills.android.enkishadereditor.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.database.DataRecords;
import enk.danwills.android.enkishadereditor.database.DataSource;
import enk.danwills.android.enkishadereditor.database.Database;
import enk.danwills.android.enkishadereditor.opengl.HeadlessShaderCompiler;
import enk.danwills.android.enkishadereditor.opengl.ModuleDirective;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinary;
import enk.danwills.android.enkishadereditor.opengl.ProgramBinaryCache;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefine;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefineParser;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.opengl.ShaderSourcePreparer;
import enk.danwills.android.enkishadereditor.pack.PackShow;
import enk.danwills.android.enkishadereditor.project.LegacyShaderProjectSource;
import enk.danwills.android.enkishadereditor.widget.ShaderView;

// Plays a presentation pack (see PackShow): its shaders full screen, one
// after another in a loop, under one soundtrack - a tracker module, one
// shader's Sound pass played under them all, or else each shader's own
// sound. A timeline's scenes can also move a shader's #defines along
// (made live, as Adjust Defines does). Tap for the next shader; back
// leaves.
//
// So a change of shader doesn't stall the show, every slide's passes
// are compiled ahead on a background thread (HeadlessShaderCompiler.
// warm()) into a cache the renderer loads binaries from, and the change
// itself dips briefly through black until the new shader's first frame.
public class ShowActivity extends AppCompatActivity {
	private static final String TAG = "ShowActivity";
	public static final String PACK_ID = "pack_id";
	// No title text and no toasts - nothing but the shaders (Ultra Rest
	// Area, which shows no Android UI).
	public static final String QUIET = "quiet";
	private static final long TITLE_MILLIS = 2500;
	private static final long KEY_FRAME_MILLIS = 33;
	private static final long FADE_MILLIS = 250;
	// Back up anyway if the new shader's first frame never says so.
	private static final long MAX_DARK_MILLIS = 2500;
	private static final Pattern MOD_LINE = Pattern.compile(
			"(?m)^[ \\t]*///mod:[A-Za-z0-9_]*[ \\t]*$");

	public static void start(@NonNull Context context, @NonNull String packId) {
		start(context, packId, false);
	}

	public static void start(@NonNull Context context, @NonNull String packId, boolean quiet) {
		context.startActivity(new Intent(context, ShowActivity.class)
				.putExtra(PACK_ID, packId)
				.putExtra(QUIET, quiet));
	}

	// One shader's turn, ready to run. sound: what's heard, shown under
	// the title - a module's name, "Shader audio", or null for nothing.
	private record Slide(@NonNull String title, @NonNull String source,
			@Nullable String[] buffers, float quality, long shaderId, long millis,
			@Nullable PackShow.Scene scene, @NonNull Map<String, ShaderDefine.Type> live,
			@Nullable String sound, @NonNull Map<String, String> texts) {
	}

	// What load() makes of a pack.
	private record Loaded(@NonNull List<Slide> slides, boolean continuousSound,
			@Nullable String module) {
	}

	// Compiled programs for this show only, by shader, pass and source -
	// the slides' sources are the show's own (soundtrack, live defines),
	// so they're kept out of the library's cache.
	private static final class ShowBinaryCache implements ProgramBinaryCache {
		private final Map<String, ProgramBinary> binaries = new ConcurrentHashMap<>();

		@Nullable
		@Override
		public ProgramBinary get(long shaderId, @NonNull String sourceHash,
				@NonNull String driverSignature) {
			return binaries.get(shaderId + "/-/" + sourceHash + "/" + driverSignature);
		}

		@Override
		public void put(long shaderId, @NonNull ProgramBinary binary,
				@NonNull String sourceHash, @NonNull String driverSignature, long compileTimeMs) {
			binaries.put(shaderId + "/-/" + sourceHash + "/" + driverSignature, binary);
		}

		@Nullable
		@Override
		public ProgramBinary getBuffer(long shaderId, int buffer, @NonNull String sourceHash,
				@NonNull String driverSignature) {
			return binaries.get(shaderId + "/" + buffer + "/" + sourceHash + "/" +
					driverSignature);
		}

		@Override
		public void putBuffer(long shaderId, int buffer, @NonNull ProgramBinary binary,
				@NonNull String sourceHash, @NonNull String driverSignature) {
			binaries.put(shaderId + "/" + buffer + "/" + sourceHash + "/" + driverSignature,
					binary);
		}
	}

	private ShaderView shaderView;
	private TextView titleView;
	// Black, over the shader, for the dip between slides.
	private View curtain;
	private final ShowBinaryCache binaryCache = new ShowBinaryCache();
	private final HeadlessShaderCompiler warmer = new HeadlessShaderCompiler();
	private final Runnable liftCurtain = new Runnable() {
		@Override
		public void run() {
			handler.removeCallbacks(this);
			if (curtain != null) {
				curtain.animate().cancel();
				curtain.animate().alpha(0f).setDuration(FADE_MILLIS);
			}
		}
	};
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable next = this::nextSlide;
	private final Runnable hideTitle = () -> {
		if (titleView != null) {
			titleView.animate().alpha(0f).setDuration(600);
		}
	};
	private final Runnable keyFrame = new Runnable() {
		@Override
		public void run() {
			applyKeys();
			handler.postDelayed(this, KEY_FRAME_MILLIS);
		}
	};

	@NonNull
	private List<Slide> slides = new ArrayList<>();
	private int current = -1;
	private long slideStart;
	private long slideElapsed;
	private int failedInARow;
	private boolean started;
	private boolean quiet;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		String packId = getIntent().getStringExtra(PACK_ID);
		if (packId == null) {
			finish();
			return;
		}
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		quiet = getIntent().getBooleanExtra(QUIET, false);

		var taps = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
			@Override
			public boolean onSingleTapUp(@NonNull MotionEvent e) {
				nextSlide();
				return true;
			}
		});
		// Taps go to the shader as well (ShaderView takes every touch for
		// it), so watch them on the way.
		shaderView = new ShaderView(this) {
			@SuppressLint("ClickableViewAccessibility")
			@Override
			public boolean onTouchEvent(MotionEvent event) {
				taps.onTouchEvent(event);
				return super.onTouchEvent(event);
			}
		};
		shaderView.getRenderer().setOnRendererListener(new ShaderRenderer.OnRendererListener() {
			@Override
			public void onInfoLog(@NonNull List<ShaderError> infoLog) {
				// GL thread: a shader that doesn't compile here is passed.
				runOnUiThread(() -> {
					if (!infoLog.isEmpty()) {
						onSlideFailed();
					} else {
						failedInARow = 0;
					}
				});
			}

			@Override
			public void onFramesPerSecond(int fps) {
			}

			@Override
			public void onRequestAudioCapture() {
				// As in PreviewActivity: `audioSpectrum` falls back to the
				// microphone here.
			}

			@Override
			public void onFirstFrameAfterResume() {
				// GL thread: the new slide is showing - up comes the curtain.
				runOnUiThread(liftCurtain);
			}
		});
		shaderView.getRenderer().setProgramBinaryCache(binaryCache);

		curtain = new View(this);
		curtain.setBackgroundColor(0xff000000);

		titleView = new TextView(this);
		titleView.setTextColor(0xffffffff);
		titleView.setShadowLayer(8f, 0f, 2f, 0xff000000);
		titleView.setTextSize(20f);
		int pad = (int) (24 * getResources().getDisplayMetrics().density);
		titleView.setPadding(pad, pad, pad, pad);
		titleView.setAlpha(0f);
		if (quiet) {
			titleView.setVisibility(View.GONE);
		}

		var layout = new FrameLayout(this);
		layout.addView(shaderView);
		layout.addView(curtain);
		layout.addView(titleView, new FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM | Gravity.START));
		setContentView(layout);

		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
						View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
						View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
						View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_FULLSCREEN);

		Context appContext = getApplicationContext();
		new Thread(() -> {
			var loaded = load(appContext, packId);
			runOnUiThread(() -> {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (loaded == null || loaded.slides().isEmpty()) {
					nothingToPlay();
					finish();
					return;
				}
				slides = loaded.slides();
				shaderView.getRenderer().setShowSoundtrack(loaded.continuousSound(),
						loaded.module());
				warm(slides);
				nextSlide();
			});
		}, "LoadShow").start();
	}

	@Override
	protected void onStart() {
		super.onStart();
		started = true;
		shaderView.onResume();
		if (current >= 0) {
			// Carry on with the slide where it was.
			slideStart = SystemClock.uptimeMillis() - slideElapsed;
			schedule(slides.get(current).millis() - slideElapsed);
		}
	}

	@Override
	protected void onStop() {
		super.onStop();
		started = false;
		if (current >= 0) {
			slideElapsed = SystemClock.uptimeMillis() - slideStart;
		}
		handler.removeCallbacksAndMessages(null);
		shaderView.onPause();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		warmer.cancel();
		handler.removeCallbacksAndMessages(null);
		if (shaderView != null) {
			shaderView.getRenderer().releaseSound();
		}
	}

	private void nothingToPlay() {
		if (!quiet) {
			Toast.makeText(this, R.string.show_nothing_to_play, Toast.LENGTH_LONG).show();
		}
	}

	// Every slide's passes compiled ahead, the next ones first.
	private void warm(@NonNull List<Slide> slides) {
		var passes = new ArrayList<HeadlessShaderCompiler.Passes>();
		for (int i = 1; i <= slides.size(); ++i) {
			Slide slide = slides.get(i % slides.size());
			String[] buffers = null;
			if (slide.buffers() != null) {
				buffers = new String[slide.buffers().length];
				for (int b = 0; b < buffers.length; ++b) {
					buffers[b] = ShaderView.removeNonAscii(slide.buffers()[b]);
				}
			}
			passes.add(new HeadlessShaderCompiler.Passes(slide.shaderId(),
					ShaderView.removeNonAscii(slide.source()), buffers));
		}
		new Thread(() -> warmer.warm(passes, binaryCache), "WarmShow").start();
	}

	// Down goes the curtain, then the next slide - straight away for the
	// first, which starts dark.
	private void nextSlide() {
		if (slides.isEmpty()) {
			return;
		}
		handler.removeCallbacks(next);
		handler.removeCallbacks(liftCurtain);
		curtain.animate().cancel();
		if (current < 0 || curtain.getAlpha() >= 1f) {
			showNextSlide();
		} else {
			curtain.animate().alpha(1f).setDuration(FADE_MILLIS)
					.withEndAction(this::showNextSlide);
		}
	}

	private void showNextSlide() {
		if (slides.isEmpty() || isFinishing()) {
			return;
		}
		current = (current + 1) % slides.size();
		Slide slide = slides.get(current);
		var renderer = shaderView.getRenderer();
		renderer.getLiveDefines().clear();
		slideStart = SystemClock.uptimeMillis();
		slideElapsed = 0;
		applyKeys();
		shaderView.getRenderer().setShaderTexts(slide.texts(), false);
		shaderView.setFragmentShader(slide.source(), slide.buffers(), slide.quality(),
				slide.shaderId());
		if (slide.sound() != null) {
			var text = new SpannableString(slide.title() + "\n\u266a " + slide.sound());
			text.setSpan(new RelativeSizeSpan(0.7f), slide.title().length(), text.length(),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			titleView.setText(text);
		} else {
			titleView.setText(slide.title());
		}
		titleView.animate().cancel();
		titleView.setAlpha(1f);
		handler.removeCallbacks(hideTitle);
		handler.postDelayed(hideTitle, TITLE_MILLIS);
		handler.postDelayed(liftCurtain, MAX_DARK_MILLIS);
		if (started) {
			// The dip out of this slide comes out of its time.
			schedule(slide.millis() - FADE_MILLIS);
		}
	}

	private void schedule(long millis) {
		handler.removeCallbacks(next);
		handler.removeCallbacks(keyFrame);
		handler.postDelayed(next, Math.max(0, millis));
		if (current >= 0 && !slides.get(current).live().isEmpty()) {
			handler.post(keyFrame);
		}
	}

	private void onSlideFailed() {
		if (++failedInARow >= slides.size()) {
			nothingToPlay();
			finish();
			return;
		}
		// Not straight away, so a run of failures can't spin.
		handler.removeCallbacks(next);
		handler.postDelayed(next, 500);
	}

	// A timeline scene's #defines, where they are now.
	private void applyKeys() {
		if (current < 0) {
			return;
		}
		Slide slide = slides.get(current);
		if (slide.scene() == null || slide.live().isEmpty()) {
			return;
		}
		float t = (SystemClock.uptimeMillis() - slideStart) / 1000f;
		var liveDefines = shaderView.getRenderer().getLiveDefines();
		for (var entry : slide.scene().definesAt(t).entrySet()) {
			ShaderDefine.Type type = slide.live().get(entry.getKey());
			if (type != null) {
				float[] v = new float[4];
				System.arraycopy(entry.getValue(), 0, v, 0, Math.min(4, entry.getValue().length));
				liveDefines.set(entry.getKey(), type, v);
			}
		}
	}

	// The show's slides, from the database - off the UI thread.
	@Nullable
	private static Loaded load(@NonNull Context context, @NonNull String packId) {
		DataSource dataSource = Database.getInstance(context).getDataSource();
		DataRecords.ShaderPack pack = dataSource.pack.getPack(packId);
		PackShow show = pack != null ? PackShow.parse(pack.show()) : null;
		if (show == null) {
			show = PackShow.simple(PackShow.DEFAULT_SECONDS, null, null);
		}
		var shaders = new ArrayList<DataRecords.Shader>();
		for (long id : dataSource.pack.getShaderIds(packId)) {
			var shader = dataSource.shader.getShader(id);
			if (shader != null) {
				shaders.add(shader);
			}
		}
		if (shaders.isEmpty()) {
			return null;
		}

		// A Sound pass soundtrack: that shader's, put in every slide.
		String soundtrackSource = null;
		if (show.soundShader() != null) {
			for (var shader : shaders) {
				if (PackShow.matchesName(shader.name(), show.soundShader())) {
					for (String buffer : dataSource.buffer.getBufferSources(shader.id())) {
						if (ShaderSourcePreparer.isSoundSource(buffer)) {
							soundtrackSource = buffer;
							break;
						}
					}
					break;
				}
			}
			if (soundtrackSource == null) {
				Log.w(TAG, "No Sound pass in " + show.soundShader());
			}
		}
		boolean soundtrack = show.module() != null || soundtrackSource != null;
		String shaderAudio = context.getString(R.string.show_shader_audio);
		String showSound = show.module() != null ? show.module()
				: soundtrackSource != null ? shaderAudio : null;

		var slides = new ArrayList<Slide>();
		if (show.scenes().isEmpty()) {
			for (var shader : shaders) {
				slides.add(slide(dataSource, shader, show.seconds() * 1000L, null,
						soundtrack, soundtrackSource, show.quality(), showSound, shaderAudio));
			}
		} else {
			for (var scene : show.scenes()) {
				for (var shader : shaders) {
					if (PackShow.matchesName(shader.name(), scene.shader())) {
						slides.add(slide(dataSource, shader, (long) (scene.seconds() * 1000),
								scene, soundtrack, soundtrackSource, show.quality(), showSound,
								shaderAudio));
						break;
					}
				}
			}
		}
		return new Loaded(slides, soundtrack, show.module());
	}

	@NonNull
	private static Slide slide(@NonNull DataSource dataSource, @NonNull DataRecords.Shader shader,
			long millis, @Nullable PackShow.Scene scene, boolean soundtrack,
			@Nullable String soundtrackSource, float quality, @Nullable String showSound,
			@NonNull String shaderAudio) {
		var session = new LegacyShaderProjectSource(shader,
				dataSource.buffer.getBufferSources(shader.id()),
				dataSource.text.getTexts(shader.id())).openSession();
		String source = session.getEntryPointSource();
		String[] buffers = session.getProject().getBufferSources();
		// Each shader's own sound: its module, else its Sound pass.
		String sound = showSound;
		if (!soundtrack) {
			String[] all = new String[1 + (buffers != null ? buffers.length : 0)];
			all[0] = source;
			if (buffers != null) {
				System.arraycopy(buffers, 0, all, 1, buffers.length);
			}
			sound = ModuleDirective.find(all);
			for (int i = 0; sound == null && buffers != null && i < buffers.length; ++i) {
				if (buffers[i] != null && ShaderSourcePreparer.isSoundSource(buffers[i])) {
					sound = shaderAudio;
				}
			}
		}
		if (soundtrack) {
			// The show's soundtrack in place of the shader's own sound.
			source = MOD_LINE.matcher(source).replaceAll("");
			buffers = buffers != null ? buffers.clone() : new String[0];
			int free = -1;
			for (int i = 0; i < buffers.length; ++i) {
				if (buffers[i] == null) {
					continue;
				}
				if (ShaderSourcePreparer.isSoundSource(buffers[i])) {
					buffers[i] = null;
				} else {
					buffers[i] = MOD_LINE.matcher(buffers[i]).replaceAll("");
				}
			}
			for (int i = 0; i < buffers.length; ++i) {
				if (buffers[i] == null) {
					free = i;
					break;
				}
			}
			if (soundtrackSource != null) {
				if (free >= 0) {
					buffers[free] = soundtrackSource;
				} else {
					Log.w(TAG, "No buffer free for the soundtrack in " + shader.name());
				}
			}
		}
		// A scene's keyed #defines become uniforms, where they can.
		var live = new LinkedHashMap<String, ShaderDefine.Type>();
		if (scene != null && !scene.keys().isEmpty()) {
			Set<String> keyed = new HashSet<>();
			for (var key : scene.keys()) {
				keyed.addAll(key.defines().keySet());
			}
			List<ShaderDefine> defines = ShaderDefineParser.parseUsedDefines(source);
			var liveDefines = new ArrayList<ShaderDefine>();
			for (ShaderDefine define : defines) {
				if (keyed.contains(define.name) && define.canBeLive() &&
						!ShaderDefineParser.isUsedInConditional(source, define)) {
					liveDefines.add(define);
					live.put(define.name, define.type);
				}
			}
			if (!liveDefines.isEmpty()) {
				source = ShaderDefineParser.toLiveSource(source, defines, liveDefines);
			}
		}
		String title = shader.name() != null ? shader.name() : "";
		int separator = title.indexOf(DataRecords.ShaderInfo.PACK_NAME_SEPARATOR);
		if (separator >= 0) {
			title = title.substring(separator + DataRecords.ShaderInfo.PACK_NAME_SEPARATOR.length());
		}
		return new Slide(title, source, buffers, quality > 0 ? quality : shader.quality(),
				shader.id(), millis, scene, live, sound, session.getProject().getTexts());
	}
}
