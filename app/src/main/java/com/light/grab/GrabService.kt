package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GrabService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "lightgrab_fg"
        const val NOTI_ID = 1001

        @Volatile
        var currentMode: Int = MainActivity.MODE_SHADOW
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        startFg()
        log("[服务] 已连接，模式=$currentMode")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (MainActivity.APPS.values.none { pkg.contains(it) }) return

        when (currentMode) {
            MainActivity.MODE_SHADOW -> log("[影子]发现窗口 pkg=$pkg")
            MainActivity.MODE_SENTRY -> log("[哨兵]监听中 pkg=$pkg")
            MainActivity.MODE_SNIPER -> tryGrab(event)
            MainActivity.MODE_SILENT -> log("[静默]事件 pkg=$pkg")
        }
    }

    private fun tryGrab(event: AccessibilityEvent) {
        val node: AccessibilityNodeInfo = rootInActiveWindow ?: return
        val keywords = listOf("抢单", "接单", "立即抢")
        for (kw in keywords) {
            val targets = node.findAccessibilityNodeInfosByText(kw)
            if (targets != null && targets.isNotEmpty()) {
                log("[狙击]命中「$kw」，执行点击")
                for (t in targets) {
                    if (t.isClickable) { t.performAction(AccessibilityNodeInfo.ACTION_CLICK); break }
                }
                return
            }
        }
    }

    private fun log(msg: String) {
        android.util.Log.d("LightGrab", msg)
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "轻单前台服务", NotificationManager.IMPORTANCE_LOW)
        )

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("轻单运行中")
            .setContentText("模式=$currentMode")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        startForeground(NOTI_ID, n)
    }

    override fun onInterrupt() {
        log("[服务] 被中断")
    }
}
