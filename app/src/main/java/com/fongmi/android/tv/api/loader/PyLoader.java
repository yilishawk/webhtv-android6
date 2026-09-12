package com.fongmi.android.tv.api.loader;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = createLoader();
    }

    /**
     * The Python runtime is optional and must never be able to take the rest of the app
     * down with it.
     *
     * This constructor runs from BaseLoader's static initialiser, so anything that
     * escapes here poisons the whole loader: config loading reports "配置取得失败",
     * and every VOD/live request through NanoHTTPD dies too. That is exactly what used
     * to happen on 32-bit Android 6, where the bundled CPython (built for android-24)
     * cannot be dlopen'ed because bionic there lacks lockf64/preadv64/pwritev64.
     *
     * Platform#loadLibcShim supplies those symbols, so on most devices the runtime now
     * loads fine. This catch is the safety net for the cases where it still does not:
     * a device we did not anticipate, a corrupted install, a future CPython needing
     * something else. Degrading to "no Python spiders" beats a dead app.
     */
    private static Loader createLoader() {
        try {
            Loader created = new Loader();
            SpiderDebug.log("PyLoader", "python runtime ready");
            return created;
        } catch (Throwable e) {
            SpiderDebug.log("PyLoader", "python runtime unavailable, python spiders disabled");
            SpiderDebug.log("PyLoader", e);
            return null;
        }
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        if (loader == null) return new SpiderNull();
        return spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader.spider(api);
                spider.siteKey = key;
                spider.init(App.get(), normalizeExt(ext));
                return spider;
            } catch (Throwable e) {
                SpiderDebug.log("PyLoader", "python spider init failed: key=%s api=%s", key, api);
                SpiderDebug.log("PyLoader", e);
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    private String normalizeExt(String ext) {
        String value = TextUtils.isEmpty(ext) ? "" : ext.trim();
        // Many live Python spiders treat ext as an option object and call .get().
        // TV-style configs commonly express an empty extension as [], which would
        // otherwise deserialize to a list and crash those spiders during init.
        return "[]".equals(value) ? "{}" : ext;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (loader == null || recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
