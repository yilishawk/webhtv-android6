package androidx.media3.mpvplayer;

import java.nio.charset.StandardCharsets;

/**
 * 把 URL 转成 mpv 内嵌 libcurl 能接受的百分号编码形式。
 *
 * <p>2026-09-18 新增。起因是一个只影响 **MPV 引擎**的真机故障：爬虫给出的地址里含**原样中文**
 * （或空格）时，mpv 走的是自带的 `stream_curl`（libcurl 8.21.0）而不是 ffmpeg，而 libcurl 8.x 的
 * URL 解析器（`curl_url_set(CURLUPART_URL, …)`）**拒绝任何非 ASCII 字节**，于是：
 *
 * <pre>
 *   curl: error: URL using bad/illegal format or missing URL
 *   → MPV_LOAD_FAILED (code=2000)
 * </pre>
 *
 * <p>为什么 ExoPlayer 没这个问题：OkHttp 会在发请求前自行规范化 URL，非 ASCII 由它编码掉。
 * 只有 mpv 这条链路是「原样字符串直接进 C 层」。
 *
 * <p>⛔ **只在 mpv 边界上编码**（`loadfile` / `sub-add` 的实参），**不改 `currentPlayableUri` 的存储值** ——
 * 后者还要参与日志脱敏、`sameUri()` 比较、`PlaybackRoute.resolve()`，改动它等于扩大故障面。
 *
 * <p>⛔ **不碰 `%`**：否则已经编码过的 URL 会被二次编码（`%E4` → `%25E4`）而失效。
 * 其余 URL 合法字符（`!$&'()*+,;=:@/?#[]~-_.` 与字母数字）全部原样保留。
 *
 * <p>纯 ASCII 且无空格/控制字符时**直接返回原对象**，行为与改动前逐字节一致。
 */
final class MpvUrlEncodingPolicy {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * 在 URL 里永远非法、且 libcurl 会直接拒收的 ASCII 字符。
     *
     * <p>不含 `[` `]`（IPv6 字面量要用），也不含 `%`（见类注释）。
     */
    private static final String ASCII_ESCAPE = "\"<>\\^`{|}";

    private MpvUrlEncodingPolicy() {
    }

    /**
     * 需要编码时返回编码后的新字符串，否则返回**入参本身**（同一个对象）。
     *
     * <p>判定按 UTF-8 字节走：`<= 0x20`（控制字符与空格）、`>= 0x7F`（DEL 与全部非 ASCII），
     * 以及 {@link #ASCII_ESCAPE} 里的那几个。
     */
    static String encodeForMpv(String url) {
        if (url == null || url.isEmpty()) return url;
        byte[] bytes = url.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = null;
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            boolean encode = value <= 0x20
                    || value >= 0x7F
                    || ASCII_ESCAPE.indexOf((char) value) >= 0;
            if (!encode) {
                if (out != null) out.append((char) value);
                continue;
            }
            if (out == null) {
                // 走到这里说明 url[0, i) 全是无需编码的 ASCII（0x21..0x7E），
                // 因此**字节下标 i 与字符下标 i 相等**，可以直接切片。
                out = new StringBuilder(bytes.length + 32);
                out.append(url, 0, i);
            }
            out.append('%').append(HEX[value >> 4]).append(HEX[value & 0x0F]);
        }
        return out == null ? url : out.toString();
    }
}
