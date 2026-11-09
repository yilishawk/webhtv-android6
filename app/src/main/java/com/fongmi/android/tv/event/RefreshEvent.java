package com.fongmi.android.tv.event;

import com.fongmi.android.tv.bean.Vod;

import org.greenrobot.eventbus.EventBus;

public class RefreshEvent {

    private final Type type;
    private String path;
    private Vod vod;

    public static void home() {
        EventBus.getDefault().post(new RefreshEvent(Type.HOME));
    }

    public static void category() {
        EventBus.getDefault().post(new RefreshEvent(Type.CATEGORY));
    }

    public static void history() {
        EventBus.getDefault().post(new RefreshEvent(Type.HISTORY));
    }

    public static void keep() {
        EventBus.getDefault().post(new RefreshEvent(Type.KEEP));
    }

    public static void size() {
        EventBus.getDefault().post(new RefreshEvent(Type.SIZE));
    }

    public static void theme() {
        EventBus.getDefault().post(new RefreshEvent(Type.THEME));
    }

    public static void language() {
        EventBus.getDefault().post(new RefreshEvent(Type.LANGUAGE));
    }

    public static void live() {
        EventBus.getDefault().post(new RefreshEvent(Type.LIVE));
    }

    public static void detail() {
        EventBus.getDefault().post(new RefreshEvent(Type.DETAIL));
    }

    public static void player() {
        EventBus.getDefault().post(new RefreshEvent(Type.PLAYER));
    }

    /**
     * 缓存内容发生变化（例如一次清理结束）。
     *
     * <p>设置页的缓存行只在收到通知时重读清单，否则清理结果要等退出再进入页面才看得到。</p>
     */
    public static void cache() {
        EventBus.getDefault().post(new RefreshEvent(Type.CACHE));
    }

    public static void subtitle(String path) {
        EventBus.getDefault().post(new RefreshEvent(Type.SUBTITLE, path));
    }

    public static void danmaku(String path) {
        EventBus.getDefault().post(new RefreshEvent(Type.DANMAKU, path));
    }

    public static void vod(Vod vod) {
        EventBus.getDefault().post(new RefreshEvent(Type.VOD, vod));
    }

    private RefreshEvent(Type type) {
        this.type = type;
    }

    public RefreshEvent(Type type, String path) {
        this.type = type;
        this.path = path;
    }

    private RefreshEvent(Type type, Vod vod) {
        this.type = type;
        this.vod = vod;
    }

    public Type getType() {
        return type;
    }

    public String getPath() {
        return path;
    }

    public Vod getVod() {
        return vod;
    }

    public enum Type {
        HOME, CATEGORY, HISTORY, KEEP, SIZE, THEME, LANGUAGE, LIVE, DETAIL, PLAYER, SUBTITLE, DANMAKU, VOD, CACHE
    }
}
