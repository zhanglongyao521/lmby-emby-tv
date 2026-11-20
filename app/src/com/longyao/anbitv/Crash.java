package com.longyao.anbitv;

import android.content.Context;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 崩溃兜底：后台线程出错只记日志、进程继续活着；主线程出错先落盘再交给系统 */
public class Crash {
    private static volatile boolean installed = false;
    private static File dir;

    public static void install(Context c) {
        if (installed) return;
        installed = true;
        try {
            dir = new File(c.getCacheDir(), "crash");
            if (!dir.exists()) dir.mkdirs();
        } catch (Throwable ignored) { }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                writeLog(t, e);
                boolean main = Looper.getMainLooper() != null
                        && Looper.getMainLooper().getThread() == t;
                if (!main) {
                    // 后台线程（图片、接口、播放器辅助线程）：记录后吞掉，界面继续可用
                    android.util.Log.e("LMBY", "后台线程异常已拦截 " + t.getName(), e);
                    return;
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    private static void writeLog(Thread t, Throwable e) {
        if (dir == null) return;
        FileOutputStream out = null;
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            File f = new File(dir, "crash_" + ts + "_" + Thread.currentThread().getId() + ".txt");
            out = new FileOutputStream(f, true);
            out.write((ts + "  thread=" + t.getName() + "\r\n\r\n" + sw.toString()).getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try { if (out != null) out.close(); } catch (Throwable ignored) { }
        }
        trimLogs();
    }

    /** 只留最近 5 份，别把设备存储写满 */
    private static void trimLogs() {
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length <= 5) return;
            java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                @Override public int compare(File a, File b) {
                    long x = a.lastModified(), y = b.lastModified();
                    return x < y ? -1 : (x > y ? 1 : 0);
                }
            });
            for (int i = 0; i < fs.length - 5; i++) fs[i].delete();
        } catch (Throwable ignored) { }
    }

    /** 崩溃日志目录，排查时直接把里面的文件导出来 */
    public static File dir() { return dir; }
}
