package com.nightdoze.app.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nightdoze.app.admin.Admin
import com.nightdoze.app.data.SettingsStore
import com.nightdoze.app.engine.MonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 开机自启（PRD F14 / D2）。
 *
 * **这是「设置一次就不用管」唯一的缺口所在。** 服务被系统回收有 `START_STICKY` 兜底，
 * 从最近任务划掉也不会死（清单里 `stopWithTask="false"`），但**重启手机是全部清零**。
 * 进程没了、通知没了、`START_STICKY` 也不管跨重启的事。没有这个 Receiver，
 * 用户重启一次手机，功能就静默失效，而界面照样显示「监测已开启」。
 *
 * ## 为什么这里可以启动前台服务
 *
 * Android 12 起因「不许 App 自己爬起来」而禁止后台启动前台服务，但
 * **开机完成是官方明确豁免的时机之一**（与用户主动点开 App 同级）。
 * 这不是钻空子。用户开机的动作本身就是"用户在场"的证明。
 *
 * ## 为什么两点都要判
 *
 * `enabled` 是用户的意图，`isAdminActive()` 是执行能力。两者必须同时成立才拉起服务：
 * - 只判 `enabled`：授权在重启前被撤销过，这次照样拉起来，而它一跑就会 `lockNow` 失败
 * - 只判授权：用户明明关了开关，重启后又自己开回来。那是**违背意愿地复活**
 *
 * 两个都判，语义才等于「用户上次开着，而且现在还有能力开着」。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // goAsync()：onReceive 默认只能跑 10 秒且**不保证进程活着**。
        // 读 DataStore 是异步的，不声明"我还需要时间"，读完那一刻进程可能已经被回收。
        val pending = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                // 超时兜底：DataStore 理论上很快，但开机瞬间磁盘 IO 挤成一团，
                // 真卡住时宁可这次放弃（用户下次打开 App 会补启），也不要吊着系统
                val settings = withTimeoutOrNull(READ_TIMEOUT_MS) {
                    SettingsStore.readOnce(appContext)
                }

                if (settings == null) {
                    Log.w(TAG, "读取设置超时，本次不自动启动（打开 App 时会补上）")
                    return@launch
                }

                // 两条都成立才动手，理由见类注释
                if (!settings.enabled) {
                    Log.i(TAG, "开机自启跳过：总开关是关的")
                    return@launch
                }
                if (!Admin.isActive(appContext)) {
                    Log.i(TAG, "开机自启跳过：设备管理员授权已失效")
                    return@launch
                }

                MonitorService.start(appContext)
                Log.i(TAG, "开机自启：监测服务已拉起（action=$action）")
            } catch (t: Throwable) {
                // Receiver 里漏出去的异常会弹「应用已停止运行」，而这只是个后台动作。
                // 用户什么都没做却看到崩溃框，是最没有道理的一种失败
                Log.w(TAG, "开机自启失败", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "NightDozeBoot"
        const val READ_TIMEOUT_MS = 3000L
    }
}
