package com.nightdoze.app.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.nightdoze.app.R
import com.nightdoze.app.admin.Admin
import com.nightdoze.app.data.ScheduleMode
import com.nightdoze.app.data.SensitivityScale
import com.nightdoze.app.data.Settings
import com.nightdoze.app.data.SettingsStore
import com.nightdoze.app.data.parseHhmm
import com.nightdoze.app.ui.MainActivity
import java.util.Calendar
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 常驻监测服务：本产品的全部逻辑都在这里（PRD 7.3 / 3.2–3.6）。
 *
 * 判定模型只有一条推论：**屏幕亮着 + 手机持续静止 = 没人看**。
 * 不监听触摸（普通 App 做不到全局触摸监听），也不申请 WakeLock。
 * 人只要还在操作，手机必然在动；睡着了手机才会持续静默。
 *
 * 省电上同样干净：**屏幕一灭立刻停采样**，只等亮屏广播，静止期间不做任何定时唤醒。
 *
 * 所有平台调用都包在 try 里：服务 `onCreate` 里未捕获的异常会连整个进程一起带走，
 * 对外表现就是「点开就闪退」，且没有任何线索。
 */
class MonitorService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    /**
     * DataStore 的最新设置。
     *
     * 服务与 UI 同进程，配置只有 DataStore 一条通道（PRD 7.5 / D10）：UI 只写、这里只收。
     * 读它的既有每秒一拍的 ticker（Dispatchers.Default），也有传感器回调（主线程），
     * 所以必须 @Volatile。换个引用而已，不需要锁。
     */
    @Volatile
    private var settings = Settings()

    private var screenOn = false
    private var charging = false

    // ---------- 传感器状态 ----------

    private var sampling = false
    private val mags = ArrayDeque<Float>(WINDOW)

    /** 保护 [mags]：传感器回调在主线程，ticker / warn 在后台线程。 */
    private val windowLock = Any()

    /** 环境噪声底（σ 的慢速估计）。负值表示还没标定。 */
    private var baseline = -1f

    /** 最近一次判定为「动」的时刻。静止时长 = now - lastMotionAt，不另设计数器。 */
    @Volatile
    private var lastMotionAt = 0L

    /** 进入预警那一刻的 lastMotionAt。两者不等即表示预警期内动过（PRD 3.6）。 */
    private var warnedAtMotion = 0L

    // ---------- 动作确认（v0.8） ----------
    //
    // 睡着时的无意识抽动曾让计时反复归零：旧实现是「任一样本 σ 超阈值 → lastMotionAt = now」，
    // 一个 200ms 的抖动就够重启一轮，T 一长就永远走不完。
    // 现在在「判定为动」之后再加一层确认：只有累计足够时长的动作才归零，见 accumulateMotion()。
    // 判定阈值（σ / baseline / k）本身完全不变，确认只是加在它后面。

    /** 上一拍的时刻。用来把连续动作累计成时长（5Hz 下 dt 约 200ms）。 */
    private var lastSampleAt = 0L

    /**
     * 上一拍被**接受**的时刻（降采样闸门）。
     *
     * 见 [onSensorChanged] 顶部：系统按最快的客户端给数据，我们实际拿到 50Hz。
     * 不拦一下，下面所有按 5Hz 标定的常量（窗口、α、间隔样本数）全部失准。
     */
    private var lastAcceptedAt = 0L

    /** 当前这一「动作段」的累计时长（毫秒）。连续非动作样本够多即清零。 */
    private var motionRunMs = 0L

    /** 连续非动作样本数。达到 [MOTION_GAP_SAMPLES] 即视为这一小段动作结束。 */
    private var quietSamples = 0

    /** 本动作段内已计过的触发数，防止同一段动作被重复计数。 */
    private var countedInEpisode = 0

    /** 当前确认簇里已累计的触发数。够 [CONFIRM_COUNT] 就把计时归零。 */
    private var clusterCount = 0

    /** 确认簇的起点。超过 [CONFIRM_WINDOW_MS] 还没凑够触发数就作废重计。 */
    private var clusterStartAt = 0L

    /** σ/baseline 的采样日志节流。真机调参全靠它，见 logSensor()。 */
    private var lastSensorLogAt = 0L

    // ---------- 灵敏度试调探针 ----------
    //
    // 与状态机的采样完全分开：**独立的 SensorEventListener、独立的窗口、独立的 baseline**。
    //
    // 一开始图省事让探针复用状态机的 onSensorChanged，结果是真机日志里立刻看到
    // `σ=0.2912 baseline=0.2168 阈值=0.7451`。探针的 4 样本短窗在拿手机的人手上
    // 必然给出大 σ，而「判定为动」那一支会用 ADAPT_NOISY 把**状态机的** baseline
    // 一路顶上去，把功能自己搞钝。两条路径的学习速率与窗口都不同，共用任何一份状态
    // 都会互相污染，所以这里彻底分家：探针只写 probeBaseline，不碰状态机一个字段。
    //
    // 代价是探针开启时多一个 listener。5Hz 的短窗、只在设置页打开期间存在，可接受。

    private var probing = false
    private val probeMags = ArrayDeque<Float>(PROBE_WINDOW)
    private val probeLock = Any()
    private var probeBaseline = -1f
    private var lastProbePushAt = 0L
    private var probeListener: SensorEventListener? = null

    // ---------- 通知 ----------

    /**
     * 前台服务通知**只为了让系统允许这个服务活着**，不再给用户看。
     *
     * 状态文字与预警已经搬到悬浮窗（[OverlayWindow]），通知本身恒定不变，
     * 所以这里没有任何「变了才刷新」的逻辑——那是通知还在当状态面板时才需要的东西。
     */
    private val overlay = OverlayWindow(
        context = this,
        onMoved = { x, y -> scope.launch { SettingsStore.setOverlayPos(this@MonitorService, x, y) } },
    )

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
            Log.i(TAG, if (screenOn) "屏幕点亮" else "屏幕熄灭")
            // 立刻响应，不等下一拍：屏幕灭的瞬间就该停采样
            safeTick()
        }
    }

    /** 桩型广播，注册时立刻回一次当前电量状态，之后只在变化时回调。 */
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateCharging(intent)
        }
    }

    private fun updateCharging(intent: Intent?) {
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    override fun onCreate() {
        super.onCreate()

        try {
            createChannel()
        } catch (t: Throwable) {
            // 建渠道失败不致命：通知还能发，只是可能走默认档位
            Log.w(TAG, "创建通知渠道失败", t)
        }

        try {
            startAsForeground()
        } catch (t: Throwable) {
            // 前台服务立不起来就没有「服务在跑」的凭据，只能收工
            Log.w(TAG, "前台服务启动失败", t)
            stopSelf()
            return
        }

        EngineState.setRunning(true)
        EngineState.setGranted(Admin.isActive(this))
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive == true

        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )
        // ACTION_BATTERY_CHANGED 是粘性广播：后续变化靠 receiver，
        // 而**注册那一刻的当前值必须显式取返回值**（receiver 不保证被回调）。
        // 只注册不读的后果是充电状态一直停在初始 false，「仅充电时生效」等于永不生效。
        updateCharging(registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))

        scope.launch {
            SettingsStore.flow(this@MonitorService).collect {
                settings = it
                syncOverlay()
            }
        }
        // 界面回到前台就把浮层收起来：状态环已经在说同一句话，浮层压着自家界面只会碍事
        scope.launch {
            EngineState.uiVisible.collect { syncOverlay() }
        }
        scope.launch { SettingsStore.touchLastSeen(this@MonitorService) }

        ticker = scope.launch {
            while (isActive) {
                safeTick()
                syncOverlay()
                delay(TICK_MS)
            }
        }

        // 探针的开关只在每秒这一拍检查。界面来回切时多活一拍最多多耗 5Hz×1s 的采样，
        // 不值得为它引入状态回调；反过来漏开一拍也只是延迟 1 秒才看到读数。
        ticker = scope.launch {
            while (isActive) {
                syncProbe()
                delay(TICK_MS)
            }
        }

        Log.i(TAG, "监测服务已启动｜屏幕${if (screenOn) "亮" else "灭"}｜充电$charging")
    }

    /**
     * 把探针的采样状态对齐到「当前还有没有人看」。
     *
     * 探针**不做时段/屏幕门控**：用户调灵敏度时就是想立刻试手感，
     * 此刻如果被「不在生效时段」挡住不给读数，滑块就变成了盲调。
     * 它的开销上限也就是 5Hz 的一段短窗，且只存在于设置页打开期间。
     */
    private fun syncProbe() {
        val want = EngineState.probeActive
        if (want == probing) return
        if (want) {
            startProbe()
        } else {
            stopProbe()
        }
    }

    private fun startProbe() {
        if (probing) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        synchronized(probeLock) { probeMags.clear() }
        probeBaseline = -1f
        lastProbePushAt = 0L

        // 独立 listener：见上方注释。即使状态机正在采样也不共用，否则两边互相污染
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                onProbeSample(sqrt(x * x + y * y + z * z))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        probeListener = listener
        sm.registerListener(listener, sensor, SAMPLING_US)
        probing = true
        Log.i(TAG, "灵敏度探针已开启")
    }

    private fun stopProbe() {
        if (!probing) return
        probeListener?.let {
            try {
                getSystemService(SensorManager::class.java)?.unregisterListener(it)
            } catch (t: Throwable) {
                Log.w(TAG, "注销探针监听失败", t)
            }
        }
        probeListener = null
        probing = false
        synchronized(probeLock) { probeMags.clear() }
        probeBaseline = -1f
        EngineState.setProbe(null)
        Log.i(TAG, "灵敏度探针已关闭")
    }

    /**
     * 探针推进。窗口更短（[PROBE_WINDOW] 个样本 = 0.8 秒），噪声底学习速率沿用
     * 状态机那两条 α（静止快、判定为动慢），但**只写 probeBaseline**。
     *
     * 短窗是刻意的：试调要的是「动一下马上亮、停手马上灭」的即时感。2 秒窗会让指示
     * 亮起来后拖 2 秒才灭，用户会以为自己没停手。代价是这根 baseline 比状态机的抖，
     * 但试调看的本来就是相对变化，不是绝对精度。
     */
    private fun onProbeSample(magnitude: Float) {
        val sigma: Float
        synchronized(probeLock) {
            probeMags.addLast(magnitude)
            while (probeMags.size > PROBE_WINDOW) probeMags.removeFirst()
            if (probeMags.size < PROBE_WINDOW) return
            sigma = stdDevOf(probeMags)
        }

        var base = probeBaseline
        if (base < 0f) base = sigma

        val threshold = max(FLOOR_SIGMA, SensitivityScale.kOf(settings.sensitivityT) * base)
        val captured = sigma > threshold

        probeBaseline = if (captured) {
            base + (sigma - base) * ADAPT_NOISY
        } else {
            base + (sigma - base) * ADAPT_QUIET
        }

        // 推送节流：5Hz 直接推 StateFlow 会让设置页每秒重组 5 次，纯属浪费。
        // 判定为「捕获」的那一刻必须立刻推（否则指示会滞后），其余情况按间隔推一次。
        val now = SystemClock.elapsedRealtime()
        if (captured || now - lastProbePushAt >= PROBE_PUSH_INTERVAL_MS) {
            lastProbePushAt = now
            EngineState.setProbe(SensitivityProbe(sigma, threshold, captured))
        }
    }

    /**
     * `START_STICKY`：被系统回收后自动重建。
     *
     * 两条边界必须让开，否则不是保活而是**违背用户意愿地复活**（PRD 7.3）：
     *  1. 用户自己关掉了开关 → 重建时立刻收工，绝不偷偷开回来；
     *  2. 授权已被撤销 → 同理，lockNow 本来就调不动了。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Admin.isActive(this)) {
            Log.i(TAG, "授权已失效，服务收工")
            stopSelf()
            return START_NOT_STICKY
        }

        scope.launch { SettingsStore.touchLastSeen(this@MonitorService) }
        return START_STICKY
    }

    override fun onDestroy() {
        ticker?.cancel()
        stopSampling()
        // 探针有独立的 listener，必须自己注销。不注销的话进程虽然走了、
        // 传感器回调却还挂着，界面会停在最后一帧读数
        stopProbe()
        // 浮层必须显式摘掉：窗口挂在系统上，服务没了它还会留在屏幕上
        overlay.release()
        try {
            unregisterReceiver(screenReceiver)
            unregisterReceiver(batteryReceiver)
        } catch (t: Throwable) {
            Log.w(TAG, "注销广播失败", t)
        }
        scope.cancel()
        EngineState.resetToIdle()
        Log.i(TAG, "监测服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 核心：每秒一拍的状态机 ----------

    private fun safeTick() {
        try {
            tick()
        } catch (t: Throwable) {
            // ticker 里漏出去的异常会把循环整个带走，而服务看起来还活着。最难查的那种
            Log.w(TAG, "tick 异常", t)
        }
    }

    /**
     * 判定优先级（PRD 3.2）：授权 → 暂停 → 屏幕 → 时段 → 充电 → 监测。
     *
     * 顺序即语义：屏幕灭着的时候不该报「不在时段」，因为它根本没在等时段。
     */
    private fun tick() {
        if (!Admin.isActive(this)) {
            Log.i(TAG, "授权已失效，服务收工")
            stopSelf()
            return
        }

        val s = settings
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()

        // 1) 暂停。优先级只低于 IDLE。用户明确说了别管我，其余条件一律不必再看
        if (s.pausedUntil > 0L && s.pausedUntil > nowWall) {
            stopSampling()
            EngineState.setPausedUntil(s.pausedUntil)
            setBlocked(BlockReason.PAUSED)
            applyPhase(Phase.PAUSED)
            return
        }
        // 到点了才清。**不能用 `!= 0L` 判**：暂停时长可由用户设到 5 分钟，秒针还没走完
        // 就会被清成 0，而界面那一拍读到的仍是旧的结束时刻，于是显示「已暂停」实际已恢复。
        if (s.pausedUntil in 1..nowWall) {
            EngineState.setPausedUntil(0L)
            scope.launch { SettingsStore.clearPausedUntil(this@MonitorService) }
        }

        // 2) 屏幕灭 → 立刻停采样，等亮屏广播（省电的关键：灭屏期间零采样）
        if (!screenOn) {
            stopSampling()
            lastMotionAt = 0L
            setBlocked(BlockReason.SCREEN_OFF)
            applyPhase(Phase.ARMED)
            return
        }

        // 3) 生效时段
        if (!inSchedule(s)) {
            stopSampling()
            setBlocked(BlockReason.OUT_OF_SCHEDULE)
            applyPhase(Phase.ARMED)
            return
        }

        // 4) 仅充电时生效
        if (s.chargingOnly && !charging) {
            stopSampling()
            setBlocked(BlockReason.NEEDS_CHARGING)
            applyPhase(Phase.ARMED)
            return
        }

        // 5) 条件齐了 → 采样计时
        setBlocked(BlockReason.NONE)
        if (!sampling) {
            startSampling()
            lastMotionAt = nowElapsed
        }
        if (lastMotionAt == 0L) lastMotionAt = nowElapsed

        val idleSec = (nowElapsed - lastMotionAt) / 1000
        // 预警放在阈值**之前**：静止满 T 就息屏，预警只是提前 warnSeconds 提醒一下，
        // 总时长仍是 T（与界面文案「到点前先轻震一下」一致）
        val threshold = s.idleMinutes * 60L

        if (EngineState.snapshot.value.phase == Phase.PENDING_FIRE) {
            if (lastMotionAt != warnedAtMotion) {
                Log.i(TAG, "预警期内检测到已确认动作，本轮取消（已静止 ${idleSec}s）")
                applyPhase(Phase.MONITORING)
                EngineState.setIdleSeconds(0)
            } else if (idleSec >= threshold) {
                fire()
                return
            } else {
                EngineState.setIdleSeconds(idleSec)
            }
        } else {
            applyPhase(Phase.MONITORING)
            EngineState.setIdleSeconds(idleSec)
            // 预警窗起点 = T − 预警时长。夹住上界，防止 T 被调到比预警时长还小时算出负值
            val warnAt = threshold - s.warnSeconds.toLong().coerceAtMost(threshold)
            when {
                s.warnSeconds > 0 && idleSec >= warnAt -> {
                    warnedAtMotion = lastMotionAt
                    applyPhase(Phase.PENDING_FIRE)
                    warn()
                }

                s.warnSeconds == 0 && idleSec >= threshold -> {
                    fire()
                    return
                }
            }
        }
    }

    /**
     * 触发息屏。
     *
     * 息屏后不再有冷却期：屏幕会被系统熄灭，而屏幕灭着本来就不采样（见 tick 第 2 条）。
     * 用户重新点亮屏幕时想立刻重新计时，而不是先等一段谁也说不清的「冷却」——
     * 从前那 5 分钟会让「亮屏后不动」白白空转，看起来就是功能坏了。
     */
    private fun fire() {
        val ok = Admin.lockNow(this)
        Log.i(TAG, if (ok) "触发息屏" else "触发息屏失败：授权已失效")
        if (!ok) {
            // 授权刚被撤销的那一瞬会走到这里，收工把状态交还给用户
            stopSelf()
            return
        }

        stopSampling()
        lastMotionAt = 0L
        scope.launch { SettingsStore.touchLastSeen(this@MonitorService) }
        EngineState.setIdleSeconds(0)
        // 锁屏到系统真正熄灭屏幕之间还有几拍，此时屏幕仍是亮的。若报 ARMED 会有一瞬
        // 显示「有动作即开始计时」，所以先落到「屏幕已熄灭」，由灭屏广播接管后续。
        setBlocked(BlockReason.SCREEN_OFF)
        applyPhase(Phase.ARMED)
    }

    /**
     * 息屏前的轻提醒：**只把悬浮窗提亮一下**（PRD 3.6 改版）。
     *
     * 原来这里是「换通知文案 + 震一次」。两个都去掉了：通知栏不再显示状态，
     * 震动在床头柜/硬桌面上还会被自己的加速度计读到（老实现要专门清窗口来躲它，
     * 见下）。现在提醒就是那颗药丸从绿变黄、闪一下——不吵人，而且它本来就在屏幕上。
     *
     * 因此**不再需要 clearWindow()**：那行代码存在的唯一理由是「躲开自己震出来的动静」，
     * 震动没了，自己的提醒就不可能被自己判定成「有人在动」。
     */
    private fun warn() {
        syncOverlay()
        overlay.pulse()
        Log.i(TAG, "进入预警：${settings.warnSeconds}s 后息屏")
    }

    private fun applyPhase(phase: Phase) {
        val current = EngineState.snapshot.value.phase
        if (current != phase) {
            Log.i(TAG, "状态 $current → $phase")
            EngineState.setPhase(phase)
        }
    }

    private fun setBlocked(reason: BlockReason) {
        if (EngineState.snapshot.value.blocked != reason) {
            EngineState.setBlocked(reason)
        }
    }

    /**
     * 生效时段判定（PRD 3.5）。
     *
     * 跨午夜走 `now >= start || now < end`；起止相同（22:00–22:00）视为**全天**。
     * 这个输入没有合理语义，而本产品是兜底工具，宁可多管一次也不要静默失效。
     * 解析失败同样按全天处理。
     */
    private fun inSchedule(s: Settings): Boolean {
        if (s.schedule == ScheduleMode.ALL_DAY) return true
        val start = parseHhmm(s.nightStart) ?: return true
        val end = parseHhmm(s.nightEnd) ?: return true
        if (start == end) return true

        val now = Calendar.getInstance()
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return if (start < end) nowMin in start until end else nowMin >= start || nowMin < end
    }

    // ---------- 传感器：静止判定 ----------

    private fun startSampling() {
        if (sampling) return
        val sm = getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            // 没有加速度计就没法判定静止。只能说明白，不能假装在工作
            Log.w(TAG, "设备没有加速度计，静止判定不可用")
            return
        }
        clearWindow()
        resetMotionConfirm()
        baseline = -1f
        // 显式给微秒，不用 SENSOR_DELAY_*：那几个常量在不同厂商 ROM 上的实际频率
        // 解释不一致。**但这只是「请求」**——系统会按同一传感器上最快的客户端发数据
        // （本机实测被顶到 50Hz）。真正的 5Hz 由 onSensorChanged 顶部的闸门保证。
        sm.registerListener(this, sensor, SAMPLING_US)
        sampling = true
        Log.i(TAG, "开始采样加速度计 @5Hz")
    }

    private fun stopSampling() {
        if (!sampling) return
        getSystemService(SensorManager::class.java)?.unregisterListener(this)
        sampling = false
        clearWindow()
        resetMotionConfirm()
        baseline = -1f
        Log.i(TAG, "停止采样")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // ---------- 降采样闸门（v0.8，真机实测驱动） ----------
        //
        // `registerListener` 的周期只是「请求」。SensorService 会把**同一传感器所有客户端**的
        // 请求取最快的那一个，然后按它给所有人发数据。
        //
        // 本机实测（`dumpsys sensorservice`）：
        //   0x00000001) active-count = 3; sampling_period(ms) = {200.0, 200.0, 20.0}, selected = 20.00 ms
        // 三个客户端里有一个系统自己的 GameStateListener 要 50Hz，于是 selected = 20ms——
        // **我们明明请求 5Hz，实际被以 50Hz 喂数据，快 10 倍。**
        //
        // 后果是整套常量全部失准：WINDOW=10 从 2 秒窗缩成 0.2 秒（σ 抖到 6–8 m/s²），
        // ADAPT_QUIET=0.05 的时间常数从 4 秒缩成 0.2 秒，于是**任何持续动作都会被基线
        // 在 0.2 秒内追平并吸收成「噪声底」**，判定只在这段动作的头几个样本成立。
        // 真机症状就是「明明在故意用力摇晃，它却检测不出来」。
        //
        // 修法：在入口按时间闸门丢样本，把有效速率钉回 ~5Hz。
        // 取 180ms 而不是 200ms：源正好 5Hz 时每拍都能过（不会被误丢成 2.5Hz）。
        // 阈值比源周期小就永远只会「少收」，不会「漏收」。
        val acceptedAt = SystemClock.elapsedRealtime()
        if (acceptedAt - lastAcceptedAt < ACCEPT_INTERVAL_MS) return
        lastAcceptedAt = acceptedAt

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        // 合加速度的模：手机朝向无关，用户平放/竖放/侧放都不影响阈值
        val magnitude = sqrt(x * x + y * y + z * z)

        if (!sampling) return

        // 窗口会被主线程（本回调）与 ticker 线程（startSampling / stopSampling / warn）同时碰，
        // ArrayDeque 不是线程安全的。临界区只有"加一个数、算一次标准差"，5Hz 下无争用可言，
        // 所以直接用 synchronized 而不是换成并发容器。
        val sigma: Float
        synchronized(windowLock) {
            mags.addLast(magnitude)
            while (mags.size > WINDOW) mags.removeFirst()
            if (mags.size < WINDOW) return
            sigma = stdDevOf(mags)
        }

        if (baseline < 0f) baseline = sigma

        // 灵敏度是滑块位置（0..1）→ 噪声倍数 k。取值与映射都在 Settings 侧，
        // 服务只消费，避免同一套换算散落两处（PRD 7.6）
        val k = SensitivityScale.kOf(settings.sensitivityT)
        val threshold = max(FLOOR_SIGMA, k * baseline)
        val moving = sigma > threshold

        if (moving) {
            // 「动」的时候也慢慢更新噪声底，但慢 10 倍：床头无线充电器的风扇、
            // 音箱、按摩仪这类持续微振必须能被吸收掉，否则功能永远不触发（PRD R2）。
            // 而人手的动作是几秒级的瞬态，慢速学习不会被它带偏。
            baseline += (sigma - baseline) * ADAPT_NOISY
        } else {
            baseline += (sigma - baseline) * ADAPT_QUIET
        }

        // lastMotionAt 只在「动作确认」之后才更新，见 accumulateMotion()。
        // 不再是一动就归零——那是睡着时抽动能把计时反复打断的根因。
        accumulateMotion(moving)

        logSensor(sigma, threshold, moving)
    }

    /**
     * 动作确认（v0.8）：短促抽动不重置计时，只有「累计够时长的动作」才归零。
     *
     * 两段式判据：
     *  1. **一次触发** = 连续动作累计满 [SUSTAIN_MS]（1 秒）。短于它的抽动（肌肉抽动、
     *     抓被子那一下）根本不计数，计时照走。
     *  2. **需要 [CONFIRM_COUNT] 次触发**（≈累计 2 秒动作）才把 `lastMotionAt` 归零，
     *     且这些触发必须落在 [CONFIRM_WINDOW_MS]（1 分钟）内。孤立的单次抽动永远凑不齐，
     *     而人在真的用手机时动作是连续的，几秒内就会凑够。
     *
     * 这一层**不改判定阈值**（σ / baseline / k 全不动），只作用于「判定为动」之后。
     * 预警窗沿用同一规则（PRD 3.6 的取消判据比较的还是 `lastMotionAt`），
     * 所以睡着的抽动落在最后 10 秒里也不会再把本轮取消掉。
     */
    private fun accumulateMotion(moving: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (lastSampleAt == 0L) {
            lastSampleAt = now
            return
        }
        // 进程被冻结后 dt 会异常大，夹一下防止凭空凑出一次触发
        val dt = (now - lastSampleAt).coerceIn(0L, MAX_SAMPLE_DT_MS)
        lastSampleAt = now

        if (!moving) {
            quietSamples++
            if (quietSamples >= MOTION_GAP_SAMPLES) {
                // 这一小段动作结束：本段累计归零。
                // 确认簇**不在此处清空**——两次触发允许来自两次独立动作，只要落在窗口内。
                motionRunMs = 0L
                countedInEpisode = 0
            }
            return
        }

        quietSamples = 0
        motionRunMs += dt

        // 本段累计已满几次触发。每满一次 SUSTAIN_MS 记一次，一段连续动作可产生多次触发
        val total = (motionRunMs / SUSTAIN_MS).toInt()
        if (total <= countedInEpisode) return
        countedInEpisode = total

        // 超出确认窗口 → 簇作废，这次触发当作新的第一次
        if (clusterStartAt != 0L && now - clusterStartAt > CONFIRM_WINDOW_MS) {
            clusterCount = 0
            clusterStartAt = 0L
        }
        if (clusterCount == 0) clusterStartAt = now
        clusterCount++

        if (clusterCount >= CONFIRM_COUNT) {
            lastMotionAt = now
            clusterCount = 0
            clusterStartAt = 0L
            Log.i(TAG, "动作已确认（本段累计 %.1fs），计时归零".format(motionRunMs / 1000f))
        } else {
            Log.i(TAG, "动作未确认（本段累计 %.1fs），计时继续".format(motionRunMs / 1000f))
        }
    }

    /** 采样起停时把确认状态清干净，避免跨会话带着上次的簇计数。 */
    private fun resetMotionConfirm() {
        lastSampleAt = 0L
        lastAcceptedAt = 0L
        motionRunMs = 0L
        quietSamples = 0
        countedInEpisode = 0
        clusterCount = 0
        clusterStartAt = 0L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun clearWindow() = synchronized(windowLock) { mags.clear() }

    /** 窗口标准差。两个采样路径（状态机长窗、探针短窗）共用同一段计算。 */
    private fun stdDevOf(window: ArrayDeque<Float>): Float {
        var sum = 0f
        for (v in window) sum += v
        val mean = sum / window.size
        var acc = 0f
        for (v in window) {
            val d = v - mean
            acc += d * d
        }
        return sqrt(acc / window.size)
    }

    /**
     * 采样值日志，5 秒一条。真机调参唯一的依据（阈值的绝对值没法拍脑袋定，
     * 不同机型加速度计噪声差一个数量级）。
     */
    private fun logSensor(sigma: Float, threshold: Float, moving: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSensorLogAt < SENSOR_LOG_INTERVAL_MS) return
        lastSensorLogAt = now
        Log.i(
            TAG,
            "σ=%.4f baseline=%.4f 阈值=%.4f %s".format(
                sigma, baseline, threshold, if (moving) "→ 判定为动" else "→ 静止"
            ),
        )
    }

    // ---------- 常驻通知（系统凭据，不面向用户） ----------

    private fun startAsForeground() {
        // API 34 起必须显式给类型，否则 startForeground 直接抛
        // MissingForegroundServiceTypeException；33 及以下传 0 = 按清单声明走（PRD 7.10）
        val type = if (Build.VERSION.SDK_INT >= API_34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    /**
     * 前台服务通知。**内容恒定，不再随状态变化。**
     *
     * 它现在是系统要求，不是给用户看的：Android 不允许「没有通知的前台服务」。
     * 状态文字搬去悬浮窗之后，连刷新都不需要了——省掉每次状态迁移一次发往
     * system_server 的 IPC，那套「文案变了才重发」的节流逻辑也一并去掉。
     *
     * 通知本身在 Android 13+ 上默认不出现：本应用不再申请 POST_NOTIFICATIONS，
     * 而新装的 App 在 13+ 上通知默认关闭；渠道档位另压到最低，见 [createChannel]。
     * 注意这台机器上系统仍会在「正在运行的应用」里列出本服务，那是系统行为，改不掉。
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_ready))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    /**
     * 渠道档位取 **MIN**，与旧版的 HIGH 相反。
     *
     * 需求反了，结论就反了：通知从前是状态面板（要看得见，所以取 HIGH 再去关声音），
     * 现在只是系统凭据（要看不见，所以取最低档）。
     *
     * 渠道 id 带版本后缀：重要性创建后不可修改，换档位只能换 id。旧的 v1 顺手删掉，
     * 否则用户的通知设置里会留一条同名死项。
     */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "删除旧通知渠道失败", t)
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_monitor),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                description = getString(R.string.notif_ready)
            }
        )
    }

    // ---------- 悬浮窗 ----------

    /**
     * 把浮层对齐到「此刻该不该显示、该说什么」。
     *
     * 每秒调一次（ticker）、每次设置变化、界面前后台切换时各调一次。
     * 重复调用很廉价：[OverlayWindow] 内部只在文案/底色真的变了才碰视图。
     *
     * 三种情况不显示，缺一种都会出别扭的东西：
     *  - 用户关掉了悬浮窗开关；
     *  - 没拿到「显示在其他应用上层」权限（`canDrawOverlays` 为假时 `addView` 会直接抛）；
     *  - 本应用界面在前台（状态环已经把同一句话说得更完整）。
     */
    private fun syncOverlay() {
        val s = settings
        if (!s.overlayEnabled || !canDrawOverlay() || EngineState.uiVisible.value) {
            overlay.hide()
            return
        }
        val snap = EngineState.snapshot.value
        overlay.setStatus(overlayText(snap), overlayColor(snap))
        overlay.show(s.overlayX, s.overlayY, s.overlayAlpha)
    }

    /**
     * 浮层文案。和状态环说同一件事，但**必须更短**：这是在别人的应用上面飘着，
     * 一行十几字封顶。所以这里不写「已暂停 · 至 13:05 恢复」这种完整句，
     * 只留用户此刻要的那半句。
     */
    private fun overlayText(snap: EngineSnapshot): String = when (snap.phase) {
        Phase.MONITORING -> getString(R.string.overlay_monitoring, clock(snap.idleSeconds))
        Phase.PENDING_FIRE -> getString(R.string.overlay_pending_fire)
        Phase.PAUSED -> getString(R.string.overlay_paused, clockWall(snap.pausedUntil))
        Phase.ARMED, Phase.IDLE -> when (snap.blocked) {
            BlockReason.SCREEN_OFF -> getString(R.string.overlay_screen_off)
            BlockReason.OUT_OF_SCHEDULE -> getString(R.string.overlay_out_of_schedule)
            BlockReason.NEEDS_CHARGING -> getString(R.string.overlay_needs_charging)
            BlockReason.PAUSED -> getString(R.string.overlay_paused, clockWall(snap.pausedUntil))
            else -> getString(R.string.overlay_ready)
        }
    }

    /**
     * 底色只表达三件事：正在计时（绿）、马上息屏（黄）、其他（灰）。
     * 状态不靠颜色单独传达——文字已经把话说完了，颜色只负责让「变黄了」更早被注意到。
     */
    private fun overlayColor(snap: EngineSnapshot): Int = when (snap.phase) {
        Phase.PENDING_FIRE -> OverlayWindow.COLOR_WARN
        Phase.MONITORING -> OverlayWindow.COLOR_MONITORING
        else -> OverlayWindow.COLOR_IDLE
    }

    /**
     * 「显示在其他应用上层」是否已授权。
     *
     * 必须提前问：没权限时 `WindowManager.addView` 直接抛 `BadTokenException`，
     * 而这里每秒都会调一次，靠 catch 兜底等于每秒写一条日志。
     */
    private fun canDrawOverlay(): Boolean =
        try {
            android.provider.Settings.canDrawOverlays(this)
        } catch (t: Throwable) {
            Log.w(TAG, "查询悬浮窗权限失败", t)
            false
        }

    private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun clockWall(at: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    companion object {
        private const val TAG = "NightDozeService"
        private const val API_34 = 34

        /** 渠道重要性创建后不可修改，换档位必须换 id，故带版本后缀。 */
        private const val CHANNEL_ID = "nightdoze_monitor_v2"

        /** 旧渠道（HIGH 档、带动作按钮）。每次启动顺手删掉，见 createChannel()。 */
        private const val LEGACY_CHANNEL_ID = "nightdoze_monitor_v1"

        private const val NOTIF_ID = 1001
        private const val REQUEST_OPEN = 0

        /** 状态机节拍。1 秒足够，且屏幕灭后进程会被冻结，实际不会持续跑。 */
        private const val TICK_MS = 1000L

        /**
         * 采样周期（微秒）→ 请求 5Hz。
         *
         * **只是请求，不是保证**：SensorService 按同一传感器上所有客户端里最快的那个
         * 发数据（本机被系统自己的 50Hz 客户端顶到 20ms）。有效速率由
         * [ACCEPT_INTERVAL_MS] 的闸门钉死，见 [onSensorChanged]。
         */
        private const val SAMPLING_US = 200_000

        /**
         * σ 的滑动窗口。10 个样本 = 2 秒，够短以捕捉动作，够长以滤掉单点毛刺。
         *
         * 「10 个样本 = 2 秒」只在有效速率为 5Hz 时成立——**这依赖 [ACCEPT_INTERVAL_MS]
         * 的降采样闸门**。删掉那道闸门，本机的实际速率是 50Hz，这个窗就只剩 0.2 秒。
         */
        private const val WINDOW = 10

        /** 探针窗口。0.8 秒。试调要的是即时感，指示亮起/熄灭不能拖到 2 秒。 */
        private const val PROBE_WINDOW = 4

        /** 探针推送节流（毫秒）。判定为「捕获」时不受此限，界面指示不会滞后。 */
        private const val PROBE_PUSH_INTERVAL_MS = 200L

        /** 判定阈值下限（m/s²）。低于它的差异都算传感器噪声，不可能是「人的动作」。 */
        private const val FLOOR_SIGMA = 0.025f

        // ---------- 动作确认（v0.8，见 accumulateMotion） ----------

        /**
         * 降采样闸门：两拍之间至少隔这么久才接受新样本（≈5.5Hz 上限）。
         *
         * 必须**小于**正常源周期 200ms：源正好 5Hz 时每一拍都要能过，
         * 否则会把 5Hz 误降成 2.5Hz。180ms 只会在源更快时少收，不会在源正常时漏收。
         */
        private const val ACCEPT_INTERVAL_MS = 180L

        /** 一次「触发」所需的连续动作累计时长。短于它的抽动不计数——本层的主旋钮。 */
        private const val SUSTAIN_MS = 1000L

        /** 需要几次触发才把静止计时归零。2 次 ≈ 累计 2 秒动作。 */
        private const val CONFIRM_COUNT = 2

        /**
         * 两次触发须落在同一窗口内，超出即重新计数。
         *
         * 取 1 分钟而不是更长：睡眠中的无意识动作常常是「一两分钟内动几下、然后长时间不动」，
         * 窗口开大就等于把这些抽动攒成一簇、照样归零。1 分钟只容得下真正连续的操作。
         */
        private const val CONFIRM_WINDOW_MS = 60_000L

        /** 连续多少个非动作样本算「这一小段动作结束」（5Hz × 5 ≈ 1 秒）。 */
        private const val MOTION_GAP_SAMPLES = 5

        /** 单拍 dt 上界。进程被冻结后 dt 会很大，不夹住会凭空凑出一次触发。 */
        private const val MAX_SAMPLE_DT_MS = 500L

        /** 静止时噪声底的学习速率。5Hz 下时间常数约 4 秒（同样依赖 [ACCEPT_INTERVAL_MS] 的闸门）。 */
        private const val ADAPT_QUIET = 0.05f

        /** 判定为动时的学习速率，慢 10 倍：让持续微振被吸收，而人手动作不被吸收。 */
        private const val ADAPT_NOISY = 0.005f

        private const val SENSOR_LOG_INTERVAL_MS = 5000L

        /**
         * 启动监测服务。
         *
         * **调用时机受白名单限制**（PRD 7.3）：Android 12+ 从后台调
         * startForegroundService() 会抛 ForegroundServiceStartNotAllowedException，
         * 而屏幕亮灭、暂停到期这些后台事件都**不在豁免清单里**。这也是为什么
         * 本应用不做「到点自动启停服务」，而是让服务常驻、时段外只是不干活（PRD D2）。
         *
         * 合法调用方只有两个，都在豁免清单上：
         * 1. 用户主动交互（MainActivity 里开关打开时）
         * 2. [com.nightdoze.app.boot.BootReceiver] 收到开机 / 应用更新完成的广播
         */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        /** 停止服务。任何时机都允许调用，不受后台启动限制。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }
}
