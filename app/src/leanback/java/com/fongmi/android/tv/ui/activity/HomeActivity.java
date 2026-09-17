package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomScroller;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.dialog.ExitConfirmDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.web.HomeWebController;
import com.fongmi.android.tv.web.WebHomeViewport;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.common.collect.Lists;
import com.google.gson.JsonObject;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener, TypeAdapter.OnClickListener, HomeWebController.Listener {

    private static final String TV_NORMAL = "tv-normal";
    private static final String TV_TOOLBAR_HIDDEN = "tv-toolbar-hidden";
    private static final String TV_OVERLAY = "tv-overlay";
    private static final String TV_FULL = "tv-full";

    private static final String HOME_TYPE_ID = "home";
    private static final int PENDING_NONE = 0;
    private static final int PENDING_HOME = 1;
    private static final int PENDING_CATEGORY = 2;
    /** 预览态翻页（第 2 页起）。与 PENDING_CATEGORY 分开：那条会 clearContentRows()，翻页必须**追加**。 */
    private static final int PENDING_CATEGORY_MORE = 3;

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;
    private SiteViewModel mViewModel;
    private TypeAdapter mTypeAdapter;
    private HomeWebController mWeb;
    private WebView mHomeWeb;
    private Result mResult;
    private Result mHomeResult;
    private Clock mClock;
    private String webChromeMode = TV_NORMAL;
    private String webDefaultChromeMode = TV_FULL;
    private boolean webToolbarVisible = true;
    private Class mHomeType;
    private List<Object> mHomeRows;
    private int pendingResult = PENDING_NONE;
    private boolean previewingCategory;
    /**
     * 预览态翻页。挂在 mBinding.recycler 上；非预览态 onLoadMore() 直接返回 false，什么都不做。
     * ⚠️ 在字段处就构造，不在 setRecyclerView() 里 new —— dropPreview() 可能被 ConfigEvent/RefreshEvent
     *    提前触发（那时 setRecyclerView() 还没跑），字段为 null 会让 reset() 直接 NPE。
     *    CustomScroller 的构造函数只存回调，不碰任何 View，所以在字段处构造是安全的。
     */
    private final CustomScroller mScroller = new CustomScroller(this::onLoadMore);
    /** 当前预览的分类 typeId（翻页要用）。null = 不在预览。 */
    private String mPreviewTypeId;
    /** 预览态网格的**最后一行**。它可能没填满，下一页要先把它补齐，否则每页都留一条半行。 */
    private ArrayObjectAdapter mPreviewLast;

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_App);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        SpiderDebug.log("startup", "home initView start cost=%sms", System.currentTimeMillis() - App.time());
        mResult = Result.empty();
        mHomeResult = Result.empty();
        mClock = Clock.create(mBinding.clock);
        mBinding.progressLayout.showProgress();
        setRecyclerView();
        setViewModel();
        setAdapter();
        runAfterFirstFrame(this::initAfterFirstFrame);
        SpiderDebug.log("startup", "home initView end cost=%sms", System.currentTimeMillis() - App.time());
    }

    private void initAfterFirstFrame() {
        SpiderDebug.log("startup", "home first frame cost=%sms", System.currentTimeMillis() - App.time());
        App.post(this::initConfig, 80);
        App.post(() -> PermissionUtil.requestFile(this, allGranted -> PermissionUtil.requestNotify(this)), 1800);
        App.post(() -> DLNARendererService.start(this), 2500);
    }

    private void runAfterFirstFrame(Runnable runnable) {
        View root = mBinding.getRoot();
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnPreDrawListener(this);
                root.post(runnable);
                return true;
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.toolbar.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            syncNativeContentInset();
            syncWebOverlayLayout();
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                // 预览期间适配器里没有「最近观看」/「推荐」标题行 ⇒ indexOf 返回 -1 ⇒ isTopRow() 恒为 true，
                // 不显式压掉的话，滚一下分类数据顶栏就又冒出来了。
                updateToolbarVisibility(isTopRow(position) && !previewingCategory);
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
        mBinding.typeRecycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                // 这里不能加 parent.hasFocus() 判断：实测它会让整个预览失效（焦点已经落在芯片上时仍为 false），
                // 而首帧那次 position=0 的选中本来就不需要拦（exitPreview 会自己 return）。
                if (child == null) return;
                SpiderDebug.log("home-chip", "type chip selected pos=%s focused=%s", position, parent.hasFocus());
                // 焦点停在分类芯片上（position>0）时连顶栏一起收起：nativeContent 的上边距是
                // toolbarHeight() 撑起来的（≈80dp = logo 48 + 上 24 + 下 8），只藏 title 不会让内容上移，
                // 撑高度的是 logo 和 padding。用芯片位置判、**不要**用 previewingCategory —— 后者由
                // mCategoryRunnable 延迟 100ms 才置位，会先显示一下再收起。
                updateToolbarVisibility(position <= 0);
                onTypeFocused(position);
            }
        });
        // 芯片行每次获焦都重算顶栏：否则从内容区按上键回到芯片行时选中项没变、上面那个回调不触发，
        // 顶栏会停在「可见」，预览态下又冒出爬虫名。
        mBinding.typeRecycler.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) updateToolbarVisibility(mBinding.typeRecycler.getSelectedPosition() <= 0);
        });
    }

    private void onTypeFocused(int position) {
        SpiderDebug.log("home-chip", "onTypeFocused pos=%s previewing=%s", position, previewingCategory);
        if (position <= 0) {
            App.removeCallbacks(mCategoryRunnable);
            App.post(mHomeRunnable, 100);
        } else {
            App.removeCallbacks(mHomeRunnable);
            App.post(mCategoryRunnable, 100);
        }
    }

    private final Runnable mHomeRunnable = new Runnable() {
        @Override
        public void run() {
            exitPreview();
        }
    };

    private final Runnable mCategoryRunnable = new Runnable() {
        @Override
        public void run() {
            int position = mBinding.typeRecycler.getSelectedPosition();
            if (position <= 0) return;
            // 只在「还没进预览」时拦：那时适配器里可能挂着 "progress" 占位（首页内容未落地，或
            // `首页无列表 → 自动打开首个分类` 那条路），快照会把它一起存进去，回到首页后卡一行永不消失的加载条。
            // 已经在预览里就不拦了 —— 预览期间适配器是空的（转圈由 progressLayout 负责），快照也已冻结，
            // 这时放行才能让快速扫芯片时「最后一个焦点」赢：每次 loadCategory 都会让 SiteViewModel
            // 取消上一个在飞请求并自增代次，陈旧结果根本到不了 onResult（见 SiteViewModel.execute）。
            if (!previewingCategory && pendingResult != PENDING_NONE) {
                SpiderDebug.log("home-chip", "preview skipped pending=%s", pendingResult);
                return;
            }
            loadCategory(mTypeAdapter.get(position));
        }
    };

    private Class getHomeType() {
        if (mHomeType == null) {
            mHomeType = new Class();
            mHomeType.setTypeId(HOME_TYPE_ID);
            mHomeType.setTypeName(ResUtil.getString(R.string.vod_home));
        }
        return mHomeType;
    }

    // 「首页」是本页自己合成的占位项，用引用判等，避免与真实分类里可能存在的 id=home 撞车
    private boolean isHomeChip(Class item) {
        return item != null && item == mHomeType;
    }

    // 预览 = 内容区里只剩该分类的数据行（功能按钮行也一起撤走，让分类数据顶到最上面，一眼可见）。
    // ⚠️ 预览期间适配器里**没有**「最近观看」/「推荐」标题行，而 getHistoryIndex()/getRecommendIndex()
    // 是 `indexOf(标题) + 1` 动态算出来的 —— 标题缺失时 indexOf 返回 -1，两个下标都会退化成 0，
    // 于是 removeItems(0, n) 会连功能按钮行一起删掉。所以凡是会碰这两个下标的地方，都先判 previewingCategory。
    private void clearContentRows() {
        if (mAdapter.size() > 0) mAdapter.removeItems(0, mAdapter.size());
    }

    // 进入预览前把整个适配器（功能按钮行 / 最近观看 / 推荐 / 首页视频行）快照下来。
    // 连续换分类时不重新快照，否则会把上一次的预览行当成首页内容存进去。
    private void enterPreview() {
        if (previewingCategory) return;
        mHomeRows = new ArrayList<>();
        for (int i = 0; i < mAdapter.size(); i++) mHomeRows.add(mAdapter.get(i));
        previewingCategory = true;
        // 兜住「首页无列表 → 自动打开首个分类」那条路：那时芯片行的选中回调不一定跑到，顶栏会留在可见。
        updateToolbarVisibility(false);
        SpiderDebug.log("home-chip", "enter preview snapshot rows=%s", mHomeRows.size());
    }

    // 退出预览：把快照原样放回，零网络、零重建。
    private void exitPreview() {
        if (!previewingCategory) return;
        previewingCategory = false;
        pendingResult = PENDING_NONE;
        // 翻页状态必须一起归零：否则下次进预览时页码还停在上次的 3，第一屏就要的是第 4 页。
        mPreviewTypeId = null;
        mPreviewLast = null;
        mScroller.reset();
        updateToolbarVisibility(true);   // 回到首页：把顶栏（爬虫名/时钟）连同上边距一起还回来
        SpiderDebug.log("home-chip", "exit preview restore rows=%s", mHomeRows == null ? 0 : mHomeRows.size());
        clearContentRows();
        if (mHomeRows != null) {
            mAdapter.addAll(0, mHomeRows);
            mHomeRows = null;
        }
        // 必须复位：请求还在飞时就把焦点移回「首页」，pendingResult 已归零 ⇒ 结果到达会被 onResult 早退，
        // 没人再去动 progressLayout，它会永远停在 PROGRESS（recycler INVISIBLE）⇒ 首页内容隐形。
        mBinding.progressLayout.showContent();
        getHistory();   // 预览期间若历史变过，这里补一次（上面已把 previewingCategory 置回 false）
    }

    // 整页重建（切爬虫/刷新）时丢弃预览状态，不还原快照 —— 快照属于上一份首页数据，已失效。
    // 同时把 progressLayout 复位到 CONTENT：预览可能把它留在 PROGRESS/EMPTY（那时 recycler 是 INVISIBLE），
    // 不复位的话整页重建完列表是隐形的，看起来像首页坏了。
    private void dropPreview() {
        previewingCategory = false;
        mHomeRows = null;
        mPreviewTypeId = null;
        mPreviewLast = null;
        mScroller.reset();
        updateToolbarVisibility(true);
        mBinding.progressLayout.showContent();
    }

    private void loadCategory(Class type) {
        if (type == null || type.getTypeId().isEmpty()) return;
        SpiderDebug.log("home-chip", "preview load key=%s tid=%s", getHome().getKey(), type.getTypeId());
        enterPreview();
        clearContentRows();
        // 换分类（或重进同一个）都要把翻页状态归零：页码回到 1，网格"最后一行"作废。
        // 不归零的话，从 A 分类翻到第 3 页再切到 B 分类，B 会直接从第 4 页开始要数据。
        mPreviewTypeId = type.getTypeId();
        mPreviewLast = null;
        mScroller.reset();
        // 用 progressLayout 自带的居中转圈（与分类页 TypeFragment 同一套），不往适配器里塞 "progress" 占位：
        // 预览期间适配器保持空，往下按就不会误落到一行占位上。它同时把 recycler 置为 INVISIBLE，
        // 所以必须在 exitPreview()/dropPreview() 里复位（见上面的 showContent()）。
        mBinding.progressLayout.showProgress();
        pendingResult = PENDING_CATEGORY;
        mViewModel.categoryContent(getHome().getKey(), type.getTypeId(), "1", true, new HashMap<>());
    }

    private void updateToolbarVisibility(boolean visible) {
        mBinding.toolbar.setVisibility(visible && webToolbarVisible ? View.VISIBLE : View.GONE);
        syncNativeContentInset();
        syncWebOverlayLayout();
    }

    private void syncNativeContentInset() {
        int top = isToolbarVisible() ? toolbarHeight() : 0;
        if (mBinding.nativeContent.getPaddingTop() == top) return;
        mBinding.nativeContent.setPadding(mBinding.nativeContent.getPaddingLeft(), top, mBinding.nativeContent.getPaddingRight(), mBinding.nativeContent.getPaddingBottom());
    }

    private void syncWebOverlayLayout() {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mBinding.webOverlay.getLayoutParams();
        int top = constrainWebBelowToolbar() ? toolbarHeight() : 0;
        if (params.topMargin == top) return;
        params.topMargin = top;
        mBinding.webOverlay.setLayoutParams(params);
    }

    private boolean constrainWebBelowToolbar() {
        return (TV_NORMAL.equals(webChromeMode) || TV_OVERLAY.equals(webChromeMode)) && isToolbarVisible();
    }

    private boolean isToolbarVisible() {
        return mBinding.toolbar.getVisibility() == View.VISIBLE;
    }

    private int toolbarHeight() {
        int height = mBinding.toolbar.getHeight();
        if (height <= 0) height = mBinding.toolbar.getMeasuredHeight();
        return height > 0 ? height : ResUtil.dp2px(80);
    }

    private boolean isTopRow(int position) {
        int history = mAdapter.indexOf(R.string.home_history);
        return history == -1 || position < history;
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16, FocusHighlight.ZOOM_FACTOR_SMALL, HorizontalGridView.FOCUS_SCROLL_ALIGNED), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
        // 预览态翻页：与分类页 TypeFragment 同一套机制（滚到底且 IDLE ⇒ onLoadMore）。
        // 非预览态 onLoadMore() 返回 false ⇒ 不推进页码、不置 loading，等于什么都没发生。
        mBinding.recycler.addOnScrollListener(mScroller);
        mBinding.typeRecycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.typeRecycler.setRowHeight(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.typeRecycler.setAdapter(mTypeAdapter = new TypeAdapter(this));
    }

    private void setWebView() {
        SpiderDebug.log("startup", "webview create start cost=%sms", System.currentTimeMillis() - App.time());
        mWeb = new HomeWebController(this, getHomeWeb(), this);
        mWeb.setViewport(tvViewport(webChromeMode));
        SpiderDebug.log("startup", "webview create end cost=%sms", System.currentTimeMillis() - App.time());
    }

    private void ensureWebView() {
        if (mWeb == null) setWebView();
    }

    private WebView getHomeWeb() {
        if (mHomeWeb != null) return mHomeWeb;
        mHomeWeb = new WebView(this);
        mHomeWeb.setFocusable(true);
        mHomeWeb.setFocusableInTouchMode(true);
        mHomeWeb.setVisibility(View.GONE);
        mBinding.webOverlay.addView(mHomeWeb, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return mHomeWeb;
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, this::onResult);
    }

    private void onResult(Result result) {
        if (result == null || pendingResult == PENDING_NONE) return;
        // 预览翻页的结果：**不清空、不重建**，只往末尾追加。
        // 必须与 PENDING_CATEGORY 分开走 —— 那条会 clearContentRows()，用它来翻页会把已经加载的前几页全删掉。
        if (pendingResult == PENDING_CATEGORY_MORE) {
            pendingResult = PENDING_NONE;
            if (!previewingCategory) return;   // 预览已退出 / 整页已重建 ⇒ 这一页作废
            mResult = result;
            appendPreviewPage(result);
            SpiderDebug.log("home-chip", "onResult more rows=%s total=%s", result.getList().size(), mAdapter.size());
            endPreviewLoading(result);
            return;
        }
        boolean category = pendingResult == PENDING_CATEGORY;
        pendingResult = PENDING_NONE;
        // ⚠️ 预览占位必须由 clearContentRows() 独家管理。这里若再 remove("progress") 一次，
        // 占位就先没了，紧接着 clearContentRows() 仍按旧计数再删一段 —— 会连带删掉数据行。
        if (previewingCategory) clearContentRows();
        else mAdapter.remove("progress");
        if (!category) {
            Cache.clear().put(result);
            setTypes(mHomeResult = result);
        }
        mResult = result;
        addVideo(result, category);
        SpiderDebug.log("home-chip", "onResult category=%s rows=%s preview=%s", category, result.getList().size(), previewingCategory);
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        mAdapter.add(new ListRow(mFuncAdapter = new ArrayObjectAdapter(new FuncPresenter(this))));
        mAdapter.add(R.string.home_history);
        mAdapter.add(R.string.home_recommend);
    }

    // 把适配器恢复到初始骨架：功能按钮行 + 「最近观看」标题 + 「推荐」标题。
    // ⚠️ 两个标题行必须存在 —— getHistoryIndex()/getRecommendIndex() 是靠 indexOf(标题) 定位的。
    private void resetContentRows() {
        mAdapter.clear();
        mAdapter.add(new ListRow(mFuncAdapter));
        mAdapter.add(R.string.home_history);
        mAdapter.add(R.string.home_recommend);
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void initConfig() {
        SpiderDebug.log("startup", "config load start cost=%sms", System.currentTimeMillis() - App.time());
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                SpiderDebug.log("startup", "config load success cost=%sms", System.currentTimeMillis() - App.time());
                showContent();
            }

            @Override
            public void error(String msg) {
                SpiderDebug.log("startup", "config load error cost=%sms msg=%s", System.currentTimeMillis() - App.time(), msg);
                Notify.show(msg);
                showContent();
            }
        };
    }

    private void showContent() {
        SpiderDebug.log("startup", "home showContent start cost=%sms", System.currentTimeMillis() - App.time());
        mBinding.progressLayout.showContent();
        checkAction(getIntent());
        setTitle();
        setLogo();
        setFunc();
        getHistory();
        getVideo();
        setFocus();
        App.post(this::prewarmWebView, 1500);
        SpiderDebug.log("startup", "home showContent end cost=%sms", System.currentTimeMillis() - App.time());
    }

    private void prewarmWebView() {
        if (isFinishing() || mWeb != null) return;
        boolean hasWebHome = VodConfig.get().getSites().stream().anyMatch(Site::hasHomePage);
        if (!hasWebHome) return;
        SpiderDebug.log("startup", "webview prewarm start cost=%sms", System.currentTimeMillis() - App.time());
        ensureWebView();
        SpiderDebug.log("startup", "webview prewarm end cost=%sms", System.currentTimeMillis() - App.time());
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mBinding.title.setSelected(true);
        mBinding.title.setFocusable(true);
        if (!mBinding.title.hasFocus()) mBinding.recycler.requestFocus();
    }

    private void getVideo() {
        getVideo(false);
    }

    private void getVideo(boolean forceNative) {
        if (!forceNative && getHome().hasHomePage()) {
            ensureWebView();
        }
        if (!forceNative && mWeb != null && mWeb.load(getHome())) {
            mBinding.typeRecycler.setVisibility(View.GONE);
            mBinding.recycler.setVisibility(View.GONE);
            mBinding.progressLayout.showContent();
            // 走网页首页时原生列表被隐藏，这里同样要把骨架复原：预览态下适配器里是没有
            // 「最近观看/推荐」标题的，留着会让后续 getHistory() 把下标算成 0。
            dropPreview();
            resetContentRows();
            pendingResult = PENDING_NONE;
            showWebOverlay();
            return;
        }
        if (mWeb != null) mWeb.hide();
        hideWebOverlay();
        applyTvChrome(TV_NORMAL);
        mBinding.recycler.setVisibility(View.VISIBLE);
        mResult = Result.empty();
        mHomeResult = Result.empty();
        // 预览中直接刷新时，适配器里只剩分类行（功能按钮行和标题行都被撤走了），所以必须整段重建 ——
        // clearRecommendRows() 那套在标题缺失时会把下标算成 0，连功能按钮行一起删。
        dropPreview();
        resetContentRows();
        mBinding.typeRecycler.setSelectedPosition(0);
        mAdapter.add("progress");
        pendingResult = PENDING_HOME;
        mViewModel.homeContent();
    }

    private void showWebOverlay() {
        mBinding.webOverlay.setVisibility(View.VISIBLE);
        syncWebOverlayLayout();
    }

    private void hideWebOverlay() {
        mBinding.webOverlay.setVisibility(View.GONE);
    }

    private void setTypes(Result result) {
        if (result.getTypes().isEmpty()) {
            mTypeAdapter.addAll(java.util.Collections.emptyList());
            mBinding.typeRecycler.setVisibility(View.GONE);
            return;
        }
        List<Class> items = new ArrayList<>(result.getTypes().size() + 1);
        items.add(getHomeType());
        items.addAll(result.getTypes());
        mTypeAdapter.addAll(items);
        mBinding.typeRecycler.setVisibility(View.VISIBLE);
    }

    private void addVideo(Result result, boolean category) {
        if (!category && result.getList().isEmpty() && !result.getTypes().isEmpty()) {
            Class type = result.getTypes().get(0);
            SpiderDebug.log("home", "home list empty, auto open first category key=%s tid=%s", getHome().getKey(), type.getTypeId());
            mAdapter.add("progress");
            pendingResult = PENDING_CATEGORY;
            mViewModel.categoryContent(getHome().getKey(), type.getTypeId(), "1", true, new HashMap<>());
            return;
        }
        Style style = result.getStyle(getHome().getStyle());
        List<Object> rows = new ArrayList<>();
        if (style.isList()) rows.addAll(result.getList());
        else rows.addAll(buildGridRows(result.getList(), style));
        // 判据用 previewingCategory 而不是 category：`首页无列表 → 自动打开首个分类` 那条路也会
        // 带上 PENDING_CATEGORY，但它不是预览，行必须照旧追加在末尾。
        if (!previewingCategory) {
            mAdapter.addAll(mAdapter.size(), rows);
            return;
        }
        // 预览：内容区里只放分类数据（onResult 已把适配器清空，这里只负责填）
        mAdapter.addAll(0, rows);
        // 空列表必须给出可见反馈：功能按钮行已经被撤走，这时什么都不显示的话整屏空白，
        // 看起来像崩了。两种情况会走到这里 —— 分类本身无数据，以及请求超时（SiteViewModel
        // 的错误分支 postValue(Result.empty())）。showEmpty() 用 progressLayout 自带的
        // ViewEmptyBinding 居中空态，与分类页 TypeFragment 的行为一致。
        if (rows.isEmpty()) {
            SpiderDebug.log("home-chip", "preview empty, show empty view");
            mBinding.progressLayout.showEmpty();
        } else {
            mBinding.progressLayout.showContent();
        }
        // 第一页也要收尾：endLoading() 会按 pageCount 决定还能不能翻（pageCount=1 就直接禁用），
        // 并且 checkMore() 负责「内容不足一屏、滚不到底」时的自动补页。
        endPreviewLoading(result);
    }

    /**
     * 预览态翻页的收尾：把 CustomScroller 的 loading 清掉、按 pageCount 决定还能不能翻，
     * 再决定要不要自动补一页。分类页 TypeFragment 的 setAdapter() 里也是这两步。
     *
     * ⚠️ **空结果绝不能 checkMore()**：CustomScroller.endLoading() 对空列表会 `page--`，
     *    而 setEnable(0)（pageCount 未知）又会把 enable 打开 ⇒ 再自动要一次就会拿**同一个页码**
     *    无限重试。分类页 TypeFragment 是靠 `if (size > 0) addVideo(result)` 才避开这一格的
     *    （checkMore 在 addVideo 里面），这里必须在同一个地方拦住。
     */
    private void endPreviewLoading(Result result) {
        if (!previewingCategory) return;
        mScroller.endLoading(result);
        if (result.getList().isEmpty()) return;
        checkMore();
    }

    /**
     * 内容不足一屏时列表滚不到底 ⇒ CustomScroller 的 onScrollStateChanged 永远不会触发 ⇒ 得手动补一次。
     * 上限与分类页 TypeFragment 一致（5 行）：够 5 行就不再自动要，剩下的交给用户滚到底触发。
     */
    private void checkMore() {
        if (mScroller.isDisable() || mAdapter.size() >= 5) return;
        mScroller.checkMore();
    }

    /**
     * 预览态翻页（由 CustomScroller 在「滚到底且 IDLE」或 checkMore() 时回调）。
     *
     * ⚠️ 只有预览才翻：首页那几行（功能按钮 / 最近观看 / 推荐 / 首页视频行）不是分页数据。
     * 返回 false 时 CustomScroller **既不推进页码也不置 loading**，等于什么都没发生 —— 这正是我们要的。
     */
    private boolean onLoadMore(String page) {
        if (!previewingCategory || mPreviewTypeId == null) return false;
        // 还有请求在飞（最常见的是第一页）就别插队：pendingResult 是单槽，插队会把它覆盖成
        // PENDING_CATEGORY_MORE，于是第一页的结果会被当成"翻页"处理 —— 不走 clearContentRows()，
        // 而是被追加到空适配器后面，表现就是"第一页少了前面几条"。直接拒绝最稳。
        if (pendingResult != PENDING_NONE) return false;
        SpiderDebug.log("home-chip", "preview load more page=%s tid=%s rows=%s", page, mPreviewTypeId, mAdapter.size());
        pendingResult = PENDING_CATEGORY_MORE;
        mViewModel.categoryContent(getHome().getKey(), mPreviewTypeId, page, true, new HashMap<>());
        return true;
    }

    /**
     * 预览态追加一页。**只往末尾追加，绝不删改前面的行。**
     *
     * ⚠️ 必须用 mAdapter.addAll(mAdapter.size(), …)：预览期间适配器里没有「最近观看」/「推荐」标题行，
     *    getHistoryIndex()/getRecommendIndex() 是 `indexOf(标题) + 1` 动态算的，标题缺失时 indexOf 返回 -1，
     *    两个下标都会退化成 0 ⇒ 任何插到前面或按下标删除的操作都会误伤内容行（见 previewingCategory 那段注释）。
     */
    private void appendPreviewPage(Result result) {
        Style style = result.getStyle(getHome().getStyle());
        List<Vod> items = result.getList();
        if (style.isList()) {
            mAdapter.addAll(mAdapter.size(), items);
            return;
        }
        // 网格：上一页最后一行可能没填满，先用这一页把它补齐，否则每页末尾都留一条半行，看起来像断页。
        if (mPreviewLast != null && !items.isEmpty()) {
            int room = Product.getColumn(style) - mPreviewLast.size();
            if (room > 0) {
                int take = Math.min(room, items.size());
                mPreviewLast.addAll(mPreviewLast.size(), items.subList(0, take));
                items = items.subList(take, items.size());
            }
        }
        if (items.isEmpty()) return;
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            mPreviewLast = new ArrayObjectAdapter(presenter);
            mPreviewLast.addAll(0, part);
            rows.add(new ListRow(mPreviewLast));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private List<ListRow> buildGridRows(List<Vod> items, Style style) {
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            adapter.addAll(0, part);
            rows.add(new ListRow(adapter));
        }
        // 预览态翻页要靠它把下一页接着填进最后那一行；非预览路径不读它
        // （loadCategory()/exitPreview()/dropPreview() 都会清掉）。
        mPreviewLast = rows.isEmpty() ? null : (ArrayObjectAdapter) rows.get(rows.size() - 1).getAdapter();
        return rows;
    }

    private void setFunc() {
        List<Func> items = new ArrayList<>();
        if (LiveConfig.hasUrl()) items.add(Func.create(R.string.home_live));
        items.add(Func.create(R.string.home_search));
        items.add(Func.create(R.string.home_keep));
        items.add(Func.create(R.string.home_push));
        items.add(Func.create(R.string.home_setting));
        mFuncAdapter.setItems(items, new BaseDiffCallback<Func>());
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        // 预览期间内容区里没有「最近观看」标题，getHistoryIndex() 会退化成 0，
        // 照常执行会把历史行插到功能按钮行前面。预览结束由 exitPreview() 补一次。
        if (previewingCategory) return;
        List<History> items = History.get();
        int historyIndex = getHistoryIndex();
        int recommendIndex = getRecommendIndex();
        boolean exist = recommendIndex - historyIndex == 2;
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        if ((items.isEmpty() && exist) || (renew && exist)) mAdapter.removeItems(historyIndex, 1);
        if ((!items.isEmpty() && !exist) || (renew && exist)) mAdapter.add(historyIndex, new ListRow(mHistoryAdapter));
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        // 预览里没有历史行可删，别去碰下标（标题缺失时 getHistoryIndex() 会算成 0）
        if (!previewingCategory) mAdapter.removeItems(getHistoryIndex(), 1);
        History.deleteAndSync(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
    }

    private int getHistoryIndex() {
        return mAdapter.indexOf(R.string.home_history) + 1;
    }

    private int getRecommendIndex() {
        return mAdapter.indexOf(R.string.home_recommend) + 1;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                setTitle();
                SpiderDebug.log("site-dialog", "home refresh start key=%s homePage=%s", getHome().getKey(), getHome().hasHomePage());
                if (mWeb != null && mWeb.isVisible()) {
                    if (!mWeb.load(getHome(), true)) getVideo(true);
                } else {
                    getVideo();
                }
                SpiderDebug.log("site-dialog", "home refresh end key=%s", getHome().getKey());
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history().save(VodConfig.getCid()));
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onItemClick(Func item) {
        if (item.getResId() == R.string.home_live) LiveActivity.start(this);
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_search) SearchActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
    }

    @Override
    public boolean onLongClick(Func item) {
        if (item.getResId() != R.string.home_search) return false;
        SearchActivity.start(this, "", getHome().getKey());
        return true;
    }

    @Override
    public void onItemClick(Class item) {
        if (isHomeChip(item)) {
            showDialog();
            return;
        }
        Result result = mHomeResult == null || mHomeResult.getTypes().isEmpty() ? mResult : mHomeResult;
        VodActivity.start(this, getHome().getKey(), result, mTypeAdapter.indexOf(item) - 1);
    }

    @Override
    public void onRefresh(Class item) {
        if (isHomeChip(item)) return;
        onItemClick(item);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else VideoActivity.start(this, getHome().getKey(), item.getId(), item.getName(), item.getPic());
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic(), null, item.getWallPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.deleteAndSync());
        if (mHistoryAdapter.size() > 0) return;
        if (!previewingCategory) mAdapter.removeItems(getHistoryIndex(), 1);
        mPresenter.setDelete(false);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        long start = System.currentTimeMillis();
        SpiderDebug.log("site-dialog", "open requested cost=%sms", System.currentTimeMillis() - App.time());
        SiteDialog.create().show(this);
        SpiderDebug.log("site-dialog", "show returned delay=%sms", System.currentTimeMillis() - start);
    }

    @Override
    public void onRefresh() {
        if (mWeb != null && mWeb.isVisible()) mWeb.reload();
        else getVideo();
    }

    @Override
    public void reloadConfig() {
        VodConfig.get().clear().config(getConfig()).load(new Callback() {
            @Override
            public void start() {
                mBinding.progressLayout.showProgress();
            }

            @Override
            public void success() {
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        SpiderDebug.log("site-dialog", "set site key=%s name=%s homePage=%s", item.getKey(), item.getName(), item.hasHomePage());
        VodConfig.get().setHome(item);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) {
            showDialog();
            return true;
        }
        if (mWeb != null && mWeb.isVisible()) {
            if (KeyUtil.isBackKey(event)) {
                if (KeyUtil.isActionUp(event)) onBackInvoked();
                return true;
            }
            if (mBinding.toolbar.hasFocus()) {
                if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event)) return requestWebFocus();
                return super.dispatchKeyEvent(event);
            }
            if (KeyUtil.isUpKey(event) && isToolbarVisible()) return super.dispatchKeyEvent(event);
            if (mWeb.dispatchKeyEvent(event)) return true;
            return super.dispatchKeyEvent(event);
        }
        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.typeRecycler.hasFocus()) return requestTitleFocus();
        if (KeyUtil.isActionDown(event) & KeyUtil.isDownKey(event) && mBinding.typeRecycler.hasFocus()) return requestContentFocus();
        // 预览态（芯片选中项 >0）不在这里亮顶栏：下一帧焦点就回到芯片行，上面那个获焦回调会立刻再收起来，
        // 只会闪一下。
        if (KeyUtil.isActionDown(event) & KeyUtil.isUpKey(event) && mBinding.recycler.hasFocus() && mBinding.typeRecycler.getVisibility() == View.VISIBLE && mBinding.typeRecycler.getSelectedPosition() <= 0) updateToolbarVisibility(true);
        if (KeyUtil.isActionDown(event) & KeyUtil.isDownKey(event) && getCurrentFocus() == mBinding.title) return requestHomeFocus();
        return super.dispatchKeyEvent(event);
    }

    private boolean requestTitleFocus() {
        // 预览态下顶栏是收起的（title 跟着 GONE），必须先把顶栏放出来才谈得上给 title 焦点。
        // 这是刻意保留的：按上键就是要去「标题」，这时把爬虫名显示出来是对的。
        updateToolbarVisibility(true);
        mBinding.title.setFocusable(true);
        return mBinding.title.requestFocus();
    }

    private boolean requestHomeFocus() {
        if (mBinding.typeRecycler.getVisibility() == View.VISIBLE) return mBinding.typeRecycler.requestFocus();
        return requestContentFocus();
    }

    private boolean requestWebFocus() {
        return mWeb != null && mWeb.isVisible() && mWeb.requestFocus("toolbar-down");
    }

    private boolean requestContentFocus() {
        if (mBinding.recycler.getVisibility() != View.VISIBLE || mBinding.recycler.getChildCount() == 0) return false;
        View child = mBinding.recycler.getFocusedChild();
        if (child == null) child = mBinding.recycler.getChildAt(0);
        return child != null && child.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
        if (mWeb != null) mWeb.onResume();
    }

    @Override
    protected void onPause() {
        if (mWeb != null) mWeb.onPause();
        super.onPause();
        mClock.stop();
    }

    @Override
    protected void onBackInvoked() {
        if (mWeb != null && mWeb.isVisible() && mWeb.handleBack()) {
            return;
        } else if (mWeb != null && mWeb.isVisible() && consumeTvFullscreenBack()) {
            return;
        } else if (mWeb != null && mWeb.isVisible()) {
            exitHome();
            return;
        } else if (previewingCategory) {
            // 预览态（芯片停在某个分类上）按返回 = 回「首页」。放在 progressLayout 那条之前：预览加载中
            // （progressLayout 在转圈）时按返回，用户的意图同样是「退出这个分类」，而不是「取消转圈」。
            backToHomeChip();
            return;
        } else if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (mBinding.recycler.getSelectedPosition() != 0) {
            mBinding.recycler.scrollToPosition(0);
        } else {
            exitHome();
        }
    }

    private boolean consumeTvFullscreenBack() {
        if (!TV_FULL.equals(webChromeMode) && !TV_TOOLBAR_HIDDEN.equals(webChromeMode)) return false;
        applyTvChrome(TV_NORMAL);
        requestTitleFocus();
        return true;
    }

    // 预览态按返回 → 回「首页」。刻意不另写一套还原逻辑：快照回填、progressLayout 复位、顶栏放回
    // 全都在 exitPreview() 里，这里只负责把芯片位置挪回 0 并把焦点交给芯片行。
    // 芯片位置置 0 后，typeRecycler 的选中回调会在下一次 layout 再跑一遍
    // （updateToolbarVisibility(true) + onTypeFocused(0) → mHomeRunnable），那次的 exitPreview()
    // 会因为 previewingCategory 已经是 false 而直接 return，所以这里**立刻**还原不会和它打架。
    // 之所以不等 mHomeRunnable 的 100ms：返回键是明确意图，延迟 100ms 只会让人以为没反应。
    private void backToHomeChip() {
        SpiderDebug.log("home-chip", "back key in preview -> home");
        App.removeCallbacks(mCategoryRunnable);
        App.removeCallbacks(mHomeRunnable);
        mBinding.typeRecycler.setSelectedPosition(0);
        exitPreview();
        requestHomeFocus();
    }

    private void exitHome() {
        ExitConfirmDialog.create(this::confirmExitHome).show(this);
    }

    private void confirmExitHome() {
        if (PlaybackService.isRunning()) Util.moveToBackground(this);
        else super.onBackInvoked();
    }

    @Override
    protected void onDestroy() {
        if (mWeb != null) mWeb.destroy();
        DLNARendererService.stop(this);
        LiveConfig.get().clear();
        VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }

    @Override
    public void onWebLoading() {
        showWebOverlay();
        mBinding.progressLayout.showProgress();
    }

    @Override
    public void onWebReady() {
        showWebOverlay();
        mBinding.progressLayout.showContent();
        mBinding.typeRecycler.setVisibility(View.GONE);
        mBinding.recycler.setVisibility(View.GONE);
    }

    @Override
    public void onWebError() {
        applyTvChrome(TV_NORMAL);
        if (mWeb != null) mWeb.hide();
        hideWebOverlay();
        mBinding.recycler.setVisibility(View.VISIBLE);
        getVideo(true);
    }

    @Override
    public void setToolbar(boolean visible) {
        if (!Setting.isWebHomeFullscreen()) {
            applyTvChrome(TV_NORMAL);
            return;
        }
        applyTvChrome(visible ? webDefaultChromeMode : TV_TOOLBAR_HIDDEN);
    }

    @Override
    public void applyDefaultChrome(Site site) {
        if (!Setting.isWebHomeFullscreen()) {
            webDefaultChromeMode = TV_NORMAL;
            applyTvChrome(TV_NORMAL);
            return;
        }
        webDefaultChromeMode = tvDefaultMode(site == null ? "" : site.getChromeMode());
        applyTvChrome(webDefaultChromeMode);
    }

    @Override
    public void setChrome(JsonObject payload) {
        if (!Setting.isWebHomeFullscreen()) {
            applyTvChrome(TV_NORMAL);
            return;
        }
        applyTvChrome(tvRuntimeMode(Json.safeString(payload, "mode")));
    }

    @Override
    public void restoreChrome() {
        if (!Setting.isWebHomeFullscreen()) {
            applyTvChrome(TV_NORMAL);
            return;
        }
        applyTvChrome(webDefaultChromeMode);
    }

    @Override
    public WebHomeViewport getViewport() {
        return tvViewport(webChromeMode);
    }

    @Override
    public void openVod() {
        applyTvChrome(TV_NORMAL);
        if (mWeb != null) mWeb.hide();
        hideWebOverlay();
        getVideo(true);
    }

    @Override
    public void openSetting() {
        SettingActivity.start(this);
    }

    private void applyTvChrome(String mode) {
        webChromeMode = mode;
        webToolbarVisible = TV_NORMAL.equals(mode) || TV_OVERLAY.equals(mode);
        // 一并守住预览态：从网页返回原生首页时如果还停在分类预览里，别把爬虫名又亮出来。
        updateToolbarVisibility(webToolbarVisible && !previewingCategory);
        syncWebOverlayLayout();
        if (mWeb != null) mWeb.setViewport(tvViewport(mode));
    }

    private String tvDefaultMode(String mode) {
        return tvMode(mode, TV_FULL);
    }

    private String tvRuntimeMode(String mode) {
        return tvMode(mode, webChromeMode);
    }

    private String tvMode(String mode, String fallback) {
        String value = TextUtils.isEmpty(mode) ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (TV_NORMAL.equals(value) || "normal".equals(value)) return TV_NORMAL;
        if (TV_TOOLBAR_HIDDEN.equals(value)) return TV_TOOLBAR_HIDDEN;
        if (TV_OVERLAY.equals(value)) return TV_OVERLAY;
        if (TV_FULL.equals(value) || "edge".equals(value) || "immersive".equals(value)) return TV_FULL;
        return fallback;
    }

    private WebHomeViewport tvViewport(String mode) {
        return WebHomeViewport.fixed(ResUtil.dp2px(28), ResUtil.dp2px(48), ResUtil.dp2px(28), ResUtil.dp2px(48), mode);
    }

}
