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
        handler.postDelayed({ updateFgText() }, 500)  // 等前台挂稳再刷新通知文字
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // 只处理窗口切换和内容变化，避免无效全页搜索
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            t != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return  // 忽略自己界面的变化，防止死循环

        // 目标应用过滤（未选择时不过滤）
        val targetPkg = sp.getString("app_pkg", null)
        if (targetPkg != null && pkg != targetPkg) return

        when (currentMode) {
            MainActivity.MODE_SHADOW -> tryGrab(realClick = false)  // 测试线路：不真点
            MainActivity.MODE_SENTRY -> { /* 哨兵由通知驱动，不走窗口事件 */ }
            MainActivity.MODE_SNIPER -> if (isRunning) tryGrab(realClick = true)
            MainActivity.MODE_SILENT -> { /* 静默：休眠 */ }
        }
    }

    // ============ 哨兵：通知来了 → 响铃 → 待命 → 只点二级「确认抢单」 ============

    /** 通知监听器命中后调用：响铃提示 + 进入待命窗口 */
    fun onNotiFired() {
        if (!isRunning) return
        if (currentMode != MainActivity.MODE_SENTRY) return  // 只服务哨兵

        playAlert()
        val watchSec = sp.getInt("sentry_watch_sec", 15)
        log("[哨兵] 通知触发，进入待命${watchSec}秒，等待你点「抢单」")

        handler.postDelayed({ watchConfirm(watchSec * 1000 / 200) }, 200)
    }

    /** 播放用户自选铃声，没选就用系统默认通知音（约0.8秒） */
    private fun playAlert() {
        try {
            val custom = sp.getString("alert_uri", null)
            val uri = if (custom != null) android.net.Uri.parse(custom)
                      else android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            val r = android.media.RingtoneManager.getRingtone(this, uri)
            r.play()
            handler.postDelayed({ r.stop() }, 800)
        } catch (e: Exception) {
            // 取不到铃声就静默，日志照常
        }
    }

    /** 待命循环：每200ms扫一次「确认抢单」，扫满待命时长就收工 */
    private fun watchConfirm(ticksLeft: Int) {
        if (!isRunning || currentMode != MainActivity.MODE_SENTRY) return
        if (ticksLeft <= 0) {
            log("[哨兵] 待命结束，未等到确认弹窗")
            return
        }
        if (tryClickConfirm()) return  // 点到就完事，结束本轮
        handler.postDelayed({ watchConfirm(ticksLeft - 1) }, 200)
    }

    /** 只找「确认抢单」，绝不碰主界面的「抢单」 */
    private fun tryClickConfirm(): Boolean {
        val custom = sp.getString("confirm_keyword", "")?.trim() ?: ""
        val kw = if (custom.isNotEmpty()) custom else "确认抢单"

        val node = rootInActiveWindow ?: return false
        val found = node.findAccessibilityNodeInfosByText(kw)
        if (found.isNullOrEmpty()) {
            node.recycle()
            return false
        }
        node.recycle()

        val now = System.currentTimeMillis()
        if (now - lastFire < 500) return true  // 刚点过防重复
        lastFire = now
        log("[哨兵] 发现「$kw」→ 立即点击")
        clickTarget(kw)
        return true
    }

    // ============ 狙击/影子：窗口事件抓取 ============

    private fun tryGrab(realClick: Boolean) {
        val custom = sp.getString("keyword", "")?.trim() ?: ""
        val keywords = if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")

        val (minD, maxD) =
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

    /** 坐标手势点击：找节点中心，模拟真实手指按下 */
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

    /** 写 logcat + 写 SP，让 MainActivity 能读到 */
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
        updateFgText()   // 首次进入前台
    }

    /** 前台通知文字跟随模式刷新 */
    private fun updateFgText() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("轻点运行中")
            .setContentText("模式=${MainActivity.MODE_NAMES[currentMode]}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        nm.notify(NOTI_ID, n)
    }

    override fun onInterrupt() {
        log("[服务] 被中断")
    }
}
