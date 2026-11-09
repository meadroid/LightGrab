package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        @Volatile var instance: GrabService? = null
        @Volatile var logHook: ((String) -> Unit)? = null
        private var lastClick = 0L
        private var lastProbe = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        foreground()
        log("✅ 服务就绪（事件驱动）")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ==================== 核心：纯事件驱动 ====================

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (!Config.isTarget(pkg)) return

        // 探针：让你看见顺丰的事件在流动（5秒限流）
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            probe("👁 事件:窗口切换 [$pkg]")
        }

        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            e.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return

        detect()
    }

    private fun detect() {
        val now = System.currentTimeMillis()
        if (now - lastClick < 300) return

        val root = sfRoot() ?: run { probe("🙈 顺丰窗口不在前台"); return }

        if (find(root, Config.dialogKeywords) == null) return
        if (find(root, Config.btnKeywords) == null) return

        val d = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        log("🔥 弹窗出现，${d}ms 后点击")
        Thread { try { Thread.sleep(d) } catch (ex: Exception) {}; click() }.start()
    }

    private fun click() {
        val root = sfRoot() ?: return
        for (kw in Config.btnKeywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null } ?: continue
            for (n in nodes) {
                if (!visible(n)) continue
                lastClick = System.currentTimeMillis()
                tap(n)
                log("✅ 已点击[$kw]")
                return
            }
        }
        probe("⚠️ 弹窗在但按钮没找到")
    }

    private fun tap(btn: AccessibilityNodeInfo) {
        var t: AccessibilityNodeInfo? = if (btn.isClickable) btn else null
        var p = btn.parent; var i = 0
        while (t == null && p != null && i < 5) { if (p.isClickable) t = p; p = p.parent; i++ }
        val ok = try { t?.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) { false } ?: false
        if (!ok) {
            val r = Rect(); btn.getBoundsInScreen(r)
            if (r.width() > 0) {
                val path = android.graphics.Path().apply {
                    moveTo(r.centerX().toFloat(), r.centerY().toFloat())
                    lineTo(r.centerX().toFloat(), r.centerY().toFloat())
                }
                dispatchGesture(android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                    .build(), null, null)
            }
        }
    }

    // ==================== 工具 ====================

    private fun sfRoot(): AccessibilityNodeInfo? {
        try { rootInActiveWindow?.let { if (Config.isTarget(it.packageName?.toString())) return it } } catch (ex: Exception) {}
        try { windows?.forEach { w -> w.root?.let { r -> if (Config.isTarget(r.packageName?.toString())) return r } } } catch (ex: Exception) {}
        return null
    }

    private fun find(root: AccessibilityNodeInfo, kws: List<String>): String? {
        for (kw in kws) {
            val ns = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null } ?: continue
            for (n in ns) if (visible(n) && n.text == kw) return kw
        }
        return null
    }

    private fun visible(n: AccessibilityNodeInfo): Boolean = try {
        n.isVisibleToUser && n.getBoundsInScreen(Rect()).let { it.width() > 0 && it.height() > 0 }
    } catch (ex: Exception) { false }

    private fun probe(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastProbe > 5000) { lastProbe = now; log(msg) }
    }

    private fun log(msg: String) {
        try { logHook?.invoke(msg) } catch (ex: Exception) {}
    }

    // ==================== 常驻通知（简单一条） ====================

    private fun foreground() {
        try {
            val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(NotificationChannel("g", "轻单助手", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(this, 0,
                Intent(this, Class.forName("com.light.grab.MainActivity")),
                PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "g")
                .setContentTitle("轻单助手运行中")
                .setContentText("事件驱动监控中")
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (ex: Exception) {}
    }
}
