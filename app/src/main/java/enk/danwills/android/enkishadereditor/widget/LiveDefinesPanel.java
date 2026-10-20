package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
	// How far up (dp) the finger moves before a drag goes to a quarter,
	// then a sixteenth, of its usual change - see fineScale().
	private static final int[] FINE_STEPS_DP = {48, 120};
	// Slider ranges as the user left them, by rangeKeyPrefix + name -
	// see addSliderRow(). In-memory, for the app session.
	private static final Map<String, float[]> ranges = new HashMap<>();

	private final LinearLayout rows;
	private final View scroll;
	private final ImageButton collapseButton;
	private final ImageButton pauseButton;
	@Nullable
	private Listener listener;
	@NonNull
	private String rangeKeyPrefix = "";

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

	// rangeKeyPrefix: what the slider ranges are remembered under,
	// with each define's name - one per shader (see addSliderRow()).
	public void setDefines(@NonNull List<ShaderDefine> defines, @NonNull String rangeKeyPrefix) {
		this.rangeKeyPrefix = rangeKeyPrefix;
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
		float[] start = define.liveComponents();
		float[] value = {start != null ? start[0] : 0f};
		addSliderRow(define.name, define.name, value[0],
				define.type == ShaderDefine.Type.INT, () -> define.value, v -> {
					define.setLiveScalar(v);
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
			String name = define.name + VECTOR_SUFFIXES[i];
			addSliderRow(name, name, components[i], false,
					() -> ShaderDefine.formatFloat(components[index]), v -> {
						components[index] = v;
						define.syncVectorValue();
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

	private interface ValueText {
		@NonNull
		String get();
	}

	private interface OnValue {
		void onValue(float value);
	}

	// A label and a slider for one value. The slider starts on a range
	// guessed from the value (see ShaderDefineParser.inferSliderRange())
	// and can reach past it without typing:
	// - letting go with the thumb at either end extends the range past
	//   that end by its width, so pushing again goes further;
	// - moving the finger up away from the slider while dragging makes
	//   the same sideways movement change the value less (see
	//   FINE_STEPS_DP), for precision without a smaller range;
	// - double-tapping the label puts the range back to the first guess.
	// Ranges are kept per shader and name for the app session (see
	// rangeKeyPrefix), so going live again picks up where it was.
	@SuppressLint("ClickableViewAccessibility")
	private void addSliderRow(@NonNull String name, @NonNull String rangeName, float value,
			boolean integer, @NonNull ValueText valueText, @NonNull OnValue onValue) {
		OutlinedTextView label = addLabel();
		float[] guess = ShaderDefineParser.inferSliderRange(value);
		String key = rangeKeyPrefix + rangeName;
		float[] range = ranges.get(key);
		if (range == null) {
			range = guess.clone();
			ranges.put(key, range);
		}
		float[] liveRange = range;
		liveRange[0] = Math.min(liveRange[0], value);
		liveRange[1] = Math.max(liveRange[1], value);
		float[] current = {value};
		String[] hint = {""};
		Runnable showValue = () -> label.setText(String.format(Locale.US, "%s%s",
				labelText(name, valueText.get()), hint[0]));
		showValue.run();

		SeekBar slider = new SeekBar(getContext());
		slider.setMax(SLIDER_STEPS);
		Runnable placeThumb = () -> slider.setProgress(progressFor(current[0], liveRange));
		placeThumb.run();

		label.setOnTouchListener(new OnTouchListener() {
			private final GestureDetector detector = new GestureDetector(getContext(),
					new GestureDetector.SimpleOnGestureListener() {
						@Override
						public boolean onDown(@NonNull MotionEvent e) {
							return true;
						}

						@Override
						public boolean onDoubleTap(@NonNull MotionEvent e) {
							liveRange[0] = Math.min(guess[0], current[0]);
							liveRange[1] = Math.max(guess[1], current[0]);
							placeThumb.run();
							return true;
						}
					});

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				return detector.onTouchEvent(event);
			}
		});

		slider.setOnTouchListener(new OnTouchListener() {
			private float lastX;
			private float downY;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				int width = slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight();
				if (width <= 0) {
					return false;
				}
				switch (event.getActionMasked()) {
					case MotionEvent.ACTION_DOWN -> {
						v.getParent().requestDisallowInterceptTouchEvent(true);
						lastX = event.getX();
						downY = event.getY();
						// A tap puts the thumb where it lands, as usual.
						float at = (event.getX() - slider.getPaddingLeft()) / width;
						set(liveRange[0] + (liveRange[1] - liveRange[0]) *
								Math.max(0f, Math.min(1f, at)));
					}
					case MotionEvent.ACTION_MOVE -> {
						float scale = fineScale(downY - event.getY());
						hint[0] = scale < 1f
								? String.format(Locale.US, "  (\u00d71/%d)", Math.round(1f / scale))
								: "";
						float dx = event.getX() - lastX;
						lastX = event.getX();
						set(current[0] + dx / width * (liveRange[1] - liveRange[0]) * scale);
					}
					case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
						hint[0] = "";
						extendAtEnd();
						showValue.run();
					}
					default -> {
					}
				}
				return true;
			}

			private void set(float v) {
				v = Math.max(liveRange[0], Math.min(liveRange[1], v));
				if (integer) {
					v = Math.round(v);
				}
				current[0] = v;
				placeThumb.run();
				onValue.onValue(v);
				showValue.run();
			}

			private void extendAtEnd() {
				float span = liveRange[1] - liveRange[0];
				if (span <= 0f) {
					return;
				}
				if (current[0] >= liveRange[1]) {
					liveRange[1] += integer ? Math.max(1f, Math.round(span)) : span;
				} else if (current[0] <= liveRange[0]) {
					liveRange[0] -= integer ? Math.max(1f, Math.round(span)) : span;
				} else {
					return;
				}
				placeThumb.run();
			}
		});
		rows.addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(32)));
	}

	// How much a sideways drag moves the value, by how far the finger
	// has moved up away from where it started: full, then a quarter,
	// then a sixteenth.
	private float fineScale(float upPx) {
		if (upPx < dp(FINE_STEPS_DP[0])) {
			return 1f;
		}
		return upPx < dp(FINE_STEPS_DP[1]) ? 0.25f : 0.0625f;
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
