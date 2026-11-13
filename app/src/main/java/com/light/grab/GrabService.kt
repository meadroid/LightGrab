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
        @Volatile var currentMode: Int = MainActivity.MODE_SHADOW
        @Volatile var isRunning: Boolean = false
        @Volatile var instance: GrabService? = null
    }

    private lateinit var sp: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pools: Array<LogPool>
    private val flushTask = Runnable { try { pools.forEach { it.flushIfDirty() } } catch (_: Exception) {} }

    private var lastFire = 0L
    private var lastRing = 0L
    private var notiRetry = 0
    @Volatile private var newSeen = false
    @Volatile private var probeBusy = false

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
        log("[服务] 已连接 · 精简版，模式=${MainActivity.MODE_NAMES[safeLogIndex()]}")
        mountNotification()
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
            probe()
        } catch (e: Exception) {
            android.util.Log.e("LightGrab", "event", e)
        }
    }

    private fun popupValve() {
        val kw = sp.getString("keyword", "")?.trim().orEmpty().ifEmpty { "确认抢单" }
        val root = rootInActiveWindow ?: return
        val btnRect = Rect()
        val found = findExactButton(root, kw, btnRect)
        if (!found) { root.recycle(); return }
        val pass = checkFeatureWord(root, btnRect)
        root.recycle()
        if (!pass) {
            log("[阀门] 特征词不符，不点「$kw」")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastFire < 1500) return
        lastFire = now

        val (minD, maxD) = delayRange()
        val delay = Random.nextLong(minD.toLong(), maxD.toLong() + 1)

        if (currentMode == MainActivity.MODE_SHADOW) {
            log("[影子·试镜] 弹窗命中「$kw」延时${delay}ms（不点击）")
            return
        }
        log("[弹窗] 校验通过「$kw」，延时${delay}ms点击")
        handler.postDelayed({
            if (isRunning && currentMode != MainActivity.MODE_SHADOW &&
                currentMode != MainActivity.MODE_SILENT
            ) clickRect(btnRect)
        }, delay)
    }

    private fun findExactButton(root: AccessibilityNodeInfo, kw: String, out: Rect): Boolean {
        val candidates = root.findAccessibilityNodeInfosByText(kw) ?: return false
        for (c in candidates) {
            if (c.text?.toString() != kw) continue
            if (!c.isVisibleToUser) continue
            val r = Rect()
            c.getBoundsInScreen(r)
            if (r.isEmpty || r.top < 0 ||
                r.bottom > resources.displayMetrics.heightPixels
            ) continue
            out.set(r)
            c.recycle()
            return true
        }
        return false
    }

    private fun checkFeatureWord(root: AccessibilityNodeInfo, btnRect: Rect): Boolean {
        val feature = sp.getString("feature_word", "")?.trim().orEmpty()
        if (feature.isEmpty()) return true
        val words = feature.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
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
                if (horizOk && vertOk) return true
            }
        }
        return false
    }

    private fun delayRange(): Pair<Int, Int> {
        val (lo, hi) = if (currentMode == MainActivity.MODE_SNIPER)
            sp.getInt("sniper_delay_min", 0) to sp.getInt("sniper_delay_max", 200)
        else
            sp.getInt("sentry_delay_min", 0) to sp.getInt("sentry_delay_max", 800)
        val min = lo.coerceIn(0, 10000)
        val max = hi.coerceIn(min, 10000)
        return min to max
    }

    private fun grabKeywords(): List<String> {
        val custom = sp.getString("keyword", "")?.trim().orEmpty()
        return if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")
    }

    private fun probe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (probeBusy) return
        val root = rootInActiveWindow ?: return
        val rects = ArrayList<Rect>()
        for (kw in grabKeywords()) collectButtons(root, kw, rects)
        root.recycle()
        if (rects.isEmpty()) { newSeen = false; return }

        probeBusy = true
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hw = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        val has = hw?.let { countBadge(it, rects) } ?: false
                        hw?.recycle()
                        onProbeResult(has, rects.size)
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

    private fun collectButtons(root: AccessibilityNodeInfo, kw: String, out: ArrayList<Rect>) {
        val nodes = root.findAccessibilityNodeInfosByText(kw) ?: return
        for (n in nodes) {
            if (n.text?.toString() != kw) continue
            if (!n.isVisibleToUser) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (!r.isEmpty) out.add(r)
        }
    }

    private fun countBadge(src: Bitmap, exclude: List<Rect>): Boolean {
        return try {
            val soft = src.copy(Bitmap.Config.ARGB_8888, false) ?: return false
            val sw = (soft.width / 3).coerceAtLeast(1)
            val sh = (soft.height / 3).coerceAtLeast(1)
            val bmp = Bitmap.createScaledBitmap(soft, sw, sh, false)
            soft.recycle()
            val exs = exclude.map {
                Rect(it.left / 3 - 6, it.top / 3 - 6, it.right / 3 + 6, it.bottom / 3 + 6)
            }
            var hit = 0
            var x = 0
            while (x < sw) {
                var y = 0
                while (y < sh) {
                    val c = bmp.getPixel(x, y)
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    if (r >= 180 && g in 70..190 && b <= 120 && r > g + 40) {
                        var inBtn = false
                        for (rc in exs) {
                            if (x >= rc.left && x <= rc.right && y >= rc.top && y <= rc.bottom) {
                                inBtn = true; break
                            }
                        }
                        if (!inBtn) {
                            hit++
                            if (hit >= 40) { bmp.recycle(); return true }
                        }
                    }
                    y += 2
                }
                x += 2
            }
            bmp.recycle()
            false
        } catch (e: Exception) {
            log("[探针] 像素判定异常: ${e.message}")
            false
        }
    }

    private fun onProbeResult(hasNew: Boolean, cards: Int) {
        if (currentMode == MainActivity.MODE_SILENT) return
        if (hasNew) {
            val now = System.currentTimeMillis()
            if (!newSeen) {
                newSeen = true
                if (currentMode == MainActivity.MODE_SHADOW) {
                    log("[影子·探针] 发现新单角标（$cards 卡）")
                } else if (now - lastRing > 15000) {
                    lastRing = now
                    log("[探针] 发现新单（$cards 卡），响铃提醒")
                    playAlert()
                }
            }
        } else {
            newSeen = false
        }
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
