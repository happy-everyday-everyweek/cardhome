package com.cardhome.app;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.HorizontalScrollView;

/**
 * 分页卡片条：一次滑动翻一整页，页内卡片由页面自行排版，
 * 因此左右边距恒定、卡片不会被裁切。
 */
public class PagedRowScrollView extends HorizontalScrollView {

    private int pageWidth = 1;
    private int pageCount = 1;
    private boolean flingHandled;

    public PagedRowScrollView(Context context) {
        super(context);
    }

    public void setPaging(int pageWidth, int pageCount) {
        this.pageWidth = Math.max(1, pageWidth);
        this.pageCount = Math.max(1, pageCount);
    }

    public int currentPage() {
        return Math.max(0, Math.min(pageCount - 1, Math.round(getScrollX() / (float) pageWidth)));
    }

    private void goToPage(int page) {
        int clamped = Math.max(0, Math.min(pageCount - 1, page));
        smoothScrollTo(clamped * pageWidth, 0);
    }

    @Override
    public void fling(int velocityX) {
        flingHandled = true;
        int page = currentPage();
        if (velocityX > 900) {
            page++;
        } else if (velocityX < -900) {
            page--;
        }
        goToPage(page);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            flingHandled = false;
        }
        boolean handled = super.onTouchEvent(event);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && !flingHandled) {
            goToPage(currentPage());
        }
        return handled;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scrollTo(currentPage() * pageWidth, 0);
    }
}