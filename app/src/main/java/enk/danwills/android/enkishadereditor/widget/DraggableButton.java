package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

// A floating button that can be dragged anywhere over its parent: a
// touch that moves further than the touch slop drags it, anything else
// is a click. Where it was left is kept (for the app session, by the
// shared `position`), so it comes back in the same place.
public class DraggableButton extends AppCompatImageButton {
	// Its offset from where the layout puts it, shared by every
	// instance - there's only ever one on screen.
	private static final float[] position = {0f, 0f};

	private final int slop;
	private float downX;
	private float downY;
	private float startX;
	private float startY;
	private boolean dragging;

	public DraggableButton(@NonNull Context context) {
		this(context, null);
	}

	public DraggableButton(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		slop = ViewConfiguration.get(context).getScaledTouchSlop();
		// Touch only: with keyboard focus it'd show a grey focus square,
		// and keys meant for the shader would move focus onto it.
		setFocusable(false);
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		super.onLayout(changed, left, top, right, bottom);
		if (changed) {
			moveTo(position[0], position[1]);
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(@NonNull MotionEvent event) {
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN -> {
				downX = event.getRawX();
				downY = event.getRawY();
				startX = getTranslationX();
				startY = getTranslationY();
				dragging = false;
				setPressed(true);
				return true;
			}
			case MotionEvent.ACTION_MOVE -> {
				float dx = event.getRawX() - downX;
				float dy = event.getRawY() - downY;
				if (!dragging && Math.hypot(dx, dy) > slop) {
					dragging = true;
					setPressed(false);
					getParent().requestDisallowInterceptTouchEvent(true);
				}
				if (dragging) {
					moveTo(startX + dx, startY + dy);
				}
				return true;
			}
			case MotionEvent.ACTION_UP -> {
				setPressed(false);
				if (!dragging) {
					performClick();
				}
				return true;
			}
			case MotionEvent.ACTION_CANCEL -> {
				setPressed(false);
				return true;
			}
			default -> {
				return super.onTouchEvent(event);
			}
		}
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	// Kept wholly inside the parent.
	private void moveTo(float x, float y) {
		if (getParent() instanceof View parent && getWidth() > 0) {
			x = Math.max(-getLeft(), Math.min(parent.getWidth() - getRight(), x));
			y = Math.max(-getTop(), Math.min(parent.getHeight() - getBottom(), y));
		}
		setTranslationX(x);
		setTranslationY(y);
		position[0] = x;
		position[1] = y;
		// Over the shader's SurfaceView, the window only works out which
		// parts of it to draw (rather than leave see-through for the
		// surface) in a layout pass - so ask for one, or it isn't drawn
		// where it's moved to.
		requestLayout();
	}
}
