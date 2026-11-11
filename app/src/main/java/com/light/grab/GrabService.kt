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

    // ★ 哨兵待命任务句柄：保证全服务同一时刻最多一条待命链
    private var watchTask: Runnable? = null

    // ★ 猎鹰：扫描节流 + 冷静期
    private var lastFalconScan = 0L
    private var falconQuietUntil = 0L

    // ★ 探针日志限频
    private var lastProbeLog = 0L

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
        if (pkg == packageName) return
        val targetPkg = sp.getString("app_pkg", null)
        if (targetPkg != null && pkg != targetPkg) return
        when (currentMode) {
            MainActivity.MODE_SHADOW -> tryGrab(realClick = false)
            MainActivity.MODE_SENTRY -> { /* 哨兵只由通知驱动，窗口事件不参与 */ }
            MainActivity.MODE_SNIPER -> if (isRunning) tryGrab(realClick = true)
            MainActivity.MODE_FALCON -> if (isRunning) scanFalcon()
            MainActivity.MODE_SILENT -> { /* 静默：休眠 */ }
        }
    }

    // ============ 猎鹰：扫主界面「新」标 → 同卡片价格达标 → 响铃报警 ============
    // 职责单一：只看屏幕，不碰通知，不点击，只报警。

    private fun scanFalcon() {
        val now = System.currentTimeMillis()

        // 冷静期：响过铃后60秒内不再扫，避免同一波单反复嚎
        if (now < falconQuietUntil) return

        // 节流：最快1秒扫一次
        if (now - lastFalconScan < 1000) return
        lastFalconScan = now

        // ★ 探针：诊断本屏「新」字的形态（排查期临时用，定位后删这行）
        probeDump()

        val root = rootInActiveWindow ?: return
        try {
            val minPrice = sp.getString("falcon_min_price", "4.00")
                ?.toDoubleOrNull() ?: 4.00

            // 找所有「新」标（精确匹配，杜绝“新任务”之类误伤）
            val marks = root.findAccessibilityNodeInfosByText("新") ?: return
            for (mark in marks) {
                val txt = mark.text?.toString() ?: continue
                if (txt != "新") continue
                if (!mark.isVisibleToUser) continue

                // 爬父链找卡片容器，再在卡片内找价格
                val price = findPriceInCard(mark) ?: continue
                if (price >= minPrice) {
                    log("[猎鹰] 发现新单 价格=$price ≥ 门槛=$minPrice → 报警")
                    falconQuietUntil = System.currentTimeMillis() + 60_000
                    playAlert()
                    return
                } else {
                    log("[猎鹰] 新单价格=$price 未达门槛 $minPrice，跳过")
                }
            }
        } catch (e: Exception) {
            log("[猎鹰] 扫描异常: ${e.message}")
        } finally {
            root.recycle()
        }
    }

    /** ★ 探针：遍历无障碍树，数「新」字的两种形态 + ¥节点数（排查期临时用） */
    private fun probeDump() {
        try {
            val now = System.currentTimeMillis()
            if (now - lastProbeLog < 60_000) return   // 限频：1分钟最多记一条
            lastProbeLog = now

            val root = rootInActiveWindow ?: return
            var textNew = 0
            var descNew = 0
            var priceN = 0
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 15) return
                val t = n.text?.toString() ?: ""
                val d = n.contentDescription?.toString() ?: ""
                if (t == "新" && n.isVisibleToUser) textNew++
                if (d == "新" && n.isVisibleToUser) descNew++
                if (t.contains("¥") && n.isVisibleToUser) priceN++
                for (i in 0 until n.childCount) {
                    n.getChild(i)?.let {
                        walk(it, depth + 1)
                        try { it.recycle() } catch (_: Exception) {}
                    }
                }
            }
            walk(root, 0)
            log("[猎鹰·探针] 文本「新」=$textNew 描述「新」=$descNew ¥节点=$priceN")
            try { root.recycle() } catch (_: Exception) {}
        } catch (e: Exception) {
            log("[猎鹰·探针] 异常: ${e.message}")
        }
    }

    /** 从「新」标向上爬最多5层，在卡片范围内找 ¥价格 */
    private fun findPriceInCard(mark: AccessibilityNodeInfo): Double? {
        var card: AccessibilityNodeInfo? = mark
        for (i in 0 until 5) {
            card = card?.parent ?: break
            // 卡片里应该有价格文本，找到含 ¥ 的就解析
            val prices = card.findAccessibilityNodeInfosByText("¥")
            if (prices != null && prices.isNotEmpty()) {
                for (p in prices) {
                    val t = p.text?.toString() ?: continue
                    val num = parsePrice(t)
                    if (num != null) {
                        p.recycle()
                        recycleAll(prices)
                        card.recycle()
                        return num
                    }
                    p.recycle()
                }
                recycleAll(prices)
            }
        }
        card?.recycle()
        return null
    }

    /** 从 "¥12.5" / "¥12.5元" 之类文本里抠出数字 */
    private fun parsePrice(text: String): Double? {
        return try {
            val s = text.replace("¥", "").replace("￥", "")
                .trim().split("元")[0].trim()
            s.toDoubleOrNull()
        } catch (_: Exception) { null }
    }

    private fun recycleAll(list: List<AccessibilityNodeInfo>) {
        list.forEach { try { it.recycle() } catch (_: Exception) {} }
    }

    // ============ 前台通知联动：切胶囊时更新通知栏文字 ============

    /** MainActivity.setMode() 调用：前台通知跟着模式变 */
    fun onModeChanged() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
    }

    // ============ 哨兵：通知来了 → 响铃 → 待命 → 只点可见的真二级弹窗 ============

    /** 通知监听器命中后调用：响铃提示 + 进入待命窗口 */
    fun onNotiFired() {
        if (!isRunning) return
        if (currentMode != MainActivity.MODE_SENTRY) return

        // ★ 新通知到来：先掐掉上一条还没走完的待命链，再开新链
        cancelWatch()

        playAlert()
        val watchSec = sp.getInt("sentry_watch_sec", 15)
        log("[哨兵] 通知触发，进入待命${watchSec}秒，等待你点「抢单」")
        val ticks = watchSec * 1000 / 200
        scheduleWatchTick(ticks, 200)
    }

    /** 用同一个 Runnable 句柄串起待命循环，随时可整体掐断 */
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

    /** 待命循环：每200ms扫一次「确认抢单」，扫满待命时长就彻底收工 */
    private fun watchConfirm(ticksLeft: Int) {
        // 双保险：停止/切模式时立即终止，绝不再扫
        if (!isRunning || currentMode != MainActivity.MODE_SENTRY) return
        if (ticksLeft <= 0) {
            log("[哨兵] 待命结束，未等到确认弹窗，继续蹲守")
            return
        }
        if (tryClickConfirm()) return  // 点到真弹窗，本轮结束
        scheduleWatchTick(ticksLeft - 1, 200)
    }

    /** 走闹钟通道 + 音量拉满 + 每声2秒播两遍（无震动） */
    private fun playAlert() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val old = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(
            AudioManager.STREAM_ALARM,
            am.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0
        )

        repeat(2) { i ->
            handler.postDelayed({
                try {
                    val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    val r = RingtoneManager.getRingtone(this, uri)
                    r?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    r?.play()
                    handler.postDelayed({ try { r?.stop() } catch (_: Exception) {} }, 2000)
                } catch (e: Exception) {
                    log("[铃声] 第${i + 1}遍失败: ${e.message}")
                }
            }, i * 2500L)
        }

        handler.postDelayed({
            am.setStreamVolume(AudioManager.STREAM_ALARM, old, 0)
        }, 5000)
    }

    /**
     * ★ 只点「可见的真二级弹窗」上的「确认抢单」。
     * 三道校验缺一不可：
     *   1. 文本精确等于关键词
     *   2. isVisibleToUser == true
     *   3. 区域在屏幕内且可点
     */
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
        val now = System.currentTimeMillis()
        if (now - lastFire < 500) return true
        lastFire = now
        val r = Rect()
        target.getBoundsInScreen(r)
        target.recycle()
        root.recycle()
        log("[哨兵] 发现真弹窗「$kw」→ 立即点击")
        clickNodeRect(r)
        return true
    }

    // ============ 狙击/影子：窗口事件抓取（逻辑原样保留） ============

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

    /** 坐标手势点击：按关键词找节点中心（狙击/影子用） */
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

    /** 统一的手势点击 + 记命中（哨兵/狙击/影子共用） */
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
