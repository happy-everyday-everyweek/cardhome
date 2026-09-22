package com.cardhome.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.CornerFamily;
import com.google.android.material.shape.MaterialShapeDrawable;

/**
 * 桌面长按弹出的菜单。
 *
 * Material 库没有公开的 MD3 菜单组件，所以容器用 Material 库的 MaterialShapeDrawable（4dp 圆角、
 * 2dp 阴影、colorSurfaceContainer 填充），菜单项 48dp 高、label-large 文字、12% 状态层涟漪，
 * 颜色全部取自主题的 MD3 颜色角色。弹窗的 Context 会按桌面自己的深浅色设置换主题，
 * 保证菜单配色和桌面一致。
 */
final class Md3Menu {

    interface OnItemClick {
        void onItemClick(int index);
    }

    private Md3Menu() {
    }

    static void show(View parent, int screenX, int screenY, String[] items, boolean dark,
                     OnItemClick listener) {
        Context themed = new ContextThemeWrapper(parent.getContext(),
                dark ? R.style.AppTheme_Dark : R.style.AppTheme_Light);
        int containerColor = MaterialColors.getColor(themed,
                com.google.android.material.R.attr.colorSurfaceContainer, 0xFF211F26);
        int onSurface = MaterialColors.getColor(themed,
                com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);

        MaterialShapeDrawable background = MaterialShapeDrawable.createWithElevationOverlay(themed);
        background.setShapeAppearanceModel(background.getShapeAppearanceModel().toBuilder()
                .setAllCorners(CornerFamily.ROUNDED, dp(themed, 4))
                .build());
        background.setFillColor(ColorStateList.valueOf(containerColor));
        background.setElevation(dp(themed, 2));

        LinearLayout list = new LinearLayout(themed);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(themed, 8), 0, dp(themed, 8));
        list.setBackground(background);
        list.setClipToOutline(true);
        list.setMinimumWidth(dp(themed, 160));

        final PopupWindow popup = new PopupWindow(list,
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setBackgroundDrawable(null);
        popup.setOutsideTouchable(true);
        popup.setFocusable(true);
        try {
            popup.setAnimationStyle(R.style.Md3PopupAnimation);
        } catch (Throwable ignored) {
        }

        GradientDrawable rippleMask = new GradientDrawable();
        rippleMask.setShape(GradientDrawable.RECTANGLE);
        rippleMask.setColor(0xFFFFFFFF);

        for (int i = 0; i < items.length; i++) {
            final int index = i;
            TextView item = new TextView(themed);
            item.setText(items[i]);
            item.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            item.setTextColor(onSurface);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(themed, 12), 0, dp(themed, 16), 0);
            item.setMinWidth(dp(themed, 160));
            item.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(onSurface, 31)),
                    null, rippleMask));
            item.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(themed, 48)));
            item.setOnClickListener(v -> {
                popup.dismiss();
                if (listener != null) {
                    listener.onItemClick(index);
                }
            });
            list.addView(item);
        }

        popup.showAtLocation(parent, Gravity.TOP | Gravity.START, screenX, screenY);
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}