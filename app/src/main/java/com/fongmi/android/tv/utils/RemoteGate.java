package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.update.UpdateHttp;
import com.github.catvod.crawler.SpiderDebug;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 远程开关（kill switch）的 Android 侧：挂载点、线程、状态机、弹窗。
 * 纯逻辑（URL 组装 / 状态文件解析 / 放行-锁定判定）在 {@link RemoteGateStatus}，那边有离线单测。
 *
 * <p>只对赠送版生效 —— 即用 {@code WEBHTV_GIFT_MODE=1} 构建出来的包。赠送版是交给别人用的包：
 * 它没有应用内更新（{@code BuildConfig.ENABLE_UPDATE=false}），并且带着这个开关。
 * 开关状态放在我们自己仓库里的一个小 JSON 文件里，包在启动时、以及每次回到前台且距上次判定
 * 超过 {@link RemoteGateStatus#RECHECK_AFTER_MS} 时各读一次。</p>
 *
 * <p><b>语义是「严格锁」</b>（凯哥 2026-10-07 拍板）：任一来源说 false ⇒ 立刻锁；
 * 没有任何来源说 false 但至少有一个说 true ⇒ 放行；<b>所有来源都拿不到 ⇒ 也锁</b>。
 * 严格锁的代价是用户断网时会被误锁，缓解手段是多来源冗余（默认三个不同 CDN）。</p>
 *
 * <p><b>这不是防篡改机制。</b>能反编译 APK 的人可以把这段判断整个删掉。它拦的是
 * 「把安装包转手给下一个人继续用」，不是技术对抗。</p>
 *
 * <p>本类只在赠送版里被真正执行：{@link #guard(Activity)} 的第一行是常量判断，
 * 普通包里 {@code ENABLE_REMOTE_GATE=false} 是编译期常量，javac 会把整个方法体折成一条
 * {@code return;}，release 的 R8 再把整条调用链摇掉。所以普通包的行为与体积都不受影响。</p>
 */
public final class RemoteGate {

    private static final String TAG = "gift-gate";

    private static final String TITLE = "应用已停用";
    private static final String RETRY = "重试";
    private static final String EXIT = "退出";
    private static final String DEFAULT_MESSAGE = "此应用已被提供者停用，无法继续使用。\n\n如有疑问，请联系向你提供此应用的人。";

    private static final int STATE_UNKNOWN = 0;
    private static final int STATE_CHECKING = 1;
    private static final int STATE_ALLOWED = 2;
    private static final int STATE_LOCKED = 3;

    private static final AtomicInteger state = new AtomicInteger(STATE_UNKNOWN);
    private static final AtomicBoolean checking = new AtomicBoolean();
    private static volatile long allowedAt;
    private static volatile String lockMessage;

    /** 只用于「把弹窗挂到哪个 Activity 上」——弱引用，不持有 Activity。 */
    private static volatile WeakReference<Activity> host;
    private static volatile AlertDialog lockDialog;
    private static volatile WeakReference<Activity> lockOwner;

    private RemoteGate() {
    }

    /**
     * 校验入口。挂在 {@code BaseActivity} 的 {@code onResume} 上 —— 那是全工程唯一一个覆盖了
     * 所有 Activity（含 manifest 里 {@code exported=true} 的 LiveActivity / CastActivity，
     * 它们能被 {@code am start} 直接拉起）的挂载点。
     *
     * <p>普通包第一行就返回，代价是一次常量比较。</p>
     */
    public static void guard(Activity activity) {
        if (!BuildConfig.ENABLE_REMOTE_GATE) return;
        if (activity == null) return;
        host = new WeakReference<>(activity);
        int current = state.get();
        if (current == STATE_LOCKED) {
            showLock();
            return;
        }
        if (current == STATE_CHECKING) return;
        if (current == STATE_ALLOWED && SystemClock.elapsedRealtime() - allowedAt < RemoteGateStatus.RECHECK_AFTER_MS) return;
        startCheck();
    }

    private static void startCheck() {
        if (!checking.compareAndSet(false, true)) return;
        state.set(STATE_CHECKING);
        Task.execute(() -> {
            try {
                check();
            } catch (Throwable e) {
                // 校验代码自己出错也算锁：严格锁的下限是「不能因为我们的 bug 而放行」。
                SpiderDebug.log(TAG, "gate check threw %s", e);
                apply(false, null);
            } finally {
                checking.set(false);
            }
        });
    }

    private static void check() {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + RemoteGateStatus.TOTAL_BUDGET_MS;
        String[] sources = RemoteGateStatus.sources();
        List<String> failed = new ArrayList<>();
        RemoteGateStatus.Aggregator aggregator = new RemoteGateStatus.Aggregator();
        String decidedBy = null;
        for (String source : sources) {
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining < RemoteGateStatus.MIN_ATTEMPT_MS) {
                failed.add(RemoteGateStatus.host(source) + "=skipped");
                break;
            }
            try {
                String body = UpdateHttp.string(RemoteGateStatus.withCacheBucket(source), RemoteGateStatus.headers(), Math.min(remaining, RemoteGateStatus.PER_SOURCE_TIMEOUT_MS));
                RemoteGateStatus.Verdict verdict = RemoteGateStatus.parse(body);
                if (verdict == null) {
                    failed.add(RemoteGateStatus.host(source) + "=malformed");
                    continue;
                }
                if (aggregator.accept(verdict)) {
                    SpiderDebug.log(TAG, "gate verdict=locked by=%s cost=%sms", RemoteGateStatus.host(source), SystemClock.elapsedRealtime() - started);
                    apply(false, verdict.message);
                    return;
                }
                decidedBy = RemoteGateStatus.host(source);
            } catch (Throwable e) {
                failed.add(RemoteGateStatus.host(source) + "=" + e.getClass().getSimpleName());
            }
        }
        boolean allowed = aggregator.allowed();
        SpiderDebug.log(TAG, "gate verdict=%s sources=%d by=%s cost=%sms failed=%s",
                allowed ? "allowed" : "locked", sources.length, decidedBy,
                SystemClock.elapsedRealtime() - started, failed);
        apply(allowed, null);
    }

    private static void apply(boolean allowed, String message) {
        if (allowed) {
            allowedAt = SystemClock.elapsedRealtime();
            state.set(STATE_ALLOWED);
            return;
        }
        lockMessage = message;
        state.set(STATE_LOCKED);
        App.post(RemoteGate::showLock);
    }

    private static void showLock() {
        Activity activity = resolveHost();
        if (activity == null) return;
        if (lockDialog != null && lockDialog.isShowing() && lockOwner != null && lockOwner.get() == activity) return;
        dismissLock();
        lockOwner = new WeakReference<>(activity);
        lockDialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(TITLE)
                .setMessage(TextUtils.isEmpty(lockMessage) ? DEFAULT_MESSAGE : lockMessage)
                .setCancelable(false)
                .setPositiveButton(RETRY, (dialog, which) -> retry())
                .setNegativeButton(EXIT, (dialog, which) -> activity.finish())
                .create();
        lockDialog.setCanceledOnTouchOutside(false);
        lockDialog.show();
    }

    private static void retry() {
        if (state.get() != STATE_LOCKED) return;
        state.set(STATE_UNKNOWN);
        startCheck();
    }

    private static void dismissLock() {
        AlertDialog dialog = lockDialog;
        lockDialog = null;
        lockOwner = null;
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private static Activity resolveHost() {
        WeakReference<Activity> reference = host;
        Activity activity = reference == null ? null : reference.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) activity = App.activity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        return activity;
    }
}
