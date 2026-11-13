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
    private val flushTask = Runnable {
        try { pools.forEach { it.flushIfDirty() } } catch (_: Exception) {}
    }

    private var lastFire = 0L
    private var lastRing = 0L
    private var lastProbe = 0L
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
        log("[服务] 已连接 · 焦点窗口版，模式=${MainActivity.MODE_NAMES[safeLogIndex()]}")
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

    // ===== 匹配核心（A1/B1）：去空白 + 完整包含 =====

    /** 清掉所有空白字符（含全角空格、不可见空白），比对前双方都过一遍 */
    private fun norm(s: String): String = s.replace(Regex("\\s+"), "")

    /** 设置词去空白后，完整出现在节点文字（去空白后）里 → 中；差一个字都不中 */
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
     * 门卫模型（B2）：先搜特征词 → 不符只写一行日志，关键词看都不看；
     * 符合 → 进门搜关键词（未命中纯静默，B4）→ 四道关卡 → 延时点击。
     * 只搜焦点窗口（A3），不扫全窗口不递归。
     */
    private fun popupValve() {
        val root = rootInActiveWindow ?: return
        try {
            // 第一关 · 门卫：特征词先行
            val words = featureWords()
            var featureRect: Rect? = null
            if (words.isNotEmpty()) {
                featureRect = findFeatureRect(root, words)
                if (featureRect == null) {
                    log("[阀门] 特征词不符")   // B3：就这一行，不刷屏
                    return
                }
            }

            // 第二关 · 进门：搜关键词按钮（未命中 → 纯静默，B4）
            val kw = keyword()
            if (kw.isEmpty()) return
            val btnRect = findButtonRect(root, kw) ?: return

            // 第三关 · 特征词位置校验（特征词须在按钮上方 240dp 内、横向有交叠）
            if (featureRect != null && !featureAboveButton(featureRect, btnRect)) {
                log("[阀门] 特征词不符")
                return
            }

            // 第四关 · 1500ms 防重窗口
            val now = System.currentTimeMillis()
            if (now - lastFire < 1500) return
            lastFire = now

            val (minD, maxD) = delayRange()
            val delay = Random.nextLong(minD.toLong(), maxD.toLong() + 1)

            if (currentMode == MainActivity.MODE_SHADOW) {
                log("[影子·试镜] 命中「$kw」延时${delay}ms（不点击）")
                return
            }
            log("[命中校验通过] 「$kw」延时${delay}ms点击")

            // A6：延时结束、下手那一刻，重取焦点窗口 → 重搜 → 拿最新坐标
            handler.postDelayed({
                if (isRunning && currentMode != MainActivity.MODE_SHADOW &&
                    currentMode != MainActivity.MODE_SILENT
                ) regrabAndClick(kw)
            }, delay)
        } finally {
            root.recycle()
        }
    }

    /** A6：点击前重新瞄一眼，按当下最新坐标下手；瞄不到了就静默放弃 */
    private fun regrabAndClick(kw: String) {
        val root = rootInActiveWindow ?: return
        try {
            val rect = findButtonRect(root, kw) ?: return
            clickRect(rect)
        } finally {
            root.recycle()
        }
    }

    /** 焦点窗口内找关键词按钮：去空白+完整包含，可见、坐标在屏幕内 */
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

    /** 门卫搜特征词：任一特征词在焦点窗口可见即放行，返回该节点坐标 */
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

    /** 特征词须在按钮上方 240dp 内、横向有交叠 */
    private fun featureAboveButton(fr: Rect, btn: Rect): Boolean {
        val maxUp = (240 * resources.displayMetrics.density).toInt()
        val horizOk = fr.right > btn.left && fr.left < btn.right
        val vertOk = fr.bottom <= btn.top + 50 && btn.top - fr.bottom < maxUp
        return horizOk && vertOk
    }

    // ===== 以下 C 组：全部锁死，原样保留 =====

    private fun delayRange(): Pair<Int, Int> {
        val (lo, hi) = if (currentMode == MainActivity.MODE_SNIPER)
            sp.getInt("sniper_delay_min", 0) to sp.getInt("sniper_delay_max", 200)
        else
            sp.getInt("sentry_delay_min", 0) to sp.getInt("sentry_delay_max", 800)
        val min = lo.coerceIn(0, 10000)
        val max = hi.coerceIn(min, 10000)
        return min to max
    }

    /** A2：默认短词作废，只认设置里填的词，没填返回空（不搜） */
    private fun grabKeywords(): List<String> {
        val custom = sp.getString("keyword", "")?.trim().orEmpty()
        return if (custom.isNotEmpty()) listOf(custom) else emptyList()
    }

    private fun probe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (probeBusy) return
        val now = System.currentTimeMillis()
        if (now - lastProbe < 1000) return
        val root = rootInActiveWindow ?: return
        val rects = ArrayList<Rect>()
        for (kw in grabKeywords()) collectButtons(root, kw, rects)
        root.recycle()
        if (rects.isEmpty()) { newSeen = false; return }

        lastProbe = now
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
            if (!containsWord(n.text, kw)) continue
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

    /** C5 锁死：三点散点 · 安全边距20%内取点，单点永不出现 */
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
