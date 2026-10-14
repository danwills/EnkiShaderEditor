package enk.danwills.android.enkishadereditor.widget;

import android.view.MotionEvent;

import androidx.annotation.NonNull;

// A deliberately hard-to-trigger-by-accident gesture for backing out of
// fullscreen mode without needing the (hidden) toolbar: ramp up to at
// least PEAK_POINTERS_REQUIRED simultaneous touches, then lift fingers
// back down to exactly one and hold - releasing that last finger while
// "armed" exits fullscreen. Putting another finger down again while
// armed cancels the pending exit and resumes ramp-tracking instead, so
// there's always a way to back out before committing.
//
// onTouch() always returns false; it only observes events for this
// state machine, never consumes them, so it can be wired in alongside
// normal touch handling (e.g. a touch-reactive shader's own uniforms)
// without affecting it at all.
public class FullscreenExitGestureDetector {
	public interface Listener {
		void onArmedChanged(boolean armed);

		void onExitTriggered();
	}

	private static final int PEAK_POINTERS_REQUIRED = 3;

	@NonNull
	private final Listener listener;
	private boolean enabled;
	private int maxPointersThisRamp;
	private boolean armed;

	public FullscreenExitGestureDetector(@NonNull Listener listener) {
		this.listener = listener;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		reset();
	}

	public boolean onTouch(@NonNull MotionEvent event) {
		if (!enabled) {
			return false;
		}
		int pointerCount = event.getPointerCount();
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				maxPointersThisRamp = 1;
				setArmed(false);
				break;
			case MotionEvent.ACTION_POINTER_DOWN:
				if (armed) {
					// Another finger went down while armed - back out of
					// the pending exit, and start tracking a fresh ramp
					// from here rather than requiring a full release.
					setArmed(false);
					maxPointersThisRamp = pointerCount;
				} else {
					maxPointersThisRamp = Math.max(maxPointersThisRamp, pointerCount);
				}
				break;
			case MotionEvent.ACTION_POINTER_UP:
				// getPointerCount() still includes the departing pointer
				// at this point, so pointerCount - 1 is what remains.
				if (pointerCount - 1 == 1 && maxPointersThisRamp >= PEAK_POINTERS_REQUIRED) {
					setArmed(true);
				}
				break;
			case MotionEvent.ACTION_UP:
				if (armed) {
					setArmed(false);
					listener.onExitTriggered();
				} else {
					reset();
				}
				break;
			case MotionEvent.ACTION_CANCEL:
				reset();
				break;
			default:
				break;
		}
		return false;
	}

	private void setArmed(boolean armed) {
		if (this.armed == armed) {
			return;
		}
		this.armed = armed;
		listener.onArmedChanged(armed);
	}

	private void reset() {
		maxPointersThisRamp = 0;
		setArmed(false);
	}
}
