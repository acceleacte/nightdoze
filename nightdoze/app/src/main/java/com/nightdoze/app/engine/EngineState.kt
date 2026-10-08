package com.nightdoze.app.engine

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 监测引擎的状态（PRD 3.2）。
 *
 * 五个枚举覆盖全部流转，UI 与 Service 从一开始就说同一套话，不必各行其是。
 *
 * 曾有第六个 `COOLDOWN`（息屏后 5 分钟内不再计时）。删掉的理由：息屏后屏幕本就熄灭，
 * 灭屏期间不采样，那段冷却只额外拦住了「用户重新亮屏后马上不动」这一种情况——
 * 而那正是用户此刻最想要的。代价是「连续息屏」不再被拦，可它本来就是用户的正常用法。
 */
enum class Phase {
    IDLE,
    ARMED,
    MONITORING,
    PENDING_FIRE,
    PAUSED,
}

/**
 * 「为什么现在不在监测」。
 *
 * `ARMED` 太笼统：屏幕灭着、不在生效时段、没插电，三者都是「已就绪但条件不满足」，
 * 光看状态环分不出来，用户只能对着「已就绪」等一个永远不会到来的息屏。
 * 把原因单独带出来，界面才给得出一句有用的话。
 */
enum class BlockReason {
    NONE,
    SCREEN_OFF,
    OUT_OF_SCHEDULE,
    NEEDS_CHARGING,
    PAUSED,
}

data class EngineSnapshot(
    val granted: Boolean = false,
    val running: Boolean = false,
    val phase: Phase = Phase.IDLE,
    /** 当前已连续静止的秒数，仅 MONITORING / PENDING_FIRE 有意义。 */
    val idleSeconds: Long = 0L,
    val blocked: BlockReason = BlockReason.NONE,
    /** 暂停到期的墙钟时间戳，仅 PAUSED 有意义，供 UI 显示「至 13:05」。 */
    val pausedUntil: Long = 0L,
    /** 灵敏度试调探针，仅界面驻留时非空。见 [SensitivityProbe]。 */
    val probe: SensitivityProbe? = null,
)

/**
 * 灵敏度试调的实时读数（「打开设置页就能试手感」）。
 *
 * 与状态机的判定**刻意分开**：状态机只有在屏幕亮、在时段内时才采样，
 * 而用户调滑块时往往正是想立刻看到反馈，哪怕还没开始监测。所以这个探针有自己的
 * 采样窗口和生命周期（只随界面开关），不去动状态机的任何状态，两个判定互不污染。
 */
data class SensitivityProbe(
    /** 当前窗口标准差（合加速度，m/s²）。 */
    val sigma: Float = 0f,
    /** 当前生效的判定阈值。 */
    val threshold: Float = 0f,
    /** 这一拍是否被判定为「有动作」。界面上就是那个「捕获到」指示。 */
    val captured: Boolean = false,
)

/** 设置页需要把探针开起来（连上）还是关掉（断开）。 */
private val probeSubscribers = AtomicInteger(0)

/**
 * UI 与 Service 之间唯一的状态通道（PRD 7.5 / D10）。
 *
 * 用进程内单例而不是 Bound Service / LocalBroadcast：Service 与 UI 同进程
 * （清单里没有 android:process），共享内存最简、零序列化、零延迟。
 *
 * 注意这里**只有状态、没有配置**。配置的唯一通道是 DataStore：
 * UI 只写、Service 只 collect。连「暂停」这种瞬时命令也落成 pausedUntil 时间戳，
 * 于是不需要任何消息通道，也就不存在两套状态互相打架的可能。
 */
object EngineState {

    private val _snapshot = MutableStateFlow(EngineSnapshot())

    val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    /**
     * 本应用的界面此刻是不是在前台。
     *
     * 悬浮窗要拿它做一件事：**界面在前台时把浮层收起来**。否则状态环和浮层会同时
     * 显示同一句话，而且浮层压在自家界面上很碍事。界面自己看得见状态，不需要浮层复述。
     *
     * 单独一条流而不是塞进 [EngineSnapshot]：它由 Activity 的生命周期写、由 Service 读，
     * 与引擎状态无关，混进去会让「快照」这个概念变得不干净。
     */
    private val _uiVisible = MutableStateFlow(false)

    val uiVisible: StateFlow<Boolean> = _uiVisible.asStateFlow()

    fun setUiVisible(visible: Boolean) {
        _uiVisible.value = visible
    }

    fun setGranted(granted: Boolean) = _snapshot.update {
        it.copy(granted = granted, phase = if (granted) it.phase else Phase.IDLE)
    }

    fun setRunning(running: Boolean) = _snapshot.update {
        it.copy(running = running, phase = if (running) it.phase else Phase.IDLE)
    }

    fun setPhase(phase: Phase) = _snapshot.update { it.copy(phase = phase) }

    fun setIdleSeconds(seconds: Long) = _snapshot.update { it.copy(idleSeconds = seconds) }

    fun setBlocked(reason: BlockReason) = _snapshot.update { it.copy(blocked = reason) }

    fun setPausedUntil(at: Long) = _snapshot.update { it.copy(pausedUntil = at) }

    fun setProbe(probe: SensitivityProbe?) = _snapshot.update { it.copy(probe = probe) }

    /**
     * 灵敏度试调通道的引用计数。
     *
     * 用计数而不是布尔：Compose 里多个位置（开关、预览卡片）都可能订阅同一条通道，
     * 谁先走谁后走不确定。布尔量会被先走的那次关掉，症状是「另一个还在看的组件
     * 忽然不再更新」。计数天然幂等，最后一个走的人负责关灯。
     */
    fun acquireProbe(): Int = probeSubscribers.incrementAndGet()

    fun releaseProbe(): Int = probeSubscribers.updateAndGet { if (it > 0) it - 1 else 0 }

    val probeActive: Boolean get() = probeSubscribers.get() > 0

    /**
     * 复位到「已授权但未运行」，供服务停止 / 出错收摊时调用。
     * 不动 granted。那是设备管理员的真实状态，由 Admin.isActive() 决定，不由此处臆断。
     */
    fun resetToIdle() = _snapshot.update {
        it.copy(
            running = false,
            phase = Phase.IDLE,
            idleSeconds = 0L,
            blocked = BlockReason.NONE,
            pausedUntil = 0L,
            probe = null,
        )
    }
}
