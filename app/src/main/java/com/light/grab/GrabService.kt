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
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.RingtoneManager
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
    private var watchTask: Runnable? = null
    private var lastSoundTrigger = 0L
    private var targetWasPlaying = false
    private var audioManager: AudioManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startFg()
        registerSoundSentry()
        log("[服务] 已连接，模式=${MainActivity.MODE_NAMES[currentMode]}")
    }

    override fun onDestroy() {
        try {
            audioManager?.unregisterAudioPlaybackCallback(soundCallback)
        } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    // ============ 声音哨兵：目标App出声 = 新单（系统事件驱动，零轮询零权限） ============
    private val soundCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val targetPkg = sp.getString("app_pkg", null) ?: return
            val isTargetPlaying = configs.any { cfg -> isActiveConfigFor(cfg, targetPkg) }
            if (!isTargetPlaying) {
                targetWasPlaying = false
                return
            }
            // 边沿触发：响→停→响才算新一单；持续响不重复
            if (targetWasPlaying) return
            targetWasPlaying = true
            val now = System.currentTimeMillis()
            if (now - lastSoundTrigger < 3000) return
            lastSoundTrigger = now
            android.util.Log.d("LightGrab", "[声音哨兵] 命中 $targetPkg 出声")
            onNotiFired()
        }
    }

    /** 判定该播放配置属于目标App（uid 比对，API 26 兼容） */
    private fun isActiveConfigFor(cfg: AudioPlaybackConfiguration, targetPkg: String): Boolean {
        return uidMatches(cfg, targetPkg)
    }

    private fun uidMatches(cfg: AudioPlaybackConfiguration, targetPkg: String): Boolean {
        return try {
            val targetUid = packageManager.getApplicationInfo(targetPkg, 0).uid
            val cls = AudioPlaybackConfiguration::class.java
            // 先试字段 mUid
            val byField = try {
                val f = cls.getDeclaredField("mUid")
                f.isAccessible = true
                f.getInt(cfg) == targetUid
            } catch (_: Exception) { null }
            if (byField != null) return byField
            // 再兜底方法 getUid()
            try {
                val m = cls.getDeclaredMethod("getUid")
                m.isAccessible = true
                m.invoke(cfg) as Int == targetUid
            } catch (_: Exception) { false }
        } catch (_: Exception) {
            false
        }
    }

    private fun registerSoundSentry() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.registerAudioPlaybackCallback(soundCallback, handler)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val t = event.eventType
        if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            t != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        val targetPkg = sp.getString("app_pkg", null)
        if (targetPkg != null && pkg != targetPkg) return
        when (currentMode) {
            MainActivity.MODE_SHADOW -> tryGrab(realClick = false)
            MainActivity.MODE_SENTRY -> { /* 哨兵由声音驱动 */ }
            MainActivity.MODE_SNIPER -> if (isRunning) tryGrab(realClick = true)
            MainActivity.MODE_SILENT -> { /* 静默：休眠 */ }
        }
    }

    // ============ 前台通知联动 ============
    fun onModeChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
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
        nm.notify(NOTI_ID, n)
    }

    // ============ 哨兵：声音触发 → 响铃 → 待命 → 点真二级弹窗 ============
    fun onNotiFired() {
        if (!isRunning) return
        if (currentMode != MainActivity.MODE_SENTRY) return
        cancelWatch()
        playAlert()
        val watchSec = sp.getInt("sentry_watch_sec", 15)
        log("[哨兵] 声音触发，进入待命${watchSec}秒，等待你点「抢单」")
        val ticks = watchSec * 1000 / 200
        scheduleWatchTick(ticks, 200)
    }

    private fun scheduleWatchTick(ticksLeft: Int, delayMs: Long) {
        val task = Runnable {
            watchTask = null
            watchConfirm(ticksLeft)
        }
        watchTask = task
        handler.postDelayed(task, delayMs)
    }

    private fun cancelWatch() {
        watchTask?.let { handler.removeCallbacks(it) }
        watchTask = null
    }

    private fun watchConfirm(ticksLeft: Int) {
        if (!isRunning || currentMode != MainActivity.MODE_SENTRY) return
        if (ticksLeft <= 0) {
            log("[哨兵] 待命结束，未等到确认弹窗，继续蹲守")
            return
        }
        if (tryClickConfirm()) return
        scheduleWatchTick(ticksLeft - 1, 200)
    }

    /** 响铃：播铃声库选的铃声（没选过回落默认闹钟声），锁死闹钟通道，音量拉满，播两遍 */
    private fun playAlert() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val old = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(
            AudioManager.STREAM_ALARM,
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )
        val uri = sp.getString("alert_uri", null)?.let {
            try { android.net.Uri.parse(it) } catch (_: Exception) { null }
        } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        repeat(2) { i ->
            handler.postDelayed({
                try {
                    val r = RingtoneManager.getRingtone(this, uri)
                    if (r != null) {
                        r.audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                        r.play()
                        handler.postDelayed({
                            try { r.stop() } catch (_: Exception) {}
                        }, 2000)
                    } else {
                        log("[铃声] 获取铃声失败")
                    }
                } catch (e: Exception) {
                    log("[铃声] 第${i + 1}遍失败: ${e.message}")
                }
            }, i * 2500L)
        }
        handler.postDelayed({
            am.setStreamVolume(AudioManager.STREAM_ALARM, old, 0)
        }, 5000)
    }

    /** 只点「可见的真二级弹窗」。四道校验缺一不可 */
    private fun tryClickConfirm(): Boolean {
        val custom = sp.getString("confirm_keyword", "")?.trim() ?: ""
        val kw = if (custom.isNotEmpty()) custom else "确认抢单"
        val root = rootInActiveWindow ?: return false
        val candidates = root.findAccessibilityNodeInfosByText(kw)
        var target: AccessibilityNodeInfo? = null
        if (candidates != null) {
            for (c in candidates) {
                val txt = c.text?.toString() ?: continue
                if (txt != kw) continue
                if (!c.isVisibleToUser) continue
                val r = Rect()
                c.getBoundsInScreen(r)
                if (r.isEmpty || r.top < 0 ||
                    r.bottom > resources.displayMetrics.heightPixels) continue
                target = c
                break
            }
        }
        if (target == null) {
            root.recycle()
            return false
        }
        val btnRect = Rect()
        target.getBoundsInScreen(btnRect)
        target.recycle()
        val feature = sp.getString("feature_word", "")?.trim() ?: ""
        if (feature.isNotEmpty()) {
            val words = feature.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
            var matched = false
            for (w in words) {
                val fNodes = root.findAccessibilityNodeInfosByText(w) ?: continue
                for (f in fNodes) {
                    if (!f.isVisibleToUser) continue
                    val fr = Rect()
                    f.getBoundsInScreen(fr)
                    f.recycle()
                    val maxUp = (240 * resources.displayMetrics.density).toInt()
                    val horizOk = fr.right > btnRect.left && fr.left < btnRect.right
                    val vertOk = fr.bottom <= btnRect.top + 50 &&
                            btnRect.top - fr.bottom < maxUp
                    if (horizOk && vertOk) { matched = true; break }
                }
                if (matched) break
            }
            if (!matched) {
                root.recycle()
                log("[哨兵] 🚫 特征词未匹配 → 不点")
                return false
            }
            log("[哨兵] ✅ 特征词匹配，放行")
        }
        root.recycle()
        val now = System.currentTimeMillis()
        if (now - lastFire < 500) return true
        lastFire = now
        log("[哨兵] 弹窗校验通过「$kw」→ 立即点击")
        clickNodeRect(btnRect)
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

    private fun clickTarget(keyword: String) {
        val node = rootInActiveWindow ?: return
        val t = node.findAccessibilityNodeInfosByText(keyword)?.firstOrNull {
            it.isVisibleToUser
        }
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
        clickNodeRect(r)
    }

    private fun clickNodeRect(r: Rect) {
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
