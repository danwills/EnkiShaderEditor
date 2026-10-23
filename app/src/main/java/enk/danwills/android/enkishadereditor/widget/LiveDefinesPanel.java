package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefine;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefineParser;
import enk.danwills.android.enkishadereditor.view.SoftKeyboard;

// Adjust Defines' live mode: a see-through panel along the bottom of
// the fullscreen shader with a small outlined label and a slider (a
// switch for a bool, a swatch for a color) per live define. Every
// change is written straight into the ShaderDefine and reported to the
// Listener, which sends it to the shader as a uniform - see
// MainActivity.startLiveDefines(). Defines whose names start alike
// (D_a, D_b...) are grouped under a header that folds them away (see
// buildTree()), and the filter box shows only those whose names match.
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
	// then a sixteenth, then a sixty-fourth of its usual change - see
	// fineScale().
	private static final int[] FINE_STEPS_DP = {48, 120, 200};
	// The pop-up hue/saturation disc - see addColorRow().
	private static final int WHEEL_SIZE_DP = 220;
	// Slider ranges as the user left them, by rangeKeyPrefix + name -
	// see addSliderRow(). In-memory, for the app session.
	private static final Map<String, float[]> ranges = new HashMap<>();
	// Groups folded away, by rangeKeyPrefix + their prefix - also for the
	// app session.
	private static final Set<String> foldedGroups = new HashSet<>();
	private static final int INDENT_DP = 12;

	private final LinearLayout rows;
	// Where add*Row() put their views: the box of the define being built.
	private ViewGroup target;
	private final EditText filterView;
	@NonNull
	private String filter = "";
	// The tree buildRows() made: what applyFilter() shows and hides.
	@NonNull
	private Group tree = new Group("");
	// Each define's views, under its label - see buildRows().
	private final Map<ShaderDefine, View> boxes = new HashMap<>();
	private final View scroll;
	private final ImageButton collapseButton;
	private final ImageButton pauseButton;
	@Nullable
	private Listener listener;
	@NonNull
	private String rangeKeyPrefix = "";
	@NonNull
	private List<ShaderDefine> defines = List.of();
	// Each live define's value, and components, as live mode started -
	// see reset().
	private record Start(@NonNull String value, @Nullable float[] components) {
	}

	private final Map<ShaderDefine, Start> startValues = new HashMap<>();

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
		target = rows;
		filterView = findViewById(R.id.live_defines_filter);
		filterView.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence text, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence text, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable text) {
				filter = text.toString().trim().toLowerCase(Locale.ROOT);
				applyFilter();
			}
		});
		filterView.setOnEditorActionListener((v, actionId, event) -> {
			SoftKeyboard.hide(context, filterView);
			return true;
		});
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
		findViewById(R.id.live_defines_reset).setOnClickListener(v -> reset());
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
		this.defines = defines;
		this.rangeKeyPrefix = rangeKeyPrefix;
		filterView.setText("");
		startValues.clear();
		for (ShaderDefine define : defines) {
			startValues.put(define, new Start(define.value, define.vectorComponents != null
					? define.vectorComponents.clone()
					: null));
		}
		buildRows();
	}

	// Reset: every define back to its value when live mode started (see
	// setDefines()), the shader with them; slider ranges are kept.
	private void reset() {
		for (ShaderDefine define : defines) {
			Start start = startValues.get(define);
			if (start == null) {
				continue;
			}
			define.value = start.value();
			if (start.components() != null && define.vectorComponents != null) {
				System.arraycopy(start.components(), 0, define.vectorComponents, 0,
						start.components().length);
			}
			changed(define);
		}
		buildRows();
	}

	private void buildRows() {
		rows.removeAllViews();
		boxes.clear();
		for (ShaderDefine define : defines) {
			LinearLayout box = new LinearLayout(getContext());
			box.setOrientation(VERTICAL);
			target = box;
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
			boxes.put(define, box);
		}
		target = rows;
		tree = buildTree(defines, 0, "");
		addTree(tree, 0);
		applyFilter();
		setCollapsed(false);
	}

	// A group of defines whose names start with `prefix`, and the groups
	// within it - or the whole tree, with prefix "".
	private static final class Group {
		@NonNull
		final String prefix;
		// ShaderDefines and Groups, in the defines' own order.
		final List<Object> children = new ArrayList<>();
		@Nullable
		OutlinedTextView header;
		// How many defines are in it, at any depth.
		int count;

		Group(@NonNull String prefix) {
			this.prefix = prefix;
		}
	}

	// Names split at underscores: those sharing the next part, two or
	// more, make a group (ones that share more parts still make one
	// group, D_a_to_c and D_a_to_b under "D_a_to_"), and the rest stand
	// alone. `skip` is the parts the group already has in common.
	@NonNull
	private static Group buildTree(@NonNull List<ShaderDefine> defines, int skip,
			@NonNull String prefix) {
		Group group = new Group(prefix);
		Map<String, List<ShaderDefine>> byPart = new LinkedHashMap<>();
		for (ShaderDefine define : defines) {
			String[] parts = define.name.split("_", -1);
			// A name that ends here, or has only an empty part left (k_),
			// stands alone.
			String key = parts.length > skip + 1 ? parts[skip] : "\u0000" + define.name;
			byPart.computeIfAbsent(key, k -> new ArrayList<>()).add(define);
		}
		for (var entry : byPart.entrySet()) {
			List<ShaderDefine> members = entry.getValue();
			if (members.size() < 2) {
				group.children.add(members.get(0));
				continue;
			}
			// As many more parts as they all share, with more after them.
			int shared = skip + 1;
			while (true) {
				String next = null;
				boolean all = true;
				for (ShaderDefine define : members) {
					String[] parts = define.name.split("_", -1);
					if (parts.length <= shared + 1 || (next != null && !next.equals(parts[shared]))) {
						all = false;
						break;
					}
					next = parts[shared];
				}
				if (!all) {
					break;
				}
				shared++;
			}
			String[] first = members.get(0).name.split("_", -1);
			StringBuilder sub = new StringBuilder();
			for (int i = 0; i < shared; i++) {
				sub.append(first[i]).append('_');
			}
			group.children.add(buildTree(members, shared, sub.toString()));
		}
		return group;
	}

	// The tree's views into the panel, a header row per group (tap to
	// fold it), indented by depth; returns the group's count.
	private int addTree(@NonNull Group group, int depth) {
		if (!group.prefix.isEmpty()) {
			OutlinedTextView header = newLabel();
			header.setTypeface(header.getTypeface(), Typeface.BOLD);
			header.setPaddingRelative(dp(8 + INDENT_DP * (depth - 1)), dp(8), dp(8), dp(4));
			header.setOnClickListener(v -> {
				String key = rangeKeyPrefix + group.prefix;
				if (!foldedGroups.remove(key)) {
					foldedGroups.add(key);
				}
				applyFilter();
			});
			group.header = header;
			rows.addView(header);
		}
		int count = 0;
		for (Object child : group.children) {
			if (child instanceof Group inner) {
				count += addTree(inner, depth + 1);
			} else if (child instanceof ShaderDefine define) {
				View box = boxes.get(define);
				if (box == null) {
					continue;
				}
				box.setPaddingRelative(dp(INDENT_DP * depth), 0, 0, 0);
				rows.addView(box);
				count++;
			}
		}
		group.count = count;
		return count;
	}

	// Shows the defines the filter matches (all, with no filter) and the
	// headers of the groups they're in, each with its count; a folded
	// group shows only its header, unless filtering, which opens every
	// group with a match.
	private void applyFilter() {
		applyFilter(tree, true);
	}

	// Returns how many of the group's defines match.
	private int applyFilter(@NonNull Group group, boolean shown) {
		boolean folded = filter.isEmpty() && foldedGroups.contains(rangeKeyPrefix + group.prefix);
		boolean open = shown && !folded;
		int matches = 0;
		for (Object child : group.children) {
			if (child instanceof Group inner) {
				matches += applyFilter(inner, open);
			} else if (child instanceof ShaderDefine define) {
				boolean match = filter.isEmpty()
						|| define.name.toLowerCase(Locale.ROOT).contains(filter);
				View box = boxes.get(define);
				if (box != null) {
					box.setVisibility(open && match ? VISIBLE : GONE);
				}
				if (match) {
					matches++;
				}
			}
		}
		if (group.header != null) {
			group.header.setVisibility(shown && matches > 0 ? VISIBLE : GONE);
			group.header.setText(String.format(Locale.US, "%s %s  (%d)",
					folded ? "\u25b8" : "\u25be", group.prefix, filter.isEmpty() ? group.count : matches));
		}
		return matches;
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
		target.addView(row);
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

	// A color: a swatch, and under it a brightness slider. Pressing the
	// swatch pops up a hue/saturation disc (see ColorWheelView) centred so
	// the finger is already on the current color; dragging picks a new
	// one, and letting go closes it. So little of the shader is covered,
	// and nothing needs confirming. Brightness is kept apart from the
	// disc - the largest component, which the slider can take past 1 for
	// an HDR color. A tap that doesn't drag opens the full color picker
	// instead (hex, RGB and so on).
	@SuppressLint("ClickableViewAccessibility")
	private void addColorRow(@NonNull ShaderDefine define) {
		float[] c = define.vectorComponents;
		if (c == null) {
			return;
		}
		LinearLayout row = new LinearLayout(getContext());
		row.setOrientation(HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		OutlinedTextView label = newLabel();
		View swatch = new View(getContext());
		swatch.setBackground(ContextCompat.getDrawable(getContext(),
				R.drawable.color_swatch_background));
		// Hue and saturation of the color at full brightness, and the
		// brightness itself - set from the components, and back again.
		float[] hsv = new float[3];
		float[] brightness = new float[1];
		Runnable readColor = () -> {
			brightness[0] = Math.max(c[0], Math.max(c[1], c[2]));
			if (brightness[0] > 0f) {
				rgbToHsv(c[0] / brightness[0], c[1] / brightness[0], c[2] / brightness[0], hsv);
			}
			hsv[2] = 1f;
		};
		readColor.run();
		Runnable show = () -> {
			label.setText(labelText(define.name, define.value));
			if (swatch.getBackground() instanceof GradientDrawable gradient) {
				gradient.mutate();
				gradient.setColor(Color.argb(c.length == 4 ? toByte(c[3]) : 255,
						toByte(c[0]), toByte(c[1]), toByte(c[2])));
			}
		};
		Runnable writeColor = () -> {
			hsvToRgb(hsv[0], hsv[1], c);
			for (int i = 0; i < 3; i++) {
				c[i] *= brightness[0];
			}
			define.syncVectorValue();
			show.run();
			changed(define);
		};
		show.run();

		int size = dp(32);
		LayoutParams swatchParams = new LayoutParams(size * 2, size);
		swatchParams.setMargins(dp(8), dp(4), dp(4), dp(4));
		row.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
		row.addView(swatch, swatchParams);
		target.addView(row);

		OnValue setBrightness = addSliderRow(
				getContext().getString(R.string.live_defines_brightness),
				define.name + ".brightness", brightness[0], false,
				() -> ShaderDefine.formatFloat(brightness[0]), v -> {
					brightness[0] = Math.max(0f, v);
					writeColor.run();
				});

		ColorWheelView wheel = new ColorWheelView(getContext());
		int wheelSize = dp(WHEEL_SIZE_DP);
		PopupWindow popup = new PopupWindow(wheel, wheelSize, wheelSize, false);
		popup.setTouchable(false);
		popup.setClippingEnabled(false);
		int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		swatch.setOnTouchListener(new OnTouchListener() {
			// Where the disc's centre is on screen, where the press
			// started, and how far the current color's spot on the disc
			// is from there - not always under the finger, when the disc
			// had to move to stay on screen. The selector moves with the
			// finger from that spot, so the color never jumps.
			private float centreX;
			private float centreY;
			private float downX;
			private float downY;
			private float grabX;
			private float grabY;
			private boolean dragging;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				switch (event.getActionMasked()) {
					case MotionEvent.ACTION_DOWN -> {
						v.getParent().requestDisallowInterceptTouchEvent(true);
						downX = event.getRawX();
						downY = event.getRawY();
						dragging = false;
						showDisc();
					}
					case MotionEvent.ACTION_MOVE -> {
						if (!dragging && Math.hypot(event.getRawX() - downX,
								event.getRawY() - downY) < slop) {
							return true;
						}
						dragging = true;
						float dx = event.getRawX() + grabX - centreX;
						float dy = event.getRawY() + grabY - centreY;
						float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
						hsv[0] = hue < 0f ? hue + 360f : hue;
						hsv[1] = Math.min(1f, (float) Math.hypot(dx, dy) / wheel.getRadius());
						// Black has no hue to show - picking one means a
						// color.
						if (brightness[0] <= 0f) {
							brightness[0] = 1f;
							setBrightness.onValue(1f);
						}
						wheel.setHsv(hsv);
						writeColor.run();
					}
					case MotionEvent.ACTION_UP -> {
						popup.dismiss();
						if (!dragging && listener != null) {
							listener.onPickColor(define, () -> {
								readColor.run();
								setBrightness.onValue(brightness[0]);
								show.run();
								changed(define);
							});
						}
					}
					case MotionEvent.ACTION_CANCEL -> popup.dismiss();
					default -> {
					}
				}
				return true;
			}

			// Centred so the current color is under the finger, kept on
			// screen.
			private void showDisc() {
				wheel.setHsv(hsv);
				float radius = wheelSize / 2f - dp(ColorWheelView.SELECTOR_RADIUS_DP);
				double angle = Math.toRadians(hsv[0]);
				float offsetX = (float) Math.cos(angle) * hsv[1] * radius;
				float offsetY = (float) Math.sin(angle) * hsv[1] * radius;
				int screenWidth = getResources().getDisplayMetrics().widthPixels;
				int screenHeight = getRootView().getHeight();
				int x = Math.round(downX - offsetX - wheelSize / 2f);
				int y = Math.round(downY - offsetY - wheelSize / 2f);
				x = Math.max(0, Math.min(screenWidth - wheelSize, x));
				y = Math.max(0, Math.min(screenHeight - wheelSize, y));
				centreX = x + wheelSize / 2f;
				centreY = y + wheelSize / 2f;
				grabX = centreX + offsetX - downX;
				grabY = centreY + offsetY - downY;
				popup.showAtLocation(LiveDefinesPanel.this, Gravity.NO_GRAVITY, x, y);
			}
		});
	}

	// Float versions of Color.RGBToHSV()/HSVToColor(), at full value, so
	// a color isn't rounded to 8 bits on the way through the disc.
	private static void rgbToHsv(float r, float g, float b, @NonNull float[] hsv) {
		float max = Math.max(r, Math.max(g, b));
		float min = Math.min(r, Math.min(g, b));
		float delta = max - min;
		float hue = 0f;
		if (delta > 0f) {
			if (max == r) {
				hue = 60f * (((g - b) / delta) % 6f);
			} else if (max == g) {
				hue = 60f * ((b - r) / delta + 2f);
			} else {
				hue = 60f * ((r - g) / delta + 4f);
			}
		}
		hsv[0] = hue < 0f ? hue + 360f : hue;
		hsv[1] = max > 0f ? delta / max : 0f;
		hsv[2] = max;
	}

	private static void hsvToRgb(float hue, float saturation, @NonNull float[] rgb) {
		float h = (hue % 360f) / 60f;
		float x = 1f - Math.abs(h % 2f - 1f);
		float r, g, b;
		if (h < 1f) {
			r = 1f; g = x; b = 0f;
		} else if (h < 2f) {
			r = x; g = 1f; b = 0f;
		} else if (h < 3f) {
			r = 0f; g = 1f; b = x;
		} else if (h < 4f) {
			r = 0f; g = x; b = 1f;
		} else if (h < 5f) {
			r = x; g = 0f; b = 1f;
		} else {
			r = 1f; g = 0f; b = x;
		}
		rgb[0] = 1f - saturation * (1f - r);
		rgb[1] = 1f - saturation * (1f - g);
		rgb[2] = 1f - saturation * (1f - b);
	}

	private interface ValueText {
		@NonNull
		String get();
	}

	private interface OnValue {
		void onValue(float value);
	}

	// A label and a slider for one value. Only dragging changes it - a
	// touch alone doesn't jump the value to the finger, which in a running
	// simulation could wreck it - by as much as the drag moves across the
	// slider's range. The slider starts on a range guessed from the value
	// (see ShaderDefineParser.inferSliderRange()) and can reach past it
	// without typing:
	// - letting go with the thumb at either end extends the range past
	//   that end by its width, so pushing again goes further;
	// - moving the finger up away from the slider while dragging makes
	//   the same sideways movement change the value less (see
	//   FINE_STEPS_DP), for precision without a smaller range;
	// - double-tapping the label puts the range back to the first guess.
	// A drag that starts up or down rather than sideways scrolls the
	// panel instead, leaving the value alone.
	// Ranges are kept per shader and name for the app session (see
	// rangeKeyPrefix), so going live again picks up where it was.
	// Returns what sets the slider from outside (without calling
	// onValue), widening its range to fit if need be.
	@SuppressLint("ClickableViewAccessibility")
	@NonNull
	private OnValue addSliderRow(@NonNull String name, @NonNull String rangeName, float value,
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

		int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
		slider.setOnTouchListener(new OnTouchListener() {
			private float lastX;
			private float downX;
			private float downY;
			// Not a drag until the finger's moved past the touch slop:
			// sideways first makes it one, up or down first leaves the
			// touch to the panel's scrolling, so scrolling a long list
			// over the sliders doesn't nudge them.
			private boolean dragging;
			private boolean scrolling;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				int width = slider.getWidth() - slider.getPaddingLeft() - slider.getPaddingRight();
				if (width <= 0) {
					return false;
				}
				switch (event.getActionMasked()) {
					case MotionEvent.ACTION_DOWN -> {
						downX = event.getX();
						downY = event.getY();
						dragging = false;
						scrolling = false;
					}
					case MotionEvent.ACTION_MOVE -> {
						if (scrolling) {
							return false;
						}
						if (!dragging) {
							float dx = event.getX() - downX;
							float dy = event.getY() - downY;
							if (Math.hypot(dx, dy) < slop) {
								return true;
							}
							if (Math.abs(dy) > Math.abs(dx)) {
								scrolling = true;
								return false;
							}
							// From here, so the slop's movement doesn't jump it.
							dragging = true;
							lastX = event.getX();
							v.getParent().requestDisallowInterceptTouchEvent(true);
						}
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
						if (dragging) {
							extendAtEnd();
						}
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
		target.addView(slider, new LayoutParams(LayoutParams.MATCH_PARENT, dp(32)));
		return v -> {
			current[0] = v;
			liveRange[0] = Math.min(liveRange[0], v);
			liveRange[1] = Math.max(liveRange[1], v);
			placeThumb.run();
			showValue.run();
		};
	}

	// How much a sideways drag moves the value, by how far the finger
	// has moved up away from where it started: full, then a quarter, a
	// sixteenth, a sixty-fourth.
	private float fineScale(float upPx) {
		float scale = 1f;
		for (int steps : FINE_STEPS_DP) {
			if (upPx >= dp(steps)) {
				scale *= 0.25f;
			}
		}
		return scale;
	}

	@NonNull
	private OutlinedTextView addLabel() {
		OutlinedTextView label = newLabel();
		target.addView(label);
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
