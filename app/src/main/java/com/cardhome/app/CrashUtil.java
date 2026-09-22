package com.cardhome.app;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class CrashUtil {

    private CrashUtil() {
    }

    static String buildReport(Context context, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.println("== CardHome 崩溃报告 ==");
        pw.println("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date()));
        try {
            pw.println("package: " + context.getPackageName());
        } catch (Throwable ignored) {
        }
        pw.println("model: " + Build.MANUFACTURER + " " + Build.MODEL);
        pw.println("android: " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")");
        pw.println("device: " + Build.DEVICE + " / " + Build.PRODUCT);
        pw.println("-- exception --");
        throwable.printStackTrace(pw);
        pw.flush();
        return sw.toString();
    }

    static void save(Context context, String report) {
        byte[] data;
        try {
            data = report.getBytes("UTF-8");
        } catch (Throwable t) {
            data = report.getBytes();
        }

        try {
            File dir = context.getExternalFilesDir(null);
            if (dir != null) {
                writeFile(new File(dir, "CardHome-crash.txt"), data);
            }
        } catch (Throwable ignored) {
        }

        try {
            writeFile(new File(context.getFilesDir(), "CardHome-crash.txt"), data);
        } catch (Throwable ignored) {
        }

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "CardHome-crash.txt");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = context.getContentResolver()
                    .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream os = context.getContentResolver().openOutputStream(uri);
                if (os != null) {
                    try {
                        os.write(data);
                    } finally {
                        os.close();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeFile(File file, byte[] data) {
        try {
            FileOutputStream fos = new FileOutputStream(file, false);
            try {
                fos.write(data);
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }
}