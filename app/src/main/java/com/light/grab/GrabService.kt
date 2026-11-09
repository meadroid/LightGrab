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
import android.os.Handler
import android.os.Looper
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
    private var pendingStart = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyStatus(running = true)
        log("服务已启动 ✅ 监听: ${Config.targetPkg}")
        startHeartbeat()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        instance = null
        notifyStatus(running = false)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------- 心跳：不依赖顺丰事件，每 1.5 秒自己看一眼屏幕 ----------

    private fun startHeartbeat() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try { detect() } catch (ex: Exception) {}
                mainHandler.postDelayed(this, 1500)
            }
        }, 1500)
    }

    // ---------- 状态栏通知：绿=运行 / 红=停止 ----------

    private fun notifyStatus(running: Boolean) {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(CH_ID, "运行状态", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val b = Notification.Builder(this, CH_ID)
                .setSmallIcon(android.R.drawable.presence_online)
                .setContentTitle(if (running) "🟢 运行中" else "🔴 已停止")
                .setOngoing(running)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
            if (running) {
                b.setColor(Color.rgb(0, 200, 120))
                b.setAutoCancel(false)
            } else {
                b.setColor(Color.rgb(220, 60, 60))
                b.setAutoCancel(true)
            }
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
        detect()
    }

    /** 检测弹窗；锁定期内安排延时复查，事件不丢失 */
    private fun detect(): Boolean {
        if (pending) {
            // 看门狗：pending 超 5 秒 = 卡死，强制复位
            if (System.currentTimeMillis() - pendingStart > 5000) {
                log("🔧 检测到卡死，强制复位")
                pending = false
            } else {
                return false
            }
        }
        if (System.currentTimeMillis() - lastClick < 300) return false

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
        if (hit == null) return false

        log("🔥 发现弹窗[$hit]，延迟点击...")
        pending = true
        pendingStart = System.currentTimeMillis()

        val delay = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        Thread { Thread.sleep(delay); doClick() }.start()
        return true
    }

    private fun doClick() {
        var done = false
        var hitBtnKw = ""
        try {
            for (i in 1..8) {
                val roots = ArrayList<AccessibilityNodeInfo?>()
                try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
                try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}

                outer@ for (root in roots) {
                    if (root == null) continue
                    for (kw in Config.btnKeywords) {
                        val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null }
                        if (nodes != null && nodes.isNotEmpty()) {
                            val btn = nodes[nodes.size - 1]
                            // 文字节点可能不可点，往上找可点击的父节点
                            var target: AccessibilityNodeInfo = btn
                            var p = btn.parent
                            var hops = 0
                            while (p != null && hops < 5) {
                                if (p.isClickable) { target = p; break }
                                p = p.parent; hops++
                            }
                            val rect = Rect()
                            target.getBoundsInScreen(rect)
                            lastClick = System.currentTimeMillis()
                            var ok = false
                            try { ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) {}
                            if (!ok) { try { scatterClick(rect) } catch (ex: Exception) {} }
                            done = true
                            hitBtnKw = kw
                            break@outer
                        }
                    }
                }
                if (done) break
                Thread.sleep(120)
            }
        } catch (ex: Exception) {
        } finally {
            // 无论走到哪，强制解锁，pending 永不锈死
            pending = false
        }

        log(if (done) "点[$hitBtnKw] 完成 ✅" else "⚠️ 未找到按钮 ${Config.btnKeywords}")

        // 点成或没点成，都安排 250ms 后复查，弹窗挂着就持续抓
        mainHandler.postDelayed({ detect() }, 250)
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
