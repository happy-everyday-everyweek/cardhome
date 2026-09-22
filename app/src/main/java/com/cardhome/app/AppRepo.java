package com.cardhome.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 应用列表与设置的读写。桌面（MainActivity）与设置页（SettingsActivity）共用同一份键名与排序规则，
 * 避免两边的顺序/名称规则出现分歧。
 */
final class AppRepo {

    static final String PREFS = "cardhome";
    static final String KEY_SHOWN = "shown_set";
    static final String KEY_STYLE = "card_style";
    static final String KEY_DARK = "dark_mode";
    static final String KEY_ORDER = "order_list";
    static final String KEY_NAME_PREFIX = "name:";

    private AppRepo() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 读取所有可启动的应用，排除桌面自身，按中文名称排序。可在后台线程调用。 */
    static List<AppEntry> queryApps(Context context) {
        ArrayList<AppEntry> result = new ArrayList<>();
        try {
            PackageManager pm = context.getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> infos = pm.queryIntentActivities(mainIntent, 0);
            String selfPkg = context.getPackageName();
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
        return result;
    }

    static int styleMode(Context context) {
        return prefs(context).getInt(KEY_STYLE, 0);
    }

    static int darkModeIndex(Context context) {
        return prefs(context).getInt(KEY_DARK, 0);
    }

    static boolean resolveDark(Context context) {
        int mode = prefs(context).getInt(KEY_DARK, 0);
        if (mode == 1) {
            return true;
        }
        if (mode == 2) {
            return false;
        }
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return night == Configuration.UI_MODE_NIGHT_YES;
    }

    /** 自定义名称，没有设置时返回 null */
    static String customName(Context context, String packageName) {
        return prefs(context).getString(KEY_NAME_PREFIX + packageName, null);
    }

    static String displayName(Context context, AppEntry entry) {
        String custom = customName(context, entry.packageName);
        return TextUtils.isEmpty(custom) ? entry.label : custom;
    }

    /** 已保存的显示顺序（包名列表），未保存时返回空列表 */
    static List<String> orderList(Context context) {
        String raw = prefs(context).getString(KEY_ORDER, null);
        if (TextUtils.isEmpty(raw)) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isEmpty()) {
                out.add(part);
            }
        }
        return out;
    }

    static void saveOrder(Context context, List<String> packages) {
        StringBuilder order = new StringBuilder();
        for (String pkg : packages) {
            if (order.length() > 0) {
                order.append(',');
            }
            order.append(pkg);
        }
        prefs(context).edit().putString(KEY_ORDER, order.toString()).apply();
    }

    /** 按保存的顺序重排；没排进顺序表的应用保持原有相对顺序，接在后面 */
    static void applySavedOrder(List<AppEntry> list, List<String> order) {
        if (order.isEmpty() || list.isEmpty()) {
            return;
        }
        final Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            index.put(order.get(i), i);
        }
        final Map<String, Integer> base = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            base.put(list.get(i).packageName, i);
        }
        Collections.sort(list, (a, b) -> {
            Integer ia = index.get(a.packageName);
            Integer ib = index.get(b.packageName);
            if (ia != null && ib != null) {
                return ia - ib;
            }
            if (ia != null) {
                return -1;
            }
            if (ib != null) {
                return 1;
            }
            Integer ba = base.get(a.packageName);
            Integer bb = base.get(b.packageName);
            return (ba == null ? 0 : ba) - (bb == null ? 0 : bb);
        });
    }
}