package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GrabService : AccessibilityService() {

    companion object {
        const val TAG = "QD"
        const val CH = "qd_alive"
        const val JUDGE_PX = 80        // 节点与锁区偏差容忍px
        const val SCATTER = 10         // 点击散布px
        const val VERIFY_MS = 800L     // 弹窗确认延时
        const val THROTTLE_MS = 400L   // 扫描节流
        const val SENTINEL_DELAY = 300L // 哨兵稳定延时
        const val SNIPER_DELAY = 500L   // 狙击延时

        var instance: GrabService? = null
        var feature = "立即上门单"
        var btnWord = "确认抢单"
        var lockRect: Rect? = null
        var mode = "影子"
        var hit = 0
        var firing = false
        val targetPkg = "com.jingdong.pdj"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var sentineling = false

    private fun quiet() = mode == "静默"
    private fun log(s: String) { if (!quiet()) Log.i(TAG, s) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        goForeground()
        log("上岗 模式=$mode 包=$targetPkg 特征=[$feature] 按钮=[$btnWord] 锁区=${lockRect ?: "未配"}")
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}

    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(
                NotificationChannel(CH, "运行", NotificationManager.IMPORTANCE_LOW))
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("轻点·$mode")
                .setContentText("累计命中 $hit 发")
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).build()
        }
        startForeground(1, n)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val t = e?.eventType ?: return
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return
        if (e.packageName?.toString() != targetPkg) return
        if (firing) return
        val now = System.currentTimeMillis()
        if (now - lastScan < THROTTLE_MS) return
        lastScan = now

        when (mode) {
            "哨兵" -> modeSentinel()
            "狙击" -> modeSniper()
            else -> modeShadow()   // 影子、静默同一套流程
        }
    }

    // ── 影子/静默：节点+锁区双保险，立刻打，三枪补射 ──
    private fun modeShadow() {
        val root = rootInActiveWindow ?: return
        if (findText(root, feature) == null) return
        val btn = findText(root, btnWord)
        val lr = lockRect
        val target: Rect = when {
            btn != null && lr != null -> {
                val br = Rect(); btn.getBoundsInScreen(br)
                val dx = Math.abs(br.centerX() - lr.centerX())
                val dy = Math.abs(br.centerY() - lr.centerY())
                if (dx < JUDGE_PX && dy < JUDGE_PX) { log("影子:动态节点 偏差${dx}px"); br }
                else { log("影子:采信锁区 偏差${dx}px"); Rect(lr) }
            }
            btn != null -> Rect().also { btn.getBoundsInScreen(it) }.also { log("影子:仅节点") }
            lr != null -> Rect(lr).also { log("影子:仅锁区") }
            else -> return
        }
        firing = true
        fireAt(jitter(target), "影子", 1, true)
    }

    // ── 哨兵：发现弹窗→延时确认稳定→再打，三枪补射 ──
    private fun modeSentinel() {
        val root = rootInActiveWindow ?: return
        if (findText(root, feature) == null) return
        if (sentineling) return
        sentineling = true
        log("哨兵:发现弹窗,${SENTINEL_DELAY}ms后确认")
        handler.postDelayed({
            sentineling = false
            val r = rootInActiveWindow ?: return@postDelayed
            if (findText(r, feature) == null) { log("哨兵:弹窗已消失,放弃"); return@postDelayed }
            val btn = findText(r, btnWord)
            val target = if (btn != null) Rect().also { btn.getBoundsInScreen(it) }
                         else lockRect?.let { Rect(it) } ?: return@postDelayed
            log("哨兵:弹窗稳定,打")
            firing = true
            fireAt(jitter(target), "哨兵", 1, true)
        }, SENTINEL_DELAY)
    }

    // ── 狙击：只信锁区，延时后精准一枪，不补射 ──
    private fun modeSniper() {
        val lr = lockRect ?: return
        val root = rootInActiveWindow ?: return
        if (findText(root, feature) == null) return
        log("狙击:锁定${fmt(lr)},${SNIPER_DELAY}ms后一枪")
        firing = true
        handler.postDelayed({
            fireAt(Pair(lr.centerX(), lr.centerY()), "狙击", 1, false)
        }, SNIPER_DELAY)
    }

    private fun jitter(r: Rect): Pair<Int, Int> =
        Pair(r.centerX() + (0..SCATTER * 2).random() - SCATTER,
             r.centerY() + (0..SCATTER * 2).random() - SCATTER)

    private fun fireAt(pt: Pair<Int, Int>, tag: String, gun: Int, retry: Boolean) {
        val (x, y) = pt
        log("$tag:第${gun}枪($x,$y)")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gs: GestureDescription?) = verify(tag, gun, retry, x, y)
            override fun onCancelled(gs: GestureDescription?) = verify(tag, gun, retry, x, y)
        }, null)
    }

    private fun verify(tag: String, gun: Int, retry: Boolean, x: Int, y: Int) {
        handler.postDelayed({
            val root = rootInActiveWindow
            val gone = root == null || findText(root, btnWord) == null
            if (gone) {
                hit++
                log("$tag:第${gun}枪命中,累计$hit 发")
                goForeground()
                firing = false
            } else if (retry && gun < 3) {
                val nx = if (gun == 1 && lockRect != null) lockRect!!.centerX() else x + 40 * gun
                val ny = if (gun == 1 && lockRect != null) lockRect!!.centerY() else y
                fireAt(Pair(nx, ny), tag, gun + 1, true)
            } else {
                log("$tag:未命中,收工")
                firing = false
            }
        }, VERIFY_MS)
    }

    private fun findText(root: AccessibilityNodeInfo, t: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByText(t) ?: return null
        for (n in list) {
            if (!n.isVisibleToUser) continue
            if (n.text?.toString()?.contains(t) == true) {
                var p = n.parent
                while (p != null && !p.isClickable) p = p.parent
                return p ?: n
            }
        }
        return null
    }

    private fun fmt(r: Rect) = "${r.left},${r.top},${r.width()},${r.height()}"
}
