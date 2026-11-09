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

        private const val WATCHDOG_MS = 5000L    // 看门狗超时
        private const val CLICK_GAP_MS = 300L    // 两次点击最小间隔
        private const val RETRY_MS = 120L        // 找按钮重试间隔
        private const val RETRY_MAX = 8          // 找按钮最多重试次数
        private const val RECHECK_MS = 250L      // 点完复查间隔
        private const val HEARTBEAT_MS = 1500L   // 心跳间隔
    }

    private var lastClick = 0L
    private var pending = false
    private var pendingStart = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notifyStatus(running = true)
        log("服务已启动 ✅")
        startHeartbeat()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        instance = null
        notifyStatus(running = false)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ==================== 心跳：不依赖顺丰事件，定期自查屏幕 ====================

    private fun startHeartbeat() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                try { detect() } catch (ex: Exception) {}
                mainHandler.postDelayed(this, HEARTBEAT_MS)
            }
        }, HEARTBEAT_MS)
    }

    // ==================== 状态栏通知：绿=运行 / 红=停止 ====================

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
                .setColor(if (running) Color.rgb(0, 200, 120) else Color.rgb(220, 60, 60))
            b.setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, Class.forName("com.light.grab.MainActivity")),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            nm.notify(NOTI_ID, b.build())
        } catch (ex: Exception) {}
    }

    // ==================== 事件入口 + 检测 ====================

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e?.packageName?.toString() != Config.targetPkg) return
        detect()
    }

    private fun detect(): Boolean {
        // 看门狗：pending 超 5 秒 = 卡死，强制复位
        if (pending) {
            if (System.currentTimeMillis() - pendingStart > WATCHDOG_MS) {
                log("🔧 检测到卡死，强制复位")
                pending = false
            } else {
                return false
            }
        }
        if (System.currentTimeMillis() - lastClick < CLICK_GAP_MS) return false

        val roots = collectRoots()

        // 一道门：弹窗关键词在场
        var hit: String? = null
        outer@ for (root in roots) {
            if (root == null) continue
            for (kw in Config.dialogKeywords) {
                if (findByText(root, kw)) { hit = kw; break@outer }
            }
        }
        if (hit == null) return false

        // 二道门：按钮关键词也在场，才认定真弹窗（防列表页残留字样误点）
        var btnReady = false
        outer2@ for (root in roots) {
            if (root == null) continue
            for (kw in Config.btnKeywords) {
                if (findByText(root, kw)) { btnReady = true; break@outer2 }
            }
        }
        if (!btnReady) return false

        log("🔥 发现弹窗[$hit]，延迟点击...")
        pending = true
        pendingStart = System.currentTimeMillis()

        val delay = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        Thread { try { Thread.sleep(delay) } catch (ex: Exception) {} ; doClick() }.start()
        return true
    }

    // ==================== 点击：三层递进策略 ====================

    private fun doClick() {
        var done = false
        var hitBtnKw = ""
        try {
            for (i in 1..RETRY_MAX) {
                val roots = collectRoots()

                outer@ for (root in roots) {
                    if (root == null) continue
                    for (kw in Config.btnKeywords) {
                        val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null }
                        if (nodes != null && nodes.isNotEmpty()) {
                            lastClick = System.currentTimeMillis()
                            tapButton(nodes[nodes.size - 1])
                            done = true
                            hitBtnKw = kw
                            break@outer
                        }
                    }
                }
                if (done) break
                Thread.sleep(RETRY_MS)
            }
        } catch (ex: Exception) {
        } finally {
            // 无论走到哪，强制解锁，pending 永不锈死
            pending = false
        }

        log(if (done) "点[$hitBtnKw] 完成 ✅" else "⚠️ 未找到按钮 ${Config.btnKeywords}")

        // 复查：弹窗还挂着就持续抓，不彻底停
        mainHandler.postDelayed({ try { detect() } catch (ex: Exception) {} }, RECHECK_MS)
    }

    /** 三层递进：节点自点 → 爬父节点 → 坐标手势保底 */
    private fun tapButton(btn: AccessibilityNodeInfo) {
        val rect = Rect()
        btn.getBoundsInScreen(rect)

        var target: AccessibilityNodeInfo? = if (btn.isClickable) btn else null
        if (target == null) {
            var p = btn.parent
            var hops = 0
            while (p != null && hops < 5) {
                if (p.isClickable) { target = p; break }
                p = p.parent; hops++
            }
        }

        var ok = false
        if (target != null) {
            try { ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) {}
        }
        if (!ok && rect.width() > 0 && rect.height() > 0) {
            try { scatterClick(rect) } catch (ex: Exception) {}
        }
    }

    // ==================== 工具 ====================

    private fun collectRoots(): List<AccessibilityNodeInfo?> {
        val roots = ArrayList<AccessibilityNodeInfo?>()
        try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
        try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}
        return roots
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (ex: Exception) { null }
        return nodes != null && nodes.isNotEmpty()
    }

    private fun scatterClick(rect: Rect) {
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                .build(),
            null, null
        )
    }

    private fun log(msg: String) {
        val hook = logHook
        if (hook != null) { try { hook(msg) } catch (ex: Exception) {} }
        else { try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (ex: Exception) {} }
    }
}
