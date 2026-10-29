package enk.danwills.android.enkishadereditor.widget;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import enk.danwills.android.enkishadereditor.R;
import enk.danwills.android.enkishadereditor.opengl.Upscaling.Edge;
import enk.danwills.android.enkishadereditor.opengl.Upscaling.Filter;
import enk.danwills.android.enkishadereditor.opengl.Upscaling.Upscale;

// Picks how a frame rendered below the screen's resolution is scaled up to
// it (see Upscaling): a filter, what's past the frame's edges, and for
// some filters an amount. Every change shows at once, the screen left
// undimmed to see it; OK keeps it (for the session, or - from Settings -
// as the one each session starts with), Cancel goes back.
public final class UpscaleDialog {
	public interface Host {
		void preview(@NonNull Upscale upscale);

		void apply(@NonNull Upscale upscale);

		// Points and splats: apply `upscale`, then adjust the splats xform
		// (see SplatsXformAdjuster). Not offered for Settings' default.
		default void adjustXform(@NonNull Upscale upscale) {
		}
	}

	private static final int[] FILTER_NAMES = {
			R.string.upscale_default,
			R.string.upscale_nearest,
			R.string.upscale_sharp,
			R.string.upscale_linear,
			R.string.upscale_bspline,
			R.string.upscale_catmull,
			R.string.upscale_sinc,
			R.string.upscale_defocus,
			R.string.upscale_crt,
			R.string.upscale_bokeh,
			R.string.upscale_gaussian,
			R.string.upscale_points,
			R.string.upscale_splats,
	};
	private static final int[] EDGE_NAMES = {
			R.string.upscale_edge_clamp,
			R.string.upscale_edge_periodic,
			R.string.upscale_edge_mirror,
			R.string.upscale_edge_black,
	};

	private final Activity activity;
	private final Host host;
	private final Upscale original;
	private Button xformButton;
	private final boolean forDefault;
	private Filter filter;
	private Edge edge;
	private float amount;
	private TextView amountLabel;
	private Slider amountSlider;
	private View edgeSection;

	private UpscaleDialog(@NonNull Activity activity, @NonNull Upscale current,
			@NonNull Host host, boolean forDefault) {
		this.activity = activity;
		this.host = host;
		this.forDefault = forDefault;
		original = current;
		filter = current.filter();
		edge = current.edge();
		amount = current.amount();
	}

	// forDefault: picking Settings' default, rather than the view's now.
	public static void show(@NonNull Activity activity, @NonNull Upscale current,
			@NonNull Host host, boolean forDefault) {
		new UpscaleDialog(activity, current, host, forDefault).show();
	}

	// The filter's name, as the dialog lists it.
	@StringRes
	public static int filterName(@NonNull Upscale upscale) {
		return FILTER_NAMES[upscale.filter().ordinal()];
	}

	private void show() {
		int pad = dp(20);
		var column = new LinearLayout(activity);
		column.setOrientation(LinearLayout.VERTICAL);
		column.setPadding(pad, dp(8), pad, 0);

		var filters = new RadioGroup(activity);
		for (Filter f : Filter.values()) {
			var button = new RadioButton(activity);
			button.setText(FILTER_NAMES[f.ordinal()]);
			button.setId(View.generateViewId());
			button.setChecked(f == filter);
			button.setOnClickListener(v -> {
				filter = f;
				amount = f.defaultAmount;
				updateExtras();
				preview();
			});
			filters.addView(button);
		}
		column.addView(filters);

		var edgeColumn = new LinearLayout(activity);
		edgeColumn.setOrientation(LinearLayout.VERTICAL);
		edgeColumn.addView(label(R.string.upscale_edges));
		// Flowed rather than a RadioGroup's one row, which runs off the
		// side - so the buttons are kept exclusive by hand.
		var edges = new FlowLayout(activity);
		var edgeButtons = new RadioButton[Edge.values().length];
		for (Edge e : Edge.values()) {
			var button = new RadioButton(activity);
			button.setText(EDGE_NAMES[e.ordinal()]);
			button.setChecked(e == edge);
			button.setOnClickListener(v -> {
				edge = e;
				for (RadioButton other : edgeButtons) {
					other.setChecked(other == button);
				}
				preview();
			});
			edgeButtons[e.ordinal()] = button;
			edges.addView(button);
		}
		edgeColumn.addView(edges);
		edgeSection = edgeColumn;
		column.addView(edgeColumn);

		amountLabel = label(R.string.upscale_radius);
		column.addView(amountLabel);
		amountSlider = new Slider(activity);
		amountSlider.addOnChangeListener((slider, value, fromUser) -> {
			if (fromUser) {
				amount = value;
				preview();
			}
		});
		column.addView(amountSlider, new LinearLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

		xformButton = new Button(activity, null, android.R.attr.borderlessButtonStyle);
		xformButton.setText(R.string.upscale_adjust_xform);
		column.addView(xformButton);

		var hint = label(forDefault ? R.string.upscale_default_hint : R.string.upscale_hint);
		hint.setAlpha(0.7f);
		column.addView(hint);
		updateExtras();

		var scroll = new ScrollView(activity);
		scroll.addView(column);
		AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(forDefault ? R.string.default_upscaling : R.string.upscaling)
				.setView(scroll)
				.setPositiveButton(android.R.string.ok, (d, w) -> host.apply(current()))
				.setNegativeButton(android.R.string.cancel, (d, w) -> host.preview(original))
				.setOnCancelListener(d -> host.preview(original))
				.create();
		dialog.show();
		xformButton.setOnClickListener(v -> {
			dialog.dismiss();
			host.adjustXform(current());
		});
		// Undimmed, to see each choice on the shader behind.
		if (dialog.getWindow() != null) {
			dialog.getWindow().setDimAmount(0f);
		}
	}

	@NonNull
	private Upscale current() {
		return new Upscale(filter, edge, amount);
	}

	private void preview() {
		host.preview(current());
	}

	// The edge choice only for filters that read past the edges; the
	// amount only for filters that have one.
	private void updateExtras() {
		boolean reaches = filter != Filter.DEFAULT && filter != Filter.NEAREST &&
				!filter.isPrimitives();
		edgeSection.setVisibility(reaches ? View.VISIBLE : View.GONE);
		// Not for Settings' default: it's the open shader's.
		xformButton.setVisibility(filter.isPrimitives() && !forDefault ? View.VISIBLE : View.GONE);
		boolean hasAmount = filter.hasAmount();
		amountLabel.setVisibility(hasAmount ? View.VISIBLE : View.GONE);
		amountSlider.setVisibility(hasAmount ? View.VISIBLE : View.GONE);
		if (hasAmount) {
			amountLabel.setText(filter == Filter.SINC ? R.string.upscale_lobes
					: filter.isRadius() ? R.string.upscale_radius
					: filter.isPrimitives() ? R.string.upscale_exposure
					: R.string.upscale_strength);
			amountSlider.setValueFrom(filter.minAmount());
			amountSlider.setValueTo(filter.maxAmount());
			amountSlider.setStepSize(filter.amountStep());
			float step = amountSlider.getStepSize();
			float snapped = filter.minAmount() +
					Math.round((amount - filter.minAmount()) / step) * step;
			amountSlider.setValue(Math.max(filter.minAmount(),
					Math.min(filter.maxAmount(), snapped)));
		}
	}

	@NonNull
	private TextView label(@StringRes int text) {
		var label = new TextView(activity);
		label.setText(text);
		label.setPadding(0, dp(12), 0, dp(4));
		return label;
	}

	private int dp(int value) {
		return Math.round(value * activity.getResources().getDisplayMetrics().density);
	}
}
