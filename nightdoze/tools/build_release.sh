#!/usr/bin/env bash
# 构建可分发（侧载）的 APK：release + 自签名，产物放进 dist/。
#
# 用法：
#   tools/build_release.sh
#
# 为什么需要：debug APK 用 Android 默认调试签名，能被任何人重打包，且 Android 10+
# 对 debug 包有额外限制。直接给别人装、或传到 GitHub Releases，应当用 release 包。
#
# 签名密钥第一次运行自动生成到 nightdoze/keystore/，已被 .gitignore 排除。
# **这个文件丢了就无法给已安装的用户升级**（签名不一致会被系统拒绝安装）。
# 请自行备份，不要提交到版本库。
#
# 不依赖 root，不碰设备。产物路径：dist/nightdoze-<版本号>.apk

set -euo pipefail

cd "$(dirname "$0")/.."

KS_DIR="keystore"
KS="$KS_DIR/nightdoze-release.jks"
KS_PROPS="$KS_DIR/keystore.properties"
GRADLE_PROPS="keystore.gradle.properties"

# ---- 1. 准备签名密钥 ----
if [ ! -f "$KS" ]; then
  echo "==> 未找到签名密钥，生成一份（RSA 2048，有效期 10000 天）"
  mkdir -p "$KS_DIR"
  PASS="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  keytool -genkeypair \
    -keystore "$KS" \
    -alias nightdoze \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=NightDoze, OU=Self-signed, O=NightDoze, L=Nanchang, ST=Jiangxi, C=CN" \
    >/dev/null

  cat > "$KS_PROPS" <<EOF
# 自签名密钥口令。与 $KS 一对，两者都要备份，且都不可提交。
storePassword=$PASS
keyPassword=$PASS
keyAlias=nightdoze
storeFile=keystore/nightdoze-release.jks
EOF
  chmod 600 "$KS" "$KS_PROPS" 2>/dev/null || true
  echo "    密钥：$KS"
  echo "    口令：$KS_PROPS"
  echo "    [!] 备份这两个文件。丢失后已安装的用户无法升级到新版本。"
else
  echo "==> 使用已有签名密钥 $KS"
fi

# ---- 2. 把签名配置交给 Gradle ----
# 经 -P 传参，避免在 build.gradle.kts 里写死路径与口令。
# storeFile 是相对工程根（nightdoze/）的路径。
PROPS_FOR_GRADLE="keystore/keystore.properties"
trap 'rm -f "$GRADLE_PROPS"' EXIT

{
  echo "storeFile=$KS"
  grep -E '^(storePassword|keyPassword|keyAlias)=' "$KS_PROPS"
} > "$GRADLE_PROPS"

# ---- 3. 构建 ----
echo "==> 构建 release APK"
./gradlew :app:assembleRelease --console=plain

SRC="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$SRC" ]; then
  echo "构建产物不存在：$SRC" >&2
  exit 1
fi

# ---- 4. 归档到 dist/ ----
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
mkdir -p dist
OUT="dist/nightdoze-${VERSION:-unknown}.apk"
cp "$SRC" "$OUT"

SIZE="$(wc -c < "$OUT" | tr -d ' ')"
echo
echo "==> 产物：$OUT（${SIZE} 字节）"

# 校验签名。apksigner 在 Windows 上是 .bat，别只找无后缀的可执行文件。
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}"
APKSIGNER="$(ls -1 "$SDK"/build-tools/*/apksigner* 2>/dev/null | grep -vE '\.(bat|cmd)$' | sort -V | tail -1 || true)"
[ -z "$APKSIGNER" ] && APKSIGNER="$(ls -1 "$SDK"/build-tools/*/apksigner.bat 2>/dev/null | sort -V | tail -1 || true)"

echo "==> 校验签名"
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$OUT" | grep -E "Verified using|Signer #1 certificate DN|SHA-256 digest" || true
else
  echo "  未找到 apksigner，手动核对：apksigner verify --print-certs $OUT"
fi

echo
echo "分发给别人：把这个 APK 拖进 GitHub Releases 附件区。"
echo "  仓库只放源码，二进制不进版本库。"
