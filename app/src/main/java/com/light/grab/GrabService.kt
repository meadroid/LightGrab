package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "lightgrab_fg"
        const val NOTI_ID = 1001

        @Volatile var currentMode: Int = MainActivity.MODE_SHADOW
        @Volatile var isRunning: Boolean = false
    }

    private val sp: SharedPreferences by lazy {
        getSharedPreferences("lightdot", Context.MODE_PRIVATE)
    }

    private var lastFire = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        startFg()
        log("[服务] 已连接，模式=${MainActivity.MODE_NAMES[currentMode]}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // 目标应用过滤
        val appIndex = sp.getInt("app_index", MainActivity.APPS.size)
        if (appIndex < MainActivity.APPS.size) {
            val target = MainActivity.APPS.values.toList()[appIndex]
            if (!pkg.contains(target)) return
        }

        when (currentMode) {
            MainActivity.MODE_SHADOW -> tryGrab(event, realClick = false)  // 测试线路：不真点
            MainActivity.MODE_SENTRY   -> tryGrab(event, realClick = true) // 哨兵抢单
            MainActivity.MODE_SNIPER   -> if (isRunning) tryGrab(event, realClick = true) // 狙击抢单
            MainActivity.MODE_SILENT   -> { /* 静默：休眠，什么都不做 */ }
        }
    }

    private fun tryGrab(event: AccessibilityEvent, realClick: Boolean) {
        val custom = sp.getString("keyword", "")?.trim() ?: ""
        val keywords = if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")

        // 哨兵用自己的延时区间，狙击用自己的
        val (minD, maxD) = if (currentMode == MainActivity.MODE_SENTRY)
            Pair(sp.getInt("sentry_delay_min", 300), sp.getInt("sentry_delay_max", 800))
        else
            Pair(sp.getInt("sniper_delay_min", 0), sp.getInt("sniper_delay_max", 200))

        val node: AccessibilityNodeInfo = rootInActiveWindow ?: return
        for (kw in keywords) {
            val targets = node.findAccessibilityNodeInfosByText(kw)
            if (targets != null && targets.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastFire < 300) return
                lastFire = now

                val delay = Random.nextLong(minD.toLong(), maxD.toLong() + 1)
                val tag = MainActivity.MODE_NAMES[currentMode]
                if (!realClick) {
                    log("[影子·测试]命中「$kw」延时${delay}ms（不点击）")
                    node.recycle()
                    return
                }
                log("[$tag]命中「$kw」，延时${delay}ms后点击")
                node.recycle()
                handler.postDelayed({ clickTarget(kw) }, delay)
                return
            }
        }
        node.recycle()
    }

    private fun clickTarget(keyword: String) {
        val node = rootInActiveWindow ?: return
        val targets = node.findAccessibilityNodeInfosByText(keyword)
        val t = targets?.firstOrNull()
        if (t != null) {
            if (t.isClickable) t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            else t.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            recordHit()
            log("[命中] +1")
        }
        node.recycle()
    }

    private fun recordHit() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        sp.edit()
            .putInt("hit_$day", sp.getInt("hit_$day", 0) + 1)
            .putInt("total_hit", sp.getInt("total_hit", 0) + 1)
            .putLong("last_hit_time", System.currentTimeMillis())
            .apply()
    }

    private fun log(msg: String) {
        android.util.Log.d("LightGrab", msg)
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "轻点前台服务", NotificationManager.IMPORTANCE_LOW)
        )

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("轻点运行中")
            .setContentText("模式=${MainActivity.MODE_NAMES[currentMode]}")
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
