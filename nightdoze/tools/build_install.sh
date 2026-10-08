#!/usr/bin/env bash
# 构建 debug APK 并装到已连接的真机，然后拉起界面。
#
# 用法：
#   tools/build_install.sh              # 构建 + 安装 + 启动
#   tools/build_install.sh --install-only   # 跳过构建，只装现有 APK
#   tools/build_install.sh --no-root        # 即使设备有 root 也走 adb install
#
# 为什么要有这个脚本：每次改完代码都要走「构建 → 装机 → 启动 → 看日志」四步，
# 手敲四遍容易漏掉最后一步（漏了就只能对着黑屏猜）。
#
# 安装方式：默认 `adb install`，普通手机即可用。国内 ROM 会弹系统确认框，手动点一下。
# 设备有 root 时（开发机），自动改用 `su -c pm install`，免掉弹窗，适合无人值守的自动化。
# 只想装不构建、或不想让脚本碰 root，用 tools/install.sh。
set -euo pipefail

cd "$(dirname "$0")/.."

# adb 的定位顺序，后一个兜底是开发机上的常见默认位置。
# 别的机器上要么设 ANDROID_HOME，要么设 ADB，不要改这个文件。
ADB="${ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}/platform-tools/adb}"
if [ ! -x "$ADB" ]; then
  ADB="$(command -v adb || true)"
fi
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.nightdoze.app"
ACTIVITY="$PKG/.ui.MainActivity"

# 参数解析：--install-only 跳构建，--no-root 禁用 root 直装
INSTALL_ONLY=0
ALLOW_ROOT=1
for arg in "$@"; do
  case "$arg" in
    --install-only) INSTALL_ONLY=1 ;;
    --no-root)      ALLOW_ROOT=0 ;;
    *) echo "未知参数：$arg" >&2; exit 2 ;;
  esac
done

if [ -z "$ADB" ] || { [ ! -x "$ADB" ] && [ ! -f "$ADB" ]; }; then
  echo "找不到 adb。请设置 ANDROID_HOME 或直接设 ADB 指向 adb 可执行文件。" >&2
  exit 1
fi

# 设备就绪检查放在构建之前：装机那天才发现线没插，是最没必要的一次等待
if ! "$ADB" devices | grep -q "device$"; then
  echo "没有就绪的设备。检查数据线 / USB 调试授权（adb devices -l）" >&2
  exit 1
fi

if [ "$INSTALL_ONLY" = "0" ]; then
  ./gradlew :app:assembleDebug --console=plain
fi

install_apk() {
  # 1) 有 root 且没被禁用 → 推送后 su 直装，无系统弹窗，适合自动化
  if [ "$ALLOW_ROOT" = "1" ] && "$ADB" shell su -c true >/dev/null 2>&1; then
    local remote="/data/local/tmp/nightdoze-debug.apk"
    echo "==> 推送 APK 并以 root 安装（无系统弹窗）"
    # MSYS_NO_PATHCONV：Git Bash 会把 /data/local/tmp/... 当成 Windows 路径改写掉，
    # 变成 <git 安装目录>/data/local/tmp/... 然后 push 失败。设备侧路径必须原样送过去。
    MSYS_NO_PATHCONV=1 "$ADB" push "$APK" "$remote" >/dev/null
    local out
    out="$(MSYS_NO_PATHCONV=1 "$ADB" shell su -c "pm install -r -t $remote" 2>&1 || true)"
    MSYS_NO_PATHCONV=1 "$ADB" shell rm -f "$remote" >/dev/null 2>&1 || true
    # pm install 会混进 avc 之类的噪声，把关键行挑出来
    echo "$out" | grep -E "Success|Failure|Error|Exception" || echo "$out"
    case "$out" in
      *Success*)
        # 重装会把 SYSTEM_ALERT_WINDOW appop 打回 ignore，悬浮窗会静默消失。
        # 有 root 时顺手补回来，这是 root 装机唯一比 adb install 省事的地方。
        MSYS_NO_PATHCONV=1 "$ADB" shell su -c \
          "appops set $PKG SYSTEM_ALERT_WINDOW allow" >/dev/null 2>&1 || true
        return 0
        ;;
      *) echo "root 安装失败，回退 adb install" >&2 ;;
    esac
  fi

  # 2) 普通手机路径
  echo "==> adb install"
  echo "    国内 ROM 会弹系统安装确认框，请在手机上点「安装」"
  "$ADB" install -r "$APK"

  # adb install 失败时可能仍返回 0，用版本号核对是否真的装上
  if [ -z "$("$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d '\r' || true)" ]; then
    echo "安装未生效：$PKG 查不到。确认手机上点了确认框，或先卸载旧版解决签名冲突。" >&2
    exit 1
  fi
}

echo "==> 安装"
install_apk

echo "==> 启动"
"$ADB" shell am start -n "$ACTIVITY" >/dev/null

sleep 2
PID="$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' || true)"
if [ -n "$PID" ]; then
  echo "==> 进程存活 PID $PID"
else
  echo "==> [!] 进程没起来，抓最近的异常日志：" >&2
  "$ADB" logcat -d -t 120 | grep -iE "AndroidRuntime|FATAL|$PKG" | tail -30 >&2
  exit 1
fi

echo
echo "常用观测命令："
echo "  $ADB logcat -s NightDozeService NightDozeAdmin NightDozeSettings"
echo "  $ADB shell dumpsys device_policy | grep -A3 nightdoze"
echo
echo "只装不构建：tools/install.sh"
echo "打发布用 APK：tools/build_release.sh"
