package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.palette.graphics.Palette;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ViewWallBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.IOException;

import pl.droidsonroids.gif.GifDrawable;

public class CustomWallView extends FrameLayout implements DefaultLifecycleObserver {

    private static final int DEFAULT_WALL_COLOR = Setting.getBuiltInWallColor(Setting.WALL_DREAM_PURPLE);
    private static final int GREEN_WALL_COLOR = 0xFF40C090;
    /**
     * 静态壁纸的解码下限（像素）。**内置设计墙与用户自定义墙共用这一套策略**：
     * 目标边长 = {@code max(本值, 屏幕长边 / 2)}，并且统一 ARGB_8888。
     *
     * <ul>
     *   <li>壁纸只是被大面积 UI 遮住的背景。内置渐变墙全尺寸 ARGB_8888 要 7.91 MB，
     *       降到半分辨率（1/4 像素）实测平均误差 0.05~0.17/255，肉眼无差；
     *       4K 屏上不降（长边/2 已经 ≥ 1920）。</li>
     *   <li>⛔ <b>不要用 RGB_565 省内存。</b> 在 1920×1080 真实插画上实测：
     *       565 的最大差只有 5/255，但平滑天空与雾带出现<b>明显块状色带</b> ——
     *       伤害是空间上的<b>阶梯</b>，不是每像素误差能反映的；
     *       而半分辨率 ARGB_8888 的<b>平均</b>误差反而更低（1.25 vs 2.26）且无可见瑕疵。
     *       证据：{@code apk-check/asset-preview/zoom_sky_565_vs_half.png}、
     *       {@code zoom_forest_565_vs_half.png}。</li>
     *   <li>⛔ 别按「绝对上限」写死：手机壁纸是 1080×2400（竖屏），写死 960 会算出
     *       sampleSize=4 ⇒ 270×600，属于过度降采样。</li>
     * </ul>
     */
    private static final int MIN_WALL_SIDE = 960;
    private static final int TYPE_RES = 0;
    private static final int TYPE_GIF = 1;
    private static final int TYPE_VIDEO = 2;
    private ViewWallBinding binding;
    private GifDrawable drawable;
    private PlayerView video;
    private ExoPlayer player;
    private final Runnable refreshRunnable = this::refresh;
    private boolean observerAdded;
    private boolean motionEnabled = true;

    public CustomWallView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomWallView setMotionEnabled(boolean motionEnabled) {
        this.motionEnabled = motionEnabled;
        return this;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (isInEditMode()) return;
        boolean loadedPlaceholder = false;
        if (binding == null) {
            binding = ViewWallBinding.inflate(LayoutInflater.from(getContext()), this, true);
            loadPlaceholder();
            loadedPlaceholder = true;
        }
        if (!observerAdded) {
            ((ComponentActivity) getContext()).getLifecycle().addObserver(this);
            observerAdded = true;
        }
        removeCallbacks(refreshRunnable);
        if (loadedPlaceholder && isStaticBuiltInWall()) theme();
        else post(refreshRunnable);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refreshRunnable);
        super.onDetachedFromWindow();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.WALL) refresh();
    }

    private void refresh() {
        if (!isReady()) return;
        long start = System.currentTimeMillis();
        stop();
        load();
        theme();
        SpiderDebug.log("startup", "wall refresh cost=%sms", System.currentTimeMillis() - start);
    }

    private boolean isReady() {
        return binding != null && binding.image != null && isAttachedToWindow();
    }

    /**
     * 只查绑定是否就绪，**不含 {@code isAttachedToWindow()}**。
     *
     * <p>⚠️ 为什么需要它：{@link #loadPlaceholder()} 会在 {@code onAttachedToWindow()} 内部
     * 调用 {@link #loadRes(int)} / {@link #loadDesign(int)} / {@link #loadColor(int)}；
     * 而 {@code onAttachedToWindow()} 对<b>静态</b>内置墙只调 {@code theme()}、
     * <b>不</b> post {@code refresh()}。也就是说这几个 setter 一旦因为
     * {@code isAttachedToWindow()} 为假而提前返回，壁纸会<b>永久不显示</b>，没有第二次机会。
     * 给已解绑的 view 设 drawable 本身无害，所以这几个 setter 只查绑定。
     */
    private boolean bound() {
        return binding != null && binding.image != null;
    }

    private void stop() {
        if (player != null && player.isPlaying()) {
            player.stop();
            player.clearMediaItems();
        }
        if (video != null) {
            video.setPlayer(null);
            video.setVisibility(GONE);
        }
        if (drawable != null) {
            drawable.stop();
            drawable.recycle();
            drawable = null;
        }
    }

    private void load() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (isBuiltInColor(wall, type)) loadColor(Setting.getBuiltInWallColor(wall));
        else if (isBuiltInDesign(wall, type)) loadDesign(wall);
        else if (isGreen(wall, type)) loadRes(R.drawable.wallpaper_1);
        else if (motionEnabled && type == TYPE_VIDEO) loadVideo(FileUtil.getWall(wall));
        else if (motionEnabled && type == TYPE_GIF) loadGif(FileUtil.getWall(wall));
        else loadImage();
    }

    private void theme() {
        int newColor = getWallColor();
        int oldColor = Setting.getWallColor();
        if (newColor == oldColor) return;
        Setting.putWallColor(newColor);
        if (Setting.getThemeColor() == 0) RefreshEvent.theme();
    }

    /**
     * 采样解码并设置内置壁纸资源。
     * ⚠️ 守卫用 {@link #bound()} 而不是 {@link #isReady()} —— 理由见 {@code bound()}。
     */
    private void loadRes(int resId) {
        if (!bound()) return;
        Bitmap bitmap = decodeBuiltinWall(resId);
        if (bitmap != null) binding.image.setImageDrawable(new BitmapDrawable(getResources(), bitmap));
        else binding.image.setImageResource(resId);
    }

    /**
     * 采样解码内置壁纸资源。失败（OOM / 解码不出来）返回 null，调用方退回 {@code setImageResource}。
     */
    private Bitmap decodeBuiltinWall(int resId) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeResource(getResources(), resId, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = wallSampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeResource(getResources(), resId, options);
        } catch (OutOfMemoryError | RuntimeException e) {
            SpiderDebug.log("startup", "builtin wall decode fallback res=%s error=%s", resId, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 静态壁纸的统一采样倍率：目标边长 = max({@link #MIN_WALL_SIDE}, 屏幕长边 / 2)。
     * 内置设计墙与用户自定义墙共用同一套，保证两条路径的解码开销一致。
     */
    private int wallSampleSize(int srcWidth, int srcHeight) {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int target = Math.max(MIN_WALL_SIDE, Math.max(metrics.widthPixels, metrics.heightPixels) / 2);
        int sampleSize = 1;
        while (srcWidth / sampleSize > target || srcHeight / sampleSize > target) sampleSize *= 2;
        return sampleSize;
    }

    private void loadColor(int color) {
        if (!bound()) return;
        binding.image.setImageDrawable(new ColorDrawable(color));
    }

    private void loadDesign(int wall) {
        if (!bound()) return;
        int resId = getDesignResId(wall);
        if (resId != 0) loadRes(resId);
        else loadColor(Setting.getBuiltInWallColor(wall));
    }

    private void loadImage() {
        if (!isReady()) return;
        Drawable cache = cache();
        if (cache != null) binding.image.setImageDrawable(cache);
        else loadPlaceholder();
    }

    /**
     * 首帧占位壁纸（{@code onAttachedToWindow} 里 inflate 之后立刻调用，是冷启动最先画出来的东西）。
     *
     * <p>⛔ 绿色墙这里曾经用 {@code setImageResource(R.drawable.wallpaper_1)} —— 全尺寸 ARGB_8888 解码，
     * 和 {@link #load()} 里同一张图的 {@link #loadRes(int)} 路径**不一致**（那条走采样解码 + OOM 兜底）。
     * 统一走 {@code loadRes}，两条路径行为一致。
     */
    private void loadPlaceholder() {
        if (binding == null || binding.image == null) return;
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        Drawable cache = cache();
        if (isBuiltInColor(wall, type)) binding.image.setImageDrawable(new ColorDrawable(Setting.getBuiltInWallColor(wall)));
        else if (isBuiltInDesign(wall, type)) loadDesign(wall);
        else if (isGreen(wall, type)) loadRes(R.drawable.wallpaper_1);
        else if (cache != null) binding.image.setImageDrawable(cache);
        else binding.image.setImageDrawable(new ColorDrawable(DEFAULT_WALL_COLOR));
    }

    private int getDesignResId(int wall) {
        return switch (wall) {
            case Setting.WALL_AURORA_GLASS -> R.drawable.wallpaper_design_10_aurora_glass;
            case Setting.WALL_SUNSET_PRISM -> R.drawable.wallpaper_design_11_sunset_prism;
            case Setting.WALL_MINT_GLACIER -> R.drawable.wallpaper_design_12_mint_glacier;
            case Setting.WALL_LIQUID_CHROME -> R.drawable.wallpaper_design_13_liquid_chrome;
            case Setting.WALL_NEON_BERRY -> R.drawable.wallpaper_design_14_neon_berry;
            case Setting.WALL_CHAMPAGNE_MIST -> R.drawable.wallpaper_design_15_champagne_mist;
            case Setting.WALL_GLASS_GRADIENT -> R.drawable.wallpaper_design_16_glass_gradient;
            case Setting.WALL_DEEP_SPACE_GLASS -> R.drawable.wallpaper_design_17_deep_space_glass;
            case Setting.WALL_POLAR_LIGHT_GLASS -> R.drawable.wallpaper_design_18_polar_light_glass;
            case Setting.WALL_NEON_CYBER -> R.drawable.wallpaper_design_19_neon_cyber;
            case Setting.WALL_WARM_MOON_GLASS -> R.drawable.wallpaper_design_20_warm_moon_glass;
            case Setting.WALL_CRYSTAL_SKY -> R.drawable.wallpaper_design_21_crystal_sky;
            case Setting.WALL_DREAM_PURPLE -> R.drawable.wallpaper_design_22_dream_purple;
            case Setting.WALL_SKY_MINT -> R.drawable.wallpaper_design_23_sky_mint;
            case Setting.WALL_FOREST_MIST -> R.drawable.wallpaper_design_24_forest_mist;
            case Setting.WALL_DAYLIGHT_MINIMAL -> R.drawable.wallpaper_design_25_daylight_minimal;
            case Setting.WALL_DEEP_SEA -> R.drawable.wallpaper_design_26_deep_sea;
            case Setting.WALL_VIOLET_SMOKE -> R.drawable.wallpaper_design_27_violet_smoke;
            case Setting.WALL_ROSE_VEIL -> R.drawable.wallpaper_design_28_rose_veil;
            case Setting.WALL_EMERALD_AURORA -> R.drawable.wallpaper_design_29_emerald_aurora;
            case Setting.WALL_BLUE_SILK -> R.drawable.wallpaper_design_30_blue_silk;
            case Setting.WALL_PEACH_DAWN -> R.drawable.wallpaper_design_31_peach_dawn;
            case Setting.WALL_GRAPHITE_SMOKE -> R.drawable.wallpaper_design_32_graphite_smoke;
            case Setting.WALL_PASTEL_PRISM -> R.drawable.wallpaper_design_33_pastel_prism;
            case Setting.WALL_MIDNIGHT_MOON -> R.drawable.wallpaper_design_34_midnight_moon;
            case Setting.WALL_CYAN_CRYSTAL -> R.drawable.wallpaper_design_35_cyan_crystal;
            case Setting.WALL_LAVENDER_CRYSTAL -> R.drawable.wallpaper_design_36_lavender_crystal;
            default -> 0;
        };
    }

    private void loadVideo(File file) {
        if (!isReady()) return;
        ensurePlayer();
        ensureVideoView();
        video.setPlayer(player);
        video.setVisibility(VISIBLE);
        binding.image.setImageDrawable(cache());
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)));
        player.prepare();
    }

    private void loadGif(File file) {
        if (!isReady()) return;
        drawable = gif(file);
        if (drawable != null) binding.image.setImageDrawable(drawable);
        else loadImage();
    }

    private Drawable cache() {
        File file = FileUtil.getWallCache();
        Bitmap bitmap = file.exists() ? decodeWallBitmap(file) : null;
        return bitmap == null ? null : new BitmapDrawable(getResources(), bitmap);
    }

    /**
     * 解码用户自定义壁纸（{@code FileUtil.getWallCache()}）。
     *
     * <p>⚠️ 这个缓存文件由 {@code WallConfig.setSnapshot()} 写出，**本身就是屏幕尺寸的 JPEG**
     * （{@code override(screenWidth, screenHeight)}）⇒ 若不走采样，sampleSize 恒为 1。
     *
     * <p>⛔ 这里曾经用 {@code RGB_565 + inDither}。两个问题：
     * <ol>
     *   <li>{@code inDither} 在 Android 上**是被忽略的**（API 24 起正式废弃），
     *       所以实际就是裸 565 ⇒ 用户自己的壁纸（照片 / 插画）会出现色带。</li>
     *   <li>1080p 全屏 565 要 4.15 MB；改成与内置墙同一套采样 + ARGB_8888 后只要 1.98 MB
     *       —— <b>更省内存，而且没有色带</b>。</li>
     * </ol>
     */
    private Bitmap decodeWallBitmap(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = wallSampleSize(bounds.outWidth, bounds.outHeight);
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (OutOfMemoryError | RuntimeException e) {
            SpiderDebug.log("startup", "wall bitmap decode fallback file=%s error=%s", file.getName(), e.getClass().getSimpleName());
            return null;
        }
    }

    private GifDrawable gif(File file) {
        try {
            return new GifDrawable(file);
        } catch (IOException e) {
            return null;
        }
    }

    private void ensurePlayer() {
        if (player != null) return;
        player = new ExoPlayer.Builder(getContext()).build();
        player.setRepeatMode(Player.REPEAT_MODE_ALL);
        player.setPlayWhenReady(true);
        player.mute();
    }

    private void ensureVideoView() {
        if (video != null) return;
        video = (PlayerView) LayoutInflater.from(getContext()).inflate(R.layout.view_wall_video, this, false);
        addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private boolean hasVideo() {
        return player != null && video != null && video.getVisibility() == VISIBLE && player.getMediaItemCount() > 0;
    }

    private int getWallColor() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        if (type == TYPE_RES && Setting.isBuiltInWall(wall)) return Setting.getBuiltInWallColor(wall);
        if (isGreen(wall, type)) return GREEN_WALL_COLOR;
        File file = FileUtil.getWallCache();
        return file.exists() ? paletteColor(file) : DEFAULT_WALL_COLOR;
    }

    private int paletteColor(File file) {
        Bitmap bitmap = decodeBitmap(file);
        if (bitmap == null) return DEFAULT_WALL_COLOR;
        Palette palette = Palette.from(bitmap).maximumColorCount(8).generate();
        bitmap.recycle();
        return swatchColor(palette);
    }

    private Bitmap decodeBitmap(File file) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 8;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    }

    private int swatchColor(Palette palette) {
        Palette.Swatch swatch = palette.getVibrantSwatch();
        if (swatch == null) swatch = palette.getDominantSwatch();
        return swatch != null ? swatch.getRgb() : DEFAULT_WALL_COLOR;
    }

    private boolean isBuiltInColor(int wall, int type) {
        return type == TYPE_RES && Setting.isBuiltInColorWall(wall);
    }

    private boolean isBuiltInDesign(int wall, int type) {
        return type == TYPE_RES && Setting.isBuiltInDesignWall(wall);
    }

    private boolean isGreen(int wall, int type) {
        return type == TYPE_RES && wall == Setting.WALL_GREEN;
    }

    private boolean isStaticBuiltInWall() {
        int wall = Setting.getWall();
        int type = Setting.getWallType();
        return isBuiltInColor(wall, type) || isBuiltInDesign(wall, type) || isGreen(wall, type);
    }

    @Override
    public void onCreate(@NonNull LifecycleOwner owner) {
        EventBus.getDefault().register(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.start();
        if (!hasVideo()) return;
        video.setPlayer(player);
        player.play();
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        if (drawable != null) drawable.pause();
        if (!hasVideo()) return;
        video.setPlayer(null);
        player.pause();
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        removeCallbacks(refreshRunnable);
        EventBus.getDefault().unregister(this);
        if (drawable != null) drawable.recycle();
        if (video != null) removeView(video);
        if (player != null) player.release();
        observerAdded = false;
        drawable = null;
        binding = null;
        player = null;
        video = null;
    }
}
