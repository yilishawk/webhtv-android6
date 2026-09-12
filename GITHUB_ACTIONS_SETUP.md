# GitHub Actions 在线打包说明

本仓库使用 `.github/workflows/android-release.yml`，在 GitHub 上构建 **4 个 release APK**：

| APK 文件 | 形态 | ABI |
|----------|------|-----|
| `mobile-arm64_v8a.apk` | 手机版 | arm64-v8a |
| `mobile-armeabi_v7a.apk` | 手机版 | armeabi-v7a |
| `leanback-arm64_v8a.apk` | TV 版 | arm64-v8a |
| `leanback-armeabi_v7a.apk` | TV 版 | armeabi-v7a |

---

## 一、必须先配置的仓库 Secrets（缺失会导致工作流直接失败）

工作流在 **Configure release signing** 步骤会校验以下 Secrets，任何一个缺失都会 `exit 1`：

| Secret 名 | 必填 | 说明 |
|-----------|------|------|
| `RELEASE_KEYSTORE_BASE64` | 是 | release keystore（.jks）的 Base64 编码 |
| `RELEASE_KEY_ALIAS` | 是 | keystore 中的 key 别名 |
| `RELEASE_STORE_PASSWORD` | 是 | keystore 密码 |
| `RELEASE_KEY_PASSWORD` | 否 | key 密码，缺省时自动使用 store 密码 |

### 1. 生成 keystore

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias webhtv \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 你的密码 -keypass 你的密码
```

### 2. 转成 Base64

Linux / macOS：
```bash
base64 -w0 release.jks > release.jks.base64
```

Windows PowerShell：
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Content release.jks.base64
```

### 3. 写入 GitHub

GitHub 仓库 → **Settings → Secrets and variables → Actions → New repository secret**，
逐个添加上表中的 Secret。

> `release.jks` 本身**不要提交**到仓库（`.gitignore` 已排除 `*.jks` / `*.keystore`）。

---

## 二、可选的 Secrets / Variables

| 名称 | 类型 | 用途 |
|------|------|------|
| `CNB_TOKEN` | Secret | 同步到 CNB Release（工作流勾选 `sync_cnb` 时需要） |
| `OCI_USERNAME` | Secret | 发布 OCI 制品 |
| `OCI_TOKEN` | Secret | 发布 OCI 制品 |
| `OCI_REPOSITORY` | Variable | OCI 仓库地址 |

不配置这些也能正常出 4 个 APK，只是跳过 CNB 同步和 OCI 发布。

---

## 三、触发构建

GitHub → **Actions** → 左侧选 **Android Release** → **Run workflow**：

- `release_tag`：留空会自动生成 `v<versionName>-yyyyMMddHHmm`
- `release_channel`：`stable` / `beta` / `auto`
- `publish_oci` / `sync_cnb`：按需勾选

构建完成后会自动创建 **GitHub Release**，附上 4 个 APK 和对应的更新清单 JSON；
同时上传为 workflow artifact（保留 30 天）。

---

## 四、CI 构建流水线（工作流会依次执行）

| 顺序 | 步骤 | 说明 |
|------|------|------|
| 1 | Checkout | 拉取仓库 |
| 2 | Set up JDK 21 | Temurin（= Adoptium），必须 |
| 3 | Set up Python 3.10 | Chaquopy 构建期需要 |
| 4 | **Verify bundled MPV native assets** | 执行 `scripts/verify_mpv_native_assets.sh --require-elf`，校验 2 个 ABI 共 20 个 `.so` |
| 5 | Set up Android SDK + 安装包 | `platforms;android-37.0`、`build-tools;37.0.0`、`ndk;29.0.14206865`、`cmake;3.22.1` |
| 6 | **Run WebSocket danmaku tests** | 跑 `:app:testMobileArm64_v8aDebugUnitTest`，**测试不过就不会打包** |
| 7 | Resolve release tag | 由 `app/build.gradle` 的 `versionName` 推导 tag，格式 `v5.6.0-yyyyMMddHHmm` |
| 8 | Configure release signing | 从 Secrets 还原 `release.jks` 并生成 `local.properties` |
| 9 | **Build four release APKs** | 跑 4 个 `assemble*Release` 任务 |
| 10 | Collect artifacts | `cp Release/apk/*.apk dist/` |
| 11 | OCI 发布 / 生成清单 / 创建 Release / CNB 同步 / 上传 artifact | 后处理 |

**关键链路：APK 是怎么从构建产物变成 release 附件的**

```
app/build/outputs/apk/<flavor><abi>/release/<mode>-<abi>.apk
        │  ← 根 build.gradle 第 11-20 行注册的 copy 任务（assemble*Release 后自动执行）
        ▼
Release/apk/mobile-arm64_v8a.apk  ...
        │  ← 工作流第 183 行 cp Release/apk/*.apk dist/
        ▼
dist/*.apk  →  GitHub Release 附件 + workflow artifact
```

> 根目录 `build.gradle` 里那段 `tasks.matching { it.name ==~ /assemble.+Release/ }` 的 `doLast { copy ... }`
> **不能删**，删了工作流第 183 行会找不到文件而失败。

---

## 五、CI 环境要点（本仓库已配置好）

| 项 | 配置 | 位置 |
|----|------|------|
| JDK | Temurin 21 | workflow `actions/setup-java` |
| Gradle daemon JVM | `toolchainVendor=ADOPTIUM` | `gradle/gradle-daemon-jvm.properties` |
| Python | 3.10（Chaquopy 构建期需要） | workflow `actions/setup-python` |
| Android SDK | `platforms;android-37.0`、`build-tools;37.0.0` | workflow `sdkmanager` |
| NDK | `ndk;29.0.14206865` | workflow `sdkmanager` |
| CMake | `cmake;3.22.1` | workflow `sdkmanager` |

> `gradle/gradle-daemon-jvm.properties` 里的 `toolchainVendor` 必须是 `ADOPTIUM`。
> 如果写回 `JETBRAINS`，GitHub runner 上没有 JetBrains Runtime，构建会直接失败。

---

## 六、注意事项

1. **`local.properties` 不要提交**（`.gitignore` 已排除）。CI 会从 Secrets 生成它。
2. **minSdk = 23**，可安装到 Android 6。但 Chaquopy 要求 minSdk ≥ 24，因此：
   - `chaquo/build.gradle` 中该模块单独设 `minSdk 24`
   - `app/src/main/AndroidManifest.xml` 用 `<uses-sdk tools:overrideLibrary="com.fongmi.chaquo" />` 放行
   - 结果：Android 6 上 **Python 爬虫不可用**，其余功能正常（`PyLoader.getSpider` 有 `catch(Throwable)` 兜底）
3. 首次构建需下载大量依赖，耗时较长（约 30~60 分钟）。
4. 仓库体积约 300 MB（含 `third_party/maven` 本地 Maven 仓库和预编译原生库），首次 clone 会慢一些。
   单文件最大 `libmpv.so` 约 17 MB，远低于 GitHub 100 MB 单文件上限，**无需 Git LFS**。
5. 换行符已由 `.gitattributes` 锁定：`gradlew` / `*.sh` 强制 LF，`.so` / `.aar` / `*.jks` 标记为 binary。
   Windows 上克隆也不会破坏 Linux CI 的脚本执行。

---

## 七、还缺少什么？（上传前必做）

代码和构建配置**已经完整**，缺的只有两样，且都**不应该放进仓库**：

| # | 缺的东西 | 放哪里 | 为什么不能提交 |
|---|---------|--------|---------------|
| 1 | **release keystore（.jks）** | GitHub Secrets（Base64） | 提交后任何人拿到就能伪造你的签名包 |
| 2 | **GitHub Secrets 配置** | 仓库 Settings → Secrets | 属于密钥，不落盘 |

除这两项外，`webhtv-github-upload/` 里**已经包含**了在线打包所需的全部文件：

- ✅ `.github/workflows/android-release.yml`（已补 `ndk` / `cmake` 安装）
- ✅ 根 `build.gradle`（含 APK → `Release/apk` 的 copy 任务）
- ✅ `.github/scripts/publish-oci-apks.sh`、`sync-cnb-release.sh`
- ✅ `scripts/verify_mpv_native_assets.sh` + `verify_mpv_vulkan_shader_contract.py`
- ✅ `third_party/maven/`（本地 Maven 仓库，含 fongmi 版 media3）
- ✅ `app/libs/*.aar`（forcetech / jianpian / thunder / tvbus / hook）
- ✅ `app/src/{arm64_v8a,armeabi_v7a}/assets/mpv-libs/` 共 20 个 `.so`（约 82 MB）
- ✅ `gradle/wrapper/gradle-wrapper.jar`（wrapper 必需）
- ✅ 6 处构建修复（`local.properties` 路径、`toolchainVendor`、chaquo minSdk、`overrideLibrary`、`PiP.java` 与 `ic_action_audio.xml` 迁移）
- ✅ `.gitignore` + `.gitattributes`
- ✅ `GITHUB_ACTIONS_SETUP.md`（本文件）

---

## 八、上传前检查清单

```bash
cd webhtv-github-upload

# 1. 确认没有把敏感/产物文件带进来（应无输出）
find . -name "local.properties" -o -name "*.jks" -o -name "*.keystore" -o -name "*.apk"
find . -type d -name build

# 2. 初始化并提交
git init
git add -A
git commit -m "WebHTV: Android 6 适配 + GitHub Actions 在线打包"
git branch -M main
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main

# 3. 到 GitHub 仓库 Settings → Secrets and variables → Actions 配置第一节的 3 个必填 Secret
# 4. Actions → Android Release → Run workflow
```

> 打包完成后，4 个 APK 会出现在该次运行自动创建的 **GitHub Release** 里，
> 同时在 workflow 的 Artifacts 区域保留 30 天。

