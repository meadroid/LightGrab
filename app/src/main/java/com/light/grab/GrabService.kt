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
    }

    private val rand = Random()
    private val handler = Handler(Looper.getMainLooper())
    private var lastClick = 0L
    private var pending = false
    private var pendingAt = 0L   // pending 开始时间，用于超时强制复位

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        startForeground(1, buildNotice())
        startService(Intent(this, FloatService::class.java))
        // 看门狗：每 2 秒自检一次，防卡死
        handler.postDelayed(watchdog, 2000)
    }

    // ===== 保险①：看门狗，pending 超过 3 秒视为卡死，强制复位 =====
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                if (pending && System.currentTimeMillis() - pendingAt > 3000) {
                    pending = false   // 卡死了，强制放行，下一单照常接
                }
            } catch (e: Exception) { }
            if (running) handler.postDelayed(this, 2000)
        }
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        try {
            if (!running || e == null) return
            if (e.packageName?.toString() != Config.targetPkg) return
            if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
            if (pending) return

            // ===== 保险②：整段业务 try-catch，任何异常不复位就漏单 =====
            val sfWindows = try { windows.filter { it.packageName == Config.targetPkg } }
                            catch (e: Exception) { return }
            if (sfWindows.isEmpty()) return

            val hit = sfWindows.any { w ->
                Config.dialogKeywords.any { kw -> findByText(w.root, kw) }
            }
            if (!hit) return

            val now = System.currentTimeMillis()
            if (now - lastClick < 2000) return

            pending = true
            pendingAt = now
            val delay = Config.delayMin + rand.nextInt(
                (Config.delayMax - Config.delayMin).coerceAtLeast(1))
            handler.postDelayed({ tryClickWithRetry(0) }, delay.toLong())
        } catch (e: Exception) {
            // 保险③：最外层兜底，绝不让异常杀死事件处理能力
            pending = false
        }
    }

    private fun tryClickWithRetry(attempt: Int) {
        try {
            if (!running) { pending = false; return }

            val sfWindows = try { windows.filter { it.packageName == Config.targetPkg } }
                            catch (e: Exception) { emptyList<android.view.accessibility.AccessibilityWindow>() }
            val btn = Config.btnKeywords.firstNotNullOfOrNull { kw ->
                sfWindows.firstNotNullOfOrNull { w -> findClickable(w.root, kw) }
            }

            if (btn != null) {
                val r = Rect().also { btn.getBoundsInScreen(it) }
                btn.recycle()
                lastClick = System.currentTimeMillis()
                scatterClick(r)
                pending = false
                return
            }

            if (attempt < 4) {
                handler.postDelayed({ tryClickWithRetry(attempt + 1) }, 80)
            } else {
                pending = false   // 5 次没找到，正常放弃，复位等下一单
            }
        } catch (e: Exception) {
            pending = false   // 任何异常都复位，绝不卡死
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
