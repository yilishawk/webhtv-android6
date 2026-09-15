package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.Build;

import androidx.media3.common.MimeTypes;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.extractor.Force;
import com.fongmi.android.tv.player.extractor.JianPian;
import com.fongmi.android.tv.player.extractor.MpdEdlResolver;
import com.fongmi.android.tv.player.extractor.MpdSanitizer;
import com.fongmi.android.tv.player.extractor.Push;
import com.fongmi.android.tv.player.extractor.Strm;
import com.fongmi.android.tv.player.extractor.TVBus;
import com.fongmi.android.tv.player.extractor.Thunder;
import com.fongmi.android.tv.player.extractor.Video;
import com.fongmi.android.tv.player.extractor.Youtube;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Source {

    private final List<Extractor> extractors;

    public Source() {
        extractors = new ArrayList<>();
        extractors.add(new Force());
        extractors.add(new JianPian());
        extractors.add(new Push());
        extractors.add(new Strm());
        // Thunder 从 API 24 门禁里移出来了：thunder-release.aar 自己声明 minSdkVersion=23，
        // libxl_thunder_sdk.so / libxl_stat.so 的 DT_NEEDED 只含 API 1 起的库（其中 libstdc++.so
        // 在 Android 6/7/8 上存在、Android 9 起才被移除），所以它不需要 API 24。
        // 位置保持原样（Strm 之后、TVBus 之前），避免改变 getExtractor 的匹配优先级。
        // ⚠️ 与 addCallable() 里的那道门是同一个功能，必须同时放/同时收。
        extractors.add(new Thunder());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            extractors.add(new TVBus());
        }
        extractors.add(new Video());
        extractors.add(new Youtube());
    }

    public static Source get() {
        return Loader.INSTANCE;
    }

    // 哨兵：magnet / ed2k / tvbus 这三个 scheme 必须由抽取器接管，一旦落到 null 抽取器就会被
    // 原样交给播放器，并在播放器侧以 1004（Exo）/ 2000（MPV）报错 —— 那条路径以前完全静默，
    // 日志里看不出"抽取层缺了东西"。谁再把 Source() 里的注册收回去，这里会先叫。
    private void logUnclaimed(Uri uri, String url) {
        String scheme = UrlUtil.scheme(uri);
        if (!"magnet".equals(scheme) && !"ed2k".equals(scheme) && !"tvbus".equals(scheme)) return;
        SpiderDebug.log("source", "no extractor claimed scheme=%s urlLen=%d", scheme, url == null ? 0 : url.length());
    }

    private Extractor getExtractor(Uri uri) {
        return extractors.stream().filter(extractor -> extractor.match(uri)).findFirst().orElse(null);
    }

    private void addCallable(Iterator<Episode> iterator, List<Callable<List<Episode>>> items) {
        String url = iterator.next().getUrl();
        // 不再判 SDK_INT：磁力/ed2k/种子解析在 Android 6 上同样可用（见 Source() 的说明）。
        // ⚠️ 这道门和构造函数里 Thunder 的注册必须同开同收——只开一边会让原始磁力串
        // 直接进 Thunder.addTorrentTask()（match() 只看 scheme，照样命中）而崩。
        if (Thunder.Parser.match(url)) {
            items.add(Thunder.Parser.get(url));
            iterator.remove();
        } else if (Youtube.Parser.match(url)) {
            items.add(Youtube.Parser.get(url));
            iterator.remove();
        }
    }

    public void parse(Vod vod) throws Exception {
        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            for (Flag flag : vod.getFlags()) {
                List<Callable<List<Episode>>> items = new ArrayList<>();
                Iterator<Episode> iterator = flag.getEpisodes().iterator();
                while (iterator.hasNext()) addCallable(iterator, items);
                for (Future<List<Episode>> future : executor.invokeAll(items, 30, TimeUnit.SECONDS)) {
                    try {
                        flag.getEpisodes().addAll(future.get());
                    } catch (CancellationException e) {
                        // invokeAll 超时后 future 已被取消。原来这里是完全静默的：解析器卡住时
                        // 用户只看到"剧集少了"，日志里一行都没有，无法判断是不是磁力那一步超时。
                        SpiderDebug.log("source", "episode parse timeout(30s) flag=%s pending=%d", flag.getFlag(), items.size());
                    } catch (ExecutionException e) {
                        // 单个解析器失败不该让整个详情页失败：addCallable() 已经把对应剧集从列表里
                        // 摘掉了，再让异常冒到 SiteApi.detailContent() 的代价是整页打不开。
                        SpiderDebug.log("source", "episode parse failed flag=%s", flag.getFlag(), e.getCause() == null ? e : e.getCause());
                    }
                }
            }
        }
    }

    public String fetch(Result result) throws Exception {
        return fetch(result, PlayerSetting.getPlayer());
    }

    public String fetch(Result result, int playerType) throws Exception {
        Uri uri = result.getUrl().uri();
        String url = result.getUrl().v();
        int originalParse = result.getParse();
        Extractor extractor = getExtractor(uri);
        if (extractor == null) logUnclaimed(uri, url);
        if (extractor != null) result.setParse(0);
        if (extractor instanceof Video) result.setParse(1);
        String fetched;
        try {
            fetched = extractor == null ? url : extractor.fetch(result);
        } catch (Exception e) {
            if (!(extractor instanceof Youtube) || originalParse != 1) throw e;
            result.setParse(1);
            return url;
        }
        if (extractor instanceof Youtube && fetched.isEmpty() && originalParse == 1) {
            result.setParse(1);
            return url;
        }
        String sanitized = MpdSanitizer.sanitize(fetched);
        if (MpdSanitizer.hasLimitedYoutubeAudio(sanitized)) {
            String referer = getHeader(result.getHeader(), "Referer");
            if (referer.contains("youtube.com/watch")) {
                try {
                    sanitized = new Youtube().fetch(referer);
                    SpiderDebug.log("mpd-sanitizer", "replaced limited jar MPD with app YouTube extractor");
                } catch (Exception e) {
                    SpiderDebug.log("mpd-sanitizer", "app YouTube extractor fallback failed: %s", e.getMessage());
                }
            }
        }
        if (MpdSanitizer.isYoutube(sanitized)) {
            Map<String, String> headers = result.getHeader();
            headers.put("User-Agent", "com.google.android.youtube/21.02.35 (Linux; U; Android 14) gzip");
            result.setHeader(headers);
        }
        boolean dash = isDash(result, sanitized);
        if (playerType == PlayerSetting.MPV && dash) {
            String resolved = MpdEdlResolver.resolve(sanitized, result.getHeader());
            if (!resolved.equals(sanitized)) result.setFormat(null);
            sanitized = resolved;
        } else if (dash) {
            sanitized = avoidStaleLocalManifest(sanitized);
        }
        return sanitized;
    }

    private String avoidStaleLocalManifest(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) return url;
            return uri.buildUpon().appendQueryParameter("player_ts", Long.toString(System.currentTimeMillis())).build().toString();
        } catch (Throwable ignored) {
            return url;
        }
    }

    private boolean isDash(Result result, String url) {
        String format = result.getFormat();
        if (MimeTypes.APPLICATION_MPD.equals(format) || "application/dash+xml".equalsIgnoreCase(format) || "dash".equalsIgnoreCase(format)) return true;
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        return lower.startsWith("data:application/dash+xml") || lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd");
    }

    private String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    public void stop() {
        if (extractors == null) return;
        extractors.forEach(Extractor::stop);
    }

    public void exit() {
        if (extractors == null) return;
        Task.execute(() -> extractors.forEach(Extractor::exit));
    }

    public interface Extractor {

        default String fetch(Result result) throws Exception {
            return fetch(result.getUrl().v());
        }

        String fetch(String url) throws Exception;

        boolean match(Uri uri);

        void stop();

        void exit();
    }

    private static class Loader {
        static volatile Source INSTANCE = new Source();
    }
}
