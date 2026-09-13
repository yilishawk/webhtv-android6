package com.fongmi.chaquo;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;

import com.chaquo.python.Python;
import com.chaquo.python.internal.Common;
import com.github.catvod.Init;
import com.github.catvod.crawler.SpiderDebug;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Platform extends Python.Platform {

    private static final String[] OBSOLETE_FILES = {"app.zip", "requirements.zip", "chaquopy.mp3", "stdlib.mp3", "chaquopy.zip", "lib-dynload", "stdlib.zip", "bootstrap.zip", "stdlib-common.zip", "ticket.txt"};
    private static final String[] OBSOLETE_CACHE = {"AssetFinder"};

    private final SharedPreferences sp;
    private final JSONObject buildJson;
    private final AssetManager am;
    private final Context context;
    private String ABI;

    public Platform() {
        this.context = Init.context();
        this.sp = context.getSharedPreferences(Common.ASSET_DIR, Context.MODE_PRIVATE);
        this.am = context.getAssets();
        try {
            try (InputStream is = am.open(Common.ASSET_DIR + "/" + Common.ASSET_BUILD_JSON)) {
                buildJson = new JSONObject(streamToString(is));
            }
            loadNativeLibs();
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
        List<String> supportedAbis = new ArrayList<>();
        Collections.addAll(supportedAbis, Build.SUPPORTED_ABIS);
        Collections.addAll(supportedAbis, Build.CPU_ABI, Build.CPU_ABI2);
        for (String abi : supportedAbis) {
            try (InputStream ignored = am.open(Common.ASSET_DIR + "/" + Common.assetZip(Common.ASSET_STDLIB, abi))) {
                ABI = abi;
                break;
            } catch (IOException ignored) {
            }
        }
        if (ABI == null) throw new RuntimeException("No supported ABI found in: " + supportedAbis);
    }

    public static Platform create() {
        return new Platform();
    }

    @Override
    public @NotNull String getPath() {
        String assetDir = new File(context.getFilesDir(), Common.ASSET_DIR).getAbsolutePath();
        List<String> pathAssets = Arrays.asList(Common.assetZip(Common.ASSET_STDLIB, Common.ABI_COMMON), Common.assetZip(Common.ASSET_BOOTSTRAP), Common.ASSET_BOOTSTRAP_NATIVE + "/" + ABI);
        String pythonPath = pathAssets.stream().map(asset -> assetDir + "/" + asset).collect(Collectors.joining(":"));
        List<String> extractionList = new ArrayList<>(pathAssets);
        extractionList.add(Common.ASSET_CACERT);
        try {
            deleteObsolete(context.getFilesDir(), OBSOLETE_FILES);
            deleteObsolete(context.getCacheDir(), OBSOLETE_CACHE);
            extractAssets(extractionList);
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
        return pythonPath;
    }

    @Override
    public void onStart(@NotNull Python py) {
        String[] appPath = {Common.ASSET_APP, Common.ASSET_REQUIREMENTS, Common.ASSET_STDLIB + "-" + ABI};
        py.getModule("java.android").callAttr("initialize", context, buildJson, appPath);
    }

    private void deleteObsolete(File baseDir, String[] filenames) {
        for (String filename : filenames) {
            filename = filename.replace("<abi>", ABI);
            deleteRecursive(new File(baseDir, Common.ASSET_DIR + "/" + filename));
        }
    }

    private void extractAssets(List<String> assets) throws IOException, JSONException {
        JSONObject assetsJson = buildJson.getJSONObject("assets");
        Set<String> unextracted = new HashSet<>(assets);
        Set<String> directories = new HashSet<>();
        SharedPreferences.Editor spe = sp.edit();
        Iterator<String> keys = assetsJson.keys();
        while (keys.hasNext()) {
            String path = keys.next();
            for (String ea : assets) {
                if (path.equals(ea) || path.startsWith(ea + "/")) {
                    extractAsset(assetsJson, spe, path);
                    unextracted.remove(ea);
                    if (path.startsWith(ea + "/")) directories.add(ea);
                    break;
                }
            }
        }
        if (!unextracted.isEmpty()) throw new RuntimeException("Failed to find assets: " + unextracted);
        for (String dir : directories) cleanExtractedDir(dir, assetsJson);
        spe.apply();
    }

    private void extractAsset(JSONObject assetsJson, SharedPreferences.Editor spe, String path) throws IOException, JSONException {
        String fullPath = Common.ASSET_DIR + "/" + path;
        File outFile = new File(context.getFilesDir(), fullPath);
        String spKey = "asset." + path;
        String newHash = assetsJson.getString(path);
        if (outFile.exists() && sp.getString(spKey, "").equals(newHash)) return;
        outFile.delete();
        File outDir = outFile.getParentFile();
        if (outDir != null && !outDir.exists() && !outDir.mkdirs()) throw new IOException("Failed to create " + outDir);
        File tmpFile = new File(outDir, outFile.getName() + ".tmp");
        tmpFile.delete();
        try (InputStream in = am.open(fullPath); OutputStream out = new FileOutputStream(tmpFile)) {
            transferStream(in, out);
        }
        if (!tmpFile.renameTo(outFile)) throw new IOException("Failed to rename " + tmpFile);
        spe.putString(spKey, newHash);
    }

    private void cleanExtractedDir(String dir, JSONObject assetsJson) {
        File outDir = new File(context.getFilesDir(), Common.ASSET_DIR + "/" + dir);
        File[] list = outDir.listFiles();
        if (list == null) return;
        Arrays.stream(list).forEach(outFile -> {
            String name = outFile.getName();
            if (outFile.isDirectory()) {
                cleanExtractedDir(dir + "/" + name, assetsJson);
            } else if (!assetsJson.has(dir + "/" + name)) {
                outFile.delete();
            }
        });
    }

    private void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) Arrays.stream(children).forEach(this::deleteRecursive);
        file.delete();
    }

    private void transferStream(InputStream in, OutputStream out) throws IOException {
        int len;
        byte[] buffer = new byte[8192];
        while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
    }

    private String streamToString(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        transferStream(in, result);
        return result.toString(StandardCharsets.UTF_8.name());
    }

    private void loadNativeLibs() throws JSONException {
        loadLibcShim();
        String pythonVer = buildJson.getString("python_version");
        for (String lib : new String[]{"crypto_chaquopy", "ssl_chaquopy", "sqlite3_chaquopy", "python" + pythonVer, "chaquopy_java"}) {
            System.loadLibrary(lib);
        }
    }

    /**
     * The bundled CPython is built for android-24, so its native modules may reference
     * bionic symbols that Android 6 (API 23) does not export. System.loadLibrary()
     * resolves with RTLD_NOW, so a single unresolved symbol makes the whole dlopen()
     * fail. Two groups are affected (found by auditing every native module in the APK
     * against the NDK's API-23 stubs — see app/src/main/cpp/libc_shim.c):
     *
     *   libpython3.10.so          lockf64, preadv64, pwritev64     (32-bit ABI only)
     *   _socket.cpython-310.so    if_nameindex, if_freenameindex   (both ABIs)
     *   resource.cpython-310.so   prlimit                          (32-bit ABI only)
     *
     * The first group blocks loading the interpreter itself:
     *
     *     dlopen failed: cannot locate symbol "lockf64" referenced by ".../base.apk"
     *
     * The second only bites once Python starts importing — `import socket` fails, which
     * cascades into urllib3 -> requests -> the app's spiders:
     *
     *     PyException: ImportError: dlopen failed: cannot locate symbol "if_nameindex"
     *       referenced by ".../stdlib-armeabi-v7a/_socket.cpython-310.so"
     *
     * libwebhtv_shim.so supplies all of them and must be loaded before the loop above.
     *
     * Load order is not sufficient on its own. None of those modules has a DT_NEEDED
     * entry for us — they just carry undefined references under the LIBC_N version node
     * that they expect libc to provide — so they can only find ours if we are in the
     * *global* lookup scope. A plain dlopen() defaults to RTLD_LOCAL, which would put us
     * in a local group only. We do not rely on whatever flags System.loadLibrary()
     * happens to pass: the library is linked with -Wl,-z,global, so its DT_FLAGS_1
     * carries DF_1_GLOBAL, and bionic adds any freshly loaded library with that bit to
     * the global group of every namespace. That makes the promotion deterministic on
     * every Android version.
     *
     * Failure is not fatal here — PyLoader degrades to "no Python spiders".
     */
    private void loadLibcShim() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return;
        try {
            System.loadLibrary("webhtv_shim");
            SpiderDebug.log("chaquo", "libc shim loaded "
                    + "(lockf64/preadv64/pwritev64/if_nameindex/if_freenameindex/prlimit)");
        } catch (Throwable e) {
            SpiderDebug.log("chaquo", "libc shim unavailable, python may fail to load");
            SpiderDebug.log("chaquo", e);
        }
    }
}