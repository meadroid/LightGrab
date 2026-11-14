package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "grab_channel"
        const val NOTI_ID = 1001
        @Volatile var instance: GrabService? = null
        @Volatile var isRunning = false
        @Volatile var currentMode = 0
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastFireAt = 0L
    private var lastBadgeCount = -1
    private var alertPlayer: MediaPlayer? = null
    private val prefs by lazy { getSharedPreferences("lightdot", MODE_PRIVATE) }
    private val logList = mutableListOf<String>()
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA)

    fun onModeChanged() { refreshNotification() }

    private fun modeName(): String =
        listOf("shadow", "sentry", "sniper", "silent")
            .getOrElse(currentMode.coerceIn(0, 3)) { "shadow" }

    // ---------- 生命周期（纯事件驱动：无心跳、无定时器、无WakeLock） ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startForeground()
        addLog("上岗。纯事件驱动体制")
    }

    override fun onInterrupt() { addLog("服务中断") }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        stopAlert()
        super.onDestroy()
    }

    // ---------- 常驻通知 ----------

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "抢单哨兵", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val noti = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("轻点·哨兵在岗")
            .setContentText(notiText())
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setOngoing(true)
        startForeground(NOTI_ID, noti.build())
    }

    private fun notiText(): String {
        val mode = modeName()
        val lr = prefs.getString("lock_rect", null)
        val lock = if (!lr.isNullOrBlank() && lr.split(",").size == 4) "锁区✅" else "锁区未配(回退双词)"
        return "模式:$mode $lock"
    }

    fun refreshNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(NOTI_ID, Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("轻点·哨兵在岗")
            .setContentText(notiText())
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setOngoing(true)
            .build())
    }

    // ---------- 事件入口 ----------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // ===== 探针线（响铃）：保留包名闸，只认目标App =====
        val targetPkg = prefs.getString("app_pkg", null)
        if (!targetPkg.isNullOrBlank() && event.packageName == targetPkg &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            // 静默：一切停止；狙击：静音不出声
            if (isRunning && modeName() != "silent" && modeName() != "sniper") {
                probeBadge(event.packageName.toString())
            }
        }

        // ===== 主干线（抓单）：不问包名，任何App都进来认脸 =====
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val root = rootInActiveWindow ?: return
        try {
            handleMainLine(root)
        } catch (t: Throwable) {
            addLog("主干异常: ${t.message}")
        }
    }

    // ---------- 探针线：角标增单 → 响铃一遍 ----------

    private fun probeBadge(pkg: String) {
        val root = rootInActiveWindow ?: return
        val cnt = countBadgeCandidates(root, pkg) ?: return
        if (lastBadgeCount == -1) {
            lastBadgeCount = cnt          // 首帧只校准，不响铃
            addLog("角标校准=$cnt")
            return
        }
        if (cnt > lastBadgeCount) {
            addLog("角标增单 $lastBadgeCount→$cnt，响铃")
            playAlert()
        }
        lastBadgeCount = cnt
    }

    // 角标计数：在目标App界面上找"接/新/单"类短文本节点数（可按实际页面词调整）
    private fun countBadgeCandidates(root: AccessibilityNodeInfo, pkg: String): Int? {
        return try {
            val kw = prefs.getString("probe_word", "接") ?: "接"
            root.findAccessibilityNodeInfosByText(kw).count { it.text?.length ?: 99 <= 3 }
        } catch (t: Throwable) { null }
    }

    private fun playAlert() {
        // 狙击静音不出声；静默一切停止
        if (modeName() == "silent" || modeName() == "sniper") return
        try {
            val uriStr = prefs.getString("alert_uri", null)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM).toString()
            stopAlert()
            alertPlayer = MediaPlayer().apply {
                setDataSource(this@GrabService, Uri.parse(uriStr))
                setAudioStreamType(AudioManager.STREAM_ALARM)
                isLooping = false
                prepare()
                start()
            }
        } catch (t: Throwable) {
            addLog("响铃失败: ${t.message}")
        }
    }

    private fun stopAlert() {
        alertPlayer?.let { try { if (it.isPlaying) it.stop(); it.release() } catch (_: Throwable) {} }
        alertPlayer = null
    }

    fun stopAlertNow() { stopAlert() }

    // ---------- 主干线：认脸 → 锁区/回退双词 → 延时 → 下手 ----------

    private fun handleMainLine(root: AccessibilityNodeInfo) {
        if (!isRunning) return
        val mode = modeName()
        if (mode == "silent") return   // 静默：一切停止

        val featureWord = prefs.getString("feature_word", null) ?: return
        val now = System.currentTimeMillis()
        if (now - lastFireAt < 1500) return

        val featureNodes = root.findAccessibilityNodeInfosByText(featureWord)
        if (featureNodes.isEmpty()) return

        val calibMode = prefs.getBoolean("calib_mode", false)
        if (calibMode) {
            // 标定模式：只打日志量坐标，绝不点击
            val lr = parseRect(prefs.getString("lock_rect", null))
            for (fn in featureNodes) {
                val fr = Rect(); fn.getBoundsInScreen(fr)
                addLog("[标定] 特征矩形(px): ${fr.left},${fr.top},${fr.width()},${fr.height()}")
                if (lr != null) {
                    val dens = resources.displayMetrics.density
                    val dl = (fr.left / dens).toInt(); val dt = (fr.top / dens).toInt()
                    addLog("[标定] 参考答案(dp): ${dl + lr[0]},${dt + lr[1]},${lr[2]},${lr[3]}")
                }
            }
            return
        }

        val btn = locateButton(root, featureNodes) ?: return
        val br = Rect(); btn.getBoundsInScreen(br)
        if (br.width() <= 0 || br.height() <= 0) return

        // 延时：读设置区间，随机取值
        val min = prefs.getInt(if (mode == "sniper") "sniper_delay_min" else "sentry_delay_min", 200)
        val max = prefs.getInt(if (mode == "sniper") "sniper_delay_max" else "sentry_delay_max", 800)
        val delay = Random.nextLong(min.toLong(), (max + 1).toLong())

        // 散点：按钮矩形内四分之一范围随机偏移
        val ox = Random.nextInt(-br.width() / 4, br.width() / 4 + 1)
        val oy = Random.nextInt(-br.height() / 4, br.height() / 4 + 1)
        val sx = br.centerX() + ox
        val sy = br.centerY() + oy

        lastFireAt = now
        addLog("✅认脸 按钮=${br.left},${br.top} · 延时=${delay}ms(${min}~${max}) · 散点($sx,$sy)")
        incToday()

        // ===== 影子模式：检阅官，链路全通，最后点击不落 =====
        if (mode == "shadow") {
            addLog("👣检阅 目标($sx,$sy) 延时${delay}ms · 链路全通 · 不落点击")
            playAlert()
            return
        }

        handler.postDelayed({ fire(sx.toFloat(), sy.toFloat(), delay) }, delay)
    }

    private fun parseRect(s: String?): IntArray? {
        if (s.isNullOrBlank()) return null
        val p = s.split(",").mapNotNull { it.trim().toIntOrNull() }
        return if (p.size == 4) p.toIntArray() else null
    }

    // 按钮定位：锁区优先，未配置回退双词
    private fun locateButton(
        root: AccessibilityNodeInfo,
        featureNodes: List<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
        val lr = parseRect(prefs.getString("lock_rect", null))
        if (lr != null) {
            // ===== 锁区路线：特征矩形 + 四数偏移 =====
            val fn = featureNodes.firstOrNull() ?: return null
            val fr = Rect(); fn.getBoundsInScreen(fr)
            val dens = resources.displayMetrics.density
            val left = (fr.left / dens).toInt() + lr[0]
            val top = (fr.top / dens).toInt() + lr[1]
            val target = Rect(
                (left * dens).toInt(), (top * dens).toInt(),
                ((left + lr[2]) * dens).toInt(), ((top + lr[3]) * dens).toInt()
            )
            // 在目标矩形中心找可点节点
            return findClickableAt(root, target)
        }
        // ===== 回退：双词路线 =====
        val keyword = prefs.getString("keyword", null) ?: return null
        val kwNodes = root.findAccessibilityNodeInfosByText(keyword)
        for (n in kwNodes) {
            if (!n.isClickable) continue
            val r = Rect(); n.getBoundsInScreen(r)
            // 位置校验：关键词必须在某个特征词下方240dp内
            for (fn in featureNodes) {
                val fr = Rect(); fn.getBoundsInScreen(fr)
                val dens = resources.displayMetrics.density
                if (r.top >= fr.top && r.top - fr.bottom <= 240 * dens &&
                    abs(r.centerX() - fr.centerX()) < 200 * dens
                ) return n
            }
        }
        return null
    }

    // 在矩形区域内找可点击节点（遍历树，取中心落在区域内且clickable的节点）
    private fun findClickableAt(root: AccessibilityNodeInfo, target: Rect): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            val r = Rect(); n.getBoundsInScreen(r)
            if (r.contains(target.centerX(), target.centerY())) {
                if (n.isClickable) best = n
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it); it.recycle() }
            }
        }
        walk(root)
        return best
    }

    // ---------- 下手：散点点射（坐标、延时由主干线定好传入） ----------

    private fun fire(x: Float, y: Float, delay: Long) {
        val root = rootInActiveWindow ?: run { addLog("下手时无窗口"); return }
        // 重瞄：特征词还在才下手
        val featureWord = prefs.getString("feature_word", null) ?: return
        val featureNodes = root.findAccessibilityNodeInfosByText(featureWord)
        if (featureNodes.isEmpty()) { addLog("重瞄扑空，放弃"); return }

        val btn = locateButton(root, featureNodes) ?: run { addLog("重瞄按钮扑空"); return }
        val r = Rect(); btn.getBoundsInScreen(r)
        if (r.width() <= 0) { addLog("重瞄矩形异常"); return }

        addLog("🔫散点点射 落点($x,$y) 延时=${delay}ms")
        val path = android.graphics.Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(),
            object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    addLog("🎯点击回执 ($x,$y) · 全程延时=${delay}ms")
                }
                override fun onCancelled(g: GestureDescription?) {
                    addLog("点击被取消")
                }
            }, null)
    }

    // ---------- 日志与计数 ----------

    private fun incToday() {
        val key = "hits_" + sdf.format(Date()).substring(0, 5)
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1)
            .putInt("hits_total", prefs.getInt("hits_total", 0) + 1).apply()
    }

    private fun addLog(msg: String) {
        val line = "${sdf.format(Date())} $msg"
        synchronized(logList) {
            logList.add(line)
            if (logList.size > 200) logList.removeAt(0)
        }
        // 落库到 SharedPreferences，MainActivity 才能看到
        val key = "logs_m${currentMode.coerceIn(0, 3)}"
        val buf = prefs.getString(key, "") ?: ""
        prefs.edit().putString(key, "$buf\n$line".takeLast(4000)).apply()
    }

    fun getLogs(): String = synchronized(logList) { logList.joinToString("\n") }
}
