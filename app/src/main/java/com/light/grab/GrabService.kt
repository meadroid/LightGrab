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
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/** 日志池：每模式独立，300条滚动，攒批落盘 */
class LogPool(private val sp: SharedPreferences, private val key: String) {
    private val lines = ArrayDeque<String>()
    @Volatile private var dirty = false

    @Synchronized fun add(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date())
        lines.addLast("$t  $msg")
        while (lines.size > 300) lines.removeFirst()
        dirty = true
    }

    @Synchronized fun text(): String = lines.joinToString("\n")

    fun flushIfDirty() {
        if (!dirty) return
        dirty = false
        try { sp.edit().putString(key, text()).apply() } catch (_: Exception) {}
    }
}

class GrabService : AccessibilityService() {

    companion object {
        const val CHANNEL_ID = "lightgrab_fg"
        const val NOTI_ID = 1001
        /** 丝带角标在最右列的最小连续橙行数（真值约50，留一半余量） */
        const val MIN_RUN = 25
        @Volatile var currentMode: Int = MainActivity.MODE_SHADOW
        @Volatile var isRunning: Boolean = false
        @Volatile var instance: GrabService? = null
    }

    private lateinit var sp: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pools: Array<LogPool>
    private val flushTask = Runnable {
        try { pools.forEach { it.flushIfDirty() } } catch (_: Exception) {}
    }

    // 冷却全部用 elapsedRealtime（改系统时间不影响）
    private var lastFire = 0L
    private var lastRing = 0L
    private var lastProbe = 0L
    private var lastValve = 0L
    private var notiRetry = 0

    /** 修复1：布尔 newSeen 换成数字比对——1单变2单也要响 */
    @Volatile private var lastBadgeCount = 0
    @Volatile private var probeBusy = false

    private var wakeLock: PowerManager.WakeLock? = null

    /** 修复4：心跳自驱循环——不依赖无障碍事件，熄屏/页面静止也照扫 */
    private val heartbeat = object : Runnable {
        override fun run() {
            try {
                val active = isRunning &&
                    (currentMode == MainActivity.MODE_SHADOW ||
                     currentMode == MainActivity.MODE_SENTRY)
                holdWake(active)
                if (active) probe()
            } catch (_: Exception) {}
            handler.postDelayed(this, 1000)
        }
    }

    private fun holdWake(hold: Boolean) {
        try {
            if (hold) {
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightGrab:probe")
                    wakeLock?.setReferenceCounted(false)
                }
                if (wakeLock?.isHeld != true) {
                    wakeLock?.acquire(10 * 60 * 1000L) // 10分钟上限，心跳每秒续
                }
            } else {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        } catch (_: Exception) {}
    }

    private fun safeLogIndex(): Int = currentMode.coerceIn(0, 3)

    private fun notiGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun onCreate() {
        super.onCreate()
        try {
            sp = getSharedPreferences("lightdot", Context.MODE_PRIVATE)
            pools = Array(4) { LogPool(sp, "logs_m$it") }
        } catch (e: Exception) {
            android.util.Log.e("LightGrab", "init fail", e)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            currentMode = sp.getInt("mode", MainActivity.MODE_SHADOW).coerceIn(0, 3)
        } catch (_: Exception) {
            currentMode = MainActivity.MODE_SHADOW
        }
        log("[服务] 已连接 · 边缘丝带版，模式=${MainActivity.MODE_NAMES[safeLogIndex()]}")
        mountNotification()
        handler.removeCallbacks(heartbeat)
        handler.post(heartbeat)
    }

    private fun mountNotification() {
        try {
            if (!notiGranted()) {
                log("[通知] 权限未批：去设置→应用→轻点→通知，打开开关")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "轻点前台服务", NotificationManager.IMPORTANCE_LOW)
                )
            }
            startForeground(NOTI_ID, buildNotification())
            notiRetry = 0
            log("[通知] 常驻通知已挂载")
        } catch (e: Exception) {
            if (notiRetry < 3) {
                notiRetry++
                log("[通知] 挂载失败，3秒后重试($notiRetry/3): ${e.message}")
                handler.postDelayed({ mountNotification() }, 3000)
            } else {
                log("[通知] 三次重试均失败(不影响监听): ${e.message}")
            }
        }
    }

    fun onModeChanged() {
        try { mountNotification() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try {
            handler.removeCallbacksAndMessages(null)
            holdWake(false)
            if (::pools.isInitialized) pools.forEach { it.flushIfDirty() }
        } catch (_: Exception) {}
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        log("[服务] 被中断")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isRunning) return
        if (currentMode == MainActivity.MODE_SILENT) return
        try {
            val t = event.eventType
            if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            val target = sp.getString("app_pkg", null)
            if (target != null && pkg != target) return

            popupValve()
            if (currentMode != MainActivity.MODE_SNIPER) {
                probe()
            }
        } catch (e: Exception) {
            android.util.Log.e("LightGrab", "event", e)
        }
    }

    // ===== 匹配核心：去空白 + 完整包含 =====

    private fun norm(s: String): String = s.replace(Regex("\\s+"), "")

    private fun containsWord(nodeText: CharSequence?, word: String): Boolean {
        if (nodeText == null) return false
        val w = norm(word)
        if (w.isEmpty()) return false
        return norm(nodeText.toString()).contains(w)
    }

    private fun keyword(): String =
        sp.getString("keyword", "")?.trim().orEmpty()

    private fun featureWords(): List<String> =
        sp.getString("feature_word", "")?.trim().orEmpty()
            .split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * 门卫流程（哨兵/狙击/影子共享）：
     * ①特征词先行 → 不符闭嘴蹲守；
     * ②相符进门只查关键词 → 未命中纯静默；
     * ③核对特征词与按钮位置；
     * ④1500ms防重 → 随机延时 → 重取最新坐标散点点击。
     */
    private fun popupValve() {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastValve < 300) return
        lastValve = nowMs
        val root = rootInActiveWindow ?: return
        try {
            val words = featureWords()
            var featureRect: Rect? = null
            if (words.isNotEmpty()) {
                featureRect = findFeatureRect(root, words)
                if (featureRect == null) return
            }

            val kw = keyword()
            if (kw.isEmpty()) return
            val btnRect = findButtonRect(root, kw) ?: return

            if (featureRect != null && !featureAboveButton(featureRect, btnRect)) return

            val fire = SystemClock.elapsedRealtime()
            if (fire - lastFire < 1500) return
            lastFire = fire

            val (minD, maxD) = delayRange()
            val delay = Random.nextLong(minD.toLong(), maxD.toLong() + 1)

            if (currentMode == MainActivity.MODE_SHADOW) {
                log("[影子·试镜] 全链通过「$kw」延时${delay}ms（验证完成，不点击）")
                return
            }
            log("[通过] 「$kw」延时${delay}ms后点击")

            handler.postDelayed({
                if (isRunning && currentMode != MainActivity.MODE_SHADOW &&
                    currentMode != MainActivity.MODE_SILENT
                ) regrabAndClick(kw)
            }, delay)
        } finally {
            // 修复5：rootInActiveWindow 禁止 recycle，移除
        }
    }

    /** 点击前重新瞄一眼，按当下最新坐标下手；瞄不到就静默放弃 */
    private fun regrabAndClick(kw: String) {
        val root = rootInActiveWindow ?: run {
            log("[命中] 点击前取窗口失败，放弃")
            return
        }
        try {
            val rect = findButtonRect(root, kw) ?: run {
                log("[命中] 点击前重瞄未找到「$kw」，放弃")
                return
            }
            log("[命中] 重瞄成功 ($rect)，下手")
            clickRect(rect)
        } finally {
        }
    }

    private fun findButtonRect(root: AccessibilityNodeInfo, kw: String): Rect? {
        val candidates = root.findAccessibilityNodeInfosByText(kw) ?: return null
        for (c in candidates) {
            if (!containsWord(c.text, kw)) continue
            if (!c.isVisibleToUser) continue
            val r = Rect()
            c.getBoundsInScreen(r)
            if (r.isEmpty || r.top < 0 ||
                r.bottom > resources.displayMetrics.heightPixels
            ) continue
            return r
        }
        return null
    }

    private fun findFeatureRect(root: AccessibilityNodeInfo, words: List<String>): Rect? {
        for (w in words) {
            val nodes = root.findAccessibilityNodeInfosByText(w) ?: continue
            for (n in nodes) {
                if (!containsWord(n.text, w)) continue
                if (!n.isVisibleToUser) continue
                val r = Rect()
                n.getBoundsInScreen(r)
                if (!r.isEmpty) return r
            }
        }
        return null
    }

    private fun featureAboveButton(fr: Rect, btn: Rect): Boolean {
        val maxUp = (240 * resources.displayMetrics.density).toInt()
        val horizOk = fr.right > btn.left && fr.left < btn.right
        val vertOk = fr.bottom <= btn.top + 50 && btn.top - fr.bottom < maxUp
        return horizOk && vertOk
    }

    // ===== 随机延时 =====

    private fun delayRange(): Pair<Int, Int> {
        val (lo, hi) = if (currentMode == MainActivity.MODE_SNIPER)
            sp.getInt("sniper_delay_min", 0) to sp.getInt("sniper_delay_max", 200)
        else
            sp.getInt("sentry_delay_min", 0) to sp.getInt("sentry_delay_max", 800)
        val min = lo.coerceIn(0, 10000)
        val max = hi.coerceIn(min, 10000)
        return min to max
    }

    // ===== 探针：右边缘丝带扫描（颜色+位置+形状一次完成） =====

    private fun probe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (currentMode == MainActivity.MODE_SNIPER) return
        if (probeBusy) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastProbe < 1000) return
        lastProbe = now
        probeBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(
                            screenshot.hardwareBuffer, screenshot.colorSpace)
                        val count = hw?.let { countBadges(it) } ?: 0
                        hw?.recycle()
                        onProbeResult(count)
                    } catch (e: Exception) {
                        log("[探针] ${e.message}")
                    }
                    screenshot.hardwareBuffer.close()
                    probeBusy = false
                }
                override fun onFailure(errorCode: Int) {
                    probeBusy = false
                    log("[探针] 截图失败 code=$errorCode")
                }
            })
        } catch (e: Exception) {
            probeBusy = false
            log("[探针] 截图调用异常: ${e.message}")
        }
    }

    private fun isOrange(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= 200 && g in 60..170 && b <= 110 && r > g + 40
    }

    /**
     * 只扫最右2列，一趟完成 位置+颜色+形状：
     * 连续橙行 ≥ MIN_RUN 算一个丝带角标，返回角标总数。
     * 价格/按钮/阴影碰不到最右列，天然排除。
     */
    private fun countBadges(src: Bitmap): Int {
        return try {
            // 硬件位图不能直接 getPixel，先拷成软位图
            val soft = src.copy(Bitmap.Config.ARGB_8888, false) ?: return 0
            val w = soft.width
            val h = soft.height
            val x1 = w - 1
            val x2 = w - 2
            var count = 0
            var run = 0
            var maxRun = 0
            for (y in 0 until h) {
                val orange = isOrange(soft.getPixel(x1, y)) || isOrange(soft.getPixel(x2, y))
                if (orange) {
                    run++
                } else {
                    if (run > maxRun) maxRun = run
                    if (run >= MIN_RUN) count++
                    run = 0
                }
            }
            // 收尾：橙段卡在屏幕底边没有被截断的情况
            if (run > maxRun) maxRun = run
            if (run >= MIN_RUN) count++
            soft.recycle()
            log("[探针] 角标=$count 最长橙段=$maxRun 行")
            count
        } catch (e: Exception) {
            log("[探针] 像素判定异常: ${e.message}")
            0
        }
    }

    /** 消抖：连续2帧相同才认账，防单帧闪断吞掉吹哨 */
@Volatile private var pendingCount = -1

private fun onProbeResult(count: Int) {
    if (count != pendingCount) {
        pendingCount = count
        return
    }
    pendingCount = -1
    if (currentMode == MainActivity.MODE_SILENT) return
    if (count > lastBadgeCount) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRing > 10000) {
            lastRing = now
            when (currentMode) {
                MainActivity.MODE_SHADOW ->
                    log("[影子·探针] 新单！角标 $lastBadgeCount → $count，响铃验证")
                MainActivity.MODE_SENTRY ->
                    log("[哨兵·探针] 新单！角标 $lastBadgeCount → $count，吹哨")
            }
            playAlert()
        } else {
            log("[探针] 角标 $lastBadgeCount → $count，冷却中不重复响")
        }
    } else if (count < lastBadgeCount) {
        log("[探针] 角标 $lastBadgeCount → $count，单被抢/滑走，静默更新")
    }
    lastBadgeCount = count
}

    private fun playAlert() {
        try {
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
                        }
                    } catch (e: Exception) {
                        log("[铃声] 第${i + 1}遍失败: ${e.message}")
                    }
                }, i * 2500L)
            }
            handler.postDelayed({
                try { am.setStreamVolume(AudioManager.STREAM_ALARM, old, 0) } catch (_: Exception) {}
            }, 5000)
        } catch (e: Exception) {
            log("[铃声] 异常: ${e.message}")
        }
    }

    /**
     * 修复3：散点点击 + 真实回执。
     * onCompleted 才记命中，onCancelled 明确报错——日志不再说谎。
     */
    private fun clickRect(r: Rect) {
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
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                recordHit()
                log("[命中] 点击完成 → ($x, $y) ✓")
            }
            override fun onCancelled(g: GestureDescription?) {
                log("[命中] 点击被系统取消！($x, $y) ✗ 检查无障碍/悬浮窗遮挡")
            }
        }, null)
    }

    private fun recordHit() {
        val day = SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date())
        sp.edit()
            .putInt("hit_$day", sp.getInt("hit_$day", 0) + 1)
            .putInt("total_hit", sp.getInt("total_hit", 0) + 1)
            .putLong("last_hit_time", System.currentTimeMillis())
            .apply()
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("轻点运行中")
            .setContentText("模式=${MainActivity.MODE_NAMES[safeLogIndex()]}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun log(msg: String) {
        android.util.Log.d("LightGrab", msg)
        try {
            pools[safeLogIndex()].add(msg)
            handler.removeCallbacks(flushTask)
            handler.postDelayed(flushTask, 1000)
        } catch (_: Exception) {}
    }
}
