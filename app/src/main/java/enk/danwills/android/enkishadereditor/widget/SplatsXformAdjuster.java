package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import enk.danwills.android.enkishadereditor.R;

// Adjusting the splats xform (see PrimitivePresenter.setXform()): while on,
// touches on the shader move the drawn points and splats instead of
// reaching the shader - one finger drags, two pinch and twist about the
// point between them - with a bar to reset or finish. Measured in frame
// heights from the frame's centre, so it holds at any quality.
public final class SplatsXformAdjuster {
	public interface Host {
		// The view's touches, from now on - null to give them back.
		void setTouchInterceptor(@Nullable View.OnTouchListener interceptor);

		void setXform(float x, float y, float zoom, float angle);
	}

	private static final float MIN_ZOOM = 0.05f;
	private static final float MAX_ZOOM = 200f;

	private final Activity activity;
	private final View shaderView;
	private final Host host;
	private final float[] xform;
	@Nullable
	private View bar;
	private int lastCount;
	private final float[] last = new float[4];

	// xform: (x, y, zoom, angle), changed in place as it's adjusted.
	public SplatsXformAdjuster(@NonNull Activity activity, @NonNull View shaderView,
			@NonNull Host host, @NonNull float[] xform) {
		this.activity = activity;
		this.shaderView = shaderView;
		this.host = host;
		this.xform = xform;
	}

	public boolean isAdjusting() {
		return bar != null;
	}

	@SuppressLint("ClickableViewAccessibility")
	public void start() {
		if (bar != null) {
			return;
		}
		lastCount = 0;
		host.setTouchInterceptor((v, event) -> {
			onTouch(event);
			return true;
		});
		bar = makeBar();
		ViewGroup root = activity.findViewById(android.R.id.content);
		var params = new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
				Gravity.BOTTOM);
		int margin = dp(12);
		params.setMargins(margin, margin, margin, dp(48));
		root.addView(bar, params);
	}

	public void finish() {
		if (bar == null) {
			return;
		}
		host.setTouchInterceptor(null);
		((ViewGroup) bar.getParent()).removeView(bar);
		bar = null;
	}

	private void reset() {
		xform[0] = 0f;
		xform[1] = 0f;
		xform[2] = 1f;
		xform[3] = 0f;
		apply();
	}

	private void apply() {
		host.setXform(xform[0], xform[1], xform[2], xform[3]);
	}

	// A touch at (px, py) on the view, in frame heights from its centre,
	// y up.
	private float unitX(float px) {
		return (px - shaderView.getWidth() * 0.5f) / shaderView.getHeight();
	}

	private float unitY(float py) {
		return (shaderView.getHeight() * 0.5f - py) / shaderView.getHeight();
	}

	private void onTouch(@NonNull MotionEvent event) {
		int count = event.getActionMasked() == MotionEvent.ACTION_UP ||
				event.getActionMasked() == MotionEvent.ACTION_CANCEL
				? 0 : Math.min(2, event.getPointerCount());
		if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
			// The lifted finger is still counted in this event.
			count = Math.min(2, event.getPointerCount() - 1);
		}
		float[] now = new float[4];
		int up = event.getActionMasked() == MotionEvent.ACTION_POINTER_UP
				? event.getActionIndex() : -1;
		for (int i = 0, j = 0; i < event.getPointerCount() && j < 2; ++i) {
			if (i == up) {
				continue;
			}
			now[2 * j] = unitX(event.getX(i));
			now[2 * j + 1] = unitY(event.getY(i));
			++j;
		}
		if (count == lastCount && count > 0) {
			if (count == 1) {
				xform[0] += now[0] - last[0];
				xform[1] += now[1] - last[1];
			} else {
				// Keep what was under the point between the fingers there.
				float mx0 = (last[0] + last[2]) * 0.5f;
				float my0 = (last[1] + last[3]) * 0.5f;
				float mx1 = (now[0] + now[2]) * 0.5f;
				float my1 = (now[1] + now[3]) * 0.5f;
				float d0x = last[2] - last[0];
				float d0y = last[3] - last[1];
				float d1x = now[2] - now[0];
				float d1y = now[3] - now[1];
				float len0 = (float) Math.hypot(d0x, d0y);
				float len1 = (float) Math.hypot(d1x, d1y);
				if (len0 > 1e-4f && len1 > 1e-4f) {
					float zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, xform[2] * len1 / len0));
					float turn = (float) (Math.atan2(d1y, d1x) - Math.atan2(d0y, d0x));
					// The point under the old midpoint, before the xform.
					float qx = mx0 - xform[0];
					float qy = my0 - xform[1];
					float c = (float) Math.cos(-xform[3]);
					float s = (float) Math.sin(-xform[3]);
					float px = (c * qx - s * qy) / xform[2];
					float py = (s * qx + c * qy) / xform[2];
					xform[2] = zoom;
					xform[3] += turn;
					c = (float) Math.cos(xform[3]);
					s = (float) Math.sin(xform[3]);
					xform[0] = mx1 - (c * px - s * py) * zoom;
					xform[1] = my1 - (s * px + c * py) * zoom;
				}
			}
			apply();
		}
		lastCount = count;
		System.arraycopy(now, 0, last, 0, 4);
	}

	@NonNull
	private View makeBar() {
		var row = new LinearLayout(activity);
		row.setOrientation(LinearLayout.HORIZONTAL);
		row.setGravity(Gravity.CENTER_VERTICAL);
		row.setPadding(dp(16), dp(6), dp(8), dp(6));
		var background = new GradientDrawable();
		background.setColor(Color.argb(230, 24, 26, 34));
		background.setCornerRadius(dp(16));
		row.setBackground(background);
		var label = new TextView(activity);
		label.setText(R.string.splats_xform_adjusting);
		label.setTextColor(Color.WHITE);
		label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		row.addView(label, new LinearLayout.LayoutParams(0,
				ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
		row.addView(button(R.string.splats_xform_reset, v -> reset()));
		row.addView(button(R.string.splats_xform_done, v -> finish()));
		return row;
	}

	@NonNull
	private Button button(int text, @NonNull View.OnClickListener listener) {
		var button = new Button(activity, null, android.R.attr.borderlessButtonStyle);
		button.setText(text);
		button.setTextColor(Color.rgb(255, 200, 120));
		button.setOnClickListener(listener);
		return button;
	}

	private int dp(int value) {
		return Math.round(value * activity.getResources().getDisplayMetrics().density);
	}
}
