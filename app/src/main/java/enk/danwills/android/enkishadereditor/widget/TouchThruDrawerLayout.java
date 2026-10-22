package enk.danwills.android.enkishadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;

import androidx.drawerlayout.widget.DrawerLayout;

/**
 * Required to get touches through DrawerLayout. Also makes the drawer as
 * wide as the layout: DrawerLayout keeps a margin beside a
 * match_parent drawer, but not beside one of an exact width.
 */
public class TouchThruDrawerLayout extends DrawerLayout {
	private boolean touchThru = false;

	public TouchThruDrawerLayout(Context context) {
		super(context);
	}

	public TouchThruDrawerLayout(Context context, AttributeSet attr) {
		super(context, attr);
	}

	public void setTouchThru(boolean touchThru) {
		this.touchThru = touchThru;
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int width = MeasureSpec.getSize(widthMeasureSpec);
		for (int i = 0, n = getChildCount(); i < n; ++i) {
			View child = getChildAt(i);
			LayoutParams lp = (LayoutParams) child.getLayoutParams();
			if (lp.gravity != Gravity.NO_GRAVITY && width > 0 && lp.width != width) {
				lp.width = width;
			}
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}

	@Override
	public boolean onInterceptTouchEvent(MotionEvent event) {
		return !touchThru && super.onInterceptTouchEvent(event);
	}

	// Click is handled in super class.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		return !touchThru && super.onTouchEvent(event);
	}
}
