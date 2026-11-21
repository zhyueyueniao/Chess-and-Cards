#!/usr/bin/env bash
# 复用本机现成工具链构建 WebView 壳 APK（不下载 JDK/Gradle/SDK）
set -e

BASE="$(cd "$(dirname "$0")" && pwd)"
GRADLE="$USERPROFILE/.gradle/wrapper/dists/gradle-8.14-bin/38aieal9i53h9rfe7vjup95b9/gradle-8.14/bin/gradle"
export JAVA_HOME='D:\Program Files\Java\ms-21.0.12.1'
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== using local toolchain ==="
java -version 2>&1 | head -2
printf 'sdk.dir=D\\:/Program Files/Android/AndroidSdk\n' > "$BASE/app/local.properties"

echo "=== gradle build ==="
cd "$BASE/app"
"$GRADLE" assembleRelease --console=plain

echo "=== collect apk ==="
APK=$(find "$BASE/app/app/build/outputs/apk/release" -name '*.apk' | head -1)
cp "$APK" "$BASE/棋牌大厅.apk"
ls -la "$BASE/棋牌大厅.apk"
echo APK_BUILD_DONE
