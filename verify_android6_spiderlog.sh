#!/usr/bin/env bash
set -euo pipefail

echo "[1] minSdk / WebKit"
grep -nE '^minSdk =|^webkit =' gradle/libs.versions.toml

echo "[2] Spider log routes"
grep -nE '/spider/(log|stream)' app/src/main/java/com/fongmi/android/tv/server/process/DebugLogs.java

echo "[3] Spider context"
grep -nE 'setSpider|clearSpider|getSpider' catvod/src/main/java/com/github/catvod/crawler/SpiderDebug.java

echo "[4] Android 6 guards"
grep -nE 'SDK_INT >= Build.VERSION_CODES.N|SDK_INT < Build.VERSION_CODES.N' app/src/main/java/com/fongmi/android/tv/player/PlaybackSystemConditionMonitor.java app/src/main/java/com/fongmi/android/tv/service/PlaybackService.java app/src/leanback/java/com/fongmi/android/tv/receiver/BootReceiver.java

echo "[5] AAR manifests"
for f in app/libs/hook-release.aar app/libs/thunder-release.aar app/libs/tvbus-release.aar; do
  echo "--- $f"
  unzip -p "$f" AndroidManifest.xml | strings | grep 'minSdkVersion' || true
done

echo "[OK] Static Android 6 / Spider-log checks passed."
