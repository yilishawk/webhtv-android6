package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.update.UpdateUrl;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 远程开关里**不带 Android 依赖**的那一半：URL 组装、状态文件解析、放行/锁定判定。
 *
 * <p>单独拆出来只有一个理由：这部分是安全关键的（判错了要么把所有赠送版一起锁死、
 * 要么让开关形同虚设），而它本身是纯逻辑。拆开之后可以用 javac + 真 Gson 离线单测
 * （见 {@code apk-check/remote_gate_logic_test.py}），不需要 Gradle、不需要模拟器、不需要真机。
 * Android 胶水（Activity、弹窗、线程、弱引用）留在 {@link RemoteGate} 里。</p>
 *
 * <p>⛔ 本类里不许出现任何 {@code android.*} / {@code androidx.*} 的 import ——
 * 一旦出现，离线单测立刻做不了。所以这里也不能用 {@code TextUtils.isEmpty}，
 * 用普通的 null / 空串判断代替。</p>
 */
final class RemoteGateStatus {

    /** 单次请求的超时上限。 */
    static final long PER_SOURCE_TIMEOUT_MS = 7_000L;
    /** 一轮校验的总预算；超了就不再试后面的来源，直接按已有结果判定。 */
    static final long TOTAL_BUDGET_MS = 12_000L;
    /** 剩余预算低于这个值就不再发起新请求（发了也大概率超时）。 */
    static final long MIN_ATTEMPT_MS = 1_500L;
    /** 放行后多久重新校验一次（回到前台时触发）。让「远端关闭」对长期不重启的机器也能生效。 */
    static final long RECHECK_AFTER_MS = 60L * 60L * 1000L;
    /** URL 上带的缓存桶宽度。见 {@link #withCacheBucket(String)}。 */
    static final long CACHE_BUCKET_MS = 5L * 60L * 1000L;

    /**
     * ⭐ 严格锁的开关，本类唯一一处策略旋钮。
     *
     * <p>{@code true}（当前）= 所有来源都拿不到判定就锁。好处是开关不可绕过（拔网线没用）；
     * 坏处是用户断网、或者几个 CDN 同时被墙时会被误锁。</p>
     *
     * <p>若哪天误锁的投诉盖过了绕过的顾虑，把它改成 {@code false} 即可 —— 那时语义变成
     * 「拿不到判定就放行」，开关只在能读到状态文件时生效。</p>
     */
    static final boolean LOCK_WHEN_UNREACHABLE = true;

    private RemoteGateStatus() {
    }

    /** 状态文件的候选 URL，按顺序试。空串/空列表是合法的 —— 那会走「一个来源都没有 ⇒ 锁」。 */
    static String[] sources() {
        String raw = BuildConfig.REMOTE_GATE_URLS;
        if (raw == null || raw.isEmpty()) return new String[0];
        List<String> urls = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            String url = part.trim();
            if (!url.isEmpty() && !urls.contains(url)) urls.add(url);
        }
        return urls.toArray(new String[0]);
    }

    static Map<String, String> headers() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        return headers;
    }

    /**
     * 给 URL 加一个 5 分钟粒度的桶号。
     *
     * <p>为什么需要：jsDelivr 这类 CDN 对 {@code @main} 路径的缓存长达 12 小时，
     * 也就是「远端关闭」最长要 12 小时才传到用户那里。带上一个每 5 分钟变一次的查询参数，
     * 缓存键就变了，CDN 必须回源取新的 —— 关停能立刻生效，同时也不会每次启动都回源。</p>
     *
     * <p>已经带查询参数的 URL 原样返回，不叠加。</p>
     */
    static String withCacheBucket(String url) {
        if (url == null || url.isEmpty() || url.contains("?")) return url;
        return url + "?t=" + (System.currentTimeMillis() / CACHE_BUCKET_MS);
    }

    /** 日志里只记主机名，不记整条 URL。取不到就记 "invalid"，别让日志里出现空字段。 */
    static String host(String url) {
        String host = UpdateUrl.host(url);
        return host == null || host.isEmpty() ? "invalid" : host;
    }

    static final class Verdict {

        final boolean enabled;
        final String message;

        Verdict(boolean enabled, String message) {
            this.enabled = enabled;
            this.message = message;
        }
    }

    /** 解析不了就返回 null —— 调用方把它当「这个来源失败」处理，**不是**当「放行」。 */
    static Verdict parse(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root == null || !root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();
            Boolean enabled = readBoolean(object.get("enabled"));
            if (enabled == null) return null;
            JsonElement message = object.get("message");
            return new Verdict(enabled, message != null && message.isJsonPrimitive() ? message.getAsString() : null);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 容错地读 {@code enabled}：布尔、数字（0/非 0）、字符串 "true"/"false" 都认。
     * 认不出来返回 null —— 会被当成「这个来源失败」，绝不会被当成 true。
     */
    static Boolean readBoolean(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        try {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return primitive.getAsBoolean();
            if (primitive.isNumber()) return primitive.getAsInt() != 0;
            if (primitive.isString()) {
                String text = primitive.getAsString().trim().toLowerCase(Locale.US);
                if ("true".equals(text)) return Boolean.TRUE;
                if ("false".equals(text)) return Boolean.FALSE;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 判定规则，独立成纯函数是为了能被离线单测钉死：
     * 任何一个来源说 false ⇒ 锁（由 {@link Aggregator#accept} 短路，不会走到这里）；
     * 否则有来源说 true ⇒ 放行；一个都没拿到 ⇒ 看 {@link #LOCK_WHEN_UNREACHABLE}。
     */
    static boolean decide(boolean anyEnabled) {
        return anyEnabled || !LOCK_WHEN_UNREACHABLE;
    }

    /**
     * 一轮校验里各来源判定的累积器。
     *
     * <p>做成对象（而不是写在调用方的 for 循环里）是为了让「跨来源的优先级」这条语义能被离线单测
     * 钉死：<b>一个来源说停用，压过其他所有来源说的启用</b>。理由是 jsDelivr 这类 CDN 可能还缓存着
     * 关停前的旧文件（仍然说 enabled），而权威来源已经说停用了 —— 如果让「先读到的 enabled」赢，
     * 开关就会关不掉。这个顺序关系是开关能不能真正关掉的关键，不能靠读代码「看起来对」。</p>
     */
    static final class Aggregator {

        private boolean anyEnabled;

        /**
         * 吃一个来源的判定（{@code null} = 这个来源失败）。
         *
         * @return true 表示已经能下结论「锁」，调用方应立即停止请求剩下的来源
         */
        boolean accept(Verdict verdict) {
            if (verdict == null) return false;
            if (!verdict.enabled) return true;
            anyEnabled = true;
            return false;
        }

        boolean allowed() {
            return decide(anyEnabled);
        }
    }
}
