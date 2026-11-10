package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
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
        @Volatile var instance: GrabService? = null
    }

    private val sp: SharedPreferences by lazy {
        getSharedPreferences("lightdot", Context.MODE_PRIVATE)
    }

    private var lastFire = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startFg()
        log("[服务] 已连接，模式=${MainActivity.MODE_NAMES[currentMode]}")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            t != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return  // 忽略自己界面，防止死循环

        val targetPkg = sp.getString("app_pkg", null)
        if (targetPkg != null && pkg != targetPkg) return

        when (currentMode) {
            MainActivity.MODE_SHADOW -> tryGrab(realClick = false)  // 测试线路：不真点
            MainActivity.MODE_SENTRY -> if (isRunning) tryGrab(realClick = true)
            MainActivity.MODE_SNIPER -> if (isRunning) tryGrab(realClick = true)
            MainActivity.MODE_SILENT -> { /* 静默：休眠 */ }
        }
    }

    // ============ 通知触发入口（哨兵/狙击的主线路） ============

    /** 通知监听器命中后调用：延时→唤起目标App→重扫点击 */
    fun onNotiFired() {
        if (!isRunning) return
        if (currentMode == MainActivity.MODE_SILENT) {
            log("[静默] 收到通知，仅记录不动作")
            return
        }
        if (currentMode != MainActivity.MODE_SENTRY &&
            currentMode != MainActivity.MODE_SNIPER) return

        // 延时：哨兵/狙击各读各的设置项，灵活可调
        val (minD, maxD) = if (currentMode == MainActivity.MODE_SENTRY)
            Pair(sp.getInt("sentry_delay_min", 300), sp.getInt("sentry_delay_max", 800))
        else
            Pair(sp.getInt("sniper_delay_min", 0), sp.getInt("sniper_delay_max", 200))

        val delay = Random.nextLong(minD.toLong(), maxD.toLong() + 1)
        val tag = MainActivity.MODE_NAMES[currentMode]
        log("[$tag] 通知触发，延时${delay}ms")

        handler.postDelayed({ launchTargetAndGrab(tag) }, delay)
    }

    /** 唤起目标App（已在前台就跳过），然后带重试扫描 */
    private fun launchTargetAndGrab(tag: String) {
        val targetPkg = sp.getString("app_pkg", null)
        if (targetPkg != null) {
            val launch = packageManager.getLaunchIntentForPackage(targetPkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                log("[$tag] 已唤起目标App")
            }
        }
        // 给界面渲染留时间，重扫10次×200ms ≈ 2秒窗口
        scheduleGrab(tag, retriesLeft = 10)
    }

    private fun scheduleGrab(tag: String, retriesLeft: Int) {
        if (!isRunning) return
        if (findAndClick(tag)) return
        if (retriesLeft <= 0) {
            log("[$tag] 2秒内未找到按钮，放弃等下一条")
            return
        }
        handler.postDelayed({ scheduleGrab(tag, retriesLeft - 1) }, 200)
    }

    /** 扫当前窗口找关键词按钮，找到就点，返回是否命中 */
    private fun findAndClick(tag: String): Boolean {
        val custom = sp.getString("keyword", "")?.trim() ?: ""
        val keywords = if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")

        val node = rootInActiveWindow ?: return false
        for (kw in keywords) {
            val found = node.findAccessibilityNodeInfosByText(kw)
            if (!found.isNullOrEmpty()) {
                node.recycle()
                val now = System.currentTimeMillis()
                if (now - lastFire < 300) return true  // 刚点过，防重复
                lastFire = now
                log("[$tag] 命中「$kw」，点击")
                clickTarget(kw)
                return true
            }
        }
        node.recycle()
        return false
    }

    // ============ 原有：窗口事件抓取（影子测试线保留） ============

    private fun tryGrab(realClick: Boolean) {
        val custom = sp.getString("keyword", "")?.trim() ?: ""
        val keywords = if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")

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

    /** 坐标手势点击 */
    private fun clickTarget(keyword: String) {
        val node = rootInActiveWindow ?: return
        val t = node.findAccessibilityNodeInfosByText(keyword)?.firstOrNull()
        if (t == null) {
            node.recycle()
            log("[命中] 点击时目标已消失")
            return
        }

        val r = Rect()
        t.getBoundsInScreen(r)
        node.recycle()

        if (r.isEmpty) {
            log("[命中] 目标区域无效")
            return
        }

        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
        recordHit()
        log("[命中] +1 → (${r.exactCenterX().toInt()}, ${r.exactCenterY().toInt()})")
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
        val time = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        val old = sp.getString("svc_logs", "") ?: ""
        var newLogs = "$old\n$time  $msg"
        if (newLogs.length > 8000) newLogs = newLogs.substring(newLogs.length - 8000)
        sp.edit().putString("svc_logs", newLogs).apply()
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
