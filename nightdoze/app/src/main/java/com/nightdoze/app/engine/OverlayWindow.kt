package com.nightdoze.app.engine

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.widget.TextView
import com.nightdoze.app.data.Settings
import com.nightdoze.app.ui.MainActivity
import kotlin.math.abs

private const val TAG = "NightDozeOverlay"

/**
 * 状态悬浮窗：一颗圆角药丸，飘在别的应用上面显示倒计时。
 *
 * 它替掉了原来那两件事——常驻通知里的状态文字、以及预警时的震动（PRD 3.6 的改版）。
 * 所以它必须满足三条：**能开关、能调透明度、能拖着换位置**。
 *
 * ## 为什么不用 Compose
 *
 * Compose 进悬浮窗要自己造 `LifecycleOwner` + `SavedStateRegistryOwner` + `ViewTreeLifecycleOwner`，
 * 而且要打包 `compose.ui` 的窗口宿主。这里只有一个 TextView，用 Compose 是拿一整套运行时
 * 换一句话。整个类就是「一个 TextView + WindowManager.LayoutParams」，没有任何状态需要组合。
 *
 * ## 透明度必须走 View 级 alpha，不能走 `LayoutParams.alpha`（真机实测）
 *
 * 早先透明度写在 `lp.alpha` 上，视觉上没问题，**但这颗药丸从此收不到任何触摸**：
 * 点击、长按、拖动全部无效，`dumpsys` 里 window 与 input 都显示它可触摸、在最上层、
 * 触摸区域也覆盖触点，却连 `ACTION_DOWN` 都进不来。
 *
 * 两者的差别在输入系统：`lp.alpha` 会变成 `InputWindowInfo.alpha`，而窗口的 alpha
 * 只参与「遮挡判定」（`touchOcclusionMode=USE_OPACITY`，阈值 0.8）——**它让窗口在系统
 * 眼里变成半透明遮挡物**。同机 `dumpsys input` 对比：全机只有这一颗药丸
 * `alpha=0.3826 + USE_OPACITY`，其余窗口（含所有系统窗口）都是 `alpha=1 +
 * BLOCK_UNTRUSTED`。改回 `View.alpha` 后窗口恢复 `alpha=1`，触摸立刻正常。
 *
 * 结论：**要半透明的是「画出来的东西」，不是「窗口」**。`View.alpha` 只影响绘制，
 * 不改变窗口在输入系统里的身份。
 *
 * ## 线程约定
 *
 * 窗口只能在自己的 looper 线程（主线程）上动，而调用方是 Service 的 ticker（`Dispatchers.Default`）。
 * 所以**所有公开方法都往主线程投递**，内部用 [onMain] 统一处理，调用方不必关心线程。
 *
 * 唯一的例外是构造：构造函数什么都不碰窗口，只存引用，可以在任何线程调用。
 */
class OverlayWindow(
    private val context: Context,
    /** 拖完一次回报一次落点，由服务落盘。 */
    private val onMoved: (Int, Int) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())

    private var wm: WindowManager? = null
    private var root: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 用户设定的不透明度。预警时那一下「提亮」是临时值，用完要回落到它。 */
    private var baseAlpha = Settings.DEFAULT_OVERLAY_ALPHA

    /** 提亮期间不许 [show] 改 alpha，否则每秒那一拍会把 1.5 秒的提亮砍成不到 1 秒。 */
    private var pulsing = false

    /** 用户拖过没有。没拖过 = 位置由默认落点决定，要跟着文案宽度贴右边缘。 */
    private var userPos = false

    /** 是否已经挂了「布局完再贴右边缘」的监听（防止重复挂）。 */
    private var autoArmed = false

    /** 待应用的文案/底色。`root` 还没建时先攒着，见 [attach]。 */
    private var lastText: String? = null
    private var lastColor = COLOR_IDLE

    /** apply() 被调过之后不再接受任何操作，避免已移除的视图被延迟回调二次操作。 */
    private var released = false

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    /** 显示。位置用 [Settings.POS_UNSET] 表示「还没拖过，用默认位」。 */
    fun show(x: Int, y: Int, alpha: Float) = onMain {
        if (released) return@onMain
        val view = root ?: attach() ?: return@onMain
        val lp = params ?: return@onMain

        baseAlpha = alpha.coerceIn(Settings.OVERLAY_ALPHA_RANGE)
        if (!pulsing && view.alpha != baseAlpha) view.alpha = baseAlpha

        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE

        // 手势进行中绝不碰位置：这一路每秒都会跑，碰了就是跟手指抢坐标
        if (!dragging) {
            if (x != Settings.POS_UNSET && y != Settings.POS_UNSET) {
                // 用户拖过：以落盘值为准，但夹回屏幕内，防止换分辨率后跑到屏幕外抓不回来
                userPos = true
                val nx = x.coerceIn(0, maxX(view))
                val ny = y.coerceIn(0, maxY(view))
                if (lp.x != nx || lp.y != ny) {
                    lp.x = nx
                    lp.y = ny
                    wm?.updateViewLayout(view, lp)
                }
            } else if (!userPos) {
                armAutoPlacement(view)
            }
        }
    }

    /** 隐藏。用 `GONE` 而不是移除视图：窗口留着，下次亮屏就是现成的，不必重建。 */
    fun hide() = onMain {
        if (released) return@onMain
        root?.visibility = View.GONE
    }

    /** 换文案与底色。两者都没变就直接返回——倒计时每秒一次，不能白刷。 */
    fun setStatus(text: String, color: Int) = onMain {
        if (released) return@onMain
        if (text == lastText && color == lastColor) return@onMain
        lastText = text
        lastColor = color
        val view = root ?: return@onMain
        view.text = text
        view.background = pillBackground(color)
        // 文案变了药丸宽度就变了，没被拖过时要重新贴右边缘，否则会伸到屏幕外
        if (!userPos && !dragging) armAutoPlacement(view)
    }

    /** 预警那一下的「提亮」：临时拉到全不透明，一秒多后回落到用户设定的值。 */
    fun pulse() = onMain {
        if (released) return@onMain
        val view = root ?: return@onMain
        pulsing = true
        view.alpha = 1f
        handler.postDelayed({
            pulsing = false
            if (released) return@postDelayed
            view.alpha = baseAlpha
        }, PULSE_MS)
    }

    fun release() = onMain {
        released = true
        handler.removeCallbacksAndMessages(null)
        val view = root ?: return@onMain
        try {
            wm?.removeView(view)
        } catch (t: Throwable) {
            Log.w(TAG, "移除悬浮窗失败", t)
        }
        wm = null
        root = null
        params = null
    }

    private fun pillBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(CORNER_RADIUS_DP).toFloat()
        setColor(color)
    }

    private fun attach(): TextView? {
        val manager = context.getSystemService(WindowManager::class.java) ?: return null

        val view = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = TEXT_SIZE_SP
            isSingleLine = true
            setPadding(dp(PAD_H_DP), dp(PAD_V_DP), dp(PAD_H_DP), dp(PAD_V_DP))
            elevation = dp(ELEVATION_DP).toFloat()
            // 文案必须在这里就填上：服务是「先 setStatus 再 show」，
            // 而首次 setStatus 时 root 还没建，只攒下了 lastText/lastColor。
            // 不补这一下，首次测量得到的是空药丸（实测宽 90px），默认落点就会算错，
            // 之后文案补上、药丸变宽，右半边直接伸到屏幕外。
            text = lastText.orEmpty()
            background = pillBackground(lastColor)
            alpha = baseAlpha
            setOnTouchListener(dragListener)
        }

        // FLAG_NOT_FOCUSABLE：不抢输入焦点，否则会顶掉下面那个应用的光标。
        // FLAG_NOT_TOUCH_MODAL：药丸以外的区域照常传给下面的应用，不然整屏点不动。
        //
        // **不要设 lp.alpha。** 窗口级 alpha 会让输入系统把这颗药丸当成半透明遮挡物，
        // 结果是它自己一个触摸都收不到（类注释里有完整实测记录）。半透明走 View.alpha。
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(DEFAULT_TOP_DP)
        }

        return try {
            manager.addView(view, lp)
            wm = manager
            root = view
            params = lp
            Log.i(TAG, "悬浮窗已挂上")
            view
        } catch (t: Throwable) {
            // 权限被撤销、或窗口令牌被系统拒绝，都落在这里。功能降级不影响息屏主路径
            Log.w(TAG, "添加悬浮窗失败", t)
            null
        }
    }

    /**
     * 把药丸贴到右上角（留 [DEFAULT_MARGIN_DP] 右边距）。
     *
     * 用「等一次布局再算」而不是自己量文字宽度：宽度是 `TextView` 自己的事，
     * 手算会跟字体缩放、字距这些细节对不上。挂监听、用真实的 `view.width`。
     *
     * 用户一旦拖过（[userPos]）就不再调用它——那时候位置是用户说了算。
     */
    private fun armAutoPlacement(view: View) {
        if (autoArmed) return
        autoArmed = true
        view.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    autoArmed = false
                    if (released || userPos || dragging) return
                    val lp = params ?: return
                    val nx = (maxX(view) - dp(DEFAULT_MARGIN_DP)).coerceAtLeast(0)
                    if (lp.x == nx && lp.y == dp(DEFAULT_TOP_DP)) return
                    lp.x = nx
                    lp.y = dp(DEFAULT_TOP_DP)
                    wm?.updateViewLayout(view, lp)
                }
            }
        )
    }

    /**
     * 拖动 + 单击。
     *
     * 手指移动超过 touchSlop 才算拖，否则算点（打开应用）。这个判据必须自己算：
     * 悬浮窗同时挂了 `OnClickListener` 和 `OnTouchListener` 时，语义会互相打架。
     */
    private val dragListener = View.OnTouchListener { _, event ->
        val view = root ?: return@OnTouchListener false
        val lp = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downX = lp.x
                downY = lp.y
                dragging = false
                Log.i(TAG, "按下 raw=(${event.rawX},${event.rawY}) 窗口=(${lp.x},${lp.y})")
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    Log.i(TAG, "进入拖动 起点=(${downX},${downY})")
                }
                if (dragging) {
                    lp.x = (downX + dx.toInt()).coerceIn(0, maxX(view))
                    lp.y = (downY + dy.toInt()).coerceIn(0, maxY(view))
                    wm?.updateViewLayout(view, lp)
                }
                true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Log.i(TAG, "抬手 拖动=$dragging 落点=(${lp.x},${lp.y})")
                if (dragging) {
                    userPos = true
                    onMoved(lp.x, lp.y)
                } else {
                    openApp()
                }
                true
            }

            else -> false
        }
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    /** 药丸左边缘能到的最右位置。 */
    private fun maxX(view: View): Int =
        (context.resources.displayMetrics.widthPixels - view.width).coerceAtLeast(0)

    /**
     * 药丸上边缘能到的最下位置。
     *
     * 留 [BOTTOM_GUARD_DP] 的余量：`lp.y` 是相对父框架（已扣掉状态栏）的偏移，
     * 而 `displayMetrics` 是全屏尺寸，两者不是同一个坐标系。与其去算插值，
     * 不如把下沿收进来一点——药丸贴到导航条上本来也没用。
     */
    private fun maxY(view: View): Int =
        (context.resources.displayMetrics.heightPixels - view.height - dp(BOTTOM_GUARD_DP))
            .coerceAtLeast(0)

    /**
     * 拖动判定用的最小位移。
     *
     * **必须是 lazy。** 这个类是在 `MonitorService.<init>` 里构造的，而 Service 在构造函数
     * 跑完之前还没 `attachBaseContext`，此刻拿它当 Context 用会直接 NPE——
     * 真机实测踩到过：`ViewConfiguration.get(context)` 在 `<init>` 里抛
     * `Context.getResources() on a null object reference`，表现是「打开就闪退」。
     * 拖动手势发生在很久以后，那时 Context 早已可用。
     */
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    /** 点一下回到应用。服务不是 Activity，必须带 NEW_TASK。 */
    private fun openApp() {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "点击悬浮窗 → 回到应用")
        } catch (t: Throwable) {
            Log.w(TAG, "从悬浮窗打开应用失败", t)
        }
    }

    companion object {
        const val COLOR_MONITORING = 0xFF1F7A5C.toInt()
        const val COLOR_WARN = 0xFFB4690E.toInt()
        const val COLOR_IDLE = 0xFF4A4A4A.toInt()

        private const val TEXT_SIZE_SP = 14f
        private const val PAD_H_DP = 14
        private const val PAD_V_DP = 9
        private const val CORNER_RADIUS_DP = 18
        private const val ELEVATION_DP = 6
        private const val DEFAULT_MARGIN_DP = 16
        private const val DEFAULT_TOP_DP = 96
        private const val BOTTOM_GUARD_DP = 64
        private const val PULSE_MS = 1500L
    }
}
