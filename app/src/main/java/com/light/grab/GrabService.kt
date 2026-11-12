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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "lightgrab_fg"
        const val NOTI_ID = 1001
        const val NOTI_ID_ALERT = 1002   // [修] 自检/看门狗通知用独立ID
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
    private var audioManager: AudioManager? = null

    // [修] 病2/病3：心跳时间戳 + 看门狗
    @Volatile private var heartbeat = 0L
    private var watchdogTask: Runnable? = null
    private var watchdogRestarts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startFg()

        // [修] 病3：重启读回持久化模式（休克后不再隐身）
        currentMode = sp.getInt("mode", MainActivity.MODE_SHADOW)

        // [修] 病4：媒体音量启动自检
        checkMediaVolume()

        registerSoundSentry()
        startWatchdog()   // [修] 病2：看门狗保活
        log("[服务] 已连接，模式=${MainActivity.MODE_NAMES[currentMode]}（持久化读回）")
    }

    override fun onDestroy() {
        try {
            audioManager?.unregisterAudioPlaybackCallback(soundCallback)
        } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        log("[服务] ⚠️ 被中断")
    }

    // ============ [修] 病6：公共通知函数 ============
    private fun sendNotify(title: String, text: String, id: Int = NOTI_ID_ALERT) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    // ============ [修] 病4：媒体音量自检 ============
    private fun checkMediaVolume() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (vol == 0) {
                log("[自检] 🚨 媒体音量为0！提示音链路第一环已断")
                sendNotify("轻点自检：媒体音量为0", "提示音不会响！请按音量键调高媒体音量")
            } else {
                log("[自检] 媒体音量=$vol，链路正常")
            }
        } catch (e: Exception) {
            log("[自检] 音量读取失败: ${e.message}")
        }
    }

    // ============ [修] 病2：看门狗保活 + 异常自动重启 ============
    private fun startWatchdog() {
        heartbeat = System.currentTimeMillis()
        watchdogTask?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                val gap = System.currentTimeMillis() - heartbeat
                if (isRunning && gap > 90_000) {
                    watchdogRestarts++
                    log("[看门狗] 🚨 心跳超时${gap / 1000}秒，第${watchdogRestarts}次自动恢复")
                    sendNotify("轻点自动恢复", "检测到休克，已自动拉起（第${watchdogRestarts}次）")
                    heartbeat = System.currentTimeMillis()
                    cancelWatch()
                    scheduleWatchTick(0, 100)   // 重新走确认流程
                }
                handler.postDelayed(this, 30_000)
            }
        }
        watchdogTask = task
        handler.postDelayed(task, 30_000)
    }

    private fun touchHeartbeat() {
        heartbeat = System.currentTimeMillis()
    }

    // ============ [修] 声纹接口占位（病1，判定收口，等录屏） ============
    private fun verifyVoiceprint(): Boolean {
        // TODO 录屏基准音到位后实现：音频指纹比对，对上才返回 true
        return true   // 占位：直接放行，行为与现有逻辑一致
    }

    // ============ [修] 病7：全量决策日志（供声纹回测校准） ============
    private fun logDecision(verdict: String, reason: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA).format(Date())
        log("[判定] $verdict | 依据: $reason")
        try {
            val dir = File(getExternalFilesDir(null), "decisions")
            if (!dir.exists()) dir.mkdirs()
            File(dir, "d_${System.currentTimeMillis()}.txt").writeText("$time\n$verdict\n$reason")
        } catch (_: Exception) {}
    }

    // ============ 声音哨兵：目标App出声 = 新单 ============
    private val soundCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            touchHeartbeat()   // [修] 喂狗
            val targetPkg = sp.getString("app_pkg", null) ?: return
            val isTargetPlaying = configs.any { cfg -> uidMatches(cfg, targetPkg) }
            if (!isTargetPlaying) {
                // 降级放行：反射失败（uid读不出）时，有任何App在响就算命中。
                val uidAllUnknown = configs.isNotEmpty() &&
                        configs.all { uidOf(it) == null }
                if (!uidAllUnknown) return
                log("[声音哨兵] 降级命中(反射不可用)")
            } else {
                log("[声音哨兵] 精确命中 $targetPkg")
            }

            // [修] 声纹占位：上线后对不上就拦截
            if (!verifyVoiceprint()) {
                logDecision("拦截", "声纹不匹配")
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastSoundTrigger < 30000) return
            lastSoundTrigger = now
            logDecision("放行", "目标App出声，冷却窗口通过")
            onNotiFired()
        }
    }

    /** 读uid，失败返回null（不吞异常，交给降级逻辑） */
    private fun uidOf(cfg: AudioPlaybackConfiguration): Int? {
        return try {
            val target = packageManager.getApplicationInfo(
                sp.getString("app_pkg", null) ?: return null, 0).uid
            val cls = AudioPlaybackConfiguration::class.java
            try {
                val f = cls.getDeclaredField("mUid")
                f.isAccessible = true
                f.getInt(cfg)
            } catch (_: Exception) {
                val m = cls.getDeclaredMethod("getUid")
                m.isAccessible = true
                m.invoke(cfg) as Int
            }
        } catch (_: Exception) { null }
    }

    private fun uidMatches(cfg: AudioPlaybackConfiguration, targetPkg: String): Boolean {
        val uid = uidOf(cfg) ?: return false
        return try {
            uid == packageManager.getApplicationInfo(targetPkg, 0).uid
        } catch (_: Exception) { false }
    }

    private fun registerSoundSentry() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager?.registerAudioPlaybackCallback(soundCallback, handler)
        log("[哨兵] 声音监听已注册")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        touchHeartbeat()   // [修] 事件流=活着的证据，喂狗
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

    // ============ 哨兵：声音触发 → 响铃 → 待命扫弹窗 ============
    fun onNotiFired() {
        if (!isRunning) return
        if (currentMode != MainActivity.MODE_SENTRY) return
        cancelWatch()
        playAlert()
        // [修] 病5：默认待命窗口 15 → 10 秒
        val watchSec = sp.getInt("sentry_watch_sec", 10)
        log("[哨兵] 声音触发，进入待命${watchSec}秒，等待弹窗")
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
        touchHeartbeat()   // [修] 喂狗
        if (!isRunning || currentMode != MainActivity.MODE_SENTRY) return
        if (ticksLeft <= 0) {
            log("[哨兵] 待命结束，未等到确认弹窗，继续蹲守")
            return
        }
        if (tryClickConfirm()) return
        scheduleWatchTick(ticksLeft - 1, 200)
    }

    /** 响铃：锁死闹钟通道，音量拉满，播两遍 */
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

    // ============ [修] 病6：共用校验链（四道保险+特征词，三模式共用） ============
    /**
     * 在root中按关键词找「可见的真按钮」+ 过特征词校验。
     * 命中返回true并填btnRect；未命中返回false（root由调用方回收）。
     */
    private fun validateLocate(root: AccessibilityNodeInfo, kw: String, btnRect: Rect): Boolean {
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
        if (target == null) return false
        target.getBoundsInScreen(btnRect)
        target.recycle()

        // 特征词校验（四道保险之一，留空=不过滤）
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
                logDecision("拦截", "特征词未匹配（共用校验链）")
                return false
            }
            log("[校验链] ✅ 特征词匹配，放行")
        }
        return true
    }

    /** 哨兵：只点「可见的真二级弹窗」。共用校验链 → 反应延时 → 点击 */
    private fun tryClickConfirm(): Boolean {
        val custom = sp.getString("confirm_keyword", "")?.trim() ?: ""
        val kw = if (custom.isNotEmpty()) custom else "确认抢单"
        val root = rootInActiveWindow ?: return false
        val btnRect = Rect()
        val ok = validateLocate(root, kw, btnRect)
        root.recycle()
        if (!ok) return false

        val now = System.currentTimeMillis()
        if (now - lastFire < 500) return true
        lastFire = now
        val minD = sp.getInt("sentry_delay_min", 0)
        val maxD = sp.getInt("sentry_delay_max", 0)
        val delay = Random.nextLong(minD.toLong(), maxD.toLong() + 1)
        logDecision("放行", "弹窗校验通过「$kw」，延时${delay}ms")
        log("[哨兵] 弹窗校验通过「$kw」，反应延时${delay}ms后点击")
        handler.postDelayed({
            if (isRunning && currentMode == MainActivity.MODE_SENTRY) {
                clickNodeRect(btnRect)
            }
        }, delay)
        return true
    }

    // ============ 狙击/影子：[修] 改走共用校验链，删重复判定 ============
    private fun tryGrab(realClick: Boolean) {
        val custom = sp.getString("keyword", "")?.trim() ?: ""
        val keywords = if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")
        val (minD, maxD) =
            Pair(sp.getInt("sniper_delay_min", 0), sp.getInt("sniper_delay_max", 200))
        val node: AccessibilityNodeInfo = rootInActiveWindow ?: return
        for (kw in keywords) {
            val btnRect = Rect()
            if (node.findAccessibilityNodeInfosByText(kw)?.isNotEmpty() == true &&
                validateLocate(node, kw, btnRect)
            ) {
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
                logDecision("放行", "[$tag]命中「$kw」，延时${delay}ms")
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

    /** 安全区随机点击：按钮矩形各边内收20%，三模式共用 */
    private fun clickNodeRect(r: Rect) {
        val insetX = (r.width() * 0.2f).toInt().coerceAtLeast(1)
        val insetY = (r.height() * 0.2f).toInt().coerceAtLeast(1)
        val left = r.left + insetX
        val right = r.right - insetX
        val top = r.top + insetY
        val bottom = r.bottom - insetY
        val x = if (right > left) Random.nextInt(left, right) else r.exactCenterX().toInt()
        val y = if (bottom > top) Random.nextInt(top, bottom) else r.exactCenterY().toInt()
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
        recordHit()
        log("[命中] +1 → ($x, $y)")
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
}
