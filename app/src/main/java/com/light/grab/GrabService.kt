package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
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
        const val JUDGE_PX = 80          // 双来源裁决阈值
        const val SCATTER = 10           // 散点幅度 px
        const val VERIFY_MS = 800L       // 点击后回看延时
        const val MAX_GUN = 3            // 补枪上限

        var instance: GrabService? = null
        var feature = "立即上门单"
        var btnWord = "确认抢单"
        var lockRect: Rect? = null       // px 绝对坐标，唯一坐标体系
        var hit = 0
        var firing = false               // 防重入：三枪进行中不再触发
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        goForeground()
        log("上岗。闭环体制 v2")
    }

    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onInterrupt() {}
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    // ── 保活层：最高级前台通知，无看门狗无心跳 ──
    private fun goForeground() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "运行", NotificationManager.IMPORTANCE_MAX))
        }
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("轻点运行中")
                .setContentText("今日命中 $hit 发")
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true).build()
        }
        startForeground(1, n)
    }

    // ── 感知层：只管看见，输出 px 绝对坐标 ──
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (firing) return
        val root = rootInActiveWindow ?: return
        if (findText(root, feature) == null) return   // 特征词未出现，不动作

        val btn = findText(root, btnWord)
        val lr = lockRect

        // ── 决策层：双来源互证，动态优先、静态兜底 ──
        val target: Rect = when {
            btn != null && lr != null -> {
                val br = Rect(); btn.getBoundsInScreen(br)
                val dx = Math.abs(br.centerX() - lr.centerX())
                val dy = Math.abs(br.centerY() - lr.centerY())
                if (dx < JUDGE_PX && dy < JUDGE_PX) {
                    log("采信动态节点(偏差${dx}px) 按钮@${fmt(br)}")
                    br
                } else {
                    log("采信锁区(节点偏${dx}px) 锁区@${fmt(lr)}")
                    Rect(lr)
                }
            }
            btn != null -> Rect().also { btn.getBoundsInScreen(it) }.also { log("无锁区,用节点@${fmt(it)}") }
            lr != null -> Rect(lr).also { log("无节点,用锁区@${fmt(lr)}") }
            else -> { log("无按钮无锁区,放弃"); return }
        }

        val x = target.centerX() + (0..SCATTER * 2).random() - SCATTER
        val y = target.centerY() + (0..SCATTER * 2).random() - SCATTER
        log("目标点($x,$y)")
        firing = true
        shoot(x, y, target, 1)
    }

    // ── 执行层：三枪闭环，弹窗消失=命中 ──
    private fun shoot(x: Int, y: Int, rect: Rect, gun: Int) {
        if (gun > MAX_GUN) {
            log("三枪全败,静默")
            firing = false
            return
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = verify(x, y, rect, gun)
            override fun onCancelled(g: GestureDescription?) = verify(x, y, rect, gun)
        }, null)
    }

    private fun verify(x: Int, y: Int, rect: Rect, gun: Int) {
        handler.postDelayed({
            val root = rootInActiveWindow
            val gone = root == null || findText(root, btnWord) == null
            if (gone) {
                hit++
                log("第${gun}枪命中,弹窗消失,共$hit 发")
                goForeground()   // 通知上刷新命中数
                firing = false
            } else {
                // 降级路线：第1枪失败→锁区中心；再败→矩形逐枪偏移
                val nx: Int; val ny: Int
                if (gun == 1 && lockRect != null) {
                    nx = lockRect!!.centerX(); ny = lockRect!!.centerY()
                } else {
                    nx = rect.centerX() + 40 * gun; ny = rect.centerY()
                }
                log("第${gun}枪未中→补枪($nx,$ny)")
                shoot(nx, ny, rect, gun + 1)
            }
        }, VERIFY_MS)
    }

    // ── 工具 ──
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
    private fun log(s: String) { Log.i(TAG, s) }
}
