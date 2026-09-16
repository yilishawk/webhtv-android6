package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.update.OciArtifact;
import com.fongmi.android.tv.utils.AppVersion;

public class Update {

    public static final String CHANNEL_STABLE = "stable";
    public static final String CHANNEL_BETA = "beta";

    public String channel;
    public String name;
    public String versionName;
    public String desc;
    public String notes;
    public String apk;
    public String apkUrl;
    public String githubUrl;
    public String error;
    public String sha256;
    public OciArtifact oci;
    public int code;
    public long size;

    public static Update empty(String channel) {
        Update update = new Update();
        update.channel = channel;
        return update;
    }

    // 失败通道必须带上非空 error。hasErrorOnly() 用 TextUtils.isEmpty(error) 判空，而除了
    // TimeoutException，多数 IOException/JSONException 的 message 都是 null —— 直接取 getMessage()
    // 会让一次真实失败退化成「什么都没发生」，最终显示成「已是最新」，与真·最新无法区分。
    // 没有 message 时退回类名（Notify.getError 用的是同一套兜底思路）。
    public static Update error(String channel, Throwable e) {
        Update update = empty(channel);
        update.error = TextUtils.isEmpty(e.getMessage()) ? e.getClass().getSimpleName() : e.getMessage();
        return update;
    }

    public boolean isBeta() {
        return CHANNEL_BETA.equals(channel);
    }

    public boolean hasManifest() {
        return !TextUtils.isEmpty(name) && (!TextUtils.isEmpty(githubUrl) || oci != null && oci.isValid());
    }

    public boolean hasUpdate() {
        if (!hasManifest()) return false;
        return code != BuildConfig.VERSION_CODE || !AppVersion.isCurrent(name);
    }

    public String getText() {
        if (!TextUtils.isEmpty(notes)) return notes;
        if (!TextUtils.isEmpty(desc)) return desc;
        return "";
    }
}
