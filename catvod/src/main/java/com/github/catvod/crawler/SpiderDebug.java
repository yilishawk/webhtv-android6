package com.github.catvod.crawler;

import android.text.TextUtils;

import com.orhanobut.logger.Logger;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Locale;

public class SpiderDebug {

    private static final String TAG = SpiderDebug.class.getSimpleName();
    private static final ThreadLocal<String> SPIDER = new ThreadLocal<>();

    public static void setSpider(String siteKey) {
        if (TextUtils.isEmpty(siteKey)) SPIDER.remove();
        else SPIDER.set(siteKey);
    }

    public static void clearSpider() {
        SPIDER.remove();
    }

    public static String getSpider() {
        return SPIDER.get();
    }

    private static String withSpider(String msg) {
        String siteKey = SPIDER.get();
        return TextUtils.isEmpty(siteKey) ? msg : "[spider=" + siteKey + "] " + msg;
    }

    public static boolean isEnabled() {
        return DebugLogStore.isEnabled();
    }

    public static void log(Throwable th) {
        log(TAG, th);
    }

    public static void log(String tag, Throwable th) {
        if (th == null) return;
        if (!DebugLogStore.isEnabled()) return;
        StringWriter writer = new StringWriter();
        th.printStackTrace(new PrintWriter(writer));
        String text = writer.toString();
        Logger.t(tag).e(text);
        DebugLogStore.add(tag, withSpider(text));
        // The in-app log viewer's summary/export view keeps only the first line of
        // a multi-line entry, which hides the stack trace. Store a flattened copy
        // so the cause is visible without logcat.
        String flat = flatten(text);
        if (!flat.equals(text)) DebugLogStore.add(tag, withSpider(flat));
    }

    private static final int FLAT_STACK_MAX = 4000;

    private static String flatten(String text) {
        if (text == null || text.indexOf('\n') < 0) return text;
        String flat = text.replace("\r", "").replace("\t", " ").replace("\n", " <- ");
        return flat.length() <= FLAT_STACK_MAX ? flat : flat.substring(0, FLAT_STACK_MAX) + " ...(truncated)";
    }

    public static void log(String msg) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.isEnabled()) return;
        Logger.t(TAG).d(msg);
        DebugLogStore.add(TAG, withSpider(msg));
    }

    public static void log(String tag, String msg, Object... args) {
        if (TextUtils.isEmpty(msg)) return;
        if (!DebugLogStore.isEnabled()) return;
        Logger.t(tag).d(msg, args);
        DebugLogStore.add(tag, withSpider(format(msg, args)));
    }

    private static String format(String msg, Object... args) {
        try {
            return args == null || args.length == 0 ? msg : String.format(Locale.US, msg, args);
        } catch (Throwable e) {
            return msg;
        }
    }
}
