package com.nightdoze.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nightdoze.app.admin.Admin
import com.nightdoze.app.data.ScheduleMode
import com.nightdoze.app.data.Settings
import com.nightdoze.app.data.SettingsStore
import com.nightdoze.app.engine.EngineSnapshot
import com.nightdoze.app.engine.EngineState
import com.nightdoze.app.engine.MonitorService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "NightDozeUI"

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // targetSdk 34 不会被强制 edge-to-edge，但显式开启后状态栏/导航栏留白由
        // Scaffold 的 innerPadding 统一处理，不必各处手算（PRD 5.4）
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            NightDozeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(vm = vm, contentPadding = innerPadding)
                }
            }
        }
    }

    /**
     * 每次回到前台都重查授权状态。
     *
     * 用户点「去授权」跳到系统页再返回、或在系统设置里取消了授权，都只有这一条路能发现。
     * 刻意**不做乐观更新**（PRD 5.9）：授权页返回时未授权就保持原样，卡片还在、开关不动，
     * 不弹任何错误提示。用户只是按了返回键，没做错什么。
     */
    override fun onResume() {
        super.onResume()
        // 界面在前台时把悬浮窗收起来：状态环已经在说同一句话（见 EngineState.uiVisible）
        EngineState.setUiVisible(true)
        vm.onForeground()
    }

    override fun onPause() {
        super.onPause()
        EngineState.setUiVisible(false)
    }
}

/**
 * 设置 + 状态 + 授权，三条数据源汇到一处给 UI。
 *
 * 刻意不引入 DI 框架：只有一个 ViewModel、一个 DataStore、一个进程内单例（PRD 7.1）。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    /** 配置：唯一来源是 DataStore。UI 只写，Service 只 collect（PRD 7.5 / D10）。 */
    val settings: StateFlow<Settings> = SettingsStore.flow(context)
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** 运行态：来自进程内单例，与配置通道完全分开。 */
    val engine: StateFlow<EngineSnapshot> = EngineState.snapshot

    private val _granted = MutableStateFlow(Admin.isActive(context))
    val granted: StateFlow<Boolean> = _granted.asStateFlow()

    /**
     * 「显示在其他应用上层」的授权状态。
     *
     * 和 [granted] 一样只在回到前台时重查：用户去系统页开权限再返回，只有这条路能发现。
     */
    private val _overlayGranted = MutableStateFlow(canDrawOverlay())
    val overlayGranted: StateFlow<Boolean> = _overlayGranted.asStateFlow()

    init {
        EngineState.setGranted(_granted.value)
        // 自愈：订阅配置，而不是读一次快照。
        // DataStore 首帧是异步的，而 onResume 常常早于那次发射。那一瞬间 settings.value
        // 还是默认的 enabled=false，服务就永远起不来（真机实测踩到过：界面显示「监测已开启」，
        // 状态环却是「已关闭」）。订阅之后，无论配置何时到、授权何时恢复，都能补上这一次启动。
        viewModelScope.launch {
            settings.collect { ensureRunning() }
        }
    }

    fun refreshAdmin() {
        val now = Admin.isActive(context)
        _granted.value = now
        _overlayGranted.value = canDrawOverlay()
        EngineState.setGranted(now)
        if (now) ensureRunning()
    }

    private fun canDrawOverlay(): Boolean =
        try {
            android.provider.Settings.canDrawOverlays(context)
        } catch (t: Throwable) {
            Log.w(TAG, "查询悬浮窗权限失败", t)
            false
        }

    /**
     * 回到前台时的状态自校正（PRD 7.3 / F14）。
     *
     * 除了重查授权，还要补上「开关开着但服务没跑」这件事。重装、重启、被厂商清理之后
     * 都会落到这个状态，而界面照样显示「监测已开启」，用户以为有人在守夜，其实没有。
     * 这是本产品最不能接受的失败姿势，而回到前台是唯一能合法启动前台服务的时机
     * （Android 12+ 禁止从后台启动），所以必须在这里补救。
     */
    fun onForeground() {
        refreshAdmin()
    }

    /** 幂等：已经在跑就什么都不做。所有会改变「该不该在跑」的路径最后都汇到这里。 */
    private fun ensureRunning() {
        if (settings.value.enabled && Admin.isActive(context) && !EngineState.snapshot.value.running) {
            MonitorService.start(context)
        }
    }

    fun adminIntent(): Intent = Admin.requestIntent(context)

    /**
     * 跳「显示在其他应用上层」的授权页。
     *
     * 这是特殊权限，没有运行时弹窗可用，只能把用户送到系统页自己开。
     * 带上 package 参数，落地页直接就是本应用那一行。
     */
    fun overlayIntent(): Intent = Intent(
        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    /**
     * 所有设置写入都走这里，**写盘失败绝不闪退**。
     *
     * 用户点一下「定时」就崩，是这个 App 最糟糕的失败方式。一次磁盘异常（文件损坏、
     * 空间不足、被外部工具改坏）不该把整个界面带走。失败只记日志，界面停在原状态，
     * 用户看得见的「没生效」远好过看不见的「闪退」。
     */
    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "写入设置失败", t)
            }
        }
    }

    /**
     * 总开关。开启前必须已授权。未授权时"开着"是没有意义的谎。
     *
     * 起停服务只在这里发生，也就是只在应用前台发生（PRD 7.3）。
     */
    fun setEnabled(value: Boolean) = save {
        if (value && !Admin.isActive(context)) return@save
        SettingsStore.setEnabled(context, value)
        if (value) {
            MonitorService.start(context)
        } else {
            MonitorService.stop(context)
        }
    }

    fun setIdleMinutes(minutes: Int) = save { SettingsStore.setIdleMinutes(context, minutes) }

    /**
     * 「自定义」档的分钟数。
     *
     * **必须同时写 `idleMinutes`。** 它是引擎唯一的取值来源（`MonitorService` 用
     * `idleMinutes * 60` 算阈值），而 `idleCustomMinutes` 只是「下次打开滚轮时回填到哪」。
     * 早先只写了后者，症状是：选完自定义弹出了「已切到自定义 · x 分钟」，
     * 界面上的自定义 chip 也亮了，但实际息屏时长还是切换前的旧值——提示说了谎。
     * 两处必须落在同一次 `edit` 里，否则会先闪一下中间态。
     */
    fun setIdleCustomMinutes(minutes: Int) = save {
        SettingsStore.setIdleCustom(context, minutes.coerceIn(Settings.IDLE_CUSTOM_RANGE))
    }

    fun setWarnSeconds(seconds: Int) = save { SettingsStore.setWarnSeconds(context, seconds) }

    fun setSchedule(mode: ScheduleMode) = save { SettingsStore.setSchedule(context, mode) }

    fun setNightStart(value: String) = save { SettingsStore.setNightStart(context, value) }

    fun setNightEnd(value: String) = save { SettingsStore.setNightEnd(context, value) }

    /** 一键采纳推荐时段（切到「定时」+ 写入推荐起止），见 [Settings.RECOMMENDED_START]。 */
    fun applyRecommendedSchedule() = save {
        SettingsStore.applyRecommendedSchedule(
            context,
            Settings.RECOMMENDED_START,
            Settings.RECOMMENDED_END,
        )
    }

    /**
     * 灵敏度滑块位置。写入频率由 Compose 的 Slider 决定（拖动时每个像素一次），
     * 但 DataStore 的写是串行化的、且这里只是改一个 Float，5Hz 量级完全无压力。
     */
    fun setSensitivityT(t: Float) = save { SettingsStore.setSensitivityT(context, t) }

    /**
     * 灵敏度试调通道的开关。
     *
     * 这里**故意不落盘**：它是一个「此刻要不要采一段样给界面看」的瞬时意图，
     * 不是长期配置。重启 App 后当然应该是关着的。没人希望一打开就在后台多采一路传感器。
     */
    fun setSensitivityProbeOn(on: Boolean) {
        if (on) EngineState.acquireProbe() else EngineState.releaseProbe()
        // 服务没在跑时也要能试调？不能。采样逻辑在服务里，没有服务就没有采样。
        // 所以这里不尝试拉起服务（Android 12+ 只有前台能起，这里是前台，理论上可以），
        // 但要拉起就得同时开启监测开关。那等于替用户改了设置，不做。
        // 界面会明确提示「先打开监测总开关」。
    }

    fun setChargingOnly(value: Boolean) = save { SettingsStore.setChargingOnly(context, value) }

    fun setOverlayEnabled(value: Boolean) = save { SettingsStore.setOverlayEnabled(context, value) }

    /**
     * 悬浮窗透明度。滑块拖动时每个像素一次回调，与灵敏度同理：
     * 写的只是同一个 Float，DataStore 的写本身串行，无压力。
     */
    fun setOverlayAlpha(value: Float) = save { SettingsStore.setOverlayAlpha(context, value) }

    /**
     * 暂停监测，时长由用户在滚轮里选（分钟）。
     *
     * 从前这个入口在常驻通知的动作按钮上、时长写死 1 小时；通知不再对用户可见之后，
     * 唯一的入口回到这里，时长也跟着交给用户。
     * 只写 `pausedUntil` 就够：服务订阅同一份 DataStore，下一拍就会看到。
     */
    fun pauseFor(minutes: Int) = save {
        SettingsStore.setPausedUntil(
            context,
            System.currentTimeMillis() + minutes * 60_000L,
            minutes,
        )
    }

    fun resumeNow() = save { SettingsStore.clearPausedUntil(context) }

    /**
     * M1 的真机验证入口：点一下直接走一遍完整的息屏路径。
     *
     * 摆在界面上而不是藏在调试菜单里，是因为「这台 HyperOS 上 lockNow() 到底管不管用」
     * 是整个产品的第一号未验证假设。它必须是打开 App 就能点到的。
     */
    fun lockNowNow() {
        Admin.lockNow(context)
    }

    /** 一键解除授权（PRD F18）。不解除的话系统会拦截卸载（PRD 12.3）。 */
    fun releaseAdmin() {
        Admin.release(context)
        MonitorService.stop(context)
        refreshAdmin()
    }
}

@Composable
private fun NightDozeTheme(content: @Composable () -> Unit) {
    // 深浅色跟随系统（P2 才做手工切换）。这是个夜里用的 App，系统多半已经在深色模式，
    // 所以 values-night 的窗口底色也不能落下，否则每次启动闪一下白屏最刺眼
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme, content = content)
}
