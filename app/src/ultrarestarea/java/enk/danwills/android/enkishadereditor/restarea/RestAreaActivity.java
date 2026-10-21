package enk.danwills.android.enkishadereditor.restarea;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.activity.ShowActivity;
import enk.danwills.android.enkishadereditor.opengl.LiveDefines;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefine;
import enk.danwills.android.enkishadereditor.opengl.ShaderError;
import enk.danwills.android.enkishadereditor.opengl.ShaderRenderer;
import enk.danwills.android.enkishadereditor.resource.Resources;
import enk.danwills.android.enkishadereditor.widget.ShaderView;

// Ultra Rest Area's menu - its launcher, and its only screen of its own.
// Nothing Android is drawn: one full-screen shader (res/raw/rest_menu.glsl)
// draws every set as a blob. This activity owns the layout and the
// hit-testing and hands them to the shader as uniforms (through the
// renderer's LiveDefines, which bind by name); a tap on a set grows it to
// fill the screen and plays the set with ShowActivity, quietly - no titles
// or toasts. Back from the set returns here.
public class RestAreaActivity extends AppCompatActivity {
	private static final int MAX_TILES = 4;
	private static final float QUALITY = 0.5f;
	private static final long PRESS_MILLIS = 650;
	// Tiles are hit a little outside their drawn edge.
	private static final float HIT_SLOP = 1.15f;

	private ShaderView shaderView;
	private final List<BundledSets.Set> sets = BundledSets.SETS;
	// Each tile's x, y and radius, in the shader's units (the screen's
	// shorter side is 2, centred).
	private final float[][] tiles = new float[MAX_TILES][3];
	private int width;
	private int height;
	private int focus = -1;
	private volatile boolean setsReady;
	private ValueAnimator pressing;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		shaderView = new ShaderView(this) {
			@SuppressLint("ClickableViewAccessibility")
			@Override
			public boolean onTouchEvent(MotionEvent event) {
				onTouch(event);
				return super.onTouchEvent(event);
			}

			@Override
			protected void onSizeChanged(int w, int h, int oldw, int oldh) {
				super.onSizeChanged(w, h, oldw, oldh);
				layOut(w, h);
			}
		};
		shaderView.getRenderer().setOnRendererListener(new ShaderRenderer.OnRendererListener() {
			@Override
			public void onInfoLog(@NonNull List<ShaderError> infoLog) {
			}

			@Override
			public void onFramesPerSecond(int fps) {
			}

			@Override
			public void onRequestAudioCapture() {
			}

			@Override
			public void onFirstFrameAfterResume() {
			}
		});
		setContentView(shaderView);
		hideSystemUi();

		var names = new StringBuilder();
		for (int i = 0; i < Math.min(sets.size(), MAX_TILES); ++i) {
			names.append(i > 0 ? "\n" : "").append(sets.get(i).title());
		}
		var renderer = shaderView.getRenderer();
		renderer.setShaderTexts(Map.of("names", names.toString()), false);
		try {
			shaderView.setFragmentShader(Resources.loadRawResource(this, R.raw.rest_menu),
					QUALITY, 0);
		} catch (IOException e) {
			finish();
			return;
		}
		sendState();

		var appContext = getApplicationContext();
		new Thread(() -> {
			BundledSets.install(appContext);
			setsReady = true;
		}, "InstallSets").start();
	}

	@Override
	protected void onResume() {
		super.onResume();
		hideSystemUi();
		// Back from a set: the menu as it was, nothing pressed.
		if (pressing != null) {
			pressing.cancel();
			pressing = null;
		}
		focus = -1;
		set("press", 0f);
		sendState();
		shaderView.onResume();
	}

	@Override
	protected void onPause() {
		super.onPause();
		shaderView.onPause();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			hideSystemUi();
		}
	}

	private void hideSystemUi() {
		getWindow().getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
						View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
						View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
						View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
						View.SYSTEM_UI_FLAG_FULLSCREEN);
	}

	// The tiles down the screen held upright, across it held sideways.
	private void layOut(int w, int h) {
		width = w;
		height = h;
		int count = Math.min(sets.size(), MAX_TILES);
		if (w <= 0 || h <= 0 || count == 0) {
			return;
		}
		float aspect = Math.max(w, h) / (float) Math.min(w, h);
		float step = 2f * aspect / (count + 1);
		float radius = Math.min(0.42f, step * 0.36f);
		for (int i = 0; i < count; ++i) {
			float along = aspect - (i + 1) * step;
			if (h >= w) {
				tiles[i][0] = 0f;
				tiles[i][1] = along;
			} else {
				tiles[i][0] = -along;
				tiles[i][1] = 0f;
			}
			tiles[i][2] = radius;
		}
		sendState();
	}

	// Everything the shader draws from - see rest_menu.glsl.
	private void sendState() {
		int count = Math.min(sets.size(), MAX_TILES);
		float[] lengths = new float[4];
		for (int i = 0; i < MAX_TILES; ++i) {
			float hue = 0.55f + i / (float) Math.max(1, count) * 0.8f;
			set("tile" + i, ShaderDefine.Type.VEC4,
					new float[]{tiles[i][0], tiles[i][1], tiles[i][2], hue});
			if (i < count) {
				lengths[i] = sets.get(i).title().length();
			}
		}
		set("tileCount", ShaderDefine.Type.INT, new float[]{count, 0, 0, 0});
		set("labelLengths", ShaderDefine.Type.VEC4, lengths);
		set("focus", focus);
	}

	private void set(@NonNull String name, float value) {
		set(name, ShaderDefine.Type.FLOAT, new float[]{value, 0, 0, 0});
	}

	private void set(@NonNull String name, @NonNull ShaderDefine.Type type,
			@NonNull float[] value) {
		LiveDefines live = shaderView.getRenderer().getLiveDefines();
		live.set(name, type, value);
	}

	// The tile under (x, y) in view pixels, or -1.
	private int tileAt(float x, float y) {
		float shorter = Math.min(width, height);
		if (shorter <= 0) {
			return -1;
		}
		float ux = (x * 2f - width) / shorter;
		float uy = ((height - y) * 2f - height) / shorter;
		for (int i = 0; i < Math.min(sets.size(), MAX_TILES); ++i) {
			float dx = ux - tiles[i][0];
			float dy = uy - tiles[i][1];
			float reach = tiles[i][2] * HIT_SLOP;
			if (dx * dx + dy * dy < reach * reach) {
				return i;
			}
		}
		return -1;
	}

	private void onTouch(@NonNull MotionEvent event) {
		if (pressing != null) {
			return;
		}
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
				int over = event.getPointerCount() == 1 ? tileAt(event.getX(), event.getY()) : -1;
				if (over != focus) {
					focus = over;
					set("focus", focus);
				}
			}
			case MotionEvent.ACTION_UP -> {
				int over = tileAt(event.getX(), event.getY());
				if (over >= 0 && over == focus && setsReady) {
					play(over);
				} else {
					focus = -1;
					set("focus", -1f);
				}
			}
			case MotionEvent.ACTION_CANCEL -> {
				focus = -1;
				set("focus", -1f);
			}
			default -> {
			}
		}
	}

	// The tapped set grows to fill the screen, then plays.
	private void play(int tile) {
		focus = tile;
		set("focus", tile);
		pressing = ValueAnimator.ofFloat(0f, 1f);
		pressing.setDuration(PRESS_MILLIS);
		pressing.setInterpolator(new AccelerateInterpolator());
		pressing.addUpdateListener(a -> set("press", (float) a.getAnimatedValue()));
		pressing.addListener(new android.animation.AnimatorListenerAdapter() {
			private boolean cancelled;

			@Override
			public void onAnimationCancel(android.animation.Animator animation) {
				cancelled = true;
			}

			@Override
			public void onAnimationEnd(android.animation.Animator animation) {
				if (!cancelled && !isFinishing()) {
					ShowActivity.start(RestAreaActivity.this, sets.get(tile).packId(), true);
				}
			}
		});
		pressing.start();
	}
}
