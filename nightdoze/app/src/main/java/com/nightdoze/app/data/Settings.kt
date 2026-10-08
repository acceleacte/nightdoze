package com.nightdoze.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "NightDozeSettings"

/**
 * 设置文件损坏时**自动换一份空的**，而不是抛异常。
 *
 * 没有这一条时，文件一旦损坏（磁盘异常、外部工具改坏、断电），`edit {}` 里那次读盘会抛
 * `CorruptionException`，而它在协程里没被接住。结果是**应用反复启动反复崩**，用户看到的
 * 是一打开就闪退、且毫无线索。实测踩到过：这一次就是被我自己用工具改坏了文件。
 *
 * 代价是设置回到默认值。对一款「兜底工具」而言这个代价明确可接受：丢的是偏好，
 * 不是数据；而不能自愈的代价是功能整体不可用。
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nightdoze",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class Sensitivity { LOW, MEDIUM, HIGH }

/**
 * 灵敏度在存储里是**连续值**，不是三档枚举。
 *
 * 界面改成了滑块（允许用户在任意位置停住并现场试效果），三档只作为滑块上的三个
 * 参考点保留语义：值域见 [SensitivityScale]，端点即 LOW / HIGH。
 *
 * 为什么不直接存 [Sensitivity]：枚举只能表达 3 个值，滑块拖到中间就无处可存。
 * 要么把用户的选择悄悄吸附到最近档（用户会看到「拖了但没生效」），要么开关值域
 * 造成同样两个滑块位置对应同一个 k。连续值没有这两个问题。
 */
enum class ScheduleMode { ALL_DAY, NIGHT }

/**
 * 灵敏度滑块的值域与映射（PRD 7.6 的 k 映射的连续化版本）。
 *
 * 滑块位置 `t ∈ [0, 1]` 线性映射到噪声倍数 `k`。用**连续函数**而不是分段查表，
 * 是为了让「拖一点、捕获状态就变」这件事在整条滑块上处处成立。分段会让滑块
 * 中段出现「拖了没反应」的死区，那正是用户最容易被误导的地方。
 *
 * `K_MIN` / `K_MAX` 取 PRD 已确认的 HIGH=2 / LOW=6 作为端点，中位 4 对应 MEDIUM≈3.5
 * 附近的保守侧：宁可略钝一点，也不要因为灵了半档而半夜误触发。
 */
object SensitivityScale {

    const val T_MIN = 0f
    const val T_MAX = 1f

    /** 滑块最左 = 最不灵敏（要很大动作才算动），最右 = 最灵敏。 */
    const val K_MIN = 6f
    const val K_MAX = 2f

    /** 滑块初值：对应原 MEDIUM。 */
    const val T_DEFAULT = 0.4f

    fun kOf(t: Float): Float = K_MIN + (K_MAX - K_MIN) * t.coerceIn(T_MIN, T_MAX)

    fun tOfK(k: Float): Float =
        ((k - K_MIN) / (K_MAX - K_MIN)).coerceIn(T_MIN, T_MAX)

    /** 滑块位置的语义标签。只有贴到两端附近才说「低/高」，中间一律「中」。 */
    fun labelOf(t: Float): String = when {
        t <= 0.15f -> "低"
        t >= 0.85f -> "高"
        else -> "中"
    }

    /**
     * 老版本三档枚举 → 连续值的迁移（PRD 6.3 第 2 条：枚举只增不改不删，
     * 读到旧值必须能正常解析）。老用户升级后灵敏度不该变样，所以三档各自落在
     * 它原本的 k 值上，而不是最近的档位端点。
     */
    fun fromLegacy(level: Sensitivity): Float = tOfK(
        when (level) {
            Sensitivity.LOW -> 6f
            Sensitivity.MEDIUM -> 3.5f
            Sensitivity.HIGH -> 2f
        }
    )
}

/**
 * 全部本地设置（PRD 6.1）。字段顺序与键名一一对应，新增字段必须带默认值（PRD 6.3）。
 */
data class Settings(
    val enabled: Boolean = false,
    val idleMinutes: Int = DEFAULT_IDLE_MINUTES,
    /**
     * 上次用「自定义」选定的分钟数。只用于**打开滚轮时回填到哪一格**。
     *
     * 真正决定息屏时长的始终是 [idleMinutes]；两者在选定自定义时会一起写
     * （见 [SettingsStore.setIdleCustom]）。这里单独留一份，是为了「切到固定档再切回自定义」
     * 时不丢掉上次选的值。
     */
    val idleCustomMinutes: Int = DEFAULT_CUSTOM_MINUTES,
    val warnSeconds: Int = DEFAULT_WARN_SECONDS,
    val schedule: ScheduleMode = ScheduleMode.ALL_DAY,
    val nightStart: String = "22:00",
    val nightEnd: String = "07:00",
    /**
     * 灵敏度滑块位置（0..1），不是枚举。见 [SensitivityScale]。
     * 默认值等价原 MEDIUM。
     */
    val sensitivityT: Float = SensitivityScale.T_DEFAULT,
    val chargingOnly: Boolean = false,
    /**
     * 状态悬浮窗总开关。
     *
     * 关掉后没有常驻通知、也没有浮层，服务照样跑，只是状态完全不可见——
     * 界面上的状态环仍然照常显示。
     */
    val overlayEnabled: Boolean = true,
    /** 悬浮窗不透明度。见 [OVERLAY_ALPHA_RANGE]。 */
    val overlayAlpha: Float = DEFAULT_OVERLAY_ALPHA,
    /**
     * 悬浮窗位置（像素，相对屏幕左上角）。[POS_UNSET] 表示用户还没拖过，
     * 显示时落到默认位（右上角）。
     */
    val overlayX: Int = POS_UNSET,
    val overlayY: Int = POS_UNSET,
    val pausedUntil: Long = 0L,
    /**
     * 上次选定的暂停时长（分钟）。只用于**打开滚轮时回填到哪一格**。
     *
     * 与 [idleCustomMinutes] 同一手法：真正生效的是 [pausedUntil] 那个时间戳，
     * 这里留一份只是为了让「下次点暂停」沿用上次选的档位，而不是每次回到默认。
     */
    val pauseMinutes: Int = DEFAULT_PAUSE_MINUTES,
    val lastSeenAt: Long = 0L,
    val fireLog: String = "",
) {
    companion object {
        const val DEFAULT_IDLE_MINUTES = 15
        const val DEFAULT_WARN_SECONDS = 10

        /** 静置「测试档」的分钟数。选它时会同时弹提示说明这是测试用的短档。 */
        const val TEST_IDLE_MINUTES = 1

        /**
         * 固定档位（分钟）。全是「量级差异明显、用户一眼能选」的：
         * 测试 1 分验证链路，15 / 30 分是常用值。需要别的数值一律走自定义滚轮
         * （[IDLE_CUSTOM_STEPS]）。选中的是哪一格由 `idleMinutes` 是否落在这里判断。
         */
        val IDLE_PRESETS = listOf(TEST_IDLE_MINUTES, 15, 30)

        /**
         * 自定义时长的可选范围（分钟），步长 5。
         *
         * 下限从 1 改成 5：1 分钟只有「测试档」用得上，而测试档是固定档位上的独立一格，
         * 不该混进自定义值域里被挑走。上限 12 小时不变。
         *
         * 值域本身按 5 的倍数铺开（见 [IDLE_CUSTOM_STEPS]），滚轮不必再逐分钟数。
         */
        val IDLE_CUSTOM_RANGE = 5..720

        /** 自定义滚轮的可选项，步长 5 分钟：5, 10, 15, … 720。 */
        val IDLE_CUSTOM_STEPS: List<Int> = (IDLE_CUSTOM_RANGE.first..IDLE_CUSTOM_RANGE.last step 5).toList()

        const val DEFAULT_CUSTOM_MINUTES = 20

        /** 息屏前预警时长（秒）。0 = 直接息屏，不打扰（PRD 3.6）。 */
        val WARN_CHOICES = listOf(0, 5, 10, 30)

        /**
         * 暂停时长的值域与默认值（分钟）。步长 5 分钟，最长 24 小时。
         *
         * 上限取一整天：暂停的语义是「这一轮别管我」，超过一天不如直接关掉总开关。
         * 下限 5 分钟：再短就与「立即继续监测」无异，而后者本来就有按钮。
         */
        val PAUSE_MINUTES_RANGE = 5..1440
        val PAUSE_MINUTES_STEPS: List<Int> =
            (PAUSE_MINUTES_RANGE.first..PAUSE_MINUTES_RANGE.last step 5).toList()
        const val DEFAULT_PAUSE_MINUTES = 60

        /** 悬浮窗不透明度的值域与默认值。下限 0.2：再低就等于看不见，不如关掉开关。 */
        val OVERLAY_ALPHA_RANGE = 0.2f..1f
        const val DEFAULT_OVERLAY_ALPHA = 0.85f

        /** 位置「还没拖过」的哨兵值。用 Int.MIN_VALUE 而不是 -1：-1 是合法的屏幕外坐标。 */
        const val POS_UNSET = Int.MIN_VALUE

        /**
         * 推荐时段的起止点。给「按作息设置」那个按钮用。
         *
         * 取 23:00–07:00 而不是 22:00–07:00：22:00 对多数人偏早，
         * 一按就把还没睡的那一小时也纳入，反而会在看手机时突然息屏。第一次体验就踩到
         * 一件「它在我醒着想关我屏」的坏事，之后就不会再信任这个开关了。
         * 23:00 是「该睡了」的普遍共识点，而早 7 点覆盖绝大多数起床时间。
         */
        const val RECOMMENDED_START = "23:00"
        const val RECOMMENDED_END = "07:00"
    }
}

/**
 * "HH:mm" → 当天第几分钟。解析失败返回 null，调用方一律按「全天」处理。
 * 一段读不懂的时段设置，宁可少拦一次，也不要让功能整体失效。
 */
fun parseHhmm(value: String): Int? {
    val parts = value.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 当天第几分钟 → "HH:mm"。12/24 小时制一律按 24 小时存，显示也按 24 小时，避免存取不一致。 */
fun formatHhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** 键名集中在 `object` 里，避免字符串散落各处拼错。 */
private object K {
    val ENABLED = booleanPreferencesKey("enabled")
    val IDLE_MINUTES = intPreferencesKey("idle_minutes")
    val IDLE_CUSTOM_MINUTES = intPreferencesKey("idle_custom_minutes")
    val WARN_SECONDS = intPreferencesKey("warn_seconds")
    val SCHEDULE = stringPreferencesKey("schedule_mode")
    val NIGHT_START = stringPreferencesKey("night_start")
    val NIGHT_END = stringPreferencesKey("night_end")

    /** 灵敏度旧值（三档枚举名）。只读不写，仅用于老用户迁移（见 [SettingsStore.flow]）。 */
    val SENSITIVITY_LEGACY = stringPreferencesKey("sensitivity")
    val SENSITIVITY_T = floatPreferencesKey("sensitivity_t")

    val CHARGING_ONLY = booleanPreferencesKey("charging_only")
    val OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
    val OVERLAY_ALPHA = floatPreferencesKey("overlay_alpha")
    val OVERLAY_X = intPreferencesKey("overlay_x")
    val OVERLAY_Y = intPreferencesKey("overlay_y")
    val PAUSED_UNTIL = longPreferencesKey("paused_until")
    val PAUSE_MINUTES = intPreferencesKey("pause_minutes")
    val LAST_SEEN_AT = longPreferencesKey("last_seen_at")
    val FIRE_LOG = stringPreferencesKey("fire_log")
}

/**
 * 枚举容错（PRD 6.3 第 2 条）：键在、值不认识时回退默认，绝不抛异常。
 * 这样将来删掉某个枚举常量，老用户升级后最多是回到默认档，而不是启动即崩。
 */
private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T =
    enumValues<T>().firstOrNull { it.name == raw } ?: fallback

/**
 * 读取静置档位时**不做取整**。
 *
 * 早先这里有个 `nearestIdleChoice`，把任何非固定档的值就近夹到 1/15/30 上。
 * 那是为「老版本的 3/5/10/20 档已收敛掉」做的一次性迁移，但它是**读路径上的常驻逻辑**，
 * 于是自定义值一读出来就被毁掉：选了 60 分钟，落盘是 60，界面读到的却是 30。
 * 自定义档因此永远无法生效，且看起来像「选了没反应」。
 *
 * 现在不需要它了：固定档由 [Settings.IDLE_PRESETS] 表达，自定义值走
 * [Settings.IDLE_CUSTOM_STEPS]；老档位值（3/5/10/20）本来就是合法分钟数，
 * 当自定义值用即可。只有明显越界的值（0、负数）才夹到自定义值域下界。
 */
private fun idleMinutesOr(raw: Int?): Int {
    val v = raw ?: Settings.DEFAULT_IDLE_MINUTES
    // 测试档 1 分小于自定义下界 5，必须放行，否则「测试 1 分」一读出来就变成 5
    if (v == Settings.TEST_IDLE_MINUTES) return v
    return v.coerceIn(Settings.IDLE_CUSTOM_RANGE)
}

/**
 * 灵敏度取值：优先新的连续值，缺失时用老的三档枚举名迁移（PRD 6.3 第 3 条）。
 *
 * 迁移是**只读**的：这里不写回，所以不会因为读取而触发 DataStore 的写盘。
 * 用户下次在界面上动一下滑块，新键自然就写上了。
 */
private fun sensitivityT(p: Preferences): Float {
    p[K.SENSITIVITY_T]?.let { return it.coerceIn(SensitivityScale.T_MIN, SensitivityScale.T_MAX) }
    val legacy = enumOr(p[K.SENSITIVITY_LEGACY], Sensitivity.MEDIUM)
    return SensitivityScale.fromLegacy(legacy)
}

/**
 * 设置读写。用 Preferences DataStore，不引入 Room（PRD 6.2 / D6）。
 *
 * 不设 schemaVersion：DataStore 对缺 key 天然回退默认值，显式加版本号只增加维护面。
 * 代价是必须守住「新增字段带默认值、枚举只增不改」这两条。
 */
object SettingsStore {

    /**
     * 读一次当前设置（一次性的，不是订阅）。
     *
     * 开机自启那个 Receiver 用得上：它只关心「开关是不是开着」这一个问题，
     * 拿到答案就走人。用 [flow] 的首个值来拿也行，但那条 Flow 是为长驻订阅设计的
     * （带 catch 回落、持续发射），在只需要一个值的地方用它，得手动 `.first()`
     * 并记住要收尾。不如直接给一个语义明确的读一次。
     *
     * 读不到（文件损坏 / 磁盘异常）时回落默认值，与 [flow] 同一套策略：
     * 对兜底工具而言，读到一份读不懂的配置宁可当没配过，也不能抛异常。
     */
    suspend fun readOnce(context: Context): Settings =
        try {
            flow(context).first()
        } catch (t: Throwable) {
            Log.w(TAG, "一次性读取设置失败，回落默认值", t)
            Settings()
        }

    fun flow(context: Context): Flow<Settings> = context.dataStore.data
        // 磁盘读失败（文件损坏 / 权限异常）时回落默认值，不让 Flow 把异常抛给 UI
        .catch { e ->
            Log.w(TAG, "读取设置失败，回落默认值", e)
            emit(emptyPreferences())
        }
        .map { p ->
            Settings(
                enabled = p[K.ENABLED] ?: false,
                // 静置档位原样读取，不做取整（见 idleMinutesOr）。取整会把自定义值毁掉。
                idleMinutes = idleMinutesOr(p[K.IDLE_MINUTES]),
                // 旧版本存过 3/7 这类非 5 倍数的值（值域曾是 1..720）。滚轮只能停在 5 的倍数上，
                // 原样读出来会让「显示 20」与「落盘 17」对不上。这里就近取整到 5 的倍数，
                // 与 idleMinutes 读路径上的策略同理（PRD 6.3 第 3 条）。
                idleCustomMinutes = (p[K.IDLE_CUSTOM_MINUTES] ?: Settings.DEFAULT_CUSTOM_MINUTES)
                    .let { raw -> Settings.IDLE_CUSTOM_STEPS.minByOrNull { kotlin.math.abs(it - raw) } }
                    ?: Settings.DEFAULT_CUSTOM_MINUTES,
                warnSeconds = (p[K.WARN_SECONDS] ?: Settings.DEFAULT_WARN_SECONDS)
                    .coerceIn(Settings.WARN_CHOICES.first(), Settings.WARN_CHOICES.last()),
                schedule = enumOr(p[K.SCHEDULE], ScheduleMode.ALL_DAY),
                nightStart = p[K.NIGHT_START] ?: "22:00",
                nightEnd = p[K.NIGHT_END] ?: "07:00",
                sensitivityT = sensitivityT(p),
                chargingOnly = p[K.CHARGING_ONLY] ?: false,
                // 悬浮窗：开关与透明度夹在值域内；位置原样读，越界值由显示侧夹到屏幕内
                overlayEnabled = p[K.OVERLAY_ENABLED] ?: true,
                overlayAlpha = (p[K.OVERLAY_ALPHA] ?: Settings.DEFAULT_OVERLAY_ALPHA)
                    .coerceIn(Settings.OVERLAY_ALPHA_RANGE),
                overlayX = p[K.OVERLAY_X] ?: Settings.POS_UNSET,
                overlayY = p[K.OVERLAY_Y] ?: Settings.POS_UNSET,
                pausedUntil = p[K.PAUSED_UNTIL] ?: 0L,
                // 暂停档位原样读，只在越界时夹回值域（与 idleCustomMinutes 同理）
                pauseMinutes = (p[K.PAUSE_MINUTES] ?: Settings.DEFAULT_PAUSE_MINUTES)
                    .coerceIn(Settings.PAUSE_MINUTES_RANGE),
                lastSeenAt = p[K.LAST_SEEN_AT] ?: 0L,
                fireLog = p[K.FIRE_LOG] ?: "",
            )
        }

    suspend fun setEnabled(context: Context, value: Boolean) =
        context.dataStore.edit { it[K.ENABLED] = value }

    suspend fun setIdleMinutes(context: Context, minutes: Int) =
        context.dataStore.edit { it[K.IDLE_MINUTES] = minutes }

    /** 「自定义」档选定的值单独存，切走再切回来不会丢（[Settings.idleCustomMinutes]）。 */
    suspend fun setIdleCustomMinutes(context: Context, minutes: Int) =
        context.dataStore.edit { it[K.IDLE_CUSTOM_MINUTES] = minutes }

    /**
     * 选定「自定义」时长：一次写两个键。
     *
     * `idleMinutes` 是引擎实际读取的值，`idleCustomMinutes` 只用于回填滚轮。
     * 分两次 `edit` 会让界面先跳到「自定义 + 旧值」再跳到正确值，所以合成一次。
     * 这正是 `applyRecommendedSchedule` 用过的同一手法。
     */
    suspend fun setIdleCustom(context: Context, minutes: Int) =
        context.dataStore.edit {
            it[K.IDLE_CUSTOM_MINUTES] = minutes
            it[K.IDLE_MINUTES] = minutes
        }

    suspend fun setWarnSeconds(context: Context, seconds: Int) =
        context.dataStore.edit { it[K.WARN_SECONDS] = seconds }

    suspend fun setSchedule(context: Context, mode: ScheduleMode) =
        context.dataStore.edit { it[K.SCHEDULE] = mode.name }

    suspend fun setNightStart(context: Context, value: String) =
        context.dataStore.edit { it[K.NIGHT_START] = value }

    suspend fun setNightEnd(context: Context, value: String) =
        context.dataStore.edit { it[K.NIGHT_END] = value }

    /**
     * 一次性写入推荐时段：切到「定时」并设好起止。
     *
     * 三次 `edit` 会各自触发一次状态发射，界面会先闪成「定时 + 旧的起止」再跳到正确值。
     * 合成一次写，中间态就不存在了。
     */
    suspend fun applyRecommendedSchedule(context: Context, start: String, end: String) =
        context.dataStore.edit {
            it[K.SCHEDULE] = ScheduleMode.NIGHT.name
            it[K.NIGHT_START] = start
            it[K.NIGHT_END] = end
        }

    /** 灵敏度滑块位置。写新键，不再碰老的 `sensitivity` 枚举键。 */
    suspend fun setSensitivityT(context: Context, t: Float) =
        context.dataStore.edit { it[K.SENSITIVITY_T] = t.coerceIn(SensitivityScale.T_MIN, SensitivityScale.T_MAX) }

    suspend fun setChargingOnly(context: Context, value: Boolean) =
        context.dataStore.edit { it[K.CHARGING_ONLY] = value }

    suspend fun setOverlayEnabled(context: Context, value: Boolean) =
        context.dataStore.edit { it[K.OVERLAY_ENABLED] = value }

    suspend fun setOverlayAlpha(context: Context, value: Float) =
        context.dataStore.edit {
            it[K.OVERLAY_ALPHA] = value.coerceIn(Settings.OVERLAY_ALPHA_RANGE)
        }

    /** 拖完一次写一次。x/y 必须同一次 edit，否则读侧会短暂拿到「新 x + 旧 y」。 */
    suspend fun setOverlayPos(context: Context, x: Int, y: Int) =
        context.dataStore.edit {
            it[K.OVERLAY_X] = x
            it[K.OVERLAY_Y] = y
        }

    /** 服务起停时各写一次，UI 靠它回答「开关是开的，但监测到底在不在跑」（PRD 7.3 / F14）。 */
    suspend fun touchLastSeen(context: Context, at: Long = System.currentTimeMillis()) =
        context.dataStore.edit { it[K.LAST_SEEN_AT] = at }

    /**
     * 暂停到某个时刻，同时记下这次选的时长。
     *
     * 与 [setIdleCustom] 同一手法：一次 `edit` 写两个键。分两次写会让界面先闪成
     * 「已暂停 · 旧档位」再跳到新档位。
     */
    suspend fun setPausedUntil(context: Context, at: Long, minutes: Int) =
        context.dataStore.edit {
            it[K.PAUSED_UNTIL] = at
            it[K.PAUSE_MINUTES] = minutes.coerceIn(Settings.PAUSE_MINUTES_RANGE)
        }

    /** 恢复监测：只清时间戳，档位留着给下次用。 */
    suspend fun clearPausedUntil(context: Context) =
        context.dataStore.edit { it[K.PAUSED_UNTIL] = 0L }
}
