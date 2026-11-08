package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Random
import kotlin.math.abs

/**
 * 核心服务：围栏校验 → 弹窗识别 → 散射点击
 * 设计原则：事件即滤即弃，围栏外零开销；单弹窗单次触发。
 */
class GrabService : AccessibilityService() {

    companion object {
        const val CH_ID = "grab"
        @Volatile var running = false
        /** 目标包名，由MainActivity写入 */
        @Volatile var targetPkg: String = "com.sdu.didi.psnger"
        /** 随机延迟上界ms，下界固定800 */
        @Volatile var maxDelay = 2500
    }

    private val rand = Random()
    private val handler = Handler(Looper.getMainLooper())
    private var lastClick = 0L            // 防抖：3s内不重复
    private val pending = HashSet<String>() // 已处理弹窗标记，防重复

    private val usage: UsageStatsManager by lazy {
        getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        startForeground(1, buildNotice())
        // 拉起守护与悬浮窗
        startService(Intent(this, GuardService::class.java))
        startService(Intent(this, FloatService::class.java))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!running || e == null) return
        val pkg = e.packageName?.toString() ?: return
        // 围栏：非目标包名直接丢弃（绝大多数事件在此终止，零开销）
        if (pkg != targetPkg) return
        // 事件节流：仅窗口变更触发查找
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val root = rootInActiveWindow ?: return
        // 二次弹窗校验：页面须同时含"需及时上门"特征文本
        if (findByText(root, "需及时上门") == null) return

        // 目标按钮：确认抢单（兼容"共N单"后缀）
        val btn = findByPrefix(root, "确认抢单") ?: return
        val key = btn.viewIdResourceName ?: "btn"
        val now = System.currentTimeMillis()
        if (now - lastClick < 3000 || !pending.add(key + now / 3000)) return

        lastClick = now
        val r = Rect().also { btn.getBoundsInScreen(it) }
        btn.recycle()
        // 拟人：随机延迟 800~maxDelay 后散射点击
        handler.postDelayed({ scatterClick(r) }, 800 + rand.nextInt(maxDelay - 800 + 1))
    }

    /** 按钮区域内随机取点，模拟按下-微滑-抬起 */
    private fun scatterClick(r: Rect) {
        val cx = r.left + r.width() / 2 + rand.nextInt(r.width() / 3) - r.width() / 6
        val cy = r.top + r.height() / 2 + rand.nextInt(r.height() / 3) - r.height() / 6
        val path = Path().apply {
            moveTo(cx.toFloat(), cy.toFloat())
            lineTo((cx + rand.nextInt(7) - 3).toFloat(), (cy + rand.nextInt(7) - 3).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 80 + rand.nextInt(70))
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun findByText(root: AccessibilityNodeInfo, t: String): AccessibilityNodeInfo? =
        root.findAccessibilityNodeInfosByText(t).firstOrNull()

    private fun findByPrefix(root: AccessibilityNodeInfo, p: String): AccessibilityNodeInfo? {
        val q = root.findAccessibilityNodeInfosByText(p) ?: return null
        for (n in q) if (n.isClickable) return n
        return null
    }

    private fun buildNotice(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "运行状态", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_ID)
            .setContentTitle("轻单助手运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).build()
    }

    override fun onInterrupt() { running = false }
    override fun onDestroy() { running = false; super.onDestroy() }
    override fun onUnbind(intent: Intent?): Boolean { running = false; return super.onUnbind(intent) }
}
