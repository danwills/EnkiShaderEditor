package enk.danwills.android.enkishadereditor.widget;

import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

// A deliberately hard-to-trigger-by-accident gesture for backing out of
// fullscreen mode without needing the (hidden) toolbar: ramp up to at
// least PEAK_POINTERS_REQUIRED simultaneous touches, then lift fingers
// back down to exactly one and hold. Reaching that point goes PENDING
// rather than immediately exit-ready, so a quick unintentional
// roll-and-release (e.g. a 3-finger-to-1-finger slide with no pause)
// can't trigger it; only after holding at one finger for ARM_DELAY_MS
// does it become ARMED, at which point releasing that last finger
// exits fullscreen. Putting another finger down again while
// pending/armed cancels and resumes ramp-tracking instead, so there's
// always a way to back out before committing.
//
// onTouch() always returns false; it only observes events for this
// state machine, never consumes them, so it can be wired in alongside
// normal touch handling (e.g. a touch-reactive shader's own uniforms)
// without affecting it at all.
public class FullscreenExitGestureDetector {
	public enum Phase {
		IDLE, PENDING, ARMED
	}

	public interface Listener {
		void onPhaseChanged(@NonNull Phase phase);

		void onExitTriggered();
	}

	private static final int PEAK_POINTERS_REQUIRED = 3;

	// How long the gesture must be held at exactly one finger (after
	// peaking) before it arms. Callers showing a "release to exit" cue
	// should fade it in over this same duration, so the cue only
	// finishes appearing right as a release actually starts to count.
	public static final long ARM_DELAY_MS = 500;

	@NonNull
	private final Listener listener;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable armRunnable = this::arm;
	private boolean enabled;
	private int maxPointersThisRamp;
	@NonNull
	private Phase phase = Phase.IDLE;

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
				setPhase(Phase.IDLE);
				break;
			case MotionEvent.ACTION_POINTER_DOWN:
				if (phase != Phase.IDLE) {
					// Another finger went down while pending/armed - back
					// out of the pending exit, and start tracking a fresh
					// ramp from here rather than requiring a full release.
					setPhase(Phase.IDLE);
					maxPointersThisRamp = pointerCount;
				} else {
					maxPointersThisRamp = Math.max(maxPointersThisRamp, pointerCount);
				}
				break;
			case MotionEvent.ACTION_POINTER_UP:
				// getPointerCount() still includes the departing pointer
				// at this point, so pointerCount - 1 is what remains.
				if (pointerCount - 1 == 1 && maxPointersThisRamp >= PEAK_POINTERS_REQUIRED) {
					setPhase(Phase.PENDING);
					handler.removeCallbacks(armRunnable);
					handler.postDelayed(armRunnable, ARM_DELAY_MS);
				}
				break;
			case MotionEvent.ACTION_UP:
				if (phase == Phase.ARMED) {
					setPhase(Phase.IDLE);
					listener.onExitTriggered();
				} else {
					// Releasing while still PENDING (or already IDLE) is
					// not a trigger - the hint hadn't finished appearing
					// yet, so this doesn't count as a deliberate exit.
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

	private void arm() {
		// The held finger may have lifted, or another may have gone
		// down, in the time it took the delay to elapse - only commit
		// if still mid-gesture and nothing else changed the phase.
		if (phase == Phase.PENDING) {
			setPhase(Phase.ARMED);
		}
	}

	private void setPhase(@NonNull Phase phase) {
		if (this.phase == phase) {
			return;
		}
		this.phase = phase;
		if (phase != Phase.PENDING) {
			handler.removeCallbacks(armRunnable);
		}
		listener.onPhaseChanged(phase);
	}

	private void reset() {
		maxPointersThisRamp = 0;
		setPhase(Phase.IDLE);
	}
}
