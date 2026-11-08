package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.Random

class GrabService : AccessibilityService() {

    companion object {
        const val CH_ID = "grab"
        @Volatile var running = false
        @Volatile var targetPkg = "com.sfexpress.knight"          // 默认：顺丰同城骑士
        @Volatile var dialogKeywords: List<String> = listOf("需及时上门")   // 弹窗特征词
        @Volatile var btnKeywords: List<String> = listOf("确认抢单")        // 按钮文字
        @Volatile var maxDelay = 2500
    }

    private val rand = Random()
    private val handler = Handler(Looper.getMainLooper())
    private var lastClick = 0L
    private var lastScan = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        startForeground(1, buildNotice())
        startService(Intent(this, FloatService::class.java))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!running || e == null) return
        if (e.packageName?.toString() != targetPkg) return          // 围栏
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastScan < 200) return
        lastScan = now

        val root = rootInActiveWindow ?: return

        // 弹窗特征：任一关键词命中即算
        val hit = dialogKeywords.any { findByText(root, it) != null }
        if (!hit) return

        // 按钮文字：任一关键词命中即点
        val btn = btnKeywords.firstNotNullOfOrNull { findClickable(root, it) }
            ?: return
        if (now - lastClick < 3000) return
        lastClick = now

        val r = Rect().also { btn.getBoundsInScreen(it) }
        btn.recycle()
        handler.postDelayed({ scatterClick(r) },
            (800 + rand.nextInt((maxDelay - 800).coerceAtLeast(1))).toLong())
    }

    private fun scatterClick(r: Rect) {
        val cx = r.left + r.width() / 2 + rand.nextInt(r.width() / 3) - r.width() / 6
        val cy = r.top + r.height() / 2 + rand.nextInt(r.height() / 3) - r.height() / 6
        val path = Path().apply {
            moveTo(cx.toFloat(), cy.toFloat())
            lineTo((cx + rand.nextInt(7) - 3).toFloat(),
                   (cy + rand.nextInt(7) - 3).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path, 0L, (80 + rand.nextInt(70)).toLong())
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun findByText(root: AccessibilityNodeInfo, t: String) =
        root.findAccessibilityNodeInfosByText(t)?.firstOrNull()

    private fun findClickable(root: AccessibilityNodeInfo, p: String) =
        root.findAccessibilityNodeInfosByText(p)?.firstOrNull { it.isClickable }

    private fun buildNotice(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "运行状态", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH_ID)
            .setContentTitle("轻单助手运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi).setOngoing(true).build()
    }

    override fun onInterrupt() { running = false }
    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onUnbind(i: Intent?): Boolean { running = false; return super.onUnbind(i) }
}
