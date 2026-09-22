package com.cardhome.app;

import android.content.ClipData;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面设置：独立页面，用 Material 库的 Material Design 3 组件搭的。
 *
 * 主题直接用 Theme.Material3.DynamicColors.DayNight（Android 12+ 跟随系统动态色），
 * 顶部是 MaterialToolbar，设置项装在 MaterialCardView 里，分段选择用 MaterialButtonToggleGroup，
 * 应用列表是 MaterialCheckBox + MaterialButton 的列表项，改名用 MaterialAlertDialogBuilder +
 * TextInputLayout（filled）。
 *
 * 深浅色模式改动会让本页按新主题重建，重建前把未保存的草稿（顺序 / 显示状态 / 自定义名称）
 * 存进 onSaveInstanceState，重建后恢复，避免丢编辑。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final String STATE_ORDER = "draft_order";
    private static final String STATE_SHOWN = "draft_shown";
    private static final String STATE_NAME_KEYS = "draft_name_keys";
    private static final String STATE_NAME_VALUES = "draft_name_values";

    private List<AppEntry> order = new ArrayList<>();
    private final Set<String> shown = new HashSet<>();
    private final Map<String, String> names = new HashMap<>();

    private LinearLayout rowsContainer;
    private MaterialButtonToggleGroup styleGroup;
    private MaterialButtonToggleGroup darkGroup;
    private String draggingPkg;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        applyLocalNightMode();
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        rowsContainer = findViewById(R.id.settingsRows);
        styleGroup = findViewById(R.id.styleGroup);
        darkGroup = findViewById(R.id.darkGroup);

        loadDrafts(savedInstanceState);

        styleGroup.check(styleMode() == 1 ? R.id.styleIcon : R.id.styleDynamic);
        darkGroup.check(darkModeIndex() == 1 ? R.id.darkDark
                : darkModeIndex() == 2 ? R.id.darkLight : R.id.darkSystem);

        styleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            prefs().edit().putInt(AppRepo.KEY_STYLE, checkedId == R.id.styleIcon ? 1 : 0).apply();
        });
        darkGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }
            int mode = checkedId == R.id.darkDark ? 1 : checkedId == R.id.darkLight ? 2 : 0;
            prefs().edit().putInt(AppRepo.KEY_DARK, mode).apply();
            // 让整页立刻换成新的深浅色（草稿会在 onSaveInstanceState 里带过去）
            recreate();
        });

        findViewById(R.id.buttonSelectAll).setOnClickListener(v -> setAllShown(true));
        findViewById(R.id.buttonSelectNone).setOnClickListener(v -> setAllShown(false));
        findViewById(R.id.buttonRefresh).setOnClickListener(v -> reloadApps());
        findViewById(R.id.buttonSave).setOnClickListener(v -> save());

        rowsContainer.setOnDragListener((v, event) -> {
            if (draggingPkg == null) {
                return false;
            }
            int action = event.getAction();
            if (action == DragEvent.ACTION_DRAG_LOCATION || action == DragEvent.ACTION_DRAG_ENTERED) {
                int step = dp(56);
                moveDraggedTo((int) (event.getY() / Math.max(1, step)));
                return true;
            }
            if (action == DragEvent.ACTION_DRAG_ENDED) {
                draggingPkg = null;
                return true;
            }
            return true;
        });

        rebuildRows();
    }

    /** 用当前保存的深浅色设置，决定这一页用浅色还是深色主题 */
    private void applyLocalNightMode() {
        int mode = AppRepo.darkModeIndex(this);
        int night = mode == 1 ? AppCompatDelegate.MODE_NIGHT_YES
                : mode == 2 ? AppCompatDelegate.MODE_NIGHT_NO
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        getDelegate().setLocalNightMode(night);
    }

    private SharedPreferences prefs() {
        return AppRepo.prefs(this);
    }

    private int styleMode() {
        return AppRepo.styleMode(this);
    }

    private int darkModeIndex() {
        return AppRepo.darkModeIndex(this);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 草稿

    private void loadDrafts(Bundle savedInstanceState) {
        List<AppEntry> apps = new ArrayList<>(AppRepo.queryApps(this));
        AppRepo.applySavedOrder(apps, AppRepo.orderList(this));
        Map<String, AppEntry> byPackage = new HashMap<>();
        for (AppEntry entry : apps) {
            byPackage.put(entry.packageName, entry);
        }

        Set<String> savedShown = prefs().getStringSet(AppRepo.KEY_SHOWN, null);
        for (AppEntry entry : apps) {
            if (savedShown == null || savedShown.contains(entry.packageName)) {
                shown.add(entry.packageName);
            }
            String custom = AppRepo.customName(this, entry.packageName);
            if (!TextUtils.isEmpty(custom)) {
                names.put(entry.packageName, custom);
            }
        }

        if (savedInstanceState == null) {
            order = apps;
            return;
        }

        // 从重建前的状态恢复草稿
        ArrayList<String> savedOrder = savedInstanceState.getStringArrayList(STATE_ORDER);
        if (savedOrder != null) {
            order = new ArrayList<>();
            for (String pkg : savedOrder) {
                AppEntry entry = byPackage.get(pkg);
                if (entry != null) {
                    order.add(entry);
                }
            }
            for (AppEntry entry : apps) {
                if (!savedOrder.contains(entry.packageName)) {
                    order.add(entry);
                }
            }
        } else {
            order = apps;
        }
        ArrayList<String> savedShownList = savedInstanceState.getStringArrayList(STATE_SHOWN);
        if (savedShownList != null) {
            shown.clear();
            shown.addAll(savedShownList);
        }
        ArrayList<String> nameKeys = savedInstanceState.getStringArrayList(STATE_NAME_KEYS);
        ArrayList<String> nameValues = savedInstanceState.getStringArrayList(STATE_NAME_VALUES);
        if (nameKeys != null && nameValues != null) {
            names.clear();
            for (int i = 0; i < nameKeys.size() && i < nameValues.size(); i++) {
                names.put(nameKeys.get(i), nameValues.get(i));
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> packageOrder = new ArrayList<>();
        for (AppEntry entry : order) {
            packageOrder.add(entry.packageName);
        }
        outState.putStringArrayList(STATE_ORDER, packageOrder);
        outState.putStringArrayList(STATE_SHOWN, new ArrayList<>(shown));
        outState.putStringArrayList(STATE_NAME_KEYS, new ArrayList<>(names.keySet()));
        outState.putStringArrayList(STATE_NAME_VALUES, new ArrayList<>(names.values()));
    }

    // ------------------------------------------------------------------ 应用列表

    private void rebuildRows() {
        if (rowsContainer == null) {
            return;
        }
        rowsContainer.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (AppEntry entry : order) {
            rowsContainer.addView(buildRow(inflater, entry));
        }
    }

    private View buildRow(LayoutInflater inflater, final AppEntry entry) {
        View row = inflater.inflate(R.layout.item_app_row, rowsContainer, false);
        row.setTag(entry.packageName);

        final MaterialCheckBox check = row.findViewById(R.id.rowCheck);
        check.setChecked(shown.contains(entry.packageName));

        TextView name = row.findViewById(R.id.rowName);
        name.setText(displayName(entry));

        row.findViewById(R.id.rowRename).setOnClickListener(v -> promptRename(entry));

        row.setOnClickListener(v -> {
            boolean next = !shown.contains(entry.packageName);
            if (next) {
                shown.add(entry.packageName);
            } else {
                shown.remove(entry.packageName);
            }
            check.setChecked(next);
        });
        row.setOnLongClickListener(v -> {
            draggingPkg = entry.packageName;
            ClipData data = ClipData.newPlainText("pkg", entry.packageName);
            v.startDragAndDrop(data, new View.DragShadowBuilder(v), entry.packageName, 0);
            return true;
        });
        return row;
    }

    private String displayName(AppEntry entry) {
        String custom = names.get(entry.packageName);
        return TextUtils.isEmpty(custom) ? entry.label : custom;
    }

    private void setAllShown(boolean value) {
        shown.clear();
        if (value) {
            for (AppEntry entry : order) {
                shown.add(entry.packageName);
            }
        }
        rebuildRows();
    }

    private void reloadApps() {
        toast(getString(R.string.refreshing_apps));
        new Thread(() -> {
            final List<AppEntry> apps = new ArrayList<>(AppRepo.queryApps(getApplicationContext()));
            AppRepo.applySavedOrder(apps, AppRepo.orderList(getApplicationContext()));
            runOnUiThread(() -> {
                if (isFinishing()) {
                    return;
                }
                order = apps;
                rebuildRows();
            });
        }).start();
    }

    private void moveDraggedTo(int index) {
        if (draggingPkg == null) {
            return;
        }
        int from = -1;
        for (int i = 0; i < order.size(); i++) {
            if (draggingPkg.equals(order.get(i).packageName)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            return;
        }
        int to = Math.max(0, Math.min(order.size() - 1, index));
        if (to == from) {
            return;
        }
        order.add(to, order.remove(from));
        // 只挪动已有的那一行，避免把正在被拖拽的视图销毁
        View dragged = findRow(draggingPkg);
        if (dragged != null) {
            rowsContainer.removeView(dragged);
            rowsContainer.addView(dragged, Math.min(to, rowsContainer.getChildCount()));
        }
    }

    private View findRow(String packageName) {
        for (int i = 0; i < rowsContainer.getChildCount(); i++) {
            View child = rowsContainer.getChildAt(i);
            if (packageName.equals(child.getTag())) {
                return child;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 改名

    private void promptRename(final AppEntry entry) {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_rename, null, false);
        final TextInputEditText input = content.findViewById(R.id.renameInput);
        input.setText(displayName(entry));
        input.setSelection(input.getText() == null ? 0 : input.getText().length());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.rename_title)
                .setView(content)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    String value = input.getText() == null ? "" : input.getText().toString().trim();
                    if (value.isEmpty()) {
                        names.remove(entry.packageName);
                    } else {
                        names.put(entry.packageName, value);
                    }
                    rebuildRows();
                })
                .setNeutralButton(R.string.action_restore, (dialog, which) -> {
                    names.remove(entry.packageName);
                    rebuildRows();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 保存

    private void save() {
        SharedPreferences.Editor editor = prefs().edit();
        StringBuilder orderText = new StringBuilder();
        for (AppEntry entry : order) {
            if (orderText.length() > 0) {
                orderText.append(',');
            }
            orderText.append(entry.packageName);
        }
        editor.putString(AppRepo.KEY_ORDER, orderText.toString());
        if (shown.size() >= order.size()) {
            editor.remove(AppRepo.KEY_SHOWN);
        } else {
            editor.putStringSet(AppRepo.KEY_SHOWN, new HashSet<>(shown));
        }
        for (Map.Entry<String, String> item : names.entrySet()) {
            editor.putString(AppRepo.KEY_NAME_PREFIX + item.getKey(), item.getValue());
        }
        // 被清掉的自定义名称要删掉，否则改回原名后旧名字仍会生效
        for (AppEntry entry : order) {
            if (!names.containsKey(entry.packageName)) {
                editor.remove(AppRepo.KEY_NAME_PREFIX + entry.packageName);
            }
        }
        editor.apply();
        toast(getString(R.string.settings_saved));
        finish();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}