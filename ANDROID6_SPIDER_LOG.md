# WebHomeTV Android 6 + Spider 调试日志改造

基于本次提供的 WebHomeTV 源码包进行修改。

## Android 6

- `minSdk`：24 → 23（Android 6 / API 23）
- AndroidX WebKit：1.16.0 → 1.15.0。WebKit 1.16.0 将最低 SDK 提升到 API 24；1.15.0 支持 API 23。
- `Setting.wrapLanguage()`：API 24 的 `LocaleList` 仅在 API 24+ 执行。
- `PlaybackService`：API 24 的 `stopForeground(int)` 增加 API 23 兼容分支。
- `PlaybackSystemConditionMonitor`：API 23 不注册 API 24 的 default network callback，并跳过 Android 7 才有的数据节省状态读取。
- leanback `BootReceiver`：Android 6 不调用 `registerDefaultNetworkCallback()`，改为直接执行启动初始化。
- PiP：Android 6 没有画中画能力，增加兼容访问方法并避免直接调用不存在的 API。
- `Source`：Android 6 不注册 Thunder / TVBus 提取器；Android 7+ 保持原逻辑。
- `hook-release.aar`、`thunder-release.aar`、`tvbus-release.aar` 的 AAR manifest 最低 SDK 从 24 调整为 23。Native 功能本身未重编；Thunder/TVBus 在 Android 6 主动停用。
- DV5 native renderer 保持原 API 26 构建基线，并由现有 Java 层 API 检查在 Android 6 不加载。

## Spider 日志

新增：

- `/spider/log`
- `/spider/log?site=<站源key>`
- `/spider/log.txt?site=<站源key>`
- `/spider/stream?site=<站源key>`

Spider 调用 `homeContent`、`categoryContent`、`detailContent`、`playerContent`、`searchContent`、`action` 时，会设置当前 Spider 调试上下文。

日志仍使用现有 `SpiderDebug` / `DebugLogStore`，不会新增独立日志框架。Spider 日志会在原日志消息中增加 `[spider=站源key]` 标记，因此可以按站源过滤。

浏览器示例：

`http://127.0.0.1:9978/spider/log?site=csp_xxx`

## 构建验证

本环境没有 Android SDK/Gradle 9.5.1 distribution 和完整 Maven 缓存，执行 Gradle wrapper 时会因无法访问 `services.gradle.org` 而无法完成 APK 构建。因此本包已完成源码级修改、结构检查和 ZIP 完整性检查，但**没有在本环境中声称 APK 已成功编译或在 Android 6 真机上实测**。

建议在有 Android SDK 和网络/Maven 缓存的开发环境执行：

```bash
./gradlew assembleMobile_arm64_v8aRelease
```

或：

```bash
./gradlew assembleMobile_armeabi_v7aRelease
```

构建成功后，应重点测试 Android 6：启动、WebHome、Jar Spider 加载、搜索、分类、详情、播放、直播，以及 `/spider/log?site=...`。
