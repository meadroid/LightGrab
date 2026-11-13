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
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioManager
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

    private val sp: SharedPreferences by lazy {
        getSharedPreferences("lightdot", Context.MODE_PRIVATE)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val pools = Array(4) { LogPool(sp, "logs_m$it") }
    private val flushTask = Runnable { pools.forEach { it.flushIfDirty() } }

    private var lastFire = 0L        // 弹窗确认冷却
    private var lastRing = 0L        // 响铃冷却
    @Volatile private var newSeen = false   // 角标去重：同一批新单只叫一次
    @Volatile private var probeBusy = false // 探针串行闸门

    // ============ 生命周期 ============
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            startFg()
            currentMode = sp.getInt("mode", MainActivity.MODE_SHADOW)
            log("[服务] 已连接 · 精简版，模式=${MainActivity.MODE_NAMES[currentMode]}")
        } catch (e: Exception) {
            log("[服务] 启动异常: ${e.message}")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        pools.forEach { it.flushIfDirty() }
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        log("[服务] 被中断")
    }

    // ============ 唯一入口：系统喂事件 ============
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!isRunning) return
        if (currentMode == MainActivity.MODE_SILENT) return  // 静默：微秒级return
        try {
            val t = event.eventType
            if (t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                t != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            val target = sp.getString("app_pkg", null)
            if (target != null && pkg != target) return

            popupValve()   // 弹窗阀门：全软件唯一自动点击点
            probe()        // 像素探针：发现"新"角标 → 响铃
        } catch (e: Exception) {
            log("[事件] ${e.message}")
        }
    }

    // ============ 弹窗阀门（两关：关键词按钮 → 特征词） ============
    private fun popupValve() {
        val kw = sp.getString("keyword", "")?.trim().orEmpty()
            .ifEmpty { "确认抢单" }        // 关键词：弹窗按钮上的字，留空走默认
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
        if (now - lastFire < 1500) return   // 冷却：不连点
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

    /** 精确文本匹配 + 可见 + 屏幕内，输出按钮区域 */
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

    /** 特征词阀门：按钮上方必须出现特征词之一，留空=不过滤 */
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

    private fun delayRange(): Pair<Int, Int> =
        if (currentMode == MainActivity.MODE_SNIPER)
            sp.getInt("sniper_delay_min", 0) to sp.getInt("sniper_delay_max", 200)
        else
            sp.getInt("sentry_delay_min", 0) to sp.getInt("sentry_delay_max", 800)

    // ============ 像素探针：找"新"角标 ============
    private fun grabKeywords(): List<String> {
        val custom = sp.getString("keyword", "")?.trim().orEmpty()
        return if (custom.isNotEmpty()) listOf(custom)
        else listOf("抢单", "接单", "立即抢")
    }

    private fun probe() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (probeBusy) return
        // 节点树闸门：屏幕上没有抢单按钮，一步都不走（连截图都省）
        val root = rootInActiveWindow ?: return
        val rects = ArrayList<Rect>()
        for (kw in grabKeywords()) collectButtons(root, kw, rects)
        root.recycle()
        if (rects.isEmpty()) { newSeen = false; return }

        probeBusy = true
        takeScreenshot(mainExecutor, object : TakeScreenshotCallback {
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

    /** 数橙红像素（排除抢单按钮区域），命中≥40个 = 有"新"角标 */
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
            newSeen = false   // 角标消失，下次出现再叫
        }
    }

    // ============ 响铃 ============
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

    // ============ 点击 ============
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

    // ============ 前台通知（一处定义，两处调用） ============
    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("轻点运行中")
            .setContentText("模式=${MainActivity.MODE_NAMES[currentMode]}")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startFg() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "轻点前台服务", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTI_ID, buildNotification())
    }

    fun onModeChanged() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildNotification())
        } catch (_: Exception) {}
    }

    // ============ 日志：路由到当前模式池 + 攒批落盘 ============
    private fun log(msg: String) {
        android.util.Log.d("LightGrab", msg)
        pools[currentMode].add(msg)
        handler.removeCallbacks(flushTask)
        handler.postDelayed(flushTask, 1000)
    }
}
