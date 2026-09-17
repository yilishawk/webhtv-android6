package androidx.media3.mpvplayer;

import java.util.Locale;

final class MpvHlsSegmentContentPolicy {

    private static final byte[] PNG_SIGNATURE = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND = new byte[]{0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    static final int TS_PACKET_BYTES = 188;

    /** 假图片头的长度上限。实测只有 54 字节（BMP），留足余量但不无限缓冲。 */
    static final int IMAGE_PREFIX_SCAN_LIMIT = 8 * 1024;

    private MpvHlsSegmentContentPolicy() {
    }

    static boolean shouldProbePngPrefix(String contentType, boolean mediaSegment) {
        String mime = contentType == null ? "" : contentType.trim().toLowerCase(Locale.US);
        if (mime.startsWith("image/png")) return true;
        return mediaSegment && mime.startsWith("image/");
    }

    /**
     * 通用剥壳：返回第一个「188 字节对齐的 MPEG-TS 起始偏移」。
     *
     * <p>2026-09-17 新增。起因是一次真机失败 + 一次本地复现（`apk-check/ffmpeg_hls_repro.py`，
     * PyAV/ffmpeg）共同证明：**ffmpeg 的 hls demuxer 要求「它实际能打开的第一个分片」是无壳纯 TS**。
     * 该分片带假图片头时 `avformat_find_stream_info` 拿不到 `extradata`（真机表现：
     * `mpv size candidates … selected=tracks-cache:1920x1080 legacy=wh:0x0` ⇒
     * 软解/硬解三个解码器全部 `Decoder init failed` ⇒ `vid=no` ⇒ 只有声音没图像）。
     *
     * <p>本项目遇到的伪装头实测至少两种：PNG（已有逻辑，见
     * {@link #findPngWrappedTransportStreamOffset}）和 **54 字节 BMP**
     * （`BM` + 4 字节文件大小 + 4 字节保留 + `0x36` 像素偏移 + `40` BITMAPINFOHEADER 长度）。
     * 只剥 PNG 会让 BMP/JPEG 伪装的分片原样透传。
     *
     * <p>⛔ 只在**确实找到**连续 5 个 188 对齐的 `0x47` 时才剥；偏移 0 表示本来就没壳（返回 -1）。
     * 随机数据出现这种巧合的概率约 1e-12，可以忽略。找不到就原样透传，不制造新故障面。
     */
    static int findTransportStreamOffset(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        if (safeLength < TS_PACKET_BYTES * 5) return -1;
        int png = findPngWrappedTransportStreamOffset(data, safeLength);
        if (png > 0) return png;
        if (startsAtTransportStreamPacket(data, safeLength)) return -1;
        for (int offset = 1; offset + TS_PACKET_BYTES * 4 < safeLength; offset++) {
            if (data[offset] != 0x47) continue;
            boolean aligned = true;
            for (int k = 1; k <= 4; k++) {
                if (data[offset + TS_PACKET_BYTES * k] != 0x47) {
                    aligned = false;
                    break;
                }
            }
            if (aligned) return offset;
        }
        return -1;
    }

    /** 数据是否**从第 0 字节起**就是对齐的 TS（= 没有假图片头，不需要剥）。 */
    static boolean startsAtTransportStreamPacket(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        if (safeLength < TS_PACKET_BYTES * 5) return false;
        for (int k = 0; k <= 4; k++) {
            if (data[TS_PACKET_BYTES * k] != 0x47) return false;
        }
        return true;
    }

    static int findPngWrappedTransportStreamOffset(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        if (!startsWithPngSignature(data, safeLength)) return -1;
        int iend = indexOf(data, safeLength, PNG_IEND);
        if (iend < 0) return -1;
        int start = iend + PNG_IEND.length;
        for (int offset = start; offset + TS_PACKET_BYTES < safeLength; offset++) {
            if (data[offset] != 0x47 || data[offset + TS_PACKET_BYTES] != 0x47) continue;
            if (offset + TS_PACKET_BYTES * 2 < safeLength
                    && data[offset + TS_PACKET_BYTES * 2] != 0x47) continue;
            return offset;
        }
        return -1;
    }

    static boolean startsWithPngSignature(byte[] data, int length) {
        int safeLength = Math.max(0, Math.min(length, data == null ? 0 : data.length));
        return startsWith(data, safeLength, PNG_SIGNATURE);
    }

    static long strippedContentLength(long contentLength, int strippedPrefixBytes) {
        if (contentLength <= 0
                || strippedPrefixBytes <= 0
                || strippedPrefixBytes >= contentLength) return -1;
        return contentLength - strippedPrefixBytes;
    }

    private static boolean startsWith(byte[] data, int length, byte[] prefix) {
        if (data == null || length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (data[i] != prefix[i]) return false;
        return true;
    }

    private static int indexOf(byte[] data, int length, byte[] needle) {
        int end = length - needle.length;
        for (int i = 0; i <= end; i++) {
            boolean match = true;
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}
