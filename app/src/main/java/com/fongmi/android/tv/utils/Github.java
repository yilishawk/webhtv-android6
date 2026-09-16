package com.fongmi.android.tv.utils;

public class Github {

    // This is a fork that ships its own builds. The in-app update check MUST look at our own
    // releases: pointing it at upstream (fish2018/webhtv) would offer an APK built for minSdk 24
    // that carries none of the Android 6 patches, and Update.hasUpdate() compares with "!="
    // rather than ">", so any upstream bump would be advertised as an update to our users.
    // Keep every repository reference derived from this one constant.
    private static final String REPO = "yilishawk/webhtv-android6";
    private static final String GITHUB_LATEST = "https://github.com/" + REPO + "/releases/latest/download";
    private static final String GITHUB_RELEASE = "https://github.com/" + REPO + "/releases/download";
    private static final String GITHUB_API = "https://api.github.com/repos/" + REPO + "/releases/tags";
    private static final String GITHUB_RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases";
    private static final String GITHUB_RELEASE_ASSETS_API = "https://api.github.com/repos/" + REPO + "/releases/assets";
    // Was "https://cnb.cool/fish2035/webhtv-release/-/git/raw/main" — a third-party release
    // mirror belonging to someone else. This fork has no CNB mirror of its own, so the mirror
    // helpers now derive from our own repository. They are still unused (no callers in this
    // fork), but re-wiring them can no longer reach a stranger's release feed.
    private static final String CNB = "https://github.com/" + REPO + "/releases/latest/download";

    public static String getCnbAsset(String name) {
        return CNB + "/" + name;
    }

    public static String getGithubLatestAsset(String name) {
        return GITHUB_LATEST + "/" + name;
    }

    public static String getGithubReleaseAsset(String tag, String name) {
        return GITHUB_RELEASE + "/" + tag + "/" + name;
    }

    public static String getJson(String name) {
        return getCnbAsset(name + ".json");
    }

    public static String getJson(String name, String channel) {
        if ("beta".equals(channel)) return getCnbAsset(name + "-beta.json");
        return getJson(name);
    }

    public static String getApk(String name) {
        return getCnbAsset(name + ".apk");
    }

    public static String getApk(String name, String channel) {
        if ("beta".equals(channel)) return getCnbAsset(name + "-beta.apk");
        return getApk(name);
    }

    public static String getAsset(String name, String channel) {
        return getCnbAsset(name);
    }

    public static String getReleaseApi(String tag) {
        return GITHUB_API + "/" + tag;
    }

    public static String getReleasesApi() {
        return GITHUB_RELEASES_API + "?per_page=20";
    }

    public static String getLatestReleaseApi() {
        return GITHUB_RELEASES_API + "/latest";
    }

    public static String getReleaseAssetApi(long id) {
        return GITHUB_RELEASE_ASSETS_API + "/" + id;
    }
}
