package com.fongmi.android.tv.utils;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.text.DecimalFormat;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class FileUtil {

    public static File getWall(int index) {
        return Path.files("wallpaper_" + index);
    }

    public static File getWallCache() {
        return Path.files("wallpaper_cache");
    }

    /**
     * 打开 / 安装一个文件。当前 4 个调用点**全部是 APK**：应用内更新（Updater）、
     * 服务端推送（Action）、局域网推送（ApkUrlPush），所以这里按"安装"来设计。
     *
     * ⭐ 必须按 API 等级分支 —— 两个平台各只认一种，不是兼容性洁癖：
     *
     * <p>API ≥ 24：{@code file://} 会抛 FileUriExposedException ⇒ 只能用 FileProvider 的 content://。
     *
     * <p>API ≤ 23：系统安装器（AOSP android-6.0.1_r81 的
     * {@code packages/apps/PackageInstaller/AndroidManifest.xml}）intent-filter 是
     * <pre>
     *   &lt;action android:name="android.intent.action.VIEW" /&gt;
     *   &lt;action android:name="android.intent.action.INSTALL_PACKAGE" /&gt;
     *   &lt;data android:scheme="file" /&gt;
     *   &lt;data android:mimeType="application/vnd.android.package-archive" /&gt;
     * </pre>
     * <b>没有 content</b> ⇒ 传 content:// 时**没有任何组件匹配** ⇒ ActivityNotFoundException。
     * （2026-09-17 电视端 Android 6.0.1 实测崩溃。）而且即使匹配上了，那个 Activity 也会
     * {@code Log.w("Unsupported scheme content")} → INSTALL_FAILED_INVALID_URI → finish
     * —— 它在 Android 6 上**根本不支持 content://**，不是我们哪里配错了。
     *
     * <p>⭐ 第二重（只换 scheme 还不够）：同一个 Activity 是
     * {@code new File(mPackageURI.getPath())} —— **自己按路径读文件**，它没有把 content://
     * 复制到临时文件的逻辑。所以 API ≤ 23 时文件还必须落在**安装器进程读得到**的位置，
     * 而这一点比看上去窄得多，两个"看起来能行"的地方都不行：
     * <ul>
     *   <li>应用私有目录 /data/data/&lt;pkg&gt;/cache：0700，别的 uid 读不到；</li>
     *   <li>getExternalFilesDir()/getExternalCacheDir()（在 Android/data/&lt;pkg&gt; 下）：
     *       Android 6 的 sdcard.c 对 Android/data|obb|media 做了**按包 uid 隔离**，也不行。</li>
     * </ul>
     * 只有**共享外部存储的公共目录**可以。判据与实现见 {@link #getInstallStagingDir}。
     */
    public static void openFile(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            startInstall(file, getShareUri(file));
            return;
        }
        if (readableByInstaller(file)) {
            startInstall(file, Uri.fromFile(file));
            return;
        }
        // 需要搬运。上百 MB 不能在主线程拷（Updater 就是从 App.post 里调进来的）⇒ 后台搬完再回主线程发 Intent。
        Task.execute(() -> {
            File staged = stageForInstaller(file);
            File opened = staged == null ? file : staged;
            if (staged != null && !staged.equals(file)) Task.schedule(() -> Path.clear(staged), 30, TimeUnit.MINUTES);
            App.post(() -> startInstall(opened, Uri.fromFile(opened)));
        });
    }

    private static void startInstall(File file, Uri uri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setDataAndType(uri, getMimeType(file.getName()));
        SpiderDebug.log("install", "open file=%s size=%s sdk=%s scheme=%s", file.getAbsolutePath(), file.length(), Build.VERSION.SDK_INT, uri.getScheme());
        try {
            App.get().startActivity(intent);
        } catch (ActivityNotFoundException e) {
            // 以前这里是一个 crash 报告，只带 ANFE —— 看不出是「没人能接这个 Intent」
            // 还是「文件路径安装器读不到」。现在转成一条能读的日志 + 提示：宁可少一个功能，不要再交一份崩溃。
            SpiderDebug.log("install", "no activity file=%s intent=%s", file.getAbsolutePath(), intent);
            Notify.show(R.string.update_install_unavailable);
        }
    }

    /**
     * API ≤ 23 时**安装器进程读得到**的目录；API ≥ 24 或拿不到写权限时返回 null。
     *
     * <p>必须是**共享外部存储里的公共目录**（这里用 Download/），两个理由缺一不可：
     *
     * <p>① 不在 {@code Android/} 下。Android 6 的 sdcard.c 对 {@code Android/data|obb|media}
     * 做了按包隔离：{@code derive_permissions_locked()} 把 {@code Android/data/<pkg>} 这个节点的
     * uid 直接设成**该包自己的 uid**，{@code attr_from_stat()} 再去掉 "other" 位
     * （{@code visible_mode & ~0006/0007}），挂载参数带 {@code default_permissions}
     * ⇒ **由内核强制**。所以 getExternalFilesDir()/getExternalCacheDir() 看着像"共享存储"，
     * 实际安装器读不到 —— 这一点极易误判，实测依据是 AOSP android-6.0.1_r81 system/core/sdcard/sdcard.c。
     *
     * <p>② 在共享存储里 ⇒ 持有 READ_EXTERNAL_STORAGE 的安装器读得到（公共目录不隔离）。
     *
     * <p>代价是写它需要 WRITE_EXTERNAL_STORAGE（运行时权限）。拿不到就返回 null，
     * 调用方退回私有目录 —— 行为不比改动前更差，且日志里看得出来。
     */
    public static File getInstallStagingDir() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return null;
        if (!Setting.hasFileAccess()) return null;
        try {
            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch (Exception e) {
            SpiderDebug.log("install", e);
            return null;
        }
    }

    /** 安装器读不读得到这个文件。判据 = 在共享外部存储里 **且** 不在 Android/ 下（那里按包隔离）。 */
    private static boolean readableByInstaller(File file) {
        File external = Environment.getExternalStorageDirectory();
        if (external == null) return false;
        String path = file.getAbsolutePath();
        String root = external.getAbsolutePath();
        if (!path.startsWith(root + File.separator)) return false;
        return !path.startsWith(root + File.separator + "Android" + File.separator);
    }

    /** 把文件搬到安装器读得到的地方。失败返回 null —— 调用方退回原文件。 */
    private static File stageForInstaller(File file) {
        try {
            File dir = getInstallStagingDir();
            if (dir == null) return null;
            File target = new File(dir, file.getName());
            if (target.length() == file.length() && target.lastModified() >= file.lastModified()) return target;
            Path.clear(target);
            Path.copy(file, target);
            return target.length() == file.length() ? target : null;
        } catch (Exception e) {
            SpiderDebug.log("install", e);
            return null;
        }
    }

    public static void gzipCompress(File target) {
        byte[] buffer = new byte[16384];
        try (FileInputStream is = new FileInputStream(target); GZIPOutputStream os = new GZIPOutputStream(new FileOutputStream(target.getAbsolutePath() + ".gz"))) {
            int read;
            while ((read = is.read(buffer)) > 0) os.write(buffer, 0, read);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            Path.clear(target);
        }
    }

    public static void gzipDecompress(File target, File path) {
        byte[] buffer = new byte[16384];
        try (GZIPInputStream is = new GZIPInputStream(new BufferedInputStream(new FileInputStream(target))); BufferedOutputStream os = new BufferedOutputStream(new FileOutputStream(path))) {
            int read;
            while ((read = is.read(buffer)) != -1) os.write(buffer, 0, read);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void zipDecompress(File target, File path) {
        try (ZipFile zip = new ZipFile(target)) {
            Enumeration<?> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = (ZipEntry) entries.nextElement();
                File out = new File(path, entry.getName());
                if (entry.isDirectory()) out.mkdirs();
                else Path.copy(zip.getInputStream(entry), out);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void clearCache(Callback callback) {
        Task.execute(() -> {
            Path.clear(Path.cache());
            App.post(callback::success);
        });
    }

    public static void getCacheSize(Callback callback) {
        Task.execute(() -> {
            String usage = byteCountToDisplaySize(getDirectorySize(Path.cache()));
            App.post(() -> callback.success(usage));
        });
    }

    public static long getDirectorySize(File dir) {
        long size = 0;
        if (dir == null) return 0;
        if (dir.isDirectory()) {
            for (File file : Path.list(dir)) {
                long child = getDirectorySize(file);
                if (size > Long.MAX_VALUE - child) return Long.MAX_VALUE;
                size += child;
            }
        }
        else size = dir.length();
        return size;
    }

    public static long getAvailableStorageSpace(File file) {
        return getStorageSpace(file).availableBytes();
    }

    public static StorageSpace getStorageSpace(File file) {
        try {
            File target = existingPath(file);
            if (target == null) return StorageSpace.unavailable();
            StatFs stat = new StatFs(target.getAbsolutePath());
            return StorageSpace.of(stat.getAvailableBytes(), stat.getTotalBytes());
        } catch (Exception e) {
            return StorageSpace.unavailable();
        }
    }

    private static File existingPath(File file) {
        File target = file;
        while (target != null && !target.exists()) target = target.getParentFile();
        return target;
    }

    public record StorageSpace(boolean available, long availableBytes, long totalBytes) {

        public static StorageSpace of(long availableBytes, long totalBytes) {
            boolean valid = availableBytes >= 0 && totalBytes > 0 && availableBytes <= totalBytes;
            return valid ? new StorageSpace(true, availableBytes, totalBytes) : unavailable();
        }

        public static StorageSpace unavailable() {
            return new StorageSpace(false, 0, 0);
        }
    }

    public static Uri getShareUri(String path) {
        return getShareUri(new File(path.replace("file://", "")));
    }

    public static Uri getShareUri(File file) {
        return FileProvider.getUriForFile(App.get(), App.get().getPackageName() + ".provider", file);
    }

    private static String getMimeType(String fileName) {
        String mimeType = URLConnection.guessContentTypeFromName(fileName);
        return TextUtils.isEmpty(mimeType) ? "*/*" : mimeType;
    }

    public static String byteCountToDisplaySize(long size) {
        if (size <= 0) return ResUtil.getString(R.string.none);
        String[] units = new String[]{"bytes", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        return new DecimalFormat("#,##0.#").format(size / Math.pow(1024, digitGroups)) + " " + units[digitGroups];
    }
}
