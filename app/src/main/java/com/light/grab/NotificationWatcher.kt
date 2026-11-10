package com.light.grab

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 通知哨兵：监听系统通知，命中目标App的新通知就通知 GrabService 动手 */
class NotificationWatcher : NotificationListenerService() {

    private fun isTarget(sbn: StatusBarNotification): Boolean {
        // 1. 自己的通知（含"轻点运行中"常驻）永远忽略
        if (sbn.packageName == packageName) return false
        // 2. 只认目标App（用户在武器库选的）
        val target = getSharedPreferences("lightdot", MODE_PRIVATE)
            .getString("app_pkg", null) ?: return false
        if (sbn.packageName != target) return false
        // 3. 常驻/进行中通知忽略（App挂后台那种），只抓真正弹出的新单
        if (sbn.isOngoing) return false
        return true
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isTarget(sbn)) return

        // 关键词过滤：设了关键词就只对包含它的通知触发，没设就全触发
        val kw = getSharedPreferences("lightdot", MODE_PRIVATE)
            .getString("keyword", "")?.trim() ?: ""
        if (kw.isNotEmpty()) {
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            if (!title.contains(kw) && !text.contains(kw)) return
        }

        android.util.Log.d("LightGrab", "[通知哨兵] 命中 ${sbn.packageName}")
        // 转交给无障碍服务执行
        GrabService.instance?.onNotiFired()
    }
}
