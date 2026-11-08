package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        @Volatile var instance: GrabService? = null
        @Volatile var logHook: ((String) -> Unit)? = null
        private const val CH_ID = "grab_status"
        private const val NOTI_ID = 1
    }

    private var lastClick = 0L
    private var pending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyStatus(running = true)
        log("服务已启动 ✅ 监听: ${Config.targetPkg}")
    }

    override fun onDestroy() {
        instance = null
        notifyStatus(running = false)   // 停止时换成红点提示
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------- 状态栏通知：极简一行，绿=运行 / 红=停止 ----------

    private fun notifyStatus(running: Boolean) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "运行状态", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val b = Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.presence_online)   // 系统自带小圆点图标
                .setContentTitle(if (running) "🟢 运行中" else "🔴 已停止")
                .setOngoing(running)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
            if (running) {
                b.setColor(Color.rgb(0, 200, 120))   // 薄荷绿
                b.setAutoCancel(false)
            } else {
                b.setColor(Color.rgb(220, 60, 60))   // 红
                b.setAutoCancel(true)                // 红条用户可划走
            }
            // 点击跳到本 App 界面
            val pi = PendingIntent.getActivity(
                this, 0,
                Intent(this, Class.forName("com.light.grab.MainActivity")),
                PendingIntent.FLAG_IMMUTABLE
            )
            b.setContentIntent(pi)
            nm.notify(NOTI_ID, b.build())
        } catch (ex: Exception) {}
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return

        if (pkg != Config.targetPkg) return
        if (pending) return
        if (System.currentTimeMillis() - lastClick < 399) return

        val roots = ArrayList<AccessibilityNodeInfo?>()
        try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
        try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}

        var hit: String? = null
        for (root in roots) {
            if (root == null) continue
            for (kw in Config.dialogKeywords) {
                if (findByText(root, kw)) { hit = kw; break }
            }
            if (hit != null) break
        }
        if (hit == null) return

        log("🔥 发现弹窗[$hit]，延迟点击...")
        pending = true

        val delay = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        Thread { Thread.sleep(delay); doClick() }.start()
    }

    private fun doClick() {
        var done = false
        var hitBtnKw = ""
        try {
            for (i in 1..5) {
                val roots = ArrayList<AccessibilityNodeInfo?>()
                try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
                try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}

                outer@ for (root in roots) {
                    if (root == null) continue
                    for (kw in Config.btnKeywords) {
                        val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null }
                        if (nodes != null && nodes.isNotEmpty()) {
                            val btn = nodes[nodes.size - 1]
                            val rect = Rect()
                            btn.getBoundsInScreen(rect)
                            lastClick = System.currentTimeMillis()
                            var ok = false
                            try { ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) {}
                            if (!ok) { try { scatterClick(rect) } catch (ex: Exception) {} }
                            done = true
                            hitBtnKw = kw
                            break@outer
                        }
                    }
                }
                if (done) break
                Thread.sleep(100)
            }
        } catch (ex: Exception) {}

        log(if (done) "点[$hitBtnKw] 完成 ✅" else "⚠️ 未找到按钮 ${Config.btnKeywords}")
        pending = false
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (ex: Exception) { null }
        return nodes != null && nodes.isNotEmpty()
    }

    private fun scatterClick(rect: Rect) {
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun log(msg: String) {
        val hook = logHook
        if (hook != null) { try { hook(msg) } catch (ex: Exception) {} }
        else { try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (ex: Exception) {} }
    }
}
