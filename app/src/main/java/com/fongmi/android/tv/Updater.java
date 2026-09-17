package com.fongmi.android.tv;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;

import com.fongmi.android.tv.bean.Update;
import com.fongmi.android.tv.impl.UpdateListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.dialog.UpdateDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.AppVersion;
import com.fongmi.android.tv.utils.BundledCaTrust;
import com.fongmi.android.tv.utils.Github;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.update.GithubProxy;
import com.fongmi.android.tv.update.HttpUpdateTransfer;
import com.fongmi.android.tv.update.OciArtifact;
import com.fongmi.android.tv.update.OciMirror;
import com.fongmi.android.tv.update.OciUpdateTransfer;
import com.fongmi.android.tv.update.UpdateHttp;
import com.fongmi.android.tv.update.UpdateRoutePlanner;
import com.fongmi.android.tv.update.UpdateTarget;
import com.fongmi.android.tv.update.UpdateTransfer;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Path;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class Updater implements UpdateTransfer.Callback, UpdateListener {

    private static final String DEFAULT_RELEASE_NOTES = "手动触发 GitHub Actions 构建发布。";
    private static final long UPDATE_CHECK_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(10);
    private static final long GITHUB_REQUEST_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(4);
    private static final Map<String, String> GITHUB_API_HEADERS = Map.of("Accept", "application/vnd.github+json", "X-GitHub-Api-Version", "2022-11-28");
    private static final Map<String, String> GITHUB_ASSET_HEADERS = Map.of("Accept", "application/octet-stream", "X-GitHub-Api-Version", "2022-11-28");
    private static final Updater INSTANCE = new Updater();

    // ⛔ 更新检查**不能**复用 Task.executor()。那是个 5 线程固定池（Task.java:16），全项目 89 个调用点共用
    // （含每个爬虫请求，SiteViewModel:195）；而 doInBackground 自己就是被 Task.execute 提交进去的、占着
    // 其中一个线程，却还往**同一个池**提交两个子任务 ⇒ 池忙时子任务排队饿死 ⇒ 10s 总预算耗尽 ⇒
    // awaitUpdate 抛 TimeoutException ⇒ 报「更新失败」。启动期/繁忙的电视更容易踩，空闲的手机不踩
    // —— 这正好复现「手机检查正常、电视检查失败」。给它一对专用线程，与爬虫彻底解耦。
    private static final ExecutorService CHECK_EXECUTOR = Executors.newFixedThreadPool(2);

    private final LifecycleEventObserver lifecycleObserver = (source, event) -> {
        if (!(source instanceof FragmentActivity)) return;
        FragmentActivity activity = (FragmentActivity) source;
        if (event == Lifecycle.Event.ON_DESTROY) unbind(activity);
    };

    private WeakReference<FragmentActivity> activityRef;
    private UpdateDialog dialog;
    private UpdateTransfer transfer;
    private List<UpdateTarget> routes;
    private int routeIndex;
    private Update stable;
    private Update beta;
    private Update selected;
    private boolean force;
    private boolean downloading;
    private boolean canceled;
    private int lastProgress = -1;
    private long lastBytes;
    private long lastTotal;
    private long lastSpeed;
    private long lastElapsed;

    private Updater() {
    }

    public static Updater create() {
        return INSTANCE;
    }

    private File getFile() {
        return Path.cache("update.apk");
    }

    private String getName() {
        // Must match the release asset base name the CI workflow publishes:
        // "${mode}-${abi}${apkSuffix}" — e.g. "leanback-armeabi_v7a-a6".
        return BuildConfig.FLAVOR_mode + "-" + BuildConfig.FLAVOR_abi + BuildConfig.APK_SUFFIX;
    }

    public Updater force() {
        force = true;
        Notify.show(R.string.update_check);
        Setting.putUpdate(true);
        return this;
    }

    public void start(FragmentActivity activity) {
        bind(activity);
        boolean forceCheck = force;
        force = false;
        if (downloading) {
            restoreDialog(activity);
            return;
        }
        if (!Setting.getUpdate()) return;
        Task.execute(() -> doInBackground(activity, forceCheck));
    }

    public void resume(FragmentActivity activity) {
        bind(activity);
        restoreDialog(activity);
    }

    private void doInBackground(FragmentActivity activity, boolean forceCheck) {
        long started = SystemClock.elapsedRealtime();
        long deadline = started + UPDATE_CHECK_TIMEOUT_MS;
        // 这条链路原本一处诊断都没有，4 个失败点全是 e.printStackTrace() ⇒ 只进 logcat、不进
        // webhtv-debug-log.txt ⇒ 真机上「检查失败」根本查不到原因。下面按阶段打点，把每个通道的
        // 结局（有没有清单 / 有没有更新 / error 是什么）都落进日志，下次失败可直接定位。
        // sdk/rel 必须带上：本分支存在的理由是 API 23，而「同一份 APK 手机正常、电视失败」最常见的
        // 平台级原因是 TLS —— Android < 7.1.1 的系统信任库里**没有** ISRG Root X1，而 GitHub 的资产
        // 下载 302 到 release-assets.githubusercontent.com，那个域的链正是 Let's Encrypt
        // (leaf *.github.io <- Let's Encrypt YR1 <- ISRG Root YR <- ISRG Root X1)。没有 sdk 就没法排除它。
        SpiderDebug.log("update", "check start name=%s code=%s sdk=%s rel=%s trust=%d force=%s budget=%sms", getName(), BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, BundledCaTrust.bundledRootCount(), forceCheck, UPDATE_CHECK_TIMEOUT_MS);
        Future<Update> stableFuture = CHECK_EXECUTOR.submit(() -> getUpdate(Update.CHANNEL_STABLE));
        Future<Update> betaFuture = CHECK_EXECUTOR.submit(() -> getUpdate(Update.CHANNEL_BETA));
        stable = awaitUpdate(stableFuture, Update.CHANNEL_STABLE, deadline);
        beta = awaitUpdate(betaFuture, Update.CHANNEL_BETA, deadline);
        SpiderDebug.log("update", "check done elapsed=%sms stable[manifest=%s update=%s error=%s] beta[manifest=%s update=%s error=%s]",
                SystemClock.elapsedRealtime() - started,
                stable.hasManifest(), stable.hasUpdate(), stable.error,
                beta.hasManifest(), beta.hasUpdate(), beta.error);
        if (!stable.hasUpdate() && !beta.hasUpdate()) {
            if (forceCheck && (stable.hasManifest() || beta.hasManifest())) {
                selected = getPreferredUpdate();
                App.post(() -> show(activity));
                return;
            }
            if (forceCheck) {
                boolean errorOnly = hasErrorOnly();
                SpiderDebug.log("update", "check notify=%s", errorOnly ? "update_failed" : "update_latest");
                App.post(() -> Notify.show(errorOnly ? R.string.update_failed : R.string.update_latest));
            }
            return;
        }
        selected = getPreferredUpdate();
        SpiderDebug.log("update", "check dialog selected=%s", selected == null ? "null" : selected.channel);
        App.post(() -> show(activity));
    }

    private Update getPreferredUpdate() {
        if (stable != null && stable.hasUpdate()) return stable;
        if (beta != null && beta.hasUpdate()) return beta;
        if (stable != null && stable.hasManifest()) return stable;
        return beta;
    }

    private Update awaitUpdate(Future<Update> future, String channel, long deadline) {
        long remaining = deadline - SystemClock.elapsedRealtime();
        try {
            if (remaining <= 0) throw new TimeoutException("Update check timed out");
            return future.get(remaining, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            // 「更新失败」只可能来自这里或 readUpdate 的 catch（Update.empty() 不设 error）。
            // remaining<=0 说明 10s 总预算已耗尽，而不是这一跳网络本身慢 —— 这两者要分开看。
            SpiderDebug.log("update", "await failed channel=%s remaining=%sms cause=%s", channel, remaining, describe(e));
            SpiderDebug.log("update", e);
            return Update.error(channel, e);
        }
    }

    // 日志的「摘要」视图只保留每条记录的第一行，而 SpiderDebug.log(tag, Throwable) 那一条的第一行
    // 常常只有类名、message 是 null。把**根因**的类和 message 直接并进失败行，远程一眼就能分清是
    // TLS 证书不受信任、DNS 失败，还是纯超时 —— 这三种的处理方式完全不同。
    private String describe(Throwable e) {
        Throwable root = e;
        for (int i = 0; i < 8 && root.getCause() != null && root.getCause() != root; i++) root = root.getCause();
        return root.getClass().getSimpleName() + (TextUtils.isEmpty(root.getMessage()) ? "" : ": " + root.getMessage());
    }

    private Update getUpdate(String channel) {
        return Update.CHANNEL_BETA.equals(channel) ? getGithubBetaUpdate(channel) : getGithubStableUpdate(channel);
    }

    private Update getGithubStableUpdate(String channel) {
        String url = Github.getLatestReleaseApi();
        try {
            JSONObject release = new JSONObject(UpdateHttp.string(url, GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS));
            return readGithubReleaseUpdate(channel, release);
        } catch (Exception e) {
            // 以前这里 return Update.empty(channel) 且不设 error ⇒ release 列表请求失败
            // （DNS/TLS/超时/HTTP 403）会被报成「已是最新」，与真·最新视觉上完全一样。
            SpiderDebug.log("update", "stable list failed url=%s cause=%s", url, describe(e));
            SpiderDebug.log("update", e);
            return Update.error(channel, e);
        }
    }

    private Update getGithubBetaUpdate(String channel) {
        String manifestName = getManifestName(channel);
        String url = Github.getReleasesApi();
        try {
            JSONArray releases = new JSONArray(UpdateHttp.string(url, GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.optJSONObject(i);
                if (release == null || !isBetaRelease(release)) continue;
                if (findAsset(release.optJSONArray("assets"), manifestName) == null) continue;
                return readGithubReleaseUpdate(channel, release);
            }
            // 「没找到 beta 发布」不是失败，所以这里不设 error，只留痕。
            SpiderDebug.log("update", "beta no release carrying asset=%s", manifestName);
        } catch (Exception e) {
            SpiderDebug.log("update", "beta list failed url=%s cause=%s", url, describe(e));
            SpiderDebug.log("update", e);
            return Update.error(channel, e);
        }
        return Update.empty(channel);
    }

    private boolean isBetaRelease(JSONObject release) {
        String tag = release.optString("tag_name");
        return release.optBoolean("prerelease") || tag.contains("-beta-");
    }

    private JSONObject findAsset(JSONArray assets, String name) {
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !name.equals(asset.optString("name"))) continue;
            return asset;
        }
        return null;
    }

    private Update readGithubReleaseUpdate(String channel, JSONObject release) {
        JSONObject asset = findAsset(release.optJSONArray("assets"), getManifestName(channel));
        long assetId = asset == null ? 0 : asset.optLong("id");
        if (assetId <= 0) {
            // 资产名对不上（例如 APK_SUFFIX 少了 -a6）时这里是**静默**返回的：不设 error，最终显示
            // 「已是最新」。这不是网络失败，不该报「更新失败」，但必须留痕，否则查不到。
            SpiderDebug.log("update", "asset missing channel=%s want=%s tag=%s", channel, getManifestName(channel), release.optString("tag_name"));
            return Update.empty(channel);
        }
        return readUpdate(channel, Github.getReleaseAssetApi(assetId), GITHUB_ASSET_HEADERS, release.optString("body"));
    }

    private Update readUpdate(String channel, String manifestUrl, Map<String, String> headers, String fallbackNotes) {
        Update update = Update.empty(channel);
        try {
            String text = UpdateHttp.string(manifestUrl, headers, GITHUB_REQUEST_TIMEOUT_MS);
            if (TextUtils.isEmpty(text)) throw new IllegalStateException("Empty update manifest: " + manifestUrl);
            JSONObject object = new JSONObject(text);
            update.name = object.optString("name");
            update.versionName = object.optString("versionName");
            update.desc = normalizeText(object.optString("desc"));
            update.notes = normalizeText(object.optString("notes"));
            update.channel = object.optString("channel", channel);
            update.code = object.optInt("code");
            update.apk = object.optString("apk");
            update.size = object.optLong("size");
            update.sha256 = object.optString("sha256");
            parseDownloads(object, update);
            if (isDefaultReleaseNotes(update.notes)) update.notes = "";
            if (TextUtils.isEmpty(update.notes) && TextUtils.isEmpty(update.desc)) {
                String notes = TextUtils.isEmpty(fallbackNotes) ? getReleaseNotes(update.name) : fallbackNotes;
                if (!TextUtils.isEmpty(notes)) update.notes = normalizeText(notes);
            }
        } catch (Exception e) {
            // 这里失败会给 Update 设 error ⇒ 是「更新失败」的两条来源之一。清单那一跳是
            // api.github.com/.../releases/assets/<id> → 302 → release-assets.githubusercontent.com，
            // 而**检查链路完全没有代理**（UpdateHttp 是裸 OkHttp；GithubProxy 只在下载的
            // UpdateRoutePlanner.addGithub 里被解析）⇒ 需要代理才能访问 GitHub 的用户，下载能行、检查必失败。
            SpiderDebug.log("update", "manifest failed url=%s cause=%s", manifestUrl, describe(e));
            SpiderDebug.log("update", e);
            update.error = TextUtils.isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
        }
        return update;
    }

    private void parseDownloads(JSONObject object, Update update) {
        JSONObject downloads = object.optJSONObject("downloads");
        JSONObject github = downloads == null ? null : downloads.optJSONObject("github");
        update.githubUrl = github == null ? "" : github.optString("url");
        if (TextUtils.isEmpty(update.githubUrl)) update.githubUrl = getGithubApkUrl(update);
        update.apkUrl = update.githubUrl;
        JSONObject oci = downloads == null ? null : downloads.optJSONObject("oci");
        if (oci == null) return;
        OciArtifact artifact = new OciArtifact(
                oci.optString("registry"),
                oci.optString("repository"),
                oci.optString("reference"),
                oci.optString("manifestDigest"),
                oci.optString("layerDigest"),
                oci.optLong("size", update.size));
        String apkDigest = TextUtils.isEmpty(update.sha256) ? "" : "sha256:" + update.sha256.toLowerCase(Locale.ROOT);
        if (artifact.isValid() && (apkDigest.isEmpty() || apkDigest.equals(artifact.layerDigest)) && (update.size <= 0 || update.size == artifact.size)) update.oci = artifact;
    }

    private String normalizeText(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace("\\'", "'");
    }

    private String getManifestName(String channel) {
        return getAssetName(channel, "json");
    }

    private String getDefaultApkName(String channel) {
        return getAssetName(channel, "apk");
    }

    private String getAssetName(String channel, String ext) {
        // The channel is already baked into this build's APK_SUFFIX (the workflow publishes "-a6"
        // for stable and "-beta-a6" for beta), so appending another "-beta" here would ask for an
        // asset name CI never produces. The channel still decides which *release* to read: the
        // stable path uses /releases/latest, the beta path scans for isBetaRelease().
        return getName() + "." + ext;
    }

    private String getGithubApkUrl(Update update) {
        String apk = TextUtils.isEmpty(update.apk) ? getDefaultApkName(update.channel) : update.apk;
        if (apk.startsWith("http://") || apk.startsWith("https://")) return apk;
        return TextUtils.isEmpty(update.name) ? "" : Github.getGithubReleaseAsset(update.name, getFileName(apk, update.channel));
    }

    private String getFileName(String value, String channel) {
        int query = value.indexOf('?');
        if (query >= 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        String name = slash >= 0 ? value.substring(slash + 1) : value;
        return TextUtils.isEmpty(name) ? getDefaultApkName(channel) : name;
    }

    private boolean isDefaultReleaseNotes(String notes) {
        return !TextUtils.isEmpty(notes) && DEFAULT_RELEASE_NOTES.equals(notes.trim());
    }

    private String getReleaseNotes(String tag) {
        if (TextUtils.isEmpty(tag)) return "";
        String notes = readReleaseNotes(tag);
        if (!TextUtils.isEmpty(notes) || tag.startsWith("v")) return notes;
        return readReleaseNotes("v" + tag);
    }

    private String readReleaseNotes(String tag) {
        try {
            return new JSONObject(UpdateHttp.string(Github.getReleaseApi(tag), GITHUB_API_HEADERS, GITHUB_REQUEST_TIMEOUT_MS)).optString("body");
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasErrorOnly() {
        return !stable.hasManifest() && !beta.hasManifest() && (!TextUtils.isEmpty(stable.error) || !TextUtils.isEmpty(beta.error));
    }

    private void show(FragmentActivity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        bind(activity);
        dismiss();
        Notify.dismissToast();
        String channel = selected == null ? Update.CHANNEL_STABLE : selected.channel;
        dialog = UpdateDialog.create().stable(stable).beta(beta).selected(channel).listener(this).show(activity);
    }

    @Override
    public void onConfirm(View view) {
        if (selected == null || !selected.hasUpdate()) {
            Notify.show(R.string.update_latest);
            return;
        }
        view.setEnabled(false);
        downloading = true;
        canceled = false;
        routes = getRoutes(selected);
        routeIndex = 0;
        if (routes.isEmpty()) {
            downloading = false;
            view.setEnabled(true);
            Notify.show(R.string.update_download_source_unavailable);
            return;
        }
        resetProgress();
        Path.clear(getFile());
        setDialogProgress(0, 0, selected.size, 0, 0);
        startNextDownload();
    }

    private List<UpdateTarget> getRoutes(Update update) {
        try {
            GithubProxy.Config github = GithubProxy.resolve(Setting.getUpdateGithubProxy(), Setting.getUpdateGithubProxyUrl(), Setting.getUpdateGithubProxyMode());
            String endpoint = update.oci == null ? "" : OciMirror.resolve(Setting.getUpdateOciMirror(), Setting.getUpdateOciMirrorUrl(), update.oci);
            return UpdateRoutePlanner.plan(Setting.getUpdateSource(), update.githubUrl, update.oci, github, endpoint);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void startNextDownload() {
        if (routes == null || routeIndex >= routes.size()) return;
        UpdateTarget target = routes.get(routeIndex++);
        transfer = target.kind == UpdateTarget.Kind.OCI ? new OciUpdateTransfer(target, getFile()) : new HttpUpdateTransfer(target.url, getFile(), selected == null ? 0 : selected.size);
        transfer.start(this);
    }

    private boolean retryFallback() {
        if (canceled || selected == null || routes == null || routeIndex >= routes.size()) return false;
        Path.clear(getFile());
        resetProgress();
        setDialogProgress(0, 0, selected.size, 0, 0);
        startNextDownload();
        return true;
    }

    @Override
    public void onCancel(View view) {
        if (downloading) {
            canceled = true;
            downloading = false;
            if (transfer != null) transfer.cancel();
            transfer = null;
            routes = null;
            resetProgress();
            Notify.show(R.string.update_canceled);
            dismiss();
            return;
        }
        Setting.putUpdate(false);
        if (transfer != null) transfer.cancel();
        transfer = null;
        dismiss();
    }

    @Override
    public void onClose() {
        dialog = null;
    }

    @Override
    public void onChannel(String channel) {
        selected = Update.CHANNEL_BETA.equals(channel) ? beta : stable;
    }

    private void dismiss() {
        try {
            if (dialog != null) dialog.dismissAllowingStateLoss();
        } catch (Exception ignored) {
        } finally {
            dialog = null;
        }
    }

    @Override
    public void progress(int progress, long bytes, long total, long speed, long elapsed) {
        setDialogProgress(progress, bytes, total, speed, elapsed);
    }

    private void setDialogProgress(int progress, long bytes, long total, long speed, long elapsed) {
        if (canceled || !downloading) return;
        long manifestSize = selected == null ? 0 : selected.size;
        if (total <= 0 && manifestSize > 0) total = manifestSize;
        if (progress < 0 && total > 0 && bytes > 0) progress = (int) (bytes * 100.0 / total);
        lastProgress = progress;
        lastBytes = bytes;
        lastTotal = total;
        lastSpeed = speed;
        lastElapsed = elapsed;
        if (dialog == null) return;
        if (!dialog.setProgress(progress, bytes, total, speed, elapsed)) dialog = null;
    }

    @Override
    public void error(String msg) {
        if (canceled) return;
        transfer = null;
        if (retryFallback()) return;
        downloading = false;
        routes = null;
        resetProgress();
        Notify.show(msg);
        dismiss();
    }

    @Override
    public void success(File file) {
        if (canceled) return;
        transfer = null;
        Update target = selected;
        Task.execute(() -> {
            String error = validate(file, target);
            App.post(() -> {
                if (canceled) return;
                downloading = false;
                resetProgress();
                if (!TextUtils.isEmpty(error)) {
                    Path.clear(file);
                    downloading = true;
                    if (retryFallback()) return;
                    downloading = false;
                    routes = null;
                    Notify.show(error);
                    dismiss();
                    return;
                }
                routes = null;
                FileUtil.openFile(file);
                dismiss();
            });
        });
    }

    private void restoreDialog(FragmentActivity activity) {
        if (!downloading || selected == null) return;
        show(activity);
        setDialogProgress(lastProgress, lastBytes, lastTotal, lastSpeed, lastElapsed);
    }

    private String validate(File file, Update update) {
        if (file == null || !file.exists() || file.length() <= 0) return ResUtil.getString(R.string.update_download_invalid);
        if (update != null && update.size > 0 && file.length() != update.size) return ResUtil.getString(R.string.update_download_incomplete);
        if (update != null && !TextUtils.isEmpty(update.sha256) && !update.sha256.equalsIgnoreCase(sha256(file))) return ResUtil.getString(R.string.update_download_checksum);
        if (!validatePackage(file, update)) return ResUtil.getString(R.string.update_download_identity);
        return "";
    }

    private boolean validatePackage(File file, Update update) {
        try {
            PackageManager manager = App.get().getPackageManager();
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            PackageInfo installed = manager.getPackageInfo(BuildConfig.APPLICATION_ID, flags);
            if (archive == null || installed == null || !BuildConfig.APPLICATION_ID.equals(archive.packageName)) return false;
            long archiveCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? archive.getLongVersionCode() : archive.versionCode;
            if (update != null && update.code > 0 && archiveCode != update.code) return false;
            if (update != null && !TextUtils.isEmpty(update.versionName) && !update.versionName.equals(archive.versionName)) return false;
            return signaturesMatch(installed, archive);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean signaturesMatch(PackageInfo installed, PackageInfo archive) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (installed.signingInfo == null || archive.signingInfo == null) return false;
            if (installed.signingInfo.hasMultipleSigners() || archive.signingInfo.hasMultipleSigners()) {
                return fingerprints(installed.signingInfo.getApkContentsSigners()).equals(fingerprints(archive.signingInfo.getApkContentsSigners()));
            }
            Set<String> current = fingerprints(installed.signingInfo.getApkContentsSigners());
            Set<String> candidateHistory = fingerprints(archive.signingInfo.getSigningCertificateHistory());
            return !current.isEmpty() && candidateHistory.containsAll(current);
        }
        return fingerprints(installed.signatures).equals(fingerprints(archive.signatures));
    }

    private Set<String> fingerprints(Signature[] signatures) {
        Set<String> values = new HashSet<>();
        if (signatures == null) return values;
        for (Signature signature : signatures) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                values.add(Arrays.toString(digest.digest(signature.toByteArray())));
            } catch (Exception ignored) {
            }
        }
        return values;
    }

    private String sha256(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            StringBuilder builder = new StringBuilder();
            for (byte value : digest.digest()) builder.append(String.format(Locale.ROOT, "%02x", value));
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void bind(FragmentActivity activity) {
        if (activity == null) return;
        FragmentActivity old = activityRef == null ? null : activityRef.get();
        if (old == activity) return;
        if (old != null) old.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = new WeakReference<>(activity);
        activity.getLifecycle().addObserver(lifecycleObserver);
    }

    private void unbind(FragmentActivity activity) {
        FragmentActivity current = activityRef == null ? null : activityRef.get();
        if (current != activity) return;
        activity.getLifecycle().removeObserver(lifecycleObserver);
        activityRef = null;
        if (!downloading) dialog = null;
    }

    private void resetProgress() {
        lastProgress = -1;
        lastBytes = 0;
        lastTotal = 0;
        lastSpeed = 0;
        lastElapsed = 0;
    }
}
