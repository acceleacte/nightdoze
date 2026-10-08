# 夜眠息屏 · NightDoze

<p>
  <img alt="平台" src="https://img.shields.io/badge/平台-Android-3DDC84">
  <img alt="minSdk" src="https://img.shields.io/badge/minSdk-26-blue">
  <img alt="targetSdk" src="https://img.shields.io/badge/targetSdk-34-blue">
  <img alt="许可" src="https://img.shields.io/badge/许可-MIT-green">
</p>

手机长时间没被移动，本应用自动息屏。不联网，不用登录。

---

## 为什么需要它

系统的「屏幕自动关闭」在看视频时不起作用。

视频 App 播放时会给屏幕加一个「别关」标记（`FLAG_KEEP_SCREEN_ON`）。系统的超时计时器被不断重置，永远数不到头。夜里看视频睡着后，屏幕会亮一整夜。次日早晨电量耗尽，闹钟不响。OLED 屏上，同一画面停留数小时还会留下烧屏残影。

系统那个开关按「有没有操作」计时。本应用改看另一个信号：手机有没有被移动。

---

## 工作原理

规则一句话：屏幕亮着，手机持续没有加速度变化，超过设定时长后就息屏。

不监听触摸（普通 App 拿不到这个能力），只用加速度计。人醒着拿手机时手一定有细微晃动；睡着时手机搁在一边就不动。这个差别足够区分两种情况。

### 怎么判断「没动」

每 0.2 秒读一次加速度计（5Hz）。算出合加速度 `√(x² + y² + z²)`，取最近 10 个样本（2 秒）的标准差 σ，当作「抖动量」。

σ 大就是在动，σ 小就是静止。判定为「动」的条件：

```
σ > max(0.025, 灵敏度系数 k × 基线)
```

基线是环境本身的底噪。它自动学习，快慢分两档：

| 情形 | 学习速度 | 为什么 |
| --- | --- | --- |
| 判定为静止 | 快（α = 0.05） | 尽快学出「这个床头柜有多吵」，比如无线充电器的风扇声 |
| 判定为在动 | 慢（α = 0.005） | 不让人手晃一下就把基线拉高。基线一高，功能会把自己越调越迟钝 |

实测放在桌面上时，σ 在 0.003 到 0.013 之间。判定下限是 0.025，留了 2 到 8 倍余量。

### 两段式触发

只靠「静满 T 分钟就息屏」有漏洞。清醒地看电子书时手也很稳，同样会被判成静止。

所以流程分两段：

```
静置满 T − 预警秒数  ──→  进入预警（悬浮窗变色并提亮）
                            │
              这段时间内动一下 ──→ 取消，重新计时
                            │
                  一直没动 ──→  到 T 时刻息屏
```

预警只是提前通知。T 才是真正的息屏时刻。默认提前 10 秒，也可设成 0，不通知直接息屏。

### 息屏是怎么实现的

第三方 App 要让屏幕立刻熄灭，免 root 只有一条路：

```kotlin
DevicePolicyManager.lockNow(0)   // 需要「设备管理员」授权
```

试过的其他方案都不可行：

| 方案 | 为什么不用 |
| --- | --- |
| `PowerManager.goToSleep()` | 需要系统签名权限，普通 App 拿不到 |
| 改 `SCREEN_OFF_TIMEOUT` | 被 App 的「别关屏」标记压制，正是要解决的那个空档 |
| 盖一层黑屏遮罩 | 背光仍亮着，不省电 |

这个授权只声明了 `<force-lock />` 一条策略。它不能擦除数据、重置密码或禁用相机。`res/xml/device_admin.xml` 里的声明和授权页文案逐字对应，可自行核对。

### 耗电

屏幕一黑立刻停掉传感器。全程不申请 WakeLock。不在生效时段时，服务挂着但不采样。

在一台 Android 16 设备上实测，处于「已就绪但没在采样」状态时，单核占用 0.2% 到 0.35%。内存 PSS 约 105 MB。

---

## 功能

### 息屏时机

| 档位 | 说明 |
| --- | --- |
| 测试 1 分 | 只为快速验证「静止判定 → 自动息屏」这条链路通不通 |
| 15 分 / 30 分 | 常用档 |
| 自定义 | 滚轮选 5 到 720 分钟，5 分钟一档（60 分钟以上按「小时」显示） |

### 动作灵敏度

界面里能当场试：

- 点「开始试调」，拿起手机晃几下
- 卡片实时显示「已识别：你在动」或「无动静：判定为睡眠」
- 同时显示当前的 σ 和判定阈值

指示亮起，说明当前灵敏度会把这次动作判成有人在动，计时归零。

往灵敏拖，不容易误息屏。代价是风扇、按摩仪这类持续微振的东西也可能把它拖住，该息屏时没息。往迟钝拖则相反。

### 生效时段

可以只在夜里生效（例如 23:00 到 07:00），白天在外不打扰。跨午夜自动识别。起止时间设成相同即全天。

### 其他

- **仅在充电时生效**：白天在外面完全不介入
- **息屏前提醒**：0 / 5 / 10 / 30 秒。到点前悬浮窗变色并提亮，动一下即可取消本轮
- **状态悬浮窗**：显示倒计时，盖在其他应用上方。可开关、调透明度、长按拖动换位
- **暂停监测**：5 分钟到 24 小时，滚轮自选
- **立即测试息屏**：手动走一遍完整息屏路径，用来确认授权仍有效
- **开机自启**：重启手机后自动恢复监测

---

## 状态说明

主界面的圆环显示当前走到哪一步。「非生效时段」不等于「正在计时」，所以它同时说明被什么挡住了：

| 显示 | 含义 |
| --- | --- |
| 待授权 | 还没给设备管理员权限 |
| 已关闭 | 总开关是关的 |
| 非生效时段 · 屏幕已熄灭 | 正常。亮屏后自动开始计时 |
| 非生效时段 · 不在生效时段 | 正常。到点自动开始 |
| 非生效时段 · 等待插上电源 | 开了「仅充电时生效」但没插电 |
| 监测中 · 已静止 4:12 | 正在计时 |
| 即将息屏 · 连续动几秒可取消 | 预警期，现在动几秒即取消 |
| 已暂停 · 至 08:00 | 手动暂停中，到点自动恢复 |

---

## 架构

单 module 的 Android 工程。不用多 module，不用 DI 框架，不用数据库。

```
nightdoze/app/src/main/java/com/nightdoze/app/
├── boot/BootReceiver.kt        开机 / 应用更新后拉起服务
├── admin/Admin.kt              设备管理员封装（lockNow / 授权状态查询）
├── data/Settings.kt            DataStore 持久化 + 灵敏度刻度换算
├── engine/
│   ├── EngineState.kt          进程内单例，UI 与服务之间的状态通道
│   └── MonitorService.kt       前台服务：采样、判定、状态机、息屏
└── ui/
    ├── MainActivity.kt         Activity + ViewModel
    ├── MainScreen.kt           单页界面
    ├── WheelTimeDialog.kt      时段选择（双滚轮）
    └── WheelNumberDialog.kt    数值选择（单滚轮，时长与暂停共用）
```

### 数据流

```
加速度计 ──5Hz──> MonitorService ──> EngineState (StateFlow) ──> MainScreen
                        │                     ▲
                        │                     │
                   lockNow()            DataStore (Settings)
                                              ▲
                                              │
                                        MainViewModel ── 用户操作
```

- **UI 与服务**：走进程内单例 `EngineState` 加 `StateFlow`，不用 Bound Service 或 LocalBroadcast
- **配置**：唯一通道是 DataStore。服务直接订阅它，界面从不把设置「传」给服务
- **服务启停**：只有 `ensureRunning()` 一个入口，幂等。界面订阅设置来驱动它。不在 `onResume` 里读一次，因为 DataStore 首帧是异步的，读一次会读到默认值

### 状态机

```
IDLE ──> ARMED ──> MONITORING ──> PENDING_FIRE ──> (息屏) ──> ARMED
          │
          └──── PAUSED ────┘
```

`ARMED` 会附带一个 `BlockReason`（屏幕灭、不在时段、没插电、已暂停）。没有它，用户只能对着「非生效时段」干等，不知道为什么不计时。

息屏后不设冷却期：屏幕熄灭后本来就不采样。用户重新亮屏即立刻重新计时。

### 关键参数

| 参数 | 值 | 位置 |
| --- | --- | --- |
| 采样率 | 5 Hz（`200_000` µs 显式指定，不用 `SENSOR_DELAY_*`） | `MonitorService` |
| 判定窗口 | 10 个样本（2 秒） | `MonitorService.WINDOW` |
| σ 下限 | 0.025 | `FLOOR_SIGMA` |
| 基线学习率 | 静止 0.05，判定为动 0.005 | `ADAPT_QUIET` / `ADAPT_NOISY` |
| 灵敏度系数 k | 6（迟钝）到 2（灵敏），默认 0.4 对应 k≈4.4 | `SensitivityScale` |
| 自定义静置时长 | 5 到 720 分钟 | `IDLE_CUSTOM_STEPS` |
| 暂停时长 | 5 到 1440 分钟 | `PAUSE_MINUTES_STEPS` |

---

## 构建

### 环境要求

| 项 | 版本 |
| --- | --- |
| JDK | 17 或以上（开发时用 Temurin 21） |
| Android SDK Platform | android-34 |
| Build Tools | 34.0.0 |
| Gradle | 8.7（wrapper 已内置，首次构建自动下载） |
| AGP / Kotlin | 8.5.2 / 2.0.0 |

依赖只有 AndroidX、Compose、Material3 和 DataStore，全部 Apache-2.0。没有 GPL 依赖，没有第三方 SDK，没有网络库。

### 命令行构建

```bash
cd nightdoze
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 构建 + 装机

```bash
cd nightdoze
./tools/build_install.sh
```

先设好 `ANDROID_HOME`（或把 `ADB` 指向 adb 可执行文件），确认 `adb devices` 能看到设备。

脚本默认走 `adb install`，**普通手机即可用**。国内 ROM（MIUI、HyperOS 等）会弹一个系统安装确认框，在手机上点「安装」即可，不是脚本卡死。装完脚本会核对版本号，确认装上的确实是这个 APK。

设备有 root 时（开发机），脚本自动改用 `su -c "pm install"`，免掉弹窗，适合无人值守的自动化。加 `--no-root` 可强制走普通安装。

| 参数 | 作用 |
| --- | --- |
| 无 | 构建 + 安装 + 启动 |
| `--install-only` | 跳过构建，只装现有 APK |
| `--no-root` | 即使设备有 root 也走 `adb install` |

只装不构建、且完全不需要 root：

```bash
cd nightdoze
./tools/install.sh
```

### 构建可分发（侧载）的 APK

```bash
cd nightdoze
./tools/build_release.sh
# 产物：dist/nightdoze-<版本号>.apk
```

debug 包用 Android 的公共调试签名，任何人可重打包，不适合分发。release 包用自签名密钥，第一次运行脚本时自动生成到 `nightdoze/keystore/`。

**这两个文件必须备份**（`nightdoze-release.jks` 和 `keystore.properties`）。密钥丢失后，已安装的用户无法升级到新版本——签名不一致会被系统拒绝。它们已被 `.gitignore` 排除，不要提交。

### 发布到 GitHub

GitHub 允许在 Releases 里直接挂 APK 附件，这是侧载应用的标准分发方式。仓库只放源码，APK 走 Releases，不提交进版本库（版本库会为每个二进制留全量历史，仓库会迅速膨胀）。

流程：

1. 跑 `./tools/build_release.sh`
2. 在仓库页 Releases → Draft a new release
3. 填 tag（如 `v0.1.0`，与 `versionName` 对齐）
4. 把 `dist/nightdoze-0.1.0.apk` 拖进附件区
5. 发布

用命令行（需安装 `gh`）：

```bash
gh release create v0.1.0 nightdoze/dist/nightdoze-0.1.0.apk \
  --title "v0.1.0" --notes "首个版本"
```

Android 12 起，浏览器下载的 APK 在安装时会提示「未知来源」。这是侧载应用的正常行为。用户需在弹窗里允许「安装未知应用」，与开发者签名无关。

---

## 第一次使用

装好后按此顺序走一遍：

1. 打开 App，点「去授权」，在系统页面点「激活」。这是免 root 锁屏的唯一途径。
2. 回到 App，把「监测已开启」开关打开。
3. **去系统设置里打开自启动**：设置 → 应用设置 → 夜眠息屏 → 自启动。同时建议把电池策略设为「无限制」。
4. **允许悬浮窗**：设置 → 应用设置 → 夜眠息屏 → 显示在其他应用上层。不开也能息屏，只是看不到倒计时。
5. 点「立即测试息屏」，确认屏幕会立刻锁定。

第 3 步最容易漏。MIUI / HyperOS 默认拦住开机广播，App 自己改不了这个开关。

本应用**不需要 root**。设备管理员授权是普通 API，任何手机都能给。

---

## 隐私

本应用不联网。

- 没有网络权限，也没有位置、相机、麦克风、通讯录、存储权限
- 无账号，无统计，无广告，无第三方 SDK
- 所有设置只存在本机（DataStore）
- `allowBackup=false`，设置不会被云备份带出去

用到的权限共四条（清单里分三组，前台服务需两条），全部是功能必需的：

| 权限 | 用途 |
| --- | --- |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 常驻监测服务（Android 14 起必须按类型声明） |
| `POST_NOTIFICATIONS` | 前台服务的系统凭据。默认不申请，拒绝也不影响功能 |
| `RECEIVE_BOOT_COMPLETED` | 重启后自动恢复监测 |

加速度计不算权限，不需要申请。

---

## 已知限制

这些是平台限制或刻意的取舍：

1. **必须先完成一次设备管理员授权。** 这是免 root 息屏的唯一途径。
2. **做不到「非生效时段彻底停服务、到点自己醒来」。** Android 12 起禁止 App 在后台自行启动前台服务。例外名单里只有开机完成、应用更新完成、用户主动打开这几种，定时器类的唤醒不在名单内。所以现在的做法是服务常驻，时段外什么都不干。
3. **国内 ROM 必须手动开一次自启动。** MIUI / HyperOS 默认拦截开机广播，报 `process is not permitted to auto start`，App 自己改不了。实测路径：设置 → 应用设置 → 夜眠息屏 → 自启动，打开它。同时建议在电池设置里允许后台活动。不开的话，重启手机后功能会静默失效，界面却照样显示「监测已开启」。
4. **清醒地看静态内容可能被误判。** 比如躺着看电子书、手很稳。靠预警期兜底：在那 10 秒内动一下就不会息屏。
5. **只打包了 arm64-v8a。** 为减小体积过滤了其他 ABI。要装在 32 位老机器上，在 `app/build.gradle.kts` 的 `abiFilters` 里加回 `armeabi-v7a`。
6. **主要在一台 Android 16 设备上验证过。** 其他机型或版本可能有差异，欢迎反馈。

---

## 开发说明

工程内注释写的是「为什么这么做」，不是「这行在做什么」。重点是那些看起来可以更简单、但改了就会坏的地方。动手前建议先读对应文件的注释，几个反复踩过的坑都记在里面：

- 有效采样率必须自己钉死（180ms 闸门）。系统按最快客户端发数据，请求 5Hz 实际会来 50Hz
- `ACTION_BATTERY_CHANGED` 是粘性广播。只挂 receiver 不读返回值，会永远读到「没充电」
- 灵敏度试调不能复用状态机的采样窗口。两条路径的学习速率不同，共用状态会互相污染
- 滚轮吸附用官方的 `rememberSnapFlingBehavior`，不要自己算几何

`tools/` 下的脚本：

| 脚本 | 用途 | 需要 root |
| --- | --- | --- |
| `install.sh` | 只装现有 APK | 否 |
| `build_install.sh` | 构建 + 装机（有 root 时自动加速） | 否，可选 |
| `build_release.sh` | 打签名 release 包到 `dist/` | 否 |

开发机（有 root）额外的注意事项：`pm install -r` 重装会把 `SYSTEM_ALERT_WINDOW` 授权打回 `ignore`，悬浮窗静默消失。`build_install.sh` 已经自动补回，手敲安装命令时要自己加：

```bash
adb shell su -c "appops set com.nightdoze.app SYSTEM_ALERT_WINDOW allow"
```

---

## 许可证

[MIT](LICENSE)
