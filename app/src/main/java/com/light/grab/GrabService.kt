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

class GrabService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "lightgrab_fg"
        const val NOTI_ID = 1001

        @Volatile
        var currentMode: Int = MainActivity.MODE_SHADOW

        @Volatile
        var isRunning: Boolean = false
    }

    // 惰性初始化：第一次访问时自动创建，永远不会有"未初始化就使用"的崩溃
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

        // 目标应用过滤：读设置页选择，全部应用时不限制
        val appIndex = sp.getInt("app_index", MainActivity.APPS.size) // 默认全部应用
        if (appIndex < MainActivity.APPS.size) {
            val target = MainActivity.APPS.values.toList()[appIndex]
            if (!pkg.contains(target)) return
        }

        when (currentMode) {
            MainActivity.MODE_SHADOW -> log("[影子]发现窗口 pkg=$pkg")
            MainActivity.MODE_SENTRY -> log("[哨兵]监听中 pkg=$pkg")
            MainActivity.MODE_SNIPER -> if (isRunning) tryGrab(event)
            MainActivity.MODE_SILENT -> log("[静默]事件 pkg=$pkg")
        }
    }

    private fun tryGrab(event: AccessibilityEvent) {
        // 关键词：设置页自定义，没填就用默认列表
        val custom = sp.getString("keyword", "")?.trim() ?: ""
        val keywords = if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")

        // 点击前延时：来自设置页「狙击延时」，不焊死
        val delayBefore = sp.getInt("sniper_delay", 0).toLong()

        val node: AccessibilityNodeInfo = rootInActiveWindow ?: return
        for (kw in keywords) {
            val targets = node.findAccessibilityNodeInfosByText(kw)
            if (targets != null && targets.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastFire < 300) return // 最小间隔保护，防连点
                lastFire = now

                log("[狙击]命中「$kw」，延时${delayBefore}ms后点击")
                if (delayBefore > 0) {
                    node.recycle()
                    handler.postDelayed({ clickTarget(kw) }, delayBefore)
                    return
                }
                clickTarget(kw)
                return
            }
        }
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

    /** 战绩：今日 + 永久累计（SharedPreferences 持久化，重启后数值一直累加不清零） */
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
