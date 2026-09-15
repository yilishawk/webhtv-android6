package com.fongmi.android.tv.player.extractor;

import android.net.Uri;
import android.os.SystemClock;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.xunlei.downloadlib.XLTaskHelper;
import com.xunlei.downloadlib.parameter.GetTaskId;
import com.xunlei.downloadlib.parameter.TorrentFileInfo;
import com.xunlei.downloadlib.parameter.XLTaskInfo;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public class Thunder implements Source.Extractor {

    private GetTaskId taskId;

    @Override
    public boolean match(Uri uri) {
        return List.of("magnet", "ed2k").contains(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        try {
            return url.startsWith("magnet") ? addTorrentTask(Uri.parse(url)) : addThunderTask(url);
        } catch (Exception e) {
            SpiderDebug.log("thunder", e);
            throw e;
        } catch (Error e) {
            // XLTaskHelper.get() 内部会 System.loadLibrary("xl_stat"/"xl_thunder_sdk")，加载失败抛的是
            // UnsatisfiedLinkError（Error）。让它冒出去会绕过调用方的 catch (Exception)，这里统一转成
            // ExtractException，至少能走到正常的"播放失败"提示而不是未知崩溃。
            SpiderDebug.log("thunder", e);
            throw new ExtractException("thunder sdk unavailable: " + e);
        }
    }

    private String addTorrentTask(Uri uri) throws Exception {
        String path = uri.getPath();
        String name = uri.getQueryParameter("name");
        String index = uri.getQueryParameter("index");
        SpiderDebug.log("thunder", "addTorrentTask path=%s name=%s index=%s", path, name, index);
        // 期望的输入是 TorrentFileInfo.getPlayUrl() 拼出来的 "magnet://<本地torrent>?name=X&index=N"，
        // 不是爬虫给的原始磁力串 —— 原始磁力串是 opaque URI，getPath() 为 null。
        // 这里先判空再 new File()，避免"只开了一半门禁"时变成 NPE / NumberFormatException。
        if (path == null || path.isEmpty() || index == null) throw new ExtractException("thunder: not a parsed torrent uri");
        File torrent = new File(path);
        File parent = torrent.getParentFile();
        int idx = Integer.parseInt(index);
        taskId = XLTaskHelper.get().addTorrentTask(torrent, parent, idx);
        for (int i = 0; i < 100; i++) {
            XLTaskInfo info = XLTaskHelper.get().getBtSubTaskInfo(taskId, idx).mTaskInfo;
            if (info.mTaskStatus == 3) throw new ExtractException(info.getErrorMsg());
            if (info.mTaskStatus != 0) return XLTaskHelper.get().getLocalUrl(new File(parent, name));
            SystemClock.sleep(100);
        }
        throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
    }

    private String addThunderTask(String url) {
        File folder = Path.thunder(Util.md5(url));
        taskId = XLTaskHelper.get().addThunderTask(url, folder);
        return XLTaskHelper.get().getLocalUrl(taskId.getSaveFile());
    }

    @Override
    public void stop() {
        if (taskId == null) return;
        XLTaskHelper.get().deleteTask(taskId);
        XLTaskHelper.get().release();
        taskId = null;
    }

    @Override
    public void exit() {
        XLTaskHelper.get().release();
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(magnet|thunder|ed2k):.*");

        public static boolean match(String url) {
            return PATTERN.matcher(url).find() || isTorrent(url);
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        private static boolean isTorrent(String url) {
            return !url.startsWith("magnet") && url.split(";")[0].toLowerCase().endsWith(".torrent");
        }

        private Episode create(GetTaskId taskId) {
            return Episode.create(taskId.getFileName(), taskId.getRealUrl());
        }

        private Episode create(TorrentFileInfo info) {
            return Episode.create(info.getFileName(), info.getSize(), info.getPlayUrl());
        }

        @Override
        public List<Episode> call() {
            try {
                return parse();
            } catch (Throwable e) {
                // 让异常冒出去会经 Future.get() 的 ExecutionException 打到 SiteApi.detailContent()，
                // 代价是整个详情页打不开；这里吞掉只损失这一条磁力（addCallable 已把它从列表摘掉了）。
                SpiderDebug.log("thunder", e);
                return List.of();
            }
        }

        private List<Episode> parse() {
            SpiderDebug.log("thunder", "parser start url=%s", url);
            boolean torrent = isTorrent(url);
            GetTaskId taskId = XLTaskHelper.get().parse(url, Path.thunder(Util.md5(url)));
            SpiderDebug.log("thunder", "parser parsed realUrl=%s saveFile=%s", taskId.getRealUrl(), taskId.getSaveFile());
            if (!torrent && !taskId.getRealUrl().startsWith("magnet")) return Arrays.asList(create(taskId));
            if (torrent && url.startsWith("http")) Download.create(url, taskId.getSaveFile()).get();
            if (!torrent) waitDone(taskId);
            try {
                List<Episode> medias = XLTaskHelper.get().getTorrentInfo(taskId.getSaveFile()).getMedias().stream().map(this::create).toList();
                SpiderDebug.log("thunder", "parser done episodes=%d", medias.size());
                return medias;
            } finally {
                XLTaskHelper.get().stopTask(taskId);
            }
        }

        private void waitDone(GetTaskId taskId) {
            for (int i = 0; i < 100; i++) {
                if (XLTaskHelper.get().getTaskInfo(taskId).getTaskStatus() == 2) return;
                SystemClock.sleep(100);
            }
            SpiderDebug.log("thunder", "waitDone timeout(10s) saveFile=%s", taskId.getSaveFile());
        }
    }
}
