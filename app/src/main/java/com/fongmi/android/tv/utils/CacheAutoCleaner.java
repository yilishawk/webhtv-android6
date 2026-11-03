package com.fongmi.android.tv.utils;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.exo.MediaSourceFactory;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.File;

/**
 * 缓存总量超过用户设定上限时的自动清理。
 *
 * <p>清理分三级，逐级进行，降到上限以下就停：
 * <ol>
 *   <li>安全项：歌词、K 歌音轨、WebHome 原始/扩展缓存、节目单、图片缓存（走 Glide 官方 API）。
 *       清掉只是下次重新生成。</li>
 *   <li>爬虫产物：jar / js / py 解压目录。下次加载会重新解压，代价是一次解压耗时。</li>
 *   <li>播放缓存：只处理 EXO（走 {@link MediaSourceFactory} 的正式关闭流程），
 *       且必须没有正在进行的播放会话。</li>
 * </ol>
 *
 * <p><b>为什么不清 cacheDir 根目录</b>：本类只删 {@code SAFE_DIRS} / {@code SPIDER_DIRS}
 * 这些<b>子目录的内容</b>，从不遍历 cacheDir 根。因此
 * {@code webhtv-debug-log.txt}（排查证据）、{@code update.apk} 与
 * {@code pushed-*.apk}（升级/推送中途的安装包）天然不会被删。
 * 这是结构上的保证，不依赖字符串白名单。
 *
 * <p><b>为什么不碰 mpv_hls</b>：MPV 的 HLS 缓存由 {@code MpvHlsCacheCoordinator}
 * 持有一份预留账本，直接删目录会让账本与磁盘不一致，而它没有暴露清理接口。
 * 该缓存另有「HLS 磁盘缓存上限」设置自管，故不在此处处理。
 *
 * <p>默认关闭（上限索引 0）。只有用户主动设置了上限才会生效，老版本行为不变。
 */
public final class CacheAutoCleaner {

    private static final String TAG = "cache-clean";

    /** 安全项：清掉只影响下次生成，不涉及正在播放或正在运行的资源。 */
    private static final String[] SAFE_DIRS = {
            "lyrics", "karaoke_tracks", "webhome_raw", "webhome_ext", "epg", "jpa", "thunder"
    };

    /** 爬虫解压产物：清掉后下次加载会重新解压。 */
    private static final String[] SPIDER_DIRS = {"jar", "js", "py"};

    private CacheAutoCleaner() {
    }

    /**
     * 检查一次缓存总量，超过上限则分级清理。必须在后台线程调用。
     *
     * @return 清理后的缓存总量
     */
    public static long runIfNeeded() {
        long limit = Setting.getCacheLimitBytes();
        File cache = Path.cache();
        if (limit <= 0 || cache == null) return 0;
        long total = FileUtil.getDirectorySize(cache);
        if (total <= limit) return total;

        long before = total;
        SpiderDebug.log(TAG, "start limit=%s total=%s", size(limit), size(total));

        for (String name : SAFE_DIRS) clearChildren(Path.cache(name));
        clearImageCache();
        total = FileUtil.getDirectorySize(cache);
        if (total <= limit) return finish(before, total, limit, "safe");

        for (String name : SPIDER_DIRS) clearChildren(Path.cache(name));
        total = FileUtil.getDirectorySize(cache);
        if (total <= limit) return finish(before, total, limit, "spider");

        long freed = MediaSourceFactory.clearDiskCacheForAutoClean();
        total = FileUtil.getDirectorySize(cache);
        return finish(before, total, limit, freed > 0 ? "playback" : "playback-skipped");
    }

    private static long finish(long before, long after, long limit, String stage) {
        SpiderDebug.log(TAG, "done stage=%s before=%s after=%s limit=%s reached=%s",
                stage, size(before), size(after), size(limit), after <= limit);
        return after;
    }

    /**
     * 只删目录内容、保留目录本身。
     *
     * <p>{@code Path.cache(name)} 不会 mkdir，消费方默认目录存在；若把目录本身删掉，
     * 歌词/爬虫等模块下一次写入会失败。所以这里刻意保留目录壳。
     */
    private static void clearChildren(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) Path.clear(child);
    }

    /** Glide 的磁盘缓存有自己的 journal，必须走官方 API，不能直接删文件。 */
    private static void clearImageCache() {
        try {
            Glide.get(App.get()).clearDiskCache();
        } catch (Throwable e) {
            SpiderDebug.log(TAG, "image-cache-failed error=%s", e.getClass().getSimpleName());
        }
    }

    private static String size(long bytes) {
        return FileUtil.byteCountToDisplaySize(bytes);
    }
}
