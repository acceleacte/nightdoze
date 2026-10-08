package com.nightdoze.app.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nightdoze.app.R
import com.nightdoze.app.engine.EngineState
import com.nightdoze.app.engine.MonitorService

/**
 * 设备管理员。**唯一用途是 lockNow() 强制锁屏**（PRD 7.4）。
 *
 * res/xml/device_admin.xml 里只声明了 `<force-lock />`，所以这里也只做这一件事。
 */
class NightDozeAdminReceiver : DeviceAdminReceiver() {

    /**
     * 授权成功。
     *
     * 刻意**不在这里启动监测服务**：这一步发生在系统设置页里，本应用不在前台，
     * 而从后台启动前台服务在 Android 12+ 会抛 ForegroundServiceStartNotAllowedException，
     * 且 DeviceAdminReceiver 不在豁免清单里（PRD 7.3）。用户回到 App 时 onResume 会补上。
     */
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i(Admin.TAG, "设备管理员已启用")
        EngineState.setGranted(true)
    }

    /**
     * 授权被撤销（用户在系统设置里取消，或 App 内「解除授权」）。
     *
     * 必须主动收摊，否则下一轮 lockNow() 会直接抛 SecurityException（PRD 7.4 / AC11）。
     * 停服务在任何时机都是允许的，不受后台启动限制影响。
     */
    override fun onDisabled(context: Context, intent: Intent) {
        Log.i(Admin.TAG, "设备管理员被撤销：停服务并落回 IDLE")
        EngineState.setGranted(false)
        MonitorService.stop(context)
    }

    /** 系统在取消授权前弹出的确认文案。 */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        context.getString(R.string.admin_disable_warning)
}

/**
 * 设备管理员的全部对外操作都收在这里，避免 ComponentName / DevicePolicyManager
 * 的取法散落在 Activity、Service、Receiver 三处各写一遍。
 */
object Admin {

    const val TAG = "NightDozeAdmin"

    fun component(context: Context): ComponentName =
        ComponentName(context, NightDozeAdminReceiver::class.java)

    fun dpm(context: Context): DevicePolicyManager? =
        context.getSystemService(DevicePolicyManager::class.java)

    fun isActive(context: Context): Boolean =
        try {
            dpm(context)?.isAdminActive(component(context)) == true
        } catch (e: Exception) {
            Log.w(TAG, "查询授权状态失败", e)
            false
        }

    /** 跳系统设备管理员授权页。附带说明文案，与 device_admin.xml 的声明保持一致。 */
    fun requestIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.admin_description)
            )

    /** 一键解除授权（PRD F18）。不解除的话系统会拦截卸载（PRD 12.3）。 */
    fun release(context: Context) {
        try {
            dpm(context)?.removeActiveAdmin(component(context))
        } catch (e: Exception) {
            Log.w(TAG, "解除设备管理员失败", e)
        }
    }

    /**
     * 强制锁屏。本产品的全部意义所在。
     *
     * 返回是否真的执行了。调用前二次校验授权，是 PRD 7.4 的「双保险」：
     * onDisabled 回调可能丢失（系统异常 / 进程当时不在），这一行是最后一道闸。
     *
     * 用 `lockNow(0)` 而不是已废弃的 `lockNow()`：无参版本 API 24 起废弃，
     * 带 flags 的版本 API 24 起可用，而 minSdk 是 26，取新的那个。
     *
     * 注意：`lockNow()` 与 `FLAG_KEEP_SCREEN_ON` 无关。视频 App 的唤醒锁只压制系统的
     * 「屏幕超时」计时器，压不住设备管理员的强制锁屏。这正是本产品能成立的原因。
     */
    fun lockNow(context: Context): Boolean {
        val dpm = dpm(context) ?: return false
        if (!dpm.isAdminActive(component(context))) {
            Log.w(TAG, "跳过 lockNow：设备管理员未激活")
            return false
        }
        return try {
            dpm.lockNow(0)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "lockNow 被系统拒绝", e)
            false
        }
    }
}
