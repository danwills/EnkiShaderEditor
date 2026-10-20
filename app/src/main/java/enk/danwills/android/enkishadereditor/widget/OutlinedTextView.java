package enk.danwills.android.enkishadereditor.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

// Text with a thin dark outline round every glyph, so it stays readable
// over whatever the shader draws behind it - the shader name and fps
// over the preview, however bright. Draws the text twice: once stroked
// in OUTLINE_COLOR, then filled in its own colour on top.
public class OutlinedTextView extends AppCompatTextView {
	private static final int OUTLINE_COLOR = 0xe6000000;
	// Total stroke width; half of it shows outside the glyphs.
	private static final float OUTLINE_DP = 3f;

	private final float outlineWidth;
	// Set while onDraw() swaps the text colour, so that doesn't trigger
	// another draw.
	private boolean drawingOutline;

	public OutlinedTextView(@NonNull Context context) {
		this(context, null);
	}

	public OutlinedTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, android.R.attr.textViewStyle);
	}

	public OutlinedTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		outlineWidth = OUTLINE_DP * getResources().getDisplayMetrics().density;
		// Room for the outline at the edges, so it isn't clipped.
		int pad = (int) Math.ceil(outlineWidth / 2f);
		setPadding(getPaddingLeft() + pad, getPaddingTop() + pad,
				getPaddingRight() + pad, getPaddingBottom() + pad);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		ColorStateList colors = getTextColors();
		TextPaint paint = getPaint();
		drawingOutline = true;
		paint.setStyle(Paint.Style.STROKE);
		paint.setStrokeJoin(Paint.Join.ROUND);
		paint.setStrokeWidth(outlineWidth);
		setTextColor(OUTLINE_COLOR);
		super.onDraw(canvas);
		paint.setStyle(Paint.Style.FILL);
		paint.setStrokeWidth(0f);
		setTextColor(colors);
		super.onDraw(canvas);
		drawingOutline = false;
	}

	@Override
	public void invalidate() {
		if (!drawingOutline) {
			super.invalidate();
		}
	}
}
