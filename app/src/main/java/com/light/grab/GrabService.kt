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
import android.widget.Toast
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
    private var lastReport = 0L

    private fun report(msg: String) {
        // 同一秒只报一次，避免刷屏
        val now = System.currentTimeMillis()
        if (now - lastReport < 1000) return
        lastReport = now
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (e: Exception) {}
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        try { startForeground(1, buildNotice()) } catch (e: Exception) {}
        try { startForegroundService(Intent(this, FloatService::class.java)) } catch (e: Exception) {}
        handler.postDelayed(watchdog, 2000)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (pending && System.currentTimeMillis() - pendingAt > 3000) pending = false
            if (running) handler.postDelayed(this, 2000)
        }
    }

    private fun targetRoots(): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            windows?.forEach { w ->
                try { w.root?.let { roots.add(it) } } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        try { rootInActiveWindow?.let { roots.add(it) } } catch (e: Exception) {}
        return roots
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        try {
            if (!running || e == null) return

            val pkg = e.packageName?.toString() ?: return

            // 调试报告：看到谁的事件、配置的目标是谁
            if (pkg != Config.targetPkg) {
                report("事件来自[$pkg]，目标[${Config.targetPkg}]，不匹配")
                return
            }
            if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
            if (pending) return

            val roots = targetRoots()
            if (roots.isEmpty()) { report("拿不到窗口节点"); return }

            val hitKw = Config.dialogKeywords.firstOrNull { kw ->
                roots.any { r -> findByText(r, kw) }
            }
            if (hitKw == null) return

            report("发现弹窗关键词[$hitKw]，准备点击")
            val now = System.currentTimeMillis()
            if (now - lastClick < 2000) { report("距上次点击太近，跳过"); return }

            pending = true
            pendingAt = now
            val delay = Config.delayMin + rand.nextInt(
                (Config.delayMax - Config.delayMin).coerceAtLeast(1))
            handler.postDelayed({ tryClickWithRetry(0) }, delay.toLong())
        } catch (e: Exception) {
            pending = false
        }
    }

    private fun tryClickWithRetry(attempt: Int) {
        try {
            if (!running) { pending = false; return }

            val roots = targetRoots()
            var btn: AccessibilityNodeInfo? = null
            var hitBtnKw: String? = null
            outer@ for (kw in Config.btnKeywords) {
                for (r in roots) {
                    btn = findClickable(r, kw)
                    if (btn != null) { hitBtnKw = kw; break@outer }
                }
            }

            if (btn != null) {
                val rect = Rect()
                btn.getBoundsInScreen(rect)
                btn.recycle()
                lastClick = System.currentTimeMillis()
                report("点[$hitBtnKw] @${rect.centerX()},${rect.centerY()}")
                scatterClick(rect)
                pending = false
                return
            }

            if (attempt < 4) {
                handler.postDelayed({ tryClickWithRetry(attempt + 1) }, 80)
            } else {
                report("重试5次没找到按钮")
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
            for (n in list) if (n.isClickable) return n
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
