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
        @Volatile var targetPkg = "com.sfexpress.knight"          // 电子围栏：顺丰同城骑士
        @Volatile var dialogKeywords: List<String> = listOf("立即上门单")  // 弹窗标题特征词
        @Volatile var btnKeywords: List<String> = listOf("确认抢单")        // 按钮文字
        @Volatile var maxDelay = 200   // 随机延迟上限(ms)，下限固定100
    }

    private val rand = Random()
    private val handler = Handler(Looper.getMainLooper())
    private var lastClick = 0L
    private var pending = false   // 防抖：已安排点击时不再重复安排

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        startForeground(1, buildNotice())
        startService(Intent(this, FloatService::class.java))
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (!running || e == null) return
        // 围栏：用事件自带包名，100%准确，不依赖窗口切换
        if (e.packageName?.toString() != targetPkg) return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (pending) return   // 已经在处理一次弹窗，忽略后续事件

        // 关键改动①：遍历所有窗口（能看到 Dialog 弹窗），不只 rootInActiveWindow
        val sfWindows = windows.filter { it.packageName == targetPkg }
        if (sfWindows.isEmpty()) return

        // 弹窗特征：任一关键词命中即算（在任何顺丰窗口里找）
        val hit = sfWindows.any { w ->
            dialogKeywords.any { kw -> findByText(w.root, kw) }
        }
        if (!hit) return

        val now = System.currentTimeMillis()
        if (now - lastClick < 2000) return

        // 关键改动②：短随机延迟 100~200ms，事件驱动下总耗时≈120~250ms
        pending = true
        handler.postDelayed({
            tryClickWithRetry(0)
        }, (100 + rand.nextInt(maxDelay.coerceAtLeast(101) - 100)).toLong())
    }

    // 关键改动③：找按钮重试 5 次，每次间隔 80ms；点中即停，绝不重复点
    private fun tryClickWithRetry(attempt: Int) {
        if (!running) { pending = false; return }

        // 双保险围栏：点击前再验一次，只认顺丰的窗口
        val sfWindows = windows.filter { it.packageName == targetPkg }
        val btn = btnKeywords.firstNotNullOfOrNull { kw ->
            sfWindows.firstNotNullOfOrNull { w -> findClickable(w.root, kw) }
        }

        if (btn != null) {
            val r = Rect().also { btn.getBoundsInScreen(it) }
            btn.recycle()
            lastClick = System.currentTimeMillis()
            scatterClick(r)
            pending = false
            return   // ← 点中即返回，不会点第二次
        }

        if (attempt < 4) {
            handler.postDelayed({ tryClickWithRetry(attempt + 1) }, 80)
        } else {
            pending = false   // 5 次都没找到（弹窗可能已关闭），放弃
        }
    }

    private fun scatterClick(r: Rect) {
        val cx = r.left + r.width() / 2 + rand.nextInt((r.width() / 3).coerceAtLeast(1)) - r.width() / 6
        val cy = r.top + r.height() / 2 + rand.nextInt((r.height() / 3).coerceAtLeast(1)) - r.height() / 6
        val path = Path().apply {
            moveTo(cx.toFloat(), cy.toFloat())
            lineTo((cx + rand.nextInt(7) - 3).toFloat(),
                   (cy + rand.nextInt(7) - 3).toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(
            path, 0L, (80 + rand.nextInt(70)).toLong())
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun findByText(root: AccessibilityNodeInfo?, t: String): Boolean {
        if (root == null) return false
        return try { root.findAccessibilityNodeInfosByText(t)?.isNotEmpty() == true } catch (e: Exception) { false }
    }

    private fun findClickable(root: AccessibilityNodeInfo?, p: String): AccessibilityNodeInfo? {
        if (root == null) return null
        return try {
            root.findAccessibilityNodeInfosByText(p)?.firstOrNull { it.isClickable }
                // 按钮本身不可点击时，向上找可点击的父节点
                ?: root.findAccessibilityNodeInfosByText(p)?.firstOrNull { n ->
                    var cur = n.parent
                    while (cur != null) {
                        if (cur.isClickable) return@firstOrNull cur
                        cur = cur.parent
                    }
                    null
                }
        } catch (e: Exception) { null }
    }

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
        pending = false
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onUnbind(i: Intent?): Boolean { running = false; return super.onUnbind(i) }
}
