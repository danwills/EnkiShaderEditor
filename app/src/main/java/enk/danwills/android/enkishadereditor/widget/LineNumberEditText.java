package enk.danwills.android.enkishadereditor.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import enk.danwills.android.enkishadereditor.R;

public class LineNumberEditText extends AppCompatEditText {
	private final float lineNumberSpacing;
	private final float lineNumberPadding;

	private boolean showLineNumbers;
	private Paint lineNumberPaint;
	private float bigChar;
	private int paddingLeft;
	// onDraw()'s clip, kept rather than made each draw.
	private final Rect clip = new Rect();

	public LineNumberEditText(Context context) {
		this(context, null);
	}

	public LineNumberEditText(Context context, AttributeSet attrs) {
		super(context, attrs);
		int lineColor;
		//noinspection resource
		TypedArray a = context.getTheme().obtainStyledAttributes(
				attrs,
				R.styleable.LineNumberEditText,
				0, 0);

		try {
			showLineNumbers = a.getBoolean(
					R.styleable.LineNumberEditText_showLineNumbers,
					true);
			lineColor = a.getColor(
					R.styleable.LineNumberEditText_lineNumberColor,
					0x88888888);
			lineNumberSpacing = a.getDimension(
					R.styleable.LineNumberEditText_lineNumberSpacing,
					16);
			lineNumberPadding = a.getDimension(
					R.styleable.LineNumberEditText_lineNumberPadding,
					8);
		} finally {
			a.recycle();
		}
		init(lineColor);
		// Shader code has no use for AppCompat's emoji compatibility,
		// which only makes laying out a long shader's spans slower.
		setEmojiCompatEnabled(false);
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		if (showLineNumbers != this.showLineNumbers) {
			this.showLineNumbers = showLineNumbers;
			postInvalidate();
		}
	}

	private void init(int color) {
		paddingLeft = getPaddingLeft();
		lineNumberPaint = new Paint(getPaint());
		lineNumberPaint.setColor(color);
		measureBigChar();
	}

	@Override
	public void setTypeface(@Nullable Typeface tf) {
		super.setTypeface(tf);
		if (lineNumberPaint != null) {
			lineNumberPaint.setTypeface(tf);
			measureBigChar();
		}
	}

	@Override
	public void setPadding(int left, int top, int right, int bottom) {
		this.paddingLeft = left;
		super.setPadding(left, top, right, bottom);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		if (!showLineNumbers) {
			setLeftPadding(paddingLeft);
			super.onDraw(canvas);
			return;
		}
		int lineCount = getLineCount();
		final int lineCountNumDigits = numDigits(lineCount);
		int editTextPaddingLeft = (int) (bigChar * lineCountNumDigits + lineNumberPadding);
		setLeftPadding((int) (editTextPaddingLeft + lineNumberSpacing));
		super.onDraw(canvas);
		// Only the lines that can be seen: the canvas is clipped to the
		// part of the editor on screen (inside its scroll views).
		var layout = getLayout();
		int first = 0;
		int last = lineCount - 1;
		if (layout != null && canvas.getClipBounds(clip)) {
			int top = getExtendedPaddingTop();
			first = Math.max(0, layout.getLineForVertical(clip.top - top));
			last = Math.min(lineCount - 1, layout.getLineForVertical(clip.bottom - top));
		}
		for (int i = first; i <= last; ++i) {
			int lineNumber = i + 1;
			int baseline = getLineBounds(i, null);
			canvas.drawText(
					Integer.toString(lineNumber),
					bigChar * (lineCountNumDigits - numDigits(lineNumber)) + lineNumberPadding,
					baseline,
					lineNumberPaint);
		}
	}

	// Only when it changes: TextView.setPadding() invalidates every time,
	// so calling it from onDraw() redrew the editor every frame - for a
	// long shader, well over 100 ms each, enough to hang the app.
	private void setLeftPadding(int left) {
		if (left != getPaddingLeft()) {
			super.setPadding(left, getPaddingTop(), getPaddingRight(), getPaddingBottom());
		}
	}

	private void measureBigChar() {
		bigChar = lineNumberPaint.measureText("m");
	}

	/**
	 * Taken from
	 * <a href="https://www.baeldung.com/java-number-of-digits-in-int#5-divide-and-conquer">Baeldung (Number of Digits in an Integer in Java)</a>
	 *
	 * @param number the number of which you want to get the number of digits.
	 * @return the number of decimal digits of the given {@code number}
	 */
	private static int numDigits(int number) {
		if (number < 100000) {
			if (number < 100) {
				if (number < 10) {
					return 1;
				} else {
					return 2;
				}
			} else if (number < 1000) {
				return 3;
			} else if (number < 10000) {
				return 4;
			} else {
				return 5;
			}
		} else if (number < 10000000) {
			return number < 1000000 ? 6 : 7;
		} else if (number < 100000000) {
			return 8;
		} else if (number < 1000000000) {
			return 9;
		} else {
			return 10;
		}
	}
}
