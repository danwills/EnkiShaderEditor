package enk.danwills.android.enkishadereditor.adapter;

import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefine;
import enk.danwills.android.enkishadereditor.opengl.ShaderDefineParser;

/**
 * Backs the scrollable list in the "Adjust Defines" tool (see
 * MainActivity.showAdjustDefinesDialog()). A plain (not ListAdapter)
 * adapter over a single mutable List<ShaderDefine> shared with the
 * caller - edits made through a row's own widgets mutate that list's
 * ShaderDefine objects directly, and moveItem() (driven by the
 * ItemTouchHelper set up alongside this adapter) reorders the list
 * itself, so by the time "Make Edits" is pressed the list already *is*
 * the result - no separate change-tracking needed.
 */
public class DefineAdapter extends RecyclerView.Adapter<DefineAdapter.ViewHolder> {
	// Resolution of every slider <-> value mapping in this list (scalar
	// and per-component alike) - a fixed progress range mapped onto
	// each value's own (value-dependent) range, rather than relying on
	// SeekBar's own `min` attribute (API 26+, this app supports API 23+).
	private static final int SLIDER_STEPS = 1000;
	private static final String[] COLOR_COMPONENT_LABELS = {"R", "G", "B", "A"};
	private static final String[] VECTOR_COMPONENT_LABELS = {"X", "Y", "Z", "W"};

	@FunctionalInterface
	public interface OnStartDragListener {
		void onStartDrag(@NonNull RecyclerView.ViewHolder viewHolder);
	}

	// Opens the color-picker dialog for `define` (a VEC3/VEC4 already
	// flagged looksLikeColor) - the implementation (MainActivity) owns
	// the dialog itself since building one needs an Activity context;
	// onChanged must be called once the picker commits a new color, so
	// the row can re-bind and show it.
	@FunctionalInterface
	public interface OnColorPickerRequested {
		void onPickColor(@NonNull ShaderDefine define, @NonNull Runnable onChanged);
	}

	@NonNull
	private final List<ShaderDefine> defines;
	@NonNull
	private final OnStartDragListener dragListener;
	@NonNull
	private final OnColorPickerRequested colorPickerRequested;
	// Names of defines the "Show Recent" checkbox in
	// dialog_adjust_defines.xml should narrow the list down to when
	// checked - see MainActivity.recentlyEditedDefineNames, the
	// session-scoped set this is a reference into (read-only from here).
	@NonNull
	private final Set<String> recentNames;
	// Lower-cased substring filter over define names, set by the
	// filter box in dialog_adjust_defines.xml - "" shows everything.
	@NonNull
	private String filter = "";
	private boolean showRecentOnly = false;
	// Indices into `defines` currently matching both filter and
	// showRecentOnly, in `defines`' own order - this (not `defines`
	// directly) is what getItemCount()/onBindViewHolder() go by, so a
	// non-matching row is genuinely absent from the adapter rather than
	// just an invisible (GONE) bound view. The earlier GONE-based
	// approach left RecyclerView not noticing a child's size had
	// changed when it happened from inside onBindViewHolder itself -
	// rows stayed stuck at their old (wrong) filtered state until some
	// unrelated layout pass (e.g. a scroll) forced it to catch up.
	@NonNull
	private final List<Integer> visibleIndices = new ArrayList<>();
	@Nullable
	private OnEmptyChanged onEmptyChanged;

	@FunctionalInterface
	public interface OnEmptyChanged {
		void onEmptyChanged(boolean empty);
	}

	public DefineAdapter(@NonNull List<ShaderDefine> defines, @NonNull OnStartDragListener dragListener,
			@NonNull OnColorPickerRequested colorPickerRequested,
			@NonNull Set<String> recentNames) {
		this.defines = defines;
		this.dragListener = dragListener;
		this.colorPickerRequested = colorPickerRequested;
		this.recentNames = recentNames;
		recomputeVisibleIndices();
	}

	// Told once, right after construction - not part of the constructor
	// itself since MainActivity needs the adapter to exist first to
	// read its initial getItemCount()==0 state for the "no matches"
	// text's own initial visibility.
	public void setOnEmptyChanged(@NonNull OnEmptyChanged onEmptyChanged) {
		this.onEmptyChanged = onEmptyChanged;
	}

	public void setFilter(@NonNull String filter) {
		this.filter = filter.toLowerCase(Locale.ROOT);
		applyFilterChange();
	}

	public void setShowRecentOnly(boolean showRecentOnly) {
		this.showRecentOnly = showRecentOnly;
		applyFilterChange();
	}

	private void applyFilterChange() {
		recomputeVisibleIndices();
		notifyDataSetChanged();
		if (onEmptyChanged != null) {
			onEmptyChanged.onEmptyChanged(visibleIndices.isEmpty());
		}
	}

	private void recomputeVisibleIndices() {
		visibleIndices.clear();
		for (int i = 0; i < defines.size(); i++) {
			ShaderDefine define = defines.get(i);
			boolean matchesFilter = filter.isEmpty()
					|| define.name.toLowerCase(Locale.ROOT).contains(filter);
			boolean matchesRecent = !showRecentOnly || recentNames.contains(define.name);
			if (matchesFilter && matchesRecent) {
				visibleIndices.add(i);
			}
		}
	}

	@Override
	public int getItemCount() {
		return visibleIndices.size();
	}

	@NonNull
	@Override
	public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new ViewHolder(LayoutInflater.from(parent.getContext())
				.inflate(R.layout.item_shader_define, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
		ShaderDefine define = defines.get(visibleIndices.get(position));
		holder.bind(define, dragListener, colorPickerRequested, () -> notifyItemChanged(position));
	}

	// Called by the ItemTouchHelper.Callback as the user drags a row -
	// from/to are adapter (i.e. *visible*, post-filter) positions;
	// translated to real `defines` indices via visibleIndices before
	// touching the backing list, which shifts every real index between
	// the two by one regardless of what's currently filtered out - so
	// visibleIndices itself has to be rebuilt afterward too, same as
	// any other change to `defines`' order (see recomputeVisibleIndices()).
	public void moveItem(int from, int to) {
		if (from == to) {
			return;
		}
		int fromReal = visibleIndices.get(from);
		int toReal = visibleIndices.get(to);
		ShaderDefine moved = defines.remove(fromReal);
		defines.add(toReal, moved);
		recomputeVisibleIndices();
		notifyItemMoved(from, to);
	}

	static final class ViewHolder extends RecyclerView.ViewHolder {
		private final ImageView dragHandle;
		private final TextView nameView;
		private final CheckBox boolValue;
		private final View sliderRow;
		private final SeekBar slider;
		private final EditText valueText;
		private final EditText otherValueText;
		private final View colorRow;
		private final CheckBox colorToggle;
		private final View colorSwatch;
		private final LinearLayout vectorRows;
		private final EditText commentText;

		@Nullable
		private TextWatcher valueWatcher;
		@Nullable
		private TextWatcher otherValueWatcher;
		@Nullable
		private TextWatcher commentWatcher;
		// Guards against the slider/text-box feedback loop: setting one
		// from the other below sets this first so the listener it also
		// triggers knows to no-op instead of writing back again.
		private boolean updatingFromCode;

		ViewHolder(@NonNull View itemView) {
			super(itemView);
			dragHandle = itemView.findViewById(R.id.define_drag_handle);
			nameView = itemView.findViewById(R.id.define_name);
			boolValue = itemView.findViewById(R.id.define_bool_value);
			sliderRow = itemView.findViewById(R.id.define_slider_row);
			slider = itemView.findViewById(R.id.define_slider);
			valueText = itemView.findViewById(R.id.define_value_text);
			otherValueText = itemView.findViewById(R.id.define_other_value_text);
			colorRow = itemView.findViewById(R.id.define_color_row);
			colorToggle = itemView.findViewById(R.id.define_color_toggle);
			colorSwatch = itemView.findViewById(R.id.define_color_swatch);
			vectorRows = itemView.findViewById(R.id.define_vector_rows);
			commentText = itemView.findViewById(R.id.define_comment_text);
			// A comment is one line of source, so the box stays single-line
			// (no newline key, and pasted newlines become spaces) but wraps
			// on screen rather than scrolling sideways.
			commentText.setHorizontallyScrolling(false);
			commentText.setMaxLines(Integer.MAX_VALUE);
			commentText.setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
				String text = source.subSequence(start, end).toString();
				return text.indexOf('\n') < 0 && text.indexOf('\r') < 0
						? null
						: text.replace('\r', ' ').replace('\n', ' ');
			}});
		}

		void bind(@NonNull ShaderDefine define, @NonNull OnStartDragListener dragListener,
				@NonNull OnColorPickerRequested colorPickerRequested, @NonNull Runnable refreshSelf) {
			nameView.setText(define.name);
			boolValue.setVisibility(View.GONE);
			sliderRow.setVisibility(View.GONE);
			otherValueText.setVisibility(View.GONE);
			colorRow.setVisibility(View.GONE);
			vectorRows.setVisibility(View.GONE);
			boolValue.setOnCheckedChangeListener(null);
			slider.setOnSeekBarChangeListener(null);
			colorToggle.setOnCheckedChangeListener(null);
			colorSwatch.setOnClickListener(null);
			if (valueWatcher != null) {
				valueText.removeTextChangedListener(valueWatcher);
				valueWatcher = null;
			}
			if (otherValueWatcher != null) {
				otherValueText.removeTextChangedListener(otherValueWatcher);
				otherValueWatcher = null;
			}

			switch (define.type) {
				case BOOL -> bindBool(define);
				case FLOAT, INT -> bindSlider(define);
				case VEC2, VEC3, VEC4 -> bindVector(define, colorPickerRequested, refreshSelf);
				case OTHER -> bindOther(define);
			}

			if (commentWatcher != null) {
				commentText.removeTextChangedListener(commentWatcher);
			}
			commentText.setText(define.comment == null ? "" : define.comment);
			commentWatcher = new SimpleTextWatcher(text ->
					define.comment = text.isEmpty() ? null : text);
			commentText.addTextChangedListener(commentWatcher);

			dragHandle.setOnTouchListener((v, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
					dragListener.onStartDrag(this);
				}
				return false;
			});
		}

		private void bindBool(@NonNull ShaderDefine define) {
			boolValue.setVisibility(View.VISIBLE);
			boolValue.setChecked(define.value.equalsIgnoreCase("true"));
			boolValue.setText(define.value);
			boolValue.setOnCheckedChangeListener((button, checked) -> {
				define.value = checked ? "true" : "false";
				boolValue.setText(define.value);
			});
		}

		private void bindOther(@NonNull ShaderDefine define) {
			otherValueText.setVisibility(View.VISIBLE);
			otherValueText.setText(define.value);
			otherValueWatcher = new SimpleTextWatcher(text -> define.value = text);
			otherValueText.addTextChangedListener(otherValueWatcher);
		}

		private void bindSlider(@NonNull ShaderDefine define) {
			sliderRow.setVisibility(View.VISIBLE);
			valueText.setInputType(define.type == ShaderDefine.Type.INT
					? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
					: InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
							| InputType.TYPE_NUMBER_FLAG_DECIMAL);
			slider.setMax(SLIDER_STEPS);
			updatingFromCode = true;
			valueText.setText(define.value);
			slider.setProgress(progressFor(parseLeading(define.value), define.sliderMin, define.sliderMax));
			updatingFromCode = false;

			slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					if (!fromUser || updatingFromCode) {
						return;
					}
					float lo = define.sliderMin;
					float hi = define.sliderMax;
					float value = lo + (hi - lo) * progress / SLIDER_STEPS;
					String formatted = define.type == ShaderDefine.Type.INT
							? String.valueOf(Math.round(value))
							: formatFloatLike(value, define.value);
					define.value = formatted;
					updatingFromCode = true;
					valueText.setText(formatted);
					updatingFromCode = false;
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
				}
			});

			valueWatcher = new SimpleTextWatcher(text -> {
				if (updatingFromCode) {
					return;
				}
				define.value = text;
				Float parsed = parseLeading(text);
				if (parsed != null) {
					updatingFromCode = true;
					slider.setProgress(progressFor(parsed, define.sliderMin, define.sliderMax));
					updatingFromCode = false;
				}
			});
			valueText.addTextChangedListener(valueWatcher);
		}

		// One mini slider+text row per vector component (see
		// item_shader_define_component.xml), rebuilt from scratch every
		// bind since the component count (2/3/4) varies row to row.
		// VEC3/VEC4 also get the "Treat as Color" toggle below these -
		// checked by default exactly when ShaderDefineParser's own
		// looksLikeColor() heuristic guessed so (see
		// ShaderDefine.showColorPicker), but always offered either way,
		// since that heuristic is a guess, not a guarantee. Toggling it
		// re-binds this whole row (refreshSelf) rather than patching
		// just the swatch/labels in place, so every bit of this method
		// only has to reason about one consistent, freshly-bound state.
		private void bindVector(@NonNull ShaderDefine define,
				@NonNull OnColorPickerRequested colorPickerRequested, @NonNull Runnable refreshSelf) {
			float[] components = define.vectorComponents;
			if (components == null) {
				return;
			}
			vectorRows.setVisibility(View.VISIBLE);
			vectorRows.removeAllViews();
			String[] labels = define.showColorPicker ? COLOR_COMPONENT_LABELS : VECTOR_COMPONENT_LABELS;
			LayoutInflater inflater = LayoutInflater.from(vectorRows.getContext());
			for (int i = 0; i < components.length; i++) {
				bindComponentRow(inflater, define, components, i, labels[i]);
			}

			boolean canBeColor = components.length == 3 || components.length == 4;
			colorRow.setVisibility(canBeColor ? View.VISIBLE : View.GONE);
			if (!canBeColor) {
				return;
			}
			colorToggle.setChecked(define.showColorPicker);
			colorToggle.setOnCheckedChangeListener((button, checked) -> {
				define.showColorPicker = checked;
				refreshSelf.run();
			});
			colorSwatch.setVisibility(define.showColorPicker ? View.VISIBLE : View.GONE);
			if (define.showColorPicker) {
				updateSwatch(define);
				colorSwatch.setOnClickListener(v ->
						colorPickerRequested.onPickColor(define, refreshSelf));
			}
		}

		private void bindComponentRow(@NonNull LayoutInflater inflater, @NonNull ShaderDefine define,
				@NonNull float[] components, int index, @NonNull String label) {
			View row = inflater.inflate(R.layout.item_shader_define_component, vectorRows, false);
			TextView labelView = row.findViewById(R.id.component_label);
			SeekBar componentSlider = row.findViewById(R.id.component_slider);
			EditText componentText = row.findViewById(R.id.component_value_text);
			labelView.setText(label);

			float[] range = ShaderDefineParser.inferSliderRange(components[index]);
			boolean[] updating = {false};

			updating[0] = true;
			componentText.setText(ShaderDefine.formatFloat(components[index]));
			componentSlider.setMax(SLIDER_STEPS);
			componentSlider.setProgress(progressFor(components[index], range[0], range[1]));
			updating[0] = false;

			componentSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
				@Override
				public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
					if (!fromUser || updating[0]) {
						return;
					}
					float value = range[0] + (range[1] - range[0]) * progress / SLIDER_STEPS;
					components[index] = value;
					define.syncVectorValue();
					updating[0] = true;
					componentText.setText(ShaderDefine.formatFloat(value));
					updating[0] = false;
					if (define.looksLikeColor) {
						updateSwatch(define);
					}
				}

				@Override
				public void onStartTrackingTouch(SeekBar seekBar) {
				}

				@Override
				public void onStopTrackingTouch(SeekBar seekBar) {
				}
			});

			componentText.addTextChangedListener(new SimpleTextWatcher(text -> {
				if (updating[0]) {
					return;
				}
				Float parsed = parseLeading(text);
				if (parsed != null) {
					components[index] = parsed;
					define.syncVectorValue();
					updating[0] = true;
					componentSlider.setProgress(progressFor(parsed, range[0], range[1]));
					updating[0] = false;
					if (define.looksLikeColor) {
						updateSwatch(define);
					}
				}
			}));

			vectorRows.addView(row);
		}

		private void updateSwatch(@NonNull ShaderDefine define) {
			float[] c = define.vectorComponents;
			if (c == null) {
				return;
			}
			int color = Color.argb(
					c.length == 4 ? clampedByte(c[3]) : 255,
					clampedByte(c[0]), clampedByte(c[1]), clampedByte(c[2]));
			Drawable background = colorSwatch.getBackground();
			if (background instanceof GradientDrawable gradient) {
				gradient.mutate();
				gradient.setColor(color);
			}
		}

		private static int clampedByte(float v) {
			return Math.max(0, Math.min(255, Math.round(v * 255)));
		}

		private static int progressFor(@Nullable Float value, float lo, float hi) {
			if (value == null || hi <= lo) {
				return SLIDER_STEPS / 2;
			}
			int progress = Math.round((value - lo) / (hi - lo) * SLIDER_STEPS);
			return Math.max(0, Math.min(SLIDER_STEPS, progress));
		}

		// Tolerates a trailing 'f'/'F' suffix (common in GLSL float
		// literals) and otherwise-unparseable text (returns null rather
		// than throwing) - callers treat null as "don't move the
		// slider", not an error.
		@Nullable
		private static Float parseLeading(@NonNull String text) {
			String candidate = (text.endsWith("f") || text.endsWith("F"))
					? text.substring(0, text.length() - 1)
					: text;
			try {
				return Float.parseFloat(candidate);
			} catch (NumberFormatException e) {
				return null;
			}
		}

		// Formats `value` the way styleLike's own value was written -
		// specifically, carries over a trailing f/F suffix if it had
		// one, so e.g. editing "2.0f" via the slider doesn't silently
		// turn it into "2.5" and change the literal's type in GLSL.
		@NonNull
		private static String formatFloatLike(float value, @NonNull String styleLike) {
			String suffix = styleLike.endsWith("f") ? "f" : styleLike.endsWith("F") ? "F" : "";
			return ShaderDefine.formatFloat(value) + suffix;
		}
	}

	// A TextWatcher that only cares about the final text, not the
	// start/count bookkeeping every one of its three methods normally
	// has to repeat - every row's comment/value boxes above use one of
	// these rather than restating that boilerplate per use.
	private static final class SimpleTextWatcher implements TextWatcher {
		@FunctionalInterface
		interface OnText {
			void onText(@NonNull String text);
		}

		private final OnText onText;

		SimpleTextWatcher(@NonNull OnText onText) {
			this.onText = onText;
		}

		@Override
		public void beforeTextChanged(CharSequence s, int start, int count, int after) {
		}

		@Override
		public void onTextChanged(CharSequence s, int start, int before, int count) {
		}

		@Override
		public void afterTextChanged(Editable s) {
			onText.onText(s.toString());
		}
	}
}
