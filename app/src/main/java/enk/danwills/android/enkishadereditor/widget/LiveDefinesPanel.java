package enk.danwills.android.enkishadereditor.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.List;
import java.util.Locale;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefine;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefineParser;

// Adjust Defines' live mode: a see-through panel along the bottom of
// the fullscreen shader with a small outlined label and a slider (a
// switch for a bool, a swatch for a color) per live define. Every
// change is written straight into the ShaderDefine and reported to the
// Listener, which sends it to the shader as a uniform - see
// MainActivity.startLiveDefines().
public class LiveDefinesPanel extends LinearLayout {
	public interface Listener {
		void onValueChanged(@NonNull ShaderDefine define);

		// Open the color picker for `define`; run `onPicked` after it
		// changes the color, so the panel can show it.
		void onPickColor(@NonNull ShaderDefine define, @NonNull Runnable onPicked);

		void onTogglePause();

		void onDone();

		void onDiscard();
	}

	private static final int SLIDER_STEPS = 1000;
	// At most this much of the screen's height, so the shader stays in
	// view however many defines are live - the rest scroll.
	private static final float MAX_HEIGHT_FRACTION = 0.45f;
	private static final String[] VECTOR_SUFFIXES = {".x", ".y", ".z", ".w"};

	private final LinearLayout rows;
	private final View scroll;
	private final ImageButton collapseButton;
	private final ImageButton pauseButton;
	@Nullable
	private Listener listener;

	public LiveDefinesPanel(@NonNull Context context) {
		this(context, null);
	}

	public LiveDefinesPanel(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		setOrientation(VERTICAL);
		setBackgroundColor(0x99000000);
		setClickable(true);
		LayoutInflater.from(context).inflate(R.layout.live_defines_panel, this, true);
		rows = findViewById(R.id.live_defines_rows);
		scroll = findViewById(R.id.live_defines_scroll);
		collapseButton = findViewById(R.id.live_defines_collapse);
		pauseButton = findViewById(R.id.live_defines_pause);
		collapseButton.setOnClickListener(v -> setCollapsed(scroll.getVisibility() == VISIBLE));
		pauseButton.setOnClickListener(v -> {
			if (listener != null) {
				listener.onTogglePause();
			}
		});
		findViewById(R.id.live_defines_done).setOnClickListener(v -> {
			if (listener != null) {
				listener.onDone();
			}
		});
		findViewById(R.id.live_defines_discard).setOnClickListener(v -> {
			if (listener != null) {
				listener.onDiscard();
			}
		});
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int max = (int) (getResources().getDisplayMetrics().heightPixels * MAX_HEIGHT_FRACTION);
		int size = MeasureSpec.getSize(heightMeasureSpec);
		if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || size > max) {
			heightMeasureSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	public void setPaused(boolean paused) {
		pauseButton.setImageResource(paused ? R.drawable.ic_play_arrow : R.drawable.ic_pause);
		pauseButton.setContentDescription(getContext().getText(
				paused ? R.string.resume_shader : R.string.pause_shader));
	}

	public void setCollapsed(boolean collapsed) {
		scroll.setVisibility(collapsed ? GONE : VISIBLE);
		collapseButton.setImageResource(collapsed ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
		collapseButton.setContentDescription(getContext().getText(
				collapsed ? R.string.live_defines_expand : R.string.live_defines_collapse));
	}

	public void setDefines(@NonNull List<ShaderDefine> defines) {
		rows.removeAllViews();
		for (ShaderDefine define : defines) {
			switch (define.type) {
				case FLOAT, INT -> addScalarRow(define);
				case BOOL -> addBoolRow(define);
				case VEC2, VEC3, VEC4 -> {
					if (define.showColorPicker) {
						addColorRow(define);
					} else {
						addVectorRows(define);
					}
				}
				default -> {
				}
			}
		}
		setCollapsed(false);
	}

	private void addScalarRow(@NonNull ShaderDefine define) {
		OutlinedTextView label = addLabel();
		float[] start = define.liveComponents();
		float value = start != null ? start[0] : 0f;
		float[] range = ShaderDefineParser.inferSliderRange(value);
		Runnable showValue = () -> label.setText(labelText(define.name, define.value));
		showValue.run();
		addSlider(value, range, progress -> {
			float v = range[0] + (range[1] - range[0]) * progress / SLIDER_STEPS;
			define.setLiveScalar(v);
			showValue.run();
			changed(define);
		});
	}

	private void addBoolRow(@NonNull ShaderDefine define) {
		LinearLayout row = new LinearLayout(getContext());
		row.setOrientation(HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		OutlinedTextView label = newLabel();
		label.setText(define.name);
		CheckBox box = new CheckBox(getContext());
		box.setChecked(define.value.equalsIgnoreCase("true"));
		box.setOnCheckedChangeListener((button, checked) -> {
			define.setLiveScalar(checked ? 1f : 0f);
			changed(define);
		});
		row.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
		row.addView(box);
		rows.addView(row);
	}

	private void addVectorRows(@NonNull ShaderDefine define) {
		float[] components = define.vectorComponents;
		if (components == null) {
			return;
		}
		for (int i = 0; i < components.length; i++) {
			int index = i;
			OutlinedTextView label = addLabel();
			String name = define.name + VECTOR_SUFFIXES[i];
			float[] range = ShaderDefineParser.inferSliderRange(components[i]);
			Runnable showValue = () -> label.setText(
					labelText(name, ShaderDefine.formatFloat(components[index])));
			showValue.run();
			addSlider(components[i], range, progress -> {
				components[index] = range[0] + (range[1] - range[0]) * progress / SLIDER_STEPS;
				define.syncVectorValue();
				showValue.run();
				changed(define);
			});
		}
	}

	private void addColorRow(@NonNull ShaderDefine define) {
		LinearLayout row = new LinearLayout(getContext());
		row.setOrientation(HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		OutlinedTextView label = newLabel();
		View swatch = new View(getContext());
		swatch.setBackground(ContextCompat.getDrawable(getContext(),
				R.drawable.color_swatch_background));
		Runnable show = () -> {
			label.setText(labelText(define.name, define.value));
			float[] c = define.vectorComponents;
			if (c != null && swatch.getBackground() instanceof GradientDrawable gradient) {
				gradient.mutate();
				gradient.setColor(Color.argb(c.length == 4 ? toByte(c[3]) : 255,
						toByte(c[0]), toByte(c[1]), toByte(c[2])));
			}
		};
		show.run();
		swatch.setOnClickListener(v -> {
			if (listener != null) {
				listener.onPickColor(define, show);
			}
		});
		int size = dp(32);
		LayoutParams swatchParams = new LayoutParams(size * 2, size);
		swatchParams.setMargins(dp(8), dp(4), dp(4), dp(4));
		row.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
		row.addView(swatch, swatchParams);
		rows.addView(row);
	}

	private interface OnProgress {
		void onProgress(int progress);
	}

	private void addSlider(float value, @NonNull float[] range, @NonNull OnProgress onProgress) {
		SeekBar slider = new SeekBar(getContext());
		slider.setMax(SLIDER_STEPS);
		slider.setProgress(progressFor(value, range));
		slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				if (fromUser) {
					onProgress.onProgress(progress);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});
		rows.addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(32)));
	}

	@NonNull
	private OutlinedTextView addLabel() {
		OutlinedTextView label = newLabel();
		rows.addView(label);
		return label;
	}

	@NonNull
	private OutlinedTextView newLabel() {
		OutlinedTextView label = new OutlinedTextView(getContext());
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
		label.setTextColor(Color.WHITE);
		label.setPadding(dp(8), dp(4), dp(8), 0);
		return label;
	}

	private void changed(@NonNull ShaderDefine define) {
		if (listener != null) {
			listener.onValueChanged(define);
		}
	}

	@NonNull
	private static String labelText(@NonNull String name, @NonNull String value) {
		return String.format(Locale.US, "%s  %s", name, value);
	}

	private static int progressFor(float value, @NonNull float[] range) {
		if (range[1] <= range[0]) {
			return SLIDER_STEPS / 2;
		}
		int progress = Math.round((value - range[0]) / (range[1] - range[0]) * SLIDER_STEPS);
		return Math.max(0, Math.min(SLIDER_STEPS, progress));
	}

	private static int toByte(float component) {
		return Math.max(0, Math.min(255, Math.round(component * 255f)));
	}

	private int dp(int dp) {
		return Math.round(dp * getResources().getDisplayMetrics().density);
	}
}
