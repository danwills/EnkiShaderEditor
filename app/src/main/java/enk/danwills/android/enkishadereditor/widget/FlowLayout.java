package enk.danwills.android.enkishadereditor.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

// A minimal left-to-right, top-to-bottom flow layout: lays children out
// like words in a paragraph, wrapping to a new row whenever the next
// child doesn't fit in the remaining width, growing to however many
// rows that takes and centering each child vertically within its row.
// Used for the toolbar's action buttons so they reflow to fit instead
// of needing a fixed row count - e.g. settling into one or two rows in
// landscape instead of a tall stack of mostly-empty fixed rows.
public class FlowLayout extends ViewGroup {
	private static final class Row {
		int startIndex;
		int endIndex;
		int height;
	}

	public FlowLayout(@NonNull Context context) {
		super(context);
	}

	public FlowLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int count = getChildCount();
		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() != GONE) {
				measureChild(child, widthMeasureSpec, heightMeasureSpec);
			}
		}

		int availableWidth = MeasureSpec.getSize(widthMeasureSpec)
				- getPaddingLeft() - getPaddingRight();
		List<Row> rows = computeRows(availableWidth);
		int totalHeight = getPaddingTop() + getPaddingBottom();
		int maxRowWidth = 0;
		for (Row row : rows) {
			totalHeight += row.height;
			maxRowWidth = Math.max(maxRowWidth, rowWidth(row));
		}

		int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
				? MeasureSpec.getSize(widthMeasureSpec)
				: maxRowWidth + getPaddingLeft() + getPaddingRight();
		setMeasuredDimension(width, resolveSize(totalHeight, heightMeasureSpec));
	}

	@Override
	protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
		int availableWidth = right - left - getPaddingLeft() - getPaddingRight();
		List<Row> rows = computeRows(availableWidth);

		int y = getPaddingTop();
		for (Row row : rows) {
			int x = getPaddingLeft();
			for (int i = row.startIndex; i < row.endIndex; i++) {
				View child = getChildAt(i);
				if (child.getVisibility() == GONE) {
					continue;
				}
				MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
				int childTop = y + lp.topMargin
						+ (row.height - lp.topMargin - lp.bottomMargin
								- child.getMeasuredHeight()) / 2;
				int childLeft = x + lp.leftMargin;
				child.layout(childLeft, childTop,
						childLeft + child.getMeasuredWidth(),
						childTop + child.getMeasuredHeight());
				x += lp.leftMargin + child.getMeasuredWidth() + lp.rightMargin;
			}
			y += row.height;
		}
	}

	// Shared by onMeasure() and onLayout() so both agree on exactly
	// where each row breaks.
	@NonNull
	private List<Row> computeRows(int availableWidth) {
		List<Row> rows = new ArrayList<>();
		Row row = new Row();
		int rowWidth = 0;
		int count = getChildCount();
		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) {
				continue;
			}
			MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
			int childWidth = lp.leftMargin + child.getMeasuredWidth() + lp.rightMargin;
			int childHeight = lp.topMargin + child.getMeasuredHeight() + lp.bottomMargin;
			if (rowWidth > 0 && rowWidth + childWidth > availableWidth) {
				row.endIndex = i;
				rows.add(row);
				row = new Row();
				row.startIndex = i;
				rowWidth = 0;
			}
			rowWidth += childWidth;
			row.height = Math.max(row.height, childHeight);
		}
		row.endIndex = count;
		if (row.height > 0) {
			rows.add(row);
		}
		return rows;
	}

	private int rowWidth(@NonNull Row row) {
		int width = 0;
		for (int i = row.startIndex; i < row.endIndex; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) {
				continue;
			}
			MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
			width += lp.leftMargin + child.getMeasuredWidth() + lp.rightMargin;
		}
		return width;
	}

	@Override
	public LayoutParams generateLayoutParams(AttributeSet attrs) {
		return new MarginLayoutParams(getContext(), attrs);
	}

	@Override
	protected LayoutParams generateDefaultLayoutParams() {
		return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
	}

	@Override
	protected LayoutParams generateLayoutParams(LayoutParams p) {
		return new MarginLayoutParams(p);
	}

	@Override
	protected boolean checkLayoutParams(LayoutParams p) {
		return p instanceof MarginLayoutParams;
	}
}
