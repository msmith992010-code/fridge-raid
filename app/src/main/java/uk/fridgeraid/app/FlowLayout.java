package uk.fridgeraid.app;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Lays children out left to right and wraps onto new rows, like chips on a fridge door. */
final class FlowLayout extends ViewGroup {

    private final int gap;

    FlowLayout(Context context, int gapPx) {
        super(context);
        this.gap = gapPx;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int maxRow = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int childWidthSpec = MeasureSpec.makeMeasureSpec(maxRow, MeasureSpec.AT_MOST);
        int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);

        int x = 0, y = 0, rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(childWidthSpec, childHeightSpec);
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > 0 && x + cw > maxRow) {
                x = 0;
                y += rowHeight + gap;
                rowHeight = 0;
            }
            x += cw + gap;
            rowHeight = Math.max(rowHeight, ch);
        }
        int height = y + rowHeight + getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int maxRow = (r - l) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int cw = child.getMeasuredWidth();
            int ch = child.getMeasuredHeight();
            if (x > 0 && x + cw > maxRow) {
                x = 0;
                y += rowHeight + gap;
                rowHeight = 0;
            }
            int left = getPaddingLeft() + x;
            int top = getPaddingTop() + y;
            child.layout(left, top, left + cw, top + ch);
            x += cw + gap;
            rowHeight = Math.max(rowHeight, ch);
        }
    }
}
