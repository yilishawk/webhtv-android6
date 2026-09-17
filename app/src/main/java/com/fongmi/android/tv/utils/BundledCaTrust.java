package com.fongmi.android.tv.utils;

import android.content.Context;
import android.os.Build;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

/**
 * 把 APK 里本来就带的那份 Mozilla 根证书包喂给 OkHttp。
 *
 * <p>为什么必须做这件事：Android 6 的系统信任库里**没有** ISRG Root X1（Let's Encrypt 的根，
 * 从 Android 7.1.1 才进系统），而 GitHub 的 release 资产下载会 302 到
 * {@code release-assets.githubusercontent.com}，那个域的证书链正是 Let's Encrypt 签的
 * （leaf *.github.io &lt;- Let's Encrypt YR1 &lt;- ISRG Root YR &lt;- ISRG Root X1）。
 * 于是同一台电视、同一个网络下：MPV 能连（它吃 assets/cacert.pem），OkHttp 连不上（它吃系统信任库）。
 * 表现就是「更新检查失败、手机正常」，或者「第一次请求能过、跟到重定向就断」。</p>
 *
 * <p>语义是**并集**：系统信任库 ∪ 内置证书包，任一通过即放行。
 * 只挂内置包等于把系统信任库整个丢掉（运营商/企业/代理自签的根就全废了）；
 * 而 {@code trustAllCertificates()} 那种写法会把中间人攻击一起放进来，这里绝不用。</p>
 *
 * <p>内置包加载失败（资源缺失、解析异常）时**直接返回原 builder**，退回系统默认行为，
 * 绝不因为这份补丁制造出新的失败面。</p>
 */
public final class BundledCaTrust {

    private static final String TAG = "tls-trust";
    private static final String ASSET = "cacert.pem";
    private static final Pattern PEM = Pattern.compile("-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", Pattern.DOTALL);

    private static volatile boolean prepared;
    private static volatile int bundledRoots;
    private static volatile SSLSocketFactory socketFactory;
    private static volatile X509TrustManager trustManager;

    private BundledCaTrust() {
    }

    /**
     * 给裸 OkHttpClient 接上「系统 ∪ 内置」的信任库。幂等，可反复调用。
     * 加载失败时原样返回，调用方不需要判空。
     */
    public static OkHttpClient.Builder apply(OkHttpClient.Builder builder) {
        prepare();
        SSLSocketFactory factory = socketFactory;
        X509TrustManager manager = trustManager;
        if (factory == null || manager == null) return builder;
        return builder.sslSocketFactory(factory, manager);
    }

    /** 内置证书包的根数量，0 表示没加载成功（只用于诊断日志）。 */
    public static int bundledRootCount() {
        prepare();
        return bundledRoots;
    }

    private static void prepare() {
        if (prepared) return;
        // Application 还没起来时不把「失败」记成永久状态，留待下次重试；
        // 其余失败（资产缺失、PEM 坏）是确定性的，缓存住不反复读资产。
        if (App.get() == null) return;
        synchronized (BundledCaTrust.class) {
            if (prepared) return;
            try {
                X509TrustManager bundled = bundledManager();
                if (bundled == null || bundled.getAcceptedIssuers().length == 0) {
                    SpiderDebug.log(TAG, "bundled ca unusable, keep system trust store");
                } else {
                    bundledRoots = bundled.getAcceptedIssuers().length;
                    X509TrustManager system = systemManager();
                    X509TrustManager composite = system == null ? bundled : new Union(system, bundled);
                    SSLContext context = SSLContext.getInstance("TLS");
                    context.init(null, new TrustManager[]{composite}, null);
                    trustManager = composite;
                    socketFactory = context.getSocketFactory();
                    // sdk 也带上：本分支存在的理由是 API 23，而这行日志是远程判断
                    // 「电视到底是证书不受信任、还是纯网络问题」的唯一入口。
                    SpiderDebug.log(TAG, "bundled ca ready roots=%d sdk=%d rel=%s", bundledRoots, Build.VERSION.SDK_INT, Build.VERSION.RELEASE);
                }
            } catch (Throwable e) {
                SpiderDebug.log(TAG, "bundled ca failed %s: %s", e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage());
            }
            prepared = true;
        }
    }

    /** assets/cacert.pem —— 逐条 PEM 解出来塞进 KeyStore，比依赖 CertificateFactory 的多证书行为更确定。 */
    private static X509TrustManager bundledManager() throws Exception {
        Context context = App.get();
        if (context == null) return null;
        String pem = readAsset(context, ASSET);
        if (pem == null || pem.isEmpty()) return null;
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        Matcher matcher = PEM.matcher(pem);
        int index = 0;
        while (matcher.find()) {
            try {
                byte[] der = Base64.decode(matcher.group(1).replaceAll("\\s", ""), Base64.DEFAULT);
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
                store.setCertificateEntry("bundled-ca-" + index, certificate);
                index++;
            } catch (Exception ignored) {
                // 单条坏证书不该拖垮整包，跳过继续。
            }
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        return firstX509(tmf.getTrustManagers());
    }

    /** 系统默认信任库。KeyStore 传 null 就是「平台默认」，这是标准写法。 */
    private static X509TrustManager systemManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return firstX509(tmf.getTrustManagers());
    }

    private static X509TrustManager firstX509(TrustManager[] managers) {
        if (managers == null) return null;
        for (TrustManager manager : managers) if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        return null;
    }

    private static String readAsset(Context context, String name) {
        try (InputStream input = context.getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(256 * 1024);
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            SpiderDebug.log(TAG, "read %s failed %s: %s", name, e.getClass().getSimpleName(), e.getMessage() == null ? "" : e.getMessage());
            return null;
        }
    }

    private static final class Union implements X509TrustManager {

        private final X509TrustManager system;
        private final X509TrustManager bundled;

        private Union(X509TrustManager system, X509TrustManager bundled) {
            this.system = system;
            this.bundled = bundled;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                system.checkClientTrusted(chain, authType);
            } catch (CertificateException e) {
                bundled.checkClientTrusted(chain, authType);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                system.checkServerTrusted(chain, authType);
            } catch (CertificateException systemError) {
                try {
                    bundled.checkServerTrusted(chain, authType);
                } catch (CertificateException bundledError) {
                    // 两边都不认：抛**系统**那条（"Trust anchor for certification path not found."
                    // 才是可读的根因），把内置包那条挂成 suppressed 不丢信息。
                    systemError.addSuppressed(bundledError);
                    throw systemError;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = system.getAcceptedIssuers();
            X509Certificate[] b = bundled.getAcceptedIssuers();
            List<X509Certificate> all = new ArrayList<>(a.length + b.length);
            for (X509Certificate c : a) all.add(c);
            for (X509Certificate c : b) all.add(c);
            return all.toArray(new X509Certificate[0]);
        }
    }
}
