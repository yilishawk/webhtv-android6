package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.databinding.ActivityVodBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.crawler.SpiderDebug;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class VodActivity extends BaseActivity implements TypeAdapter.OnClickListener {

    private static final String HOME_TYPE_ID = "home";

    private ActivityVodBinding mBinding;
    private TypeAdapter mAdapter;
    private View mOldView;
    private Class mHomeType;
    private boolean chipReady;

    public static void start(Activity activity, Result result) {
        start(activity, VodConfig.get().getHome().getKey(), result);
    }

    public static void start(Activity activity, String key, Result result) {
        start(activity, key, result, 0);
    }

    public static void start(Activity activity, String key, Result result, int position) {
        if (result == null || result.getTypes().isEmpty()) return;
        Intent intent = new Intent(activity, VodActivity.class);
        intent.putExtra("key", key);
        intent.putExtra("result", result);
        intent.putExtra("position", Math.max(position, 0));
        activity.startActivity(intent);
    }

    private String getKey() {
        return getIntent().getStringExtra("key");
    }

    private Result getResult() {
        return getIntent().getParcelableExtra("result");
    }

    private int getPosition() {
        return Math.min(getIntent().getIntExtra("position", 0), Math.max(getPageCount() - 1, 0));
    }

    private int getPageCount() {
        return Math.max(mAdapter.getItemCount() - 1, 0);
    }

    private Class getHomeType() {
        if (mHomeType == null) {
            mHomeType = new Class();
            mHomeType.setTypeId(HOME_TYPE_ID);
            mHomeType.setTypeName(ResUtil.getString(R.string.vod_home));
        }
        return mHomeType;
    }

    private boolean isHomeChip(int chipPosition) {
        return chipPosition == 0;
    }

    // 「首页」是本页自己合成的占位项，用引用判等，避免与真实分类里可能存在的 id=home 撞车
    private boolean isHomeChip(Class item) {
        return item != null && item == mHomeType;
    }

    private int chipToPage(int chipPosition) {
        return Math.max(chipPosition - 1, 0);
    }

    private int pageToChip(int pagePosition) {
        return pagePosition + 1;
    }

    private Class getType() {
        return mAdapter.get(Math.min(pageToChip(mBinding.pager.getCurrentItem()), mAdapter.getItemCount() - 1));
    }

    private FolderFragment getFragment() {
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVodBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setTypes();
        setPager();
    }

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.recycler.setSelectedPosition(pageToChip(position));
                mBinding.recycler.requestFocus();
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child, position);
            }
        });
        // 首帧前芯片行的选中态由代码设定，只有用户自己移过来的焦点才该触发返回
        mBinding.recycler.postOnAnimation(() -> chipReady = true);
    }

    private void setRecyclerView() {
        mBinding.recycler.requestFocus();
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new TypeAdapter(this));
    }

    private void setTypes() {
        List<Class> items = new ArrayList<>(getResult().getTypes().size() + 1);
        items.add(getHomeType());
        items.addAll(getResult().getTypes());
        mAdapter.addAll(items);
    }

    private void setPager() {
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager()));
        mBinding.pager.setCurrentItem(getPosition());
        mBinding.recycler.setSelectedPosition(pageToChip(getPosition()));
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child, int position) {
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        if (isHomeChip(position)) {
            if (chipReady) App.post(mHomeRunnable, 100);
        } else {
            App.post(mRunnable, 100);
        }
    }

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mBinding.pager.setCurrentItem(chipToPage(mBinding.recycler.getSelectedPosition()));
        }
    };

    private final Runnable mHomeRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            SpiderDebug.log("home-chip", "vod home chip focused, back to home key=%s", getKey());
            RefreshEvent.home();
            finish();
        }
    };

    private boolean isFilterVisible() {
        return Optional.ofNullable(getType()).map(Class::getFilter).orElse(false);
    }

    private void updateFilter() {
        Optional.ofNullable(getType()).ifPresent(this::updateFilter);
    }

    private void updateFilter(Class item) {
        item.setFilter(!item.getFilter());
        getFragment().toggleFilter(item.getFilter());
        mAdapter.notifyItemRangeChanged(mAdapter.indexOf(item), 1);
    }

    public void closeFilter() {
        if (isFilterVisible()) updateFilter();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.CATEGORY) getFragment().onRefresh();
    }

    @Override
    public void onItemClick(Class item) {
        if (isHomeChip(item)) return;
        updateFilter(item);
    }

    @Override
    public void onRefresh(Class item) {
        if (isHomeChip(item)) return;
        getFragment().onRefresh();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) updateFilter();
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && mBinding.recycler.hasFocus()) return requestContentFocus();
        return super.dispatchKeyEvent(event);
    }

    private boolean requestContentFocus() {
        FolderFragment fragment = getFragment();
        return fragment != null && fragment.requestContentFocus();
    }

    @Override
    protected void onBackInvoked() {
        if (isFilterVisible()) updateFilter();
        else if (getFragment().canBack()) getFragment().goBack();
        else super.onBackInvoked();
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(pageToChip(position));
            return FolderFragment.newInstance(getKey(), type);
        }

        @Override
        public int getCount() {
            return getPageCount();
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        }
    }
}
