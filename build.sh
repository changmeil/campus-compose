#!/usr/bin/env bash
# 免 Android Studio 构建：全程命令行，产物在 app/build/outputs/apk/release/
set -e

cd "$(dirname "$0")"

GRADLE="./tools/gradle-9.8.2/bin/gradle"
if [ ! -x "$GRADLE" ] && [ ! -f "$GRADLE.bat" ]; then
  echo "缺少 Gradle，请先下载 gradle-9.8.2-bin.zip 解压到 tools/"
  exit 1
fi

export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.8.9-hotspot"
export ANDROID_HOME="$(pwd)/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

"$GRADLE" :app:assembleRelease --console=plain "$@"

APK="app/build/outputs/apk/release/app-release.apk"
VER="$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts | head -1)"
NAME="campus-compose-${VER}.apk"
if [ -f "$APK" ]; then
  mkdir -p dist
  cp "$APK" "dist/$NAME"
  echo "==> $(pwd)/dist/$NAME"
  ls -lh "dist/$NAME"
fi
