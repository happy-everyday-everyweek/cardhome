package com.cardhome.app;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class CardHomeApp extends Application {

    private static final String PREFS = "cardhome";
    private static final String KEY_CRASHED = "crashed";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                String report = CrashUtil.buildReport(this, throwable);
                Log.e("CardHome", report);
                CrashUtil.save(this, report);
            } catch (Throwable ignored) {
            }
            try {
                SharedPreferences.Editor editor =
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit();
                editor.putBoolean(KEY_CRASHED, true);
                editor.commit();
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable);
            }
        });
    }

    static boolean isSafeMode(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KEY_CRASHED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void clearSafeMode(Context context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(KEY_CRASHED).commit();
        } catch (Throwable ignored) {
        }
    }
}