package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// A hue/saturation disc for the color picker (see MainActivity.
// showColorPickerDialog()): hue goes round the disc, saturation out from
// the white centre, and dragging anywhere on it picks both at once. The
// disc is darkened to the color's current value (brightness), which the
// picker's V slider sets.
public class ColorWheelView extends View {
	public interface OnHueSaturationChanged {
		void onHueSaturationChanged(float hue, float saturation);
	}

	private static final float SELECTOR_RADIUS_DP = 9f;

	private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final float density;
	// Hue (0-360), saturation and value (0-1), as in Color.HSVToColor().
	private final float[] hsv = {0f, 0f, 1f};
	// The disc at full value, redrawn only when the size changes.
	@Nullable
	private Bitmap disc;
	@Nullable
	private OnHueSaturationChanged listener;

	public ColorWheelView(@NonNull Context context) {
		this(context, null);
	}

	public ColorWheelView(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		density = getResources().getDisplayMetrics().density;
		shadePaint.setColor(Color.BLACK);
		selectorPaint.setStyle(Paint.Style.STROKE);
	}

	public void setOnHueSaturationChanged(@Nullable OnHueSaturationChanged listener) {
		this.listener = listener;
	}

	public void setHsv(@NonNull float[] hsv) {
		System.arraycopy(hsv, 0, this.hsv, 0, 3);
		invalidate();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		// Square, as wide as it's allowed up to its height.
		int width = MeasureSpec.getSize(widthMeasureSpec);
		int height = MeasureSpec.getSize(heightMeasureSpec);
		int size = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
				? width
				: Math.min(width, height);
		setMeasuredDimension(size, size);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		disc = null;
	}

	private float radius() {
		return Math.min(getWidth(), getHeight()) / 2f - SELECTOR_RADIUS_DP * density;
	}

	@NonNull
	private Bitmap buildDisc() {
		int size = Math.min(getWidth(), getHeight());
		Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
		float c = size / 2f;
		int[] hues = new int[7];
		for (int i = 0; i < hues.length; i++) {
			hues[i] = Color.HSVToColor(new float[]{i * 60f % 360f, 1f, 1f});
		}
		Shader hue = new SweepGradient(c, c, hues, null);
		Shader saturation = new RadialGradient(c, c, radius(), Color.WHITE,
				Color.TRANSPARENT, Shader.TileMode.CLAMP);
		discPaint.setShader(new ComposeShader(hue, saturation, PorterDuff.Mode.SRC_OVER));
		new Canvas(bitmap).drawCircle(c, c, radius(), discPaint);
		return bitmap;
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		if (getWidth() == 0 || getHeight() == 0) {
			return;
		}
		if (disc == null) {
			disc = buildDisc();
		}
		float cx = getWidth() / 2f;
		float cy = getHeight() / 2f;
		canvas.drawBitmap(disc, cx - disc.getWidth() / 2f, cy - disc.getHeight() / 2f, null);
		shadePaint.setAlpha(Math.round((1f - hsv[2]) * 255f));
		canvas.drawCircle(cx, cy, radius(), shadePaint);

		double angle = Math.toRadians(hsv[0]);
		float x = cx + (float) Math.cos(angle) * hsv[1] * radius();
		float y = cy + (float) Math.sin(angle) * hsv[1] * radius();
		float r = SELECTOR_RADIUS_DP * density;
		selectorPaint.setStrokeWidth(3f * density);
		selectorPaint.setColor(Color.BLACK);
		canvas.drawCircle(x, y, r, selectorPaint);
		selectorPaint.setStrokeWidth(1.5f * density);
		selectorPaint.setColor(Color.WHITE);
		canvas.drawCircle(x, y, r, selectorPaint);
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(@NonNull MotionEvent event) {
		int action = event.getActionMasked();
		if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE) {
			return action == MotionEvent.ACTION_UP;
		}
		// Keep the dialog's scrolling out of a drag round the disc.
		getParent().requestDisallowInterceptTouchEvent(true);
		float dx = event.getX() - getWidth() / 2f;
		float dy = event.getY() - getHeight() / 2f;
		float hue = (float) Math.toDegrees(Math.atan2(dy, dx));
		if (hue < 0f) {
			hue += 360f;
		}
		float saturation = Math.min(1f, (float) Math.hypot(dx, dy) / radius());
		hsv[0] = hue;
		hsv[1] = saturation;
		invalidate();
		if (listener != null) {
			listener.onHueSaturationChanged(hue, saturation);
		}
		return true;
	}
}
