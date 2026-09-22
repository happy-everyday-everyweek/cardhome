package com.cardhome.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity {

    static final String PREFS = AppRepo.PREFS;
    private static final String KEY_SHOWN = AppRepo.KEY_SHOWN;
    private static final String KEY_STYLE = AppRepo.KEY_STYLE;
    private static final String KEY_DARK = AppRepo.KEY_DARK;
    private static final String KEY_ORDER = AppRepo.KEY_ORDER;
    private static final String KEY_NAME_PREFIX = AppRepo.KEY_NAME_PREFIX;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile List<AppEntry> allApps = Collections.emptyList();
    private static volatile boolean cacheLoaded = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean loading = false;

    private FrameLayout rootView;
    private LinearLayout clockBlock;
    private FrameLayout rowArea;
    private PagedRowScrollView rowScroll;
    private LinearLayout pagesContainer;
    private TextView clockView;
    private TextView dateView;
    private TextView hintView;

    private String draggingPkg;
    private int lastDropIndex = -1;
    private float lastTouchX = -1f;
    private float lastTouchY = -1f;
    private List<AppEntry> shownApps = Collections.emptyList();

    private boolean safeMode;
    private boolean darkMode;
    private int dynamicColor;
    private int textColor;
    private int edgePad;
    private int cardW;
    private int cardH;
    private int cardGap;
    private int cornerRadius;
    private int cardsPerPage = 4;
    private int pageWidth;
    private float textSizePx;

    private final View.OnTouchListener scaleTouch = (v, event) -> {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            v.animate().scaleX(0.96f).scaleY(0.96f)
                    .setDuration(90)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            v.animate().scaleX(1f).scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(new OvershootInterpolator(2.0f))
                    .start();
        }
        return false;
    };

    /** 记录桌面空白处按下的位置，长按菜单就弹在手指那一带 */
    private final View.OnTouchListener backgroundTouch = (v, event) -> {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            lastTouchX = event.getRawX();
            lastTouchY = event.getRawY();
        }
        return false;
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            handler.removeCallbacks(reloadRunnable);
            handler.postDelayed(reloadRunnable, 900);
        }
    };

    private final Runnable reloadRunnable = () -> loadApps(true);

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 30000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            safeMode = CardHomeApp.isSafeMode(this);
            setupWindow();
            buildUi();
            updateClock();
            loadApps(false);
            if (safeMode) {
                CardHomeApp.clearSafeMode(this);
            }
        } catch (Throwable throwable) {
            showCrashScreen(throwable);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateClock();
        if (rowScroll != null) {
            rowScroll.smoothScrollTo(0, 0);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_TICK);
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, timeFilter);

        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pkgFilter.addDataScheme("package");
        registerReceiver(packageReceiver, pkgFilter);

        handler.postDelayed(tickRunnable, 30000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(timeReceiver);
        unregisterReceiver(packageReceiver);
        handler.removeCallbacks(tickRunnable);
        handler.removeCallbacks(reloadRunnable);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        computeColors();
        loadApps(false);
    }

    @Override
    public void onBackPressed() {
        // 桌面：返回键不退出
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 横竖屏切换：卡片尺寸、每页容量、页面方向都要重算，直接按新方向重建界面
        try {
            computeColors();
            buildUi();
            scheduleRender();
            updateClock();
        } catch (Throwable ignored) {
        }
    }

    /**
     * 手柄 / 扫描笔当键盘用时，按 A、B、C、D 直接打开当前页第 1~4 张卡片。
     * 只吃按键，不新增任何界面元素、不做任何视觉反馈，也不改布局。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            int slot = shortcutSlot(event.getKeyCode());
            if (slot >= 0 && launchSlot(slot)) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** A/B/C/D 对应当前页第 1~4 张卡片，其它按键返回 -1 */
    private int shortcutSlot(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_A) {
            return 0;
        }
        if (keyCode == KeyEvent.KEYCODE_B) {
            return 1;
        }
        if (keyCode == KeyEvent.KEYCODE_C) {
            return 2;
        }
        if (keyCode == KeyEvent.KEYCODE_D) {
            return 3;
        }
        return -1;
    }

    private boolean launchSlot(int slot) {
        List<AppEntry> apps = shownApps;
        if (apps == null || apps.isEmpty() || rowScroll == null) {
            return false;
        }
        int index = rowScroll.currentPage() * cardsPerPage + slot;
        if (index < 0 || index >= apps.size()) {
            return false;
        }
        launchApp(apps.get(index));
        return true;
    }

    // ------------------------------------------------------------------ 窗口

    private void setupWindow() {
        try {
            getWindow().getDecorView();
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            enableWindowBlur();
            if (Build.VERSION.SDK_INT >= 30) {
                getWindow().setDecorFitsSystemWindows(false);
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 恢复成上一版可用的窗口级背景模糊（Android 12+）：系统对窗口背后的真实内容做实时模糊。
     * 卡片本身是半透明的，透过它看到的就是壁纸，所以背景模糊交给系统做，不自己读壁纸铺层。
     */
    private void enableWindowBlur() {
        if (Build.VERSION.SDK_INT < 31) {
            return;
        }
        int radius = Math.max(1, dp(20));
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            lp.setBlurBehindRadius(radius);
            getWindow().setAttributes(lp);
        } catch (Throwable ignored) {
        }
        try {
            getWindow().setBackgroundBlurRadius(radius);
        } catch (Throwable ignored) {
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 界面

    private void buildUi() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int screenW = dm.widthPixels;
        int screenH = dm.heightPixels;
        float heightDp = screenH / density;

        edgePad = dp(Math.max(18f, Math.min(30f, heightDp * 0.055f)));
        cardGap = dp(14);
        computeCardSize(screenW, screenH);
        pageWidth = screenW;
        cornerRadius = dp(Math.max(16f, Math.min(26f, cardH / density * 0.22f)));
        textSizePx = Math.max(dp(14f), Math.min(dp(24f), cardH * 0.20f));
        computeColors();

        int clockSp = Math.max(30, Math.min(56, Math.round(heightDp * 0.125f)));
        int dateSp = Math.max(13, Math.min(17, Math.round(heightDp * 0.040f)));

        rootView = new FrameLayout(this);
        setContentView(rootView);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4D000000, 0x14000000, 0x00000000}));
        rootView.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(dm.heightPixels * 0.55f)));

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        rootView.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        clockBlock = new LinearLayout(this);
        clockBlock.setOrientation(LinearLayout.VERTICAL);
        clockBlock.setGravity(Gravity.START);
        clockBlock.setPadding(edgePad, dp(14), edgePad, dp(10));

        clockView = new TextView(this);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, clockSp);
        clockView.setTextColor(Color.WHITE);
        clockView.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        clockView.setLetterSpacing(0.03f);
        clockView.setIncludeFontPadding(false);
        clockView.setShadowLayer(dp(8), 0, dp(2), 0x66000000);
        clockBlock.addView(clockView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dateView = new TextView(this);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, dateSp);
        dateView.setTextColor(0xE6FFFFFF);
        dateView.setShadowLayer(dp(6), 0, dp(1), 0x59000000);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateLp.topMargin = dp(8);
        clockBlock.addView(dateView, dateLp);

        content.addView(clockBlock, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        content.addView(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rowArea = new FrameLayout(this);
        LinearLayout.LayoutParams rowAreaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowAreaLp.bottomMargin = edgePad;
        content.addView(rowArea, rowAreaLp);

        rowScroll = new PagedRowScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rowArea.addView(rowScroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        pagesContainer = new LinearLayout(this);
        pagesContainer.setOrientation(LinearLayout.HORIZONTAL);
        rowScroll.addView(pagesContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        hintView = new TextView(this);
        hintView.setTextColor(0xCCFFFFFF);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        hintView.setShadowLayer(dp(6), 0, dp(1), 0x59000000);
        hintView.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.gravity = Gravity.CENTER;
        rowArea.addView(hintView, hintLp);

        View.OnLongClickListener desktopMenu = v -> {
            showDesktopMenu();
            return true;
        };
        rootView.setOnLongClickListener(desktopMenu);
        clockBlock.setOnLongClickListener(desktopMenu);
        rowArea.setOnLongClickListener(desktopMenu);
        pagesContainer.setOnLongClickListener(desktopMenu);
        rootView.setOnTouchListener(backgroundTouch);
        clockBlock.setOnTouchListener(backgroundTouch);
        rowArea.setOnTouchListener(backgroundTouch);
        pagesContainer.setOnTouchListener(backgroundTouch);

        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
                DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    left = Math.max(left, cutout.getSafeInsetLeft());
                    top = Math.max(top, cutout.getSafeInsetTop());
                    right = Math.max(right, cutout.getSafeInsetRight());
                    bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                }
            }
            content.setPadding(left, top, right, bottom);
            if (rowArea != null && rowArea.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rowArea.getLayoutParams();
                lp.bottomMargin = Math.max(dp(6), edgePad - bottom);
                rowArea.setLayoutParams(lp);
            }
            return insets;
        });
        rootView.requestApplyInsets();
    }

    /** 每页卡片数按屏幕宽度推算，保证卡片宽度落在 [120dp, 220dp] 且左右不裁切 */
    private void computeCardSize(int screenW, int screenH) {
        int minW = dp(120);
        int maxW = dp(220);
        int n = Math.max(1, (int) Math.ceil(
                (screenW - 2 * edgePad + cardGap) / (double) (maxW + cardGap)));
        int w = (screenW - 2 * edgePad - (n - 1) * cardGap) / n;
        while (w < minW && n > 1) {
            n--;
            w = (screenW - 2 * edgePad - (n - 1) * cardGap) / n;
        }
        cardsPerPage = n;
        cardW = w;
        int h = Math.round(w * 0.62f);
        h = Math.max(dp(72), h);
        h = Math.min(h, Math.round(screenH * 0.42f));
        cardH = h;
    }

    /** 卡片在页面里的布局参数 */
    private LinearLayout.LayoutParams cardLayoutParams(int indexInPage, int countInPage) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
        if (indexInPage < countInPage - 1) {
            lp.rightMargin = cardGap;
        }
        return lp;
    }

    private void updateClock() {
        if (clockView == null || dateView == null) {
            return;
        }
        Date now = new Date();
        boolean is24Hour = DateFormat.is24HourFormat(this);
        SimpleDateFormat timeFormat = new SimpleDateFormat(
                is24Hour ? "HH:mm" : "h:mm", Locale.getDefault());
        clockView.setText(timeFormat.format(now));
        String dateText = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now);
        dateView.setText(dateText + " · " + greeting());
    }

    private String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 6) {
            return "夜深了";
        }
        if (hour < 9) {
            return "早上好";
        }
        if (hour < 12) {
            return "上午好";
        }
        if (hour < 14) {
            return "中午好";
        }
        if (hour < 18) {
            return "下午好";
        }
        return "晚上好";
    }

    // ------------------------------------------------------------------ 配色

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int styleMode() {
        return prefs().getInt(KEY_STYLE, 0);
    }

    private boolean resolveDarkMode() {
        int mode = prefs().getInt(KEY_DARK, 0);
        if (mode == 1) {
            return true;
        }
        if (mode == 2) {
            return false;
        }
        int night = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    private void computeColors() {
        darkMode = resolveDarkMode();
        dynamicColor = safeMode ? 0xFFC9CFFF : loadDynamicColor();
        textColor = darkMode ? 0xFFFFFFFF : 0xE6101218;
    }

    private int loadDynamicColor() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return getResources().getColor(android.R.color.system_accent1_200, getTheme());
            } catch (Throwable ignored) {
            }
        }
        return 0xFFC9CFFF;
    }

    private int tintFor(AppEntry entry) {
        if (styleMode() == 1 && entry.icon != null) {
            try {
                return IconProcessor.dominantColor(entry.packageName, entry.icon);
            } catch (Throwable ignored) {
            }
        }
        return dynamicColor;
    }

    /**
     * 是否对图标做灰度重映射 + 重新着色。
     * 浅色模式下的「图标取色」风格保留图标原样，其余情况都按主题色或图标主色重新着色。
     */
    private boolean shouldRecolor() {
        return darkMode || styleMode() == 0;
    }

    // ------------------------------------------------------------------ 应用列表

    private void loadApps(boolean force) {
        if (cacheLoaded && !force) {
            scheduleRender();
            return;
        }
        if (loading) {
            return;
        }
        loading = true;
        final Context appContext = getApplicationContext();
        EXECUTOR.execute(() -> {
            ArrayList<AppEntry> result = new ArrayList<>();
            try {
                PackageManager pm = appContext.getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> infos = pm.queryIntentActivities(mainIntent, 0);
                String selfPkg = appContext.getPackageName();
                for (ResolveInfo info : infos) {
                    if (info.activityInfo == null) {
                        continue;
                    }
                    String pkg = info.activityInfo.packageName;
                    if (selfPkg.equals(pkg)) {
                        continue;
                    }
                    AppEntry entry = new AppEntry();
                    entry.packageName = pkg;
                    entry.activityName = info.activityInfo.name;
                    CharSequence label = info.loadLabel(pm);
                    entry.label = label == null ? pkg : label.toString();
                    entry.icon = info.loadIcon(pm);
                    result.add(entry);
                }
                final Collator collator = Collator.getInstance(Locale.CHINA);
                Collections.sort(result, (a, b) -> {
                    int c = collator.compare(a.label, b.label);
                    return c != 0 ? c : a.packageName.compareTo(b.packageName);
                });
            } catch (Exception ignored) {
            }
            allApps = Collections.unmodifiableList(result);
            cacheLoaded = true;
            loading = false;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                scheduleRender();
            });
        });
    }

    private Set<String> shownSet() {
        return prefs().getStringSet(KEY_SHOWN, null);
    }

    private List<AppEntry> displayedApps() {
        List<AppEntry> source = allApps;
        Set<String> shown = shownSet();
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry entry : source) {
            if (shown != null && !shown.contains(entry.packageName)) {
                continue;
            }
            // 复制一份并把自定义名称写进 label，避免污染原始列表
            AppEntry copy = new AppEntry();
            copy.packageName = entry.packageName;
            copy.activityName = entry.activityName;
            copy.icon = entry.icon;
            String custom = prefs().getString(KEY_NAME_PREFIX + entry.packageName, null);
            copy.label = TextUtils.isEmpty(custom) ? entry.label : custom;
            out.add(copy);
        }
        applySavedOrder(out);
        return out;
    }

    /** 先在后台线程把各卡片的图层位图算好（缓存命中时几乎零成本），再回主线程重建页面，避免翻页掉帧 */
    private void scheduleRender() {
        if (pagesContainer == null) {
            return;
        }
        final List<AppEntry> displayed = displayedApps();
        final boolean dark = resolveDarkMode();
        final int style = styleMode();
        final boolean recolor = dark || style == 0;
        final int dynamic = safeMode ? 0xFFC9CFFF : loadDynamicColor();
        final int cw = cardW;
        final int ch = cardH;
        EXECUTOR.execute(() -> {
            for (AppEntry entry : displayed) {
                try {
                    int tint = (style == 1 && entry.icon != null)
                            ? IconProcessor.dominantColor(entry.packageName, entry.icon)
                            : dynamic;
                    IconProcessor.composeCard(entry.packageName + "|" + entry.activityName,
                            entry.icon, tint, dark, recolor, cw, ch);
                } catch (Throwable ignored) {
                }
            }
            handler.post(() -> {
                if (isFinishing() || isDestroyed() || pagesContainer == null) {
                    return;
                }
                darkMode = dark;
                dynamicColor = dynamic;
                textColor = dark ? 0xFFFFFFFF : 0xE6101218;
                renderPages();
            });
        });
    }

    private void renderPages() {
        if (pagesContainer == null) {
            return;
        }
        pagesContainer.removeAllViews();
        List<AppEntry> displayed = displayedApps();
        shownApps = displayed;
        if (displayed.isEmpty()) {
            hintView.setText(allApps.isEmpty() ? "未找到可启动的应用" : "还没有选择要显示的应用，长按桌面空白处 → 桌面设置");
            hintView.setVisibility(View.VISIBLE);
            rowScroll.setPaging(pageWidth, 1);
            return;
        }
        hintView.setVisibility(View.GONE);
        int total = displayed.size();
        int pages = (total + cardsPerPage - 1) / cardsPerPage;
        for (int page = 0; page < pages; page++) {
            int from = page * cardsPerPage;
            int to = Math.min(total, from + cardsPerPage);
            LinearLayout pageView = buildPageView();
            int count = to - from;
            for (int i = from; i < to; i++) {
                AppCardView card = createCard(displayed.get(i));
                card.setLayoutParams(cardLayoutParams(i - from, count));
                pageView.addView(card);
            }
            pagesContainer.addView(pageView);
        }
        rowScroll.setPaging(pageWidth, pages);
    }

    private AppCardView createCard(AppEntry entry) {
        AppCardView card = new AppCardView(this);
        int tint = tintFor(entry);
        Bitmap art = IconProcessor.composeCard(entry.packageName + "|" + entry.activityName,
                entry.icon, tint, darkMode, shouldRecolor(), cardW, cardH);
        card.bind(art, tint, darkMode, cornerRadius, cardW, cardH, entry.label, textColor, textSizePx);
        card.setTag(entry.packageName);
        card.setOnClickListener(v -> launchApp(entry));
        card.setOnLongClickListener(v -> startCardDrag(card, entry));
        card.setOnTouchListener(scaleTouch);
        return card;
    }

    private void launchApp(AppEntry entry) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.setComponent(new ComponentName(entry.packageName, entry.activityName));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(intent);
        } catch (Exception e) {
            toast("启动失败：" + entry.label);
            loadApps(true);
        }
    }

    // ------------------------------------------------------------------ 桌面菜单

    /** 桌面空白处长按：弹出 MD3 菜单，里面有「桌面设置」入口，点进去打开独立的设置页 */
    private void showDesktopMenu() {
        if (rootView == null) {
            return;
        }
        int[] loc = new int[2];
        rootView.getLocationOnScreen(loc);
        float tx = lastTouchX;
        float ty = lastTouchY;
        if (tx < 0 || ty < 0) {
            // 没拿到按下的坐标（触摸被上层吃掉）时，退回屏幕左上偏下一点
            tx = loc[0] + dp(48);
            ty = loc[1] + dp(96);
        }
        Md3Menu.show(rootView, Math.round(tx), Math.round(ty),
                new String[]{"桌面设置", "刷新应用列表"}, darkMode, index -> {
                    if (index == 0) {
                        try {
                            startActivity(new Intent(this, SettingsActivity.class));
                        } catch (Throwable throwable) {
                            toast("打不开设置页：" + throwable);
                        }
                    } else {
                        loadApps(true);
                        toast("正在刷新应用列表");
                    }
                });
    }

    // ------------------------------------------------------------------ 桌面拖拽排序

    /** 长按卡片：抬起并开始拖拽，松手后把新顺序写盘 */
    private boolean startCardDrag(AppCardView card, AppEntry entry) {
        if (draggingPkg != null) {
            return true;
        }
        draggingPkg = entry.packageName;
        lastDropIndex = -1;
        card.setLifted(true);
        ClipData data = ClipData.newPlainText("pkg", entry.packageName);
        card.startDragAndDrop(data, new View.DragShadowBuilder(card), entry.packageName, 0);
        return true;
    }

    /** 拖到某一页的某个位置：把被拖的卡片挪过去，再按每页容量重排 */
    private final View.OnDragListener pageDragListener = (v, event) -> {
        if (draggingPkg == null) {
            return false;
        }
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_ENTERED:
            case DragEvent.ACTION_DRAG_LOCATION:
                handleDragMove(v, event.getX());
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                finishCardDrag();
                return true;
            default:
                return true;
        }
    };

    private void handleDragMove(View pageView, float x) {
        if (pagesContainer == null) {
            return;
        }
        View dragged = findCardView(draggingPkg);
        if (dragged == null) {
            return;
        }
        int page = pagesContainer.indexOfChild(pageView);
        if (page < 0) {
            return;
        }
        int slot = (int) ((x - edgePad) / Math.max(1, cardW + cardGap));
        slot = Math.max(0, Math.min(slot, Math.max(0, cardsPerPage - 1)));
        int target = page * cardsPerPage + slot;
        if (target == lastDropIndex) {
            return;
        }
        List<View> cards = collectCards();
        int from = cards.indexOf(dragged);
        if (from < 0) {
            return;
        }
        cards.remove(from);
        int insert = Math.max(0, Math.min(target, cards.size()));
        cards.add(insert, dragged);
        lastDropIndex = insert;
        layoutCards(cards);
    }

    private void finishCardDrag() {
        if (draggingPkg == null) {
            return;
        }
        String pkg = draggingPkg;
        draggingPkg = null;
        lastDropIndex = -1;
        View dragged = findCardView(pkg);
        if (dragged instanceof AppCardView) {
            ((AppCardView) dragged).setLifted(false);
        }
        saveOrderFromViews();
    }

    /** 把现有卡片按每页容量重新分页，位置由传入的顺序决定 */
    private void layoutCards(List<View> cards) {
        if (pagesContainer == null) {
            return;
        }
        for (int i = 0; i < pagesContainer.getChildCount(); i++) {
            ViewGroup page = (ViewGroup) pagesContainer.getChildAt(i);
            while (page.getChildCount() > 0) {
                page.removeViewAt(0);
            }
        }
        int pages = Math.max(1, (cards.size() + cardsPerPage - 1) / cardsPerPage);
        while (pagesContainer.getChildCount() > pages) {
            pagesContainer.removeViewAt(pagesContainer.getChildCount() - 1);
        }
        while (pagesContainer.getChildCount() < pages) {
            pagesContainer.addView(buildPageView());
        }
        for (int p = 0; p < pages; p++) {
            LinearLayout page = (LinearLayout) pagesContainer.getChildAt(p);
            page.setOnDragListener(pageDragListener);
            int from = p * cardsPerPage;
            int to = Math.min(cards.size(), from + cardsPerPage);
            for (int i = from; i < to; i++) {
                View card = cards.get(i);
                card.setLayoutParams(cardLayoutParams(i - from, to - from));
                if (card.getParent() != null) {
                    ((ViewGroup) card.getParent()).removeView(card);
                }
                page.addView(card);
            }
        }
        if (rowScroll != null) {
            rowScroll.setPaging(pageWidth, pages);
        }
    }

    private LinearLayout buildPageView() {
        LinearLayout pageView = new LinearLayout(this);
        pageView.setOrientation(LinearLayout.HORIZONTAL);
        pageView.setGravity(Gravity.START);
        pageView.setPadding(edgePad, 0, edgePad, 0);
        pageView.setLayoutParams(new LinearLayout.LayoutParams(
                pageWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        pageView.setOnDragListener(pageDragListener);
        return pageView;
    }

    private List<View> collectCards() {
        List<View> cards = new ArrayList<>();
        if (pagesContainer == null) {
            return cards;
        }
        for (int i = 0; i < pagesContainer.getChildCount(); i++) {
            ViewGroup page = (ViewGroup) pagesContainer.getChildAt(i);
            for (int j = 0; j < page.getChildCount(); j++) {
                cards.add(page.getChildAt(j));
            }
        }
        return cards;
    }

    private View findCardView(String packageName) {
        for (View card : collectCards()) {
            if (packageName.equals(card.getTag())) {
                return card;
            }
        }
        return null;
    }

    /** 拖完把页面上的顺序存下来 */
    private void saveOrderFromViews() {
        List<String> order = new ArrayList<>();
        for (View card : collectCards()) {
            Object tag = card.getTag();
            if (tag instanceof String) {
                order.add((String) tag);
            }
        }
        if (!order.isEmpty()) {
            AppRepo.saveOrder(this, order);
        }
    }

    /** 按保存的顺序重排；没排进顺序表的应用保持原有相对顺序，接在后面 */
    private void applySavedOrder(List<AppEntry> list) {
        AppRepo.applySavedOrder(list, AppRepo.orderList(this));
    }
    // ------------------------------------------------------------------ 崩溃兜底

    private void showCrashScreen(Throwable throwable) {
        String report;
        try {
            report = CrashUtil.buildReport(this, throwable);
            CrashUtil.save(this, report);
        } catch (Throwable inner) {
            report = "生成崩溃报告失败：" + inner + "\n原始异常：\n" + throwable;
        }
        try {
            TextView text = new TextView(this);
            text.setText("启动失败，请把这一页截图发给助手\n\n" + report);
            text.setTextColor(0xFFFFFFFF);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            text.setPadding(dp(16), dp(16), dp(16), dp(16));
            ScrollView scrollView = new ScrollView(this);
            scrollView.setBackgroundColor(0xFF000000);
            scrollView.addView(text);
            setContentView(scrollView);
        } catch (Throwable ignored) {
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}