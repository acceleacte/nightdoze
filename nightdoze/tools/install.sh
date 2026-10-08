#!/usr/bin/env bash
# 把已构建好的 APK 装到已连接的真机。不构建，不需要 root。
#
# 用法：
#   tools/install.sh                # 安装 + 启动 + 存活检查
#   tools/install.sh --no-launch    # 只安装
#   tools/install.sh path/to.apk    # 指定 APK（默认 app-debug.apk）
#
# 关于 root：默认走 `adb install`（普通手机适用）。国内 ROM（MIUI / HyperOS）会弹一个
# 系统安装确认框，**在手机上点「安装」即可**，不是脚本卡死。装完脚本会核对版本号，
# 确认装上的确实是这个 APK——`adb install` 失败时是静默的，只靠「Success」字样会误判。
#
# 若设备有 root（开发机），走 tools/build_install.sh：它用 `su -c pm install` 免掉弹窗，
# 且会额外补回重装必丢的 SYSTEM_ALERT_WINDOW 悬浮窗授权。本脚本不碰 root。

set -euo pipefail

cd "$(dirname "$0")/.."

ADB="${ADB:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android}}/platform-tools/adb}"
if [ ! -x "$ADB" ]; then
  ADB="$(command -v adb || true)"
fi
PKG="com.nightdoze.app"
ACTIVITY="$PKG/.ui.MainActivity"
BASE_APK="app/build/outputs/apk/debug/app-debug.apk"

APK="$BASE_APK"
LAUNCH=1
for arg in "$@"; do
  case "$arg" in
    --no-launch) LAUNCH=0 ;;
    -*) echo "未知参数：$arg" >&2; exit 2 ;;
    *) APK="$arg" ;;
  esac
done

if [ -z "$ADB" ] || { [ ! -x "$ADB" ] && [ ! -f "$ADB" ]; }; then
  echo "找不到 adb。请设置 ANDROID_HOME，或直接设 ADB 指向 adb 可执行文件。" >&2
  exit 1
fi

if ! "$ADB" devices | grep -q "device$"; then
  echo "没有就绪的设备。检查数据线 / USB 调试授权（adb devices -l）" >&2
  exit 1
fi

if [ ! -f "$APK" ]; then
  echo "找不到 APK：$APK" >&2
  echo "先构建：./gradlew :app:assembleDebug" >&2
  exit 1
fi

# 安装前先记下版本号，装完比对。adb 在部分 ROM 上会对失败的安装照样返回 0，
# 只看输出里有没有 Success 不可靠。
pkg_version() {
  "$ADB" shell dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionName=//p' | head -1 | tr -d '\r'
}
before_version="$(pkg_version || true)"
apk_size="$(wc -c < "$APK" | tr -d ' ')"

echo "==> 安装 $APK（${apk_size} 字节）"
echo "    国内 ROM 会弹系统确认框，请在手机上点「安装」"
"$ADB" install -r "$APK"

after_version="$(pkg_version || true)"
if [ -z "$after_version" ]; then
  echo "==> 安装后查不到 $PKG，安装未生效" >&2
  echo "    常见原因：手机上的确认框没点、包名不符、或签名冲突（旧版为不同签名）。" >&2
  echo "    签名冲突时先卸载：$ADB uninstall $PKG" >&2
  exit 1
fi
echo "==> 已安装：$after_version"
[ -n "$before_version" ] && [ "$before_version" != "$after_version" ] \
  && echo "    （版本变化：$before_version → $after_version）"

# 无 root 装完后，悬浮窗授权通常还在；若被 ROM 打回，在系统设置里重新开一次
# 「显示在其他应用上层」。有 root 的机器由 tools/build_install.sh 自动补。
if [ "$("$ADB" shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>/dev/null | tr -d '\r' | grep -c allow || true)" = "0" ]; then
  echo "    [!] 悬浮窗授权未开启。功能不受影响，只是过程不可见。"
  echo "        手机设置 → 应用 → 夜眠息屏 → 显示在其他应用上层，打开它。"
fi

if [ "$LAUNCH" = "1" ]; then
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
fi

echo
echo "常用观测命令："
echo "  $ADB logcat -s NightDozeService NightDozeAdmin NightDozeSettings"
echo "  $ADB shell dumpsys device_policy | grep -A3 nightdoze"
