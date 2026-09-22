package com.cardhome.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 卡片视图，自下而上：
 * 1 背景模糊层：用系统背景模糊（Android 12+）把卡片背后的真实内容实时糊掉。卡片本身是半透明的，
 *   透过它看到的就是壁纸，所以这一层就是分层结构里“对背景的模糊”，也是第三层要的效果；
 * 2 合成位图层：底色层、深色模式层、强模糊层、图标层、渐变模糊层（由 IconProcessor 合成）；
 * 3 文字层：TextView，保持清晰。
 */
public class AppCardView extends FrameLayout {

    private final View blurView;
    private final ImageView artView;
    private final TextView labelView;

    public AppCardView(Context context) {
        super(context);
        blurView = new View(context);
        addView(blurView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        artView = new ImageView(context);
        artView.setScaleType(ImageView.ScaleType.FIT_XY);
        addView(artView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        labelView = new TextView(context);
        labelView.setMaxLines(2);
        labelView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labelView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        labelView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.gravity = Gravity.CENTER_VERTICAL;
        addView(labelView, labelLp);

        setClipToOutline(true);
    }

    void bind(Bitmap art, int tint, boolean dark, int corner, int cardW, int cardH,
              String label, int textColor, float textSizePx) {
        // 背景模糊层：轮廓略小于卡片，避免模糊区域溢出圆角
        GradientDrawable blurBg = new GradientDrawable();
        blurBg.setShape(GradientDrawable.RECTANGLE);
        blurBg.setCornerRadius(corner);
        blurBg.setColor(0x14FFFFFF);
        blurView.setBackground(blurBg);
        int inset = Math.round(corner * 0.45f);
        FrameLayout.LayoutParams blurLp = (FrameLayout.LayoutParams) blurView.getLayoutParams();
        blurLp.setMargins(inset, inset, inset, inset);
        blurView.setLayoutParams(blurLp);
        if (Build.VERSION.SDK_INT >= 31) {
            // 背景模糊走系统：窗口级模糊在 MainActivity.enableWindowBlur() 里开，
            // 这里再试一下逐视图背景模糊（隐藏 API，能生效就更贴近「只糊卡片背后」）。
            blurView.setBackgroundColor(0x00000000);
            try {
                java.lang.reflect.Method method =
                        View.class.getMethod("setBackgroundBlurRadius", int.class);
                method.invoke(blurView, Math.max(1, Math.round(cardH * 0.35f)));
            } catch (Throwable ignored) {
            }
        }

        // 合成位图里已经含底色层，这里只提供圆角轮廓与按压涟漪；
        // 万一合成失败（art 为 null），用底色兜底，避免卡片变成全透明。
        GradientDrawable content = new GradientDrawable();
        content.setShape(GradientDrawable.RECTANGLE);
        content.setCornerRadius(corner);
        content.setColor(art == null ? ((tint & 0x00FFFFFF) | 0x66000000) : 0x00000000);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(corner);
        mask.setColor(0xFFFFFFFF);
        setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x33000000), content, mask));

        artView.setImageBitmap(art);

        FrameLayout.LayoutParams labelLp = (FrameLayout.LayoutParams) labelView.getLayoutParams();
        labelLp.leftMargin = Math.round(cardW * 0.5f);
        labelLp.rightMargin = Math.round(cardH * 0.10f);
        labelView.setLayoutParams(labelLp);
        labelView.setText(label);
        labelView.setTextColor(textColor);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx);
        labelView.setShadowLayer(cardH * 0.03f, 0f, cardH * 0.012f,
                dark ? 0x66000000 : 0x40FFFFFF);
    }

    /** 拖动排序时把卡片抬起来 */
    void setLifted(boolean lifted) {
        animate().scaleX(lifted ? 1.06f : 1f)
                .scaleY(lifted ? 1.06f : 1f)
                .alpha(lifted ? 0.92f : 1f)
                .setDuration(140)
                .start();
    }
}