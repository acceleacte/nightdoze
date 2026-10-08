package com.nightdoze.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nightdoze.app.data.ScheduleMode
import com.nightdoze.app.data.SensitivityScale
import com.nightdoze.app.data.Settings
import com.nightdoze.app.engine.BlockReason
import com.nightdoze.app.engine.EngineSnapshot
import com.nightdoze.app.engine.EngineState
import com.nightdoze.app.engine.Phase
import com.nightdoze.app.engine.SensitivityProbe
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 单页界面（PRD 5）。锁定竖屏，无导航、无二级页，打开即用。
 *
 * 所有设置项即时生效并落盘，**没有「保存」按钮**。
 */
@Composable
fun MainScreen(
    vm: MainViewModel,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val engine by vm.engine.collectAsStateWithLifecycle()
    val granted by vm.granted.collectAsStateWithLifecycle()
    val overlayGranted by vm.overlayGranted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<TimeField?>(null) }
    var editingCustomIdle by remember { mutableStateOf(false) }
    var editingPause by remember { mutableStateOf(false) }
    // 「你不用时段也行」是一次性的表态，不该每次回到页面又冒出来。
    // 存内存而非落盘：重启后重新提醒一次可以接受，为此多存一个键不值得。
    var dismissedAllDayHint by remember { mutableStateOf(false) }

    // 静置档位的最外层：固定档直接生效并弹 Toast，自定义档弹滚轮。
    // 抽成局部函数而不是塞进 IdleSection 的调用点，是因为 Toast 文案要同时用到
    // 「刚选了什么」和「当前是几点几分」（时段那条），放在这里能一次取全。
    val onIdlePreset: (Int) -> Unit = { minutes ->
        vm.setIdleMinutes(minutes)
        toast(
            context,
            if (minutes == Settings.TEST_IDLE_MINUTES) "已切到测试档 · $minutes 分钟"
            else "已切到 $minutes 分钟",
        )
    }
    val onCustomIdle: (Int) -> Unit = { minutes ->
        vm.setIdleCustomMinutes(minutes)
        toast(context, "已切到自定义 · ${idleText(minutes)}")
    }

    // 打开监测开关不再申请通知权限：状态改由悬浮窗承担，通知只是前台服务的系统凭据，
    // 不申请反而正是「通知栏里不出现它」的实现方式（Android 13+ 默认关闭通知）
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text("夜眠息屏", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(20.dp))

        if (!granted) {
            AuthCard(onGrant = { context.startActivity(vm.adminIntent()) })
            Spacer(Modifier.height(20.dp))
        }

        StatusRing(engine, granted)

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (settings.enabled) "监测已开启" else "监测已关闭",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = settings.enabled,
                enabled = granted,
                onCheckedChange = { want -> vm.setEnabled(want) },
            )
        }

        // 暂停 1 小时。从前这个入口是常驻通知上的动作按钮，通知不再对用户显示后
        // 挪到这里——功能不能因为没有入口而消失。
        if (settings.enabled) {
            Spacer(Modifier.height(10.dp))
            PauseRow(
                engine = engine,
                pauseMinutes = settings.pauseMinutes,
                onPickPause = { editingPause = true },
                onResume = { vm.resumeNow() },
            )
        }

        if (!granted) {
            Spacer(Modifier.height(6.dp))
            Text(
                "授权后才能开启",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(28.dp))

        IdleSection(
            settings = settings,
            onPreset = onIdlePreset,
            onOpenCustom = { editingCustomIdle = true },
        )

        SectionTitle("息屏前提醒", lead = "息屏前先提醒一次")
        ChipRow(
            options = Settings.WARN_CHOICES,
            selected = settings.warnSeconds,
            label = { if (it == 0) "不提醒" else "$it 秒" },
            onSelect = {
                vm.setWarnSeconds(it)
                toast(context, if (it == 0) "已关掉息屏前提醒" else "息屏前提醒 $it 秒")
            },
        )
        Hint(
            if (settings.warnSeconds == 0) {
                "静止满设定时长后直接息屏，不提醒。"
            } else {
                "息屏前 ${settings.warnSeconds} 秒，悬浮窗变色并亮起。" +
                    "此时连续动几秒，取消本轮息屏；轻碰不算，睡着后的无意识抽动也不算。"
            }
        )

        SectionTitle("状态悬浮窗", lead = "倒计时显示在悬浮窗上，盖在其他应用上方")
        OverlaySection(
            settings = settings,
            granted = overlayGranted,
            onToggle = { want ->
                vm.setOverlayEnabled(want)
                if (want && !overlayGranted) context.startActivity(vm.overlayIntent())
            },
            onAlpha = { vm.setOverlayAlpha(it) },
            onGrant = { context.startActivity(vm.overlayIntent()) },
        )

        SectionTitle("生效时段", lead = "设置一天中它生效的时间")

        // 只在「全天」且用户还没表态时显示推荐卡：已经选了定时的人清楚自己要什么，
        // 这时再推一次就是打扰；已经说了「我就想全天」的人同理。
        if (settings.schedule == ScheduleMode.ALL_DAY && !dismissedAllDayHint) {
            ScheduleGuideCard(
                onAccept = {
                    dismissedAllDayHint = true
                    vm.applyRecommendedSchedule()
                    toast(context, "已设为 ${Settings.RECOMMENDED_START}–${Settings.RECOMMENDED_END}，可修改")
                },
                onKeepAllDay = { dismissedAllDayHint = true },
            )
        }

        ChipRow(
            options = ScheduleMode.entries.toList(),
            selected = settings.schedule,
            label = {
                when (it) {
                    ScheduleMode.ALL_DAY -> "全天"
                    ScheduleMode.NIGHT -> "定时"
                }
            },
            onSelect = {
                vm.setSchedule(it)
                toast(
                    context,
                    if (it == ScheduleMode.ALL_DAY) "已切到全天"
                    else "已切到定时 · ${settings.nightStart}–${settings.nightEnd}"
                )
            },
        )

        // 起止时间只在「定时」下出现：全天模式摆两个用不上的时间，会让人以为设置没生效
        if (settings.schedule == ScheduleMode.NIGHT) {
            TimeRow("开始", settings.nightStart) { editing = TimeField.START }
            TimeRow("结束", settings.nightEnd) { editing = TimeField.END }
            Hint(
                "结束时间可晚于开始时间，跨过午夜算到第二天。" +
                    "（${settings.nightStart}–${settings.nightEnd} 表示从今天 ${settings.nightStart} 到明天 ${settings.nightEnd}。）" +
                    "两个时间设成相同，等于全天。"
            )
        }

        SectionTitle("动作灵敏度", lead = "设置多大的动静算「手机被移动」")
        SensitivitySection(
            value = settings.sensitivityT,
            probe = engine.probe,
            running = engine.running,
            onChange = { vm.setSensitivityT(it) },
            onProbe = { on -> vm.setSensitivityProbeOn(on) },
        )

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("仅在充电时生效", style = MaterialTheme.typography.bodyLarge)
            }
            Switch(
                checked = settings.chargingOnly,
                onCheckedChange = { vm.setChargingOnly(it) },
            )
        }

        Spacer(Modifier.height(28.dp))

        OutlinedButton(
            onClick = { vm.lockNowNow() },
            enabled = granted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("立即测试息屏")
        }
        Spacer(Modifier.height(8.dp))
        Hint("点一下立即锁屏。此操作与自动息屏使用同一段代码，可确认授权是否有效。无反应即授权失效，请回到页面上方重新授权。")

        if (granted) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { vm.releaseAdmin() }) {
                Text("解除设备管理员授权")
            }
            Hint("卸载前需先解除授权，否则系统会阻止卸载。")
        }

        Spacer(Modifier.height(36.dp))
    }

    // 滚轮时间选择器。放在 Column 之外：Dialog 自成一个窗口，与页面滚动无关
    editing?.let { field ->
        val isStart = field == TimeField.START
        WheelTimeDialog(
            title = if (isStart) "开始时间" else "结束时间",
            current = if (isStart) settings.nightStart else settings.nightEnd,
            onDismiss = { editing = null },
            onConfirm = { value ->
                if (isStart) vm.setNightStart(value) else vm.setNightEnd(value)
                editing = null
                toast(
                    context,
                    if (isStart) "开始时间已设为 $value" else "结束时间已设为 $value",
                )            },
        )
    }

    if (editingCustomIdle) {
        WheelNumberDialog(
            title = "自定义静置时长",
            options = Settings.IDLE_CUSTOM_STEPS,
            current = settings.idleCustomMinutes,
            unit = "分钟",
            // 把选中的分钟数翻译回人话：720 分钟写成「12 小时」比数字本身好判断
            suffix = { "${idleText(it)}未操作后息屏" },
            onDismiss = { editingCustomIdle = false },
            onConfirm = { minutes ->
                editingCustomIdle = false
                onCustomIdle(minutes)
            },
        )
    }

    // 暂停时长。选完当场生效——「暂停」这个动作本身就是一次点击，
    // 再让用户去别处点第二次才生效，就多了一步没意义的确认。
    if (editingPause) {
        WheelNumberDialog(
            title = "暂停时长",
            options = Settings.PAUSE_MINUTES_STEPS,
            current = settings.pauseMinutes,
            unit = "",
            hourMinute = true,
            onDismiss = { editingPause = false },
            onConfirm = { minutes ->
                editingPause = false
                vm.pauseFor(minutes)
                toast(context, "已暂停 · ${idleText(minutes)}后恢复")
            },
        )
    }
}

/** 生效时段里正在编辑哪一个时间。 */
private enum class TimeField { START, END }

/**
 * 「按作息设个时段」引导卡。
 *
 * 为什么值得做成一张能点的卡，而不是又一段灰色小字：
 * 时段这个设置唯一的价值是它没生效的那段时间，而这一点从「全天 / 定时」两个词上
 * 完全看不出来。用户只会觉得「全天」更省事更保险，于是永远停在默认档，
 * 白天在外被自动息屏一次之后就把整个功能关掉了。
 * 所以这里必须把「全天会带来什么代价」讲明白，并把设置成本降到一次点击。
 *
 * 文案全部用大白话，不出现「生效时段」「调度」「窗口」这类词。用户不需要知道
 * 我们内部怎么称呼它，只需要知道「不设会怎样」。
 */
@Composable
private fun ScheduleGuideCard(onAccept: () -> Unit, onKeepAllDay: () -> Unit) {
    val context = LocalContext.current

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "建议先按你的作息设置时段",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "当前为全天。白天手机长时间不动，也会息屏。\n\n" +
                    "填上睡觉时段，它只在这段时间生效。白天手机不动不会息屏。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onAccept) {
                    Text("用 ${Settings.RECOMMENDED_START}–${Settings.RECOMMENDED_END}")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        onKeepAllDay()
                        toast(context, "已按全天保留，可随时修改")
                    },
                ) {
                    Text("我就用全天")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "两个时间都可修改。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * 「静止多久后息屏」。
 *
 * 档位是三个固定值 + 一格「自定义」：固定档之间要有量级差（1 分测试 / 15 分 / 30 分），
 * 中间值一律走自定义滚轮。横滑一排七八个 chip 在手机上本来就得左右找，
 * 找完还常常没有想要的数。
 *
 * 「自定义」当前是不是选中态，靠 `idleMinutes` 落不落在固定档里判断：
 * 值本身就是唯一真相，不需要再存一个「当前选的是哪一格」的标记。
 * 那种标记和值一旦不同步，界面就会显示一个和实际行为不符的选中态。
 *
 * 前提是 `idleMinutes` 必须原样反映用户选的值。早先读路径上有个「就近夹到固定档」的
 * 取整，把自定义值毁掉了，于是这里永远显示成固定档——选中态没有错，是值本身错了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdleSection(
    settings: Settings,
    onPreset: (Int) -> Unit,
    onOpenCustom: () -> Unit,
) {
    val custom = settings.idleMinutes !in Settings.IDLE_PRESETS

    SectionTitle("静止多久后息屏", lead = "无动作达到设定时长后自动锁屏")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Settings.IDLE_PRESETS.forEach { minutes ->
            FilterChip(
                selected = settings.idleMinutes == minutes,
                onClick = { onPreset(minutes) },
                label = {
                    Text(
                        // 「1 分」看不出是测试档，把用途写进标签，免得用户当成常规值选走
                        text = if (minutes == Settings.TEST_IDLE_MINUTES) "测试 1 分" else "$minutes 分",
                        maxLines = 1,
                    )
                },
            )
        }
        FilterChip(
            selected = custom,
            onClick = onOpenCustom,
            label = {
                Text(
                    text = if (custom) "自定义 ${idleText(settings.idleMinutes)}" else "自定义…",
                    maxLines = 1,
                )
            },
        )
    }

    Hint(
        if (settings.idleMinutes == Settings.TEST_IDLE_MINUTES) {
            "测试档：1 分钟"
        } else {
            "屏幕亮着且无较大动作，达到设定时长后息屏"
        }
    )
}

/**
 * 动作灵敏度：滑块 + 现场试调。
 *
 * 之所以把「捕获到」做成实时指示而不是只在文档里解释三档，是因为这个参数没有客观正确答案。
 * 它取决于手机放哪、床垫多软、周围有没有风扇。让用户在真实环境里拖到「翻身会被认成动」
 * 与「睡着又能触发」之间那个点上，比给三档让他猜有用得多（PRD R2 的典型场景就是无线充电器风扇）。
 */
@Composable
private fun SensitivitySection(
    value: Float,
    probe: SensitivityProbe?,
    running: Boolean,
    onChange: (Float) -> Unit,
    onProbe: (Boolean) -> Unit,
) {
    var testing by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 试调开关随组件存亡自动收尾：离开页面就断开探针，不让它长期占着传感器
    DisposableEffect(Unit) {
        onDispose {
            if (testing) onProbe(false)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = SensitivityScale.labelOf(value),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(28.dp),
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = SensitivityScale.T_MIN..SensitivityScale.T_MAX,
            // 拖动过程中不弹 Toast：滑块会连续触发几十次回调，那是刷屏不是反馈。
            // 手感反馈由下面的实时指示承担（这正是它存在的意义）。
            modifier = Modifier
                .weight(1f)
                .semantics {
                    contentDescription = "动作灵敏度"
                    stateDescription = SensitivityScale.labelOf(value)
                },
        )
        Text(
            text = "%d%%".format((value * 100).roundToInt()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(44.dp),
            textAlign = TextAlign.End,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "迟钝 · 躺着翻身也不一定认得出",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "灵敏 · 轻碰一下就算你在动",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(12.dp))

    ProbeCard(probe = probe, testing = testing, running = running)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = {
                val next = !testing
                testing = next
                onProbe(next)
                toast(context, if (next) "试调已开始 · 摇一下手机查看指示" else "试调已结束")
            },
        ) {
            Text(if (testing) "结束试调" else "开始试调")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (testing) "按使用习惯调试" else "打开后可直接试手感",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }

    Hint(
        "拖动滑块调整检测灵敏度，建议调高。数值过低时，睡眠中的翻身等无意识动作会触发并重新计时。"
    )
}

/**
 * 实时捕获指示。
 *
 * 三种状态要说清楚，否则用户会以为是坏的：
 * - 试调没开 → 明说「未开启」并给按钮，不是静默空白；
 * - 开了但服务没在跑 → 采样在服务里，服务没跑就没有读数，必须说出来；
 * - 开了也在跑 → 显示指示与实测数值。
 *
 * 数值（σ / 阈值）刻意打出来：调参时「指示亮不亮」只告诉你结论，σ 与阈值的距离
 * 才告诉用户离临界点还有多远。这正是他自己判断该往哪边拖的依据（PRD 7.6 的可观测性）。
 */
@Composable
private fun ProbeCard(probe: SensitivityProbe?, testing: Boolean, running: Boolean) {
    val active = testing && running
    val captured = active && probe?.captured == true
    val accent = when {
        !active -> MaterialTheme.colorScheme.onSurfaceVariant
        captured -> if (isSystemInDarkTheme()) Color(0xFFEF9F27) else Color(0xFFBA7517)
        else -> if (isSystemInDarkTheme()) Color(0xFF5DCAA5) else Color(0xFF0F6E56)
    }

    Surface(
        color = accent.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = when {
                    !testing -> "试调未开始"
                    !running -> "监测没在跑，试不了"
                    captured -> "认出你在动"
                    else -> "没动静"
                }
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        !testing -> "试调未开始"
                        !running -> "监测未运行，无法试调"
                        captured -> "已识别：你在动"
                        else -> "无动静：判定为睡眠"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                )
                Text(
                    text = when {
                        !testing -> "点下方「开始试调」，然后摇一下手机"
                        !running -> "请先打开上方的「监测已开启」开关"
                        probe == null -> "正在读取传感器…"
                        else -> "σ %.4f · 阈值 %.4f（%s）".format(
                            probe.sigma,
                            probe.threshold,
                            if (captured) "已超过阈值" else "距阈值还差 %.4f".format(
                                (probe.threshold - probe.sigma).coerceAtLeast(0f),
                            ),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 「今晚暂停监测」一行。
 *
 * 未暂停时按钮上写的就是**本次会用的时长**（用户上次选的那档）：点击即可暂停，
 * 不弹确认。要改时长点右边那颗小按钮，进滚轮选——暂停是临时动作，多一步确认
 * 只会让人以为「点了没反应」。
 */
@Composable
private fun PauseRow(
    engine: EngineSnapshot,
    pauseMinutes: Int,
    onPickPause: () -> Unit,
    onResume: () -> Unit,
) {
    val paused = engine.phase == Phase.PAUSED && engine.pausedUntil > System.currentTimeMillis()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (paused) "已暂停 · 至 ${clockWall(engine.pausedUntil)} 恢复" else "今晚暂停监测",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = if (paused) onResume else onPickPause) {
            Text(if (paused) "继续监测" else "暂停 ${idleText(pauseMinutes)}")
        }
    }
}

/**
 * 悬浮窗设置：开关 + 透明度。
 *
 * **位置不在这里设**，它只能靠手指拖出来：小窗飘在哪个应用上面、会不会挡内容，
 * 用户在真实场景里一比就知道，给一组坐标输入框反而更没用。所以这一块只管
 * 「开不开、多透明」，位置留给拖动。
 */
@Composable
private fun OverlaySection(
    settings: Settings,
    granted: Boolean,
    onToggle: (Boolean) -> Unit,
    onAlpha: (Float) -> Unit,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("显示悬浮窗", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = settings.overlayEnabled, onCheckedChange = onToggle)
    }

    if (settings.overlayEnabled && !granted) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onGrant) { Text("去开启悬浮窗权限") }
        Hint(
            "悬浮窗需要「显示在其他应用上层」权限。此权限需在系统设置页手动开启，应用无法自行申请。" +
                "未授权时监测照常运行，但过程不可见。"
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("透明度", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
            Slider(
                value = settings.overlayAlpha,
                onValueChange = onAlpha,
                valueRange = Settings.OVERLAY_ALPHA_RANGE,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "悬浮窗透明度" },
            )
            Text(
                text = "%d%%".format((settings.overlayAlpha * 100).roundToInt()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(44.dp),
                textAlign = TextAlign.End,
            )
        }

        Hint(
            "长按拖动可换位置，点一下回到应用。本页在前台时它自动收起。关闭开关不影响息屏，只是看不到过程。"
        )
    }
}

/** 轻提示。用系统 Toast：一次性、不占布局、不需要 Snackbar 的宿主与队列。 */
private fun toast(context: Context, text: String) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/**
 * 静置时长的中文写法：60 及以上按「小时」显示，其余按「分钟」。
 *
 * 720 分钟这个数字本身看不出是「12 小时」，而静置时长一旦跨到小时级，
 * 用户心里的单位就变了。显示单位跟着他换。
 */
private fun idleText(minutes: Int): String = when {
    minutes < 60 -> "$minutes 分钟"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "%d 小时 %d 分".format(minutes / 60, minutes % 60)
}

/** 「标签 + 时间」一行，点时间开滚轮。 */
@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick) {
            Text(value, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 状态环（PRD 5.2）。
 *
 * 字号不写死：用 `BoxWithConstraints` 拿到圆的实际宽度，按字符数线性换算并夹在
 * 16–34sp 之间。系统的字体放大开到 200% 时，`sp` 会跟着放大，写死的字号会把
 * 「已静止 4:12」撑出圆外；配合 `maxLines = 1` + `softWrap = false`，
 * 这几个关键数字在任何字号下都不会换行或溢出（PRD 5.4 / AC16）。
 */
@Composable
private fun StatusRing(snap: EngineSnapshot, granted: Boolean) {
    val dark = isSystemInDarkTheme()
    val (title, subtitle) = ringText(snap, granted)
    val accent = accentOf(dark, warn = snap.phase == Phase.PENDING_FIRE, idle = !granted || !snap.running)

    BoxWithConstraints(
        modifier = Modifier
            .size(210.dp)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.10f))
            .border(width = 2.dp, color = accent, shape = CircleShape)
            // 状态不靠颜色传达：读屏用户拿到的是同样完整的一句话（PRD 5.7）
            .semantics {
                contentDescription = "监测状态"
                stateDescription = if (subtitle == null) title else "$title，$subtitle"
            },
        contentAlignment = Alignment.Center,
    ) {
        val fontScale = LocalDensity.current.fontScale
        val available = maxWidth.value * 0.72f // 圆的内接正方形约为直径的 72%
        val titleSize = (available / textUnits(title) / fontScale).coerceIn(16f, 34f)

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                fontSize = titleSize.sp,
                fontWeight = FontWeight.Medium,
                color = accent,
                maxLines = 1,
                softWrap = false,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = subtitle,
                    fontSize = (titleSize * 0.45f).coerceIn(12f, 15f).sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/**
 * 状态环文案。
 *
 * `ARMED` 必须结合 `blocked` 才说得清。「已就绪」三个字无法区分「屏幕灭着」、
 * 「不在生效时段」和「没插电」，而这三件事用户该做的动作完全不同。
 */
private fun ringText(snap: EngineSnapshot, granted: Boolean): Pair<String, String?> = when {
    !granted -> "待授权" to "授权后可开启"
    !snap.running -> "已关闭" to "打开下方开关开始守护"
    snap.phase == Phase.MONITORING -> "监测中" to "已静止 ${clock(snap.idleSeconds)}"
    snap.phase == Phase.PENDING_FIRE -> "即将息屏" to "连续动几秒可取消"
    snap.phase == Phase.PAUSED -> "已暂停" to "至 ${clockWall(snap.pausedUntil)} 恢复"
    snap.phase == Phase.ARMED -> "非生效时段" to when (snap.blocked) {
        BlockReason.SCREEN_OFF -> "屏幕已熄灭 · 亮屏后开始计时"
        BlockReason.OUT_OF_SCHEDULE -> "不在生效时段"
        BlockReason.NEEDS_CHARGING -> "等待插上电源"
        BlockReason.PAUSED -> "已暂停"
        else -> "有动作即开始计时"
    }
    else -> "非生效时段" to null
}

/**
 * 强调色。硬编码会让深色模式下对比度不足，所以按主题取不同档：
 * 浅色走 600 档、深色走 200/400 档（PRD 未规定，取通用可读性做法）。
 */
private fun accentOf(dark: Boolean, warn: Boolean, idle: Boolean): Color = when {
    idle -> if (dark) Color(0xFFB4B2A9) else Color(0xFF5F5E5A)
    warn -> if (dark) Color(0xFFEF9F27) else Color(0xFFBA7517)
    else -> if (dark) Color(0xFF5DCAA5) else Color(0xFF0F6E56)
}

/** 估算字符串的「字符宽度当量」：中日韩字符算 1，其余算 0.55。用于按宽度反推字号。 */
private fun textUnits(text: String): Float {
    var units = 0f
    for (c in text) units += if (c.code > 0x2E80) 1f else 0.55f
    return units.coerceAtLeast(1f)
}

private fun clock(seconds: Long): String =
    "%d:%02d".format(seconds / 60, seconds % 60)

/** 墙钟时间戳 → "HH:mm"，用于「已暂停至 …」。 */
private fun clockWall(at: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = at }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

@Composable
private fun AuthCard(onGrant: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("还差一步：授权「锁屏」", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "应用需要锁屏权限才能为你息屏。点击后进入系统页面，点「激活」即可。此步骤由系统要求，无法跳过。\n\n" +
                    "此权限仅用于锁屏。不能删除数据、修改密码或使用相机。可随时解除。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onGrant) { Text("去授权") }
        }
    }
}

/**
 * 区块标题。
 *
 * `lead` 是这一块「在干什么」的一句话说明，和标题一起出现。不是每块都有，
 * 只给那些光看标题猜不出用途的块（比如「动作灵敏度」）。
 * 与标题同组、但字号和颜色都弱一档，形成「标题 → 一句说明 → 具体控件 → 补充提示」的层级。
 */
@Composable
private fun SectionTitle(text: String, lead: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 10.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (lead != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = lead,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 8.dp, bottom = 24.dp),
    )
}

/**
 * 一排档位选择。
 *
 * 用横向滚动而不是 `FlowRow`：7 个时长档在 1280×2772 / 520dpi 上正好挤到边缘，
 * 换行会让整页高度跳动，横滑则始终只有一行，也不用为 `FlowRow` 打开实验 API。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = { Text(label(option), maxLines = 1) },
            )
        }
    }
}
