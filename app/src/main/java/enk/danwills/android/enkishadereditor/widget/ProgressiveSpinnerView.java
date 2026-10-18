package enk.danwills.android.enkishadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

// A flat white dot shown while the live preview renders progressively
// (see ShaderRenderer's drawProgressiveStep()), counting down to the next
// finished frame: its area is the fraction of the frame still to draw, so
// it starts full-size when a new frame's first band lands, shrinks as
// bands complete, and is gone entirely while a finished frame is on show
// (e.g. through a Breathing Room pause) - until the next frame's first
// band, when it reappears full-size. Shrinking eases smoothly so a big
// band reads as a quick shrink rather than a jump; reappearing is
// immediate, since that's a real "new frame started" event.
public class ProgressiveSpinnerView extends View {
	public interface Source {
		// 0..1, how much of the frame currently being drawn is done - 1
		// once it has completed, until the next frame's first band.
		float getFrameProgress();
	}

	// Fraction of the remaining size gap closed per second (exponential).
	private static final float SHRINK_CATCH_UP_PER_SECOND = 12f;
	// Below this fraction of full size the dot is simply not drawn.
	private static final float MIN_VISIBLE_FRACTION = 0.02f;

	private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	@Nullable
	private Source source;
	// Displayed remaining fraction of the frame, 0..1.
	private float displayedRemaining;
	private long lastFrameNanos;

	public ProgressiveSpinnerView(Context context) {
		this(context, null);
	}

	public ProgressiveSpinnerView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		dotPaint.setStyle(Paint.Style.FILL);
		dotPaint.setColor(Color.WHITE);
	}

	public void setSource(@Nullable Source source) {
		this.source = source;
		invalidate();
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);
		long now = System.nanoTime();
		float dt = lastFrameNanos == 0 ? 0f : Math.min(0.1f, (now - lastFrameNanos) / 1e9f);
		lastFrameNanos = now;

		float progress = source == null ? 1f
				: Math.max(0f, Math.min(1f, source.getFrameProgress()));
		float remaining = 1f - progress;
		if (remaining <= 0f) {
			// Finished frame on show - no dot at all.
			displayedRemaining = 0f;
		} else if (remaining > displayedRemaining) {
			// A new frame started: back to (nearly) full size at once.
			displayedRemaining = remaining;
		} else {
			float catchUp = 1f - (float) Math.exp(-SHRINK_CATCH_UP_PER_SECOND * dt);
			displayedRemaining += (remaining - displayedRemaining) * catchUp;
		}

		if (displayedRemaining > MIN_VISIBLE_FRACTION) {
			float maxRadius = Math.min(getWidth(), getHeight()) / 2f;
			// Area, not radius, tracks what's left - so it reads as "how
			// much is left" rather than collapsing too fast at the end.
			float radius = maxRadius * (float) Math.sqrt(displayedRemaining);
			canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, radius, dotPaint);
		}

		if (getVisibility() == VISIBLE && isAttachedToWindow()) {
			postInvalidateOnAnimation();
		}
	}

	@Override
	protected void onVisibilityChanged(@NonNull View changedView, int visibility) {
		super.onVisibilityChanged(changedView, visibility);
		lastFrameNanos = 0;
		if (visibility == VISIBLE) {
			invalidate();
		}
	}
}
