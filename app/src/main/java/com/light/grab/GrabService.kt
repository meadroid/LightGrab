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
    private var pendingAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        // 所有启动逻辑全部兜底 —— 这里抛任何异常服务都会被系统判"故障"
        try {
            startForeground(1, buildNotice())
        } catch (e: Exception) { e.printStackTrace() }
        try {
            startForegroundService(Intent(this, FloatService::class.java))
        } catch (e: Exception) { e.printStackTrace() }
        handler.postDelayed(watchdog, 2000)
    }

    // 看门狗：每 2 秒自检，pending 超 3 秒强制复位，防装死
    private val watchdog = object : Runnable {
        override fun run() {
            try {
                if (pending && System.currentTimeMillis() - pendingAt > 3000) {
                    pending = false
                }
            } catch (e: Exception) { }
            if (running) handler.postDelayed(this, 2000)
        }
    }

    /** 收集目标应用的所有窗口根节点（含弹窗），全部兼容低版本 */
    private fun targetRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            val w = windows ?: return roots
            for (win in w) {
                try {
                    val r = win.root ?: continue
                    if (r.packageName?.toString() == Config.targetPkg) roots.add(r)
                } catch (e: Exception) { }
            }
        } catch (e: Exception) { }
        // 兜底：当前活动窗口（去重，避免和上面重复也无所谓，查词而已）
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (e: Exception) { }
        return roots
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        try {
            if (!running || e == null) return
            if (e.packageName?.toString() != Config.targetPkg) return
            if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
            if (pending) return

            val roots = targetRoots()
            if (roots.isEmpty()) return

            val hit = roots.any { r ->
                Config.dialogKeywords.any { kw -> findByText(r, kw) }
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
            pending = false
        }
    }

    // 找按钮重试 5 次×80ms；点中即停，绝不重复点
    private fun tryClickWithRetry(attempt: Int) {
        try {
            if (!running) { pending = false; return }

            val roots = targetRoots()
            var btn: AccessibilityNodeInfo? = null
            for (kw in Config.btnKeywords) {
                for (r in roots) {
                    btn = findClickable(r, kw)
                    if (btn != null) break
                }
                if (btn != null) break
            }

            if (btn != null) {
                val rect = Rect()
                btn.getBoundsInScreen(rect)
                btn.recycle()
                lastClick = System.currentTimeMillis()
                scatterClick(rect)
                pending = false
                return
            }

            if (attempt < 4) {
                handler.postDelayed({ tryClickWithRetry(attempt + 1) }, 80)
            } else {
                pending = false
            }
        } catch (e: Exception) {
            pending = false
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
        return try {
            val list = root.findAccessibilityNodeInfosByText(t)
            list != null && list.isNotEmpty()
        } catch (e: Exception) { false }
    }

    private fun findClickable(root: AccessibilityNodeInfo?, p: String): AccessibilityNodeInfo? {
        if (root == null) return null
        return try {
            val list = root.findAccessibilityNodeInfosByText(p) ?: return null
            // 先找自身可点击的
            for (n in list) if (n.isClickable) return n
            // 再向上找可点击的父节点
            for (n in list) {
                var cur: AccessibilityNodeInfo? = n.parent
                var hops = 0
                while (cur != null && hops < 5) {
                    if (cur.isClickable) return cur
                    cur = cur.parent
                    hops++
                }
            }
            null
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
