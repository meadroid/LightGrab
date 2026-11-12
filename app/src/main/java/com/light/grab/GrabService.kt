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
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.RingtoneManager
import android.media.projection.MediaProjectionManager
import android.media.projection.AudioPlaybackCaptureConfiguration
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
        const val NOTI_ID_ALERT = 1002
        const val NOTI_ID_VP = 1003
        const val MATCH_TH = 0.80f       // 相似度阈值，误拦调低、误放调高
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

    @Volatile private var heartbeat = 0L
    private var watchdogTask: Runnable? = null
    private var watchdogRestarts = 0

    // ============ [声纹] 音频捕获 + 指纹比对 ============
    private var mediaProjection: android.media.projection.MediaProjection? = null
    private var vpRecord: AudioRecord? = null
    private var vpThread: Thread? = null
    private var baselineFp: FloatArray? = null
    @Volatile private var latestMatch = 0L
    @Volatile private var lastMatchLog = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        startFg()
        currentMode = sp.getInt("mode", MainActivity.MODE_SHADOW)
        checkMediaVolume()
        buildBaseline()
        registerSoundSentry()
        startWatchdog()
        startVoiceprintIfPossible()
        log("[服务] 已连接，模式=${MainActivity.MODE_NAMES[currentMode]}（持久化读回）")
    }

    override fun onDestroy() {
        try {
            audioManager?.unregisterAudioPlaybackCallback(soundCallback)
        } catch (_: Exception) {}
        stopVoiceprint()
        handler.removeCallbacksAndMessages(null)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {
        log("[服务] ⚠️ 被中断")
    }

    // ============ [声纹] 启动音频捕获（授权令牌来自 MainActivity） ============
    fun startVoiceprintIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log("[声纹] 系统低于安卓10，不支持捕获（自动放行模式）")
            return
        }
        val data = MainActivity.projData ?: return
        if (vpThread?.isAlive == true) return
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(MainActivity.projResultCode, data)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(
                    NotificationChannel("lightgrab_vp", "轻点声纹", NotificationManager.IMPORTANCE_LOW)
                )
                val pi = PendingIntent.getActivity(
                    this, 1, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                val n: Notification = Notification.Builder(this, "lightgrab_vp")
                    .setContentTitle("轻点声纹监听中")
                    .setContentText("正在比对目标App提示音")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
                startForeground(NOTI_ID_VP, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            }

            val mp = mediaProjection!!
            // [修] 类在 android.media.projection 包里，写全限定名
            val cfg = android.media.projection.AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder()
                .setSampleRate(44100)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minBuf = AudioRecord.getMinBufferSize(
                44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            vpRecord = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(minBuf.coerceAtLeast(65536))
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            vpRecord?.startRecording()
            vpThread = Thread { captureLoop() }.apply {
                name = "vp-capture"; start()
            }
            log("[声纹] ✅ 实时音频捕获已启动")
        } catch (e: Exception) {
            log("[声纹] 启动失败: ${e.message}（回退：直接放行）")
        }
    }

    private fun stopVoiceprint() {
        try { vpRecord?.stop() } catch (_: Exception) {}
        try { vpRecord?.release() } catch (_: Exception) {}
        vpRecord = null
        vpThread = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null
    }

    /** 捕获主循环：攒4096点窗口 → 指纹 → 和基准音比对 */
    private fun captureLoop() {
        val win = 4096
        val buf = ShortArray(1024)
        var acc = ShortArray(win)
        var accLen = 0
        val rec = vpRecord ?: return
        while (vpThread != null && rec.state == AudioRecord.STATE_INITIALIZED) {
            val n = try { rec.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
            if (n <= 0) { sleepMs(10); continue }
            var i = 0
            while (i < n) {
                val take = minOf(n - i, win - accLen)
                System.arraycopy(buf, i, acc, accLen, take)
                accLen += take
                i += take
                if (accLen == win) {
                    processWindow(acc)
                    acc = ShortArray(win)
                    accLen = 0
                }
            }
        }
    }

    private fun processWindow(pcm: ShortArray) {
        val base = baselineFp ?: return
        var energy = 0.0
        for (s in pcm) energy += s.toDouble() * s
        val rms = Math.sqrt(energy / pcm.size)
        if (rms < 150.0) return
        val fp = fingerprintOf(pcm) ?: return
        val sim = cosine(fp, base)
        val matched = sim >= MATCH_TH
        if (matched) latestMatch = System.currentTimeMillis()
        if (matched != lastMatchLog) {
            lastMatchLog = matched
            if (matched) log("[声纹] ✅ 匹配（相似度%.2f）".format(sim))
            else log("[声纹] 🚫 不匹配（相似度%.2f），拦截".format(sim))
        }
    }

    // ============ [声纹] 指纹：分帧FFT幅度谱取平均 → log → 单位化 ============
    private fun fingerprintOf(pcm: ShortArray): FloatArray? {
        val n = 4096
        if (pcm.size < n) return null
        val bins = n / 2
        val acc = FloatArray(bins)
        var frames = 0
        val re = FloatArray(n)
        val im = FloatArray(n)
        val hann = FloatArray(n)
        for (i in 0 until n) hann[i] =
            (0.5 * (1 - Math.cos(2.0 * Math.PI * i / (n - 1)))).toFloat()
        var off = 0
        while (off + n <= pcm.size) {
            var e = 0.0
            for (i in 0 until n) {
                val v = pcm[off + i].toFloat()
                e += v * v
            }
            val rms = Math.sqrt(e / n)
            if (rms > 150.0) {
                for (i in 0 until n) {
                    re[i] = pcm[off + i] * hann[i]
                    im[i] = 0f
                }
                fft(re, im)
                for (i in 0 until bins) {
                    acc[i] += Math.sqrt(
                        re[i].toDouble() * re[i] + im[i].toDouble() * im[i]).toFloat()
                }
                frames++
            }
            off += n / 2
        }
        if (frames == 0) return null
        val fp = FloatArray(bins)
        // [修] Math.log 要 Double，转一下
        for (i in 0 until bins) {
            fp[i] = Math.log(1.0 + acc[i] / frames).toFloat()
        }
        var norm = 0.0
        for (v in fp) norm += v.toDouble() * v
        norm = Math.sqrt(norm)
        if (norm < 1e-6) return null
        for (i in 0 until bins) fp[i] = (fp[i] / norm).toFloat()
        return fp
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return dot.toFloat()
    }

    /** 迭代式 radix-2 FFT */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = Math.cos(ang).toFloat()
            val wi = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f
                var ci = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val xr = re[i + k + half]
                    val xi = im[i + k + half]
                    val vr = xr * cr - xi * ci
                    val vi = xr * ci + xi * cr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** [声纹] 解码 raw/baseline.mp3 → 基准指纹 */
    private fun buildBaseline() {
        try {
            val tmp = File(cacheDir, "baseline_tmp.mp3")
            resources.openRawResource(R.raw.baseline).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            val extractor = MediaExtractor()
            extractor.setDataSource(tmp.absolutePath)
            var trackIdx = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIdx = i; format = f; break
                }
            }
            if (trackIdx < 0 || format == null) {
                log("[声纹] ⚠️ 基准音里找不到音轨（放行模式）")
                return
            }
            extractor.selectTrack(trackIdx)
            val mime = format!!.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val pcm = mutableListOf<Short>()
            val info = MediaCodec.BufferInfo()
            var eos = false
            var spins = 0
            while (pcm.size < 44100 * 4 && !eos && spins < 5000) {
                spins++
                if (!eos) {
                    val inBuf = codec.dequeueInputBuffer(10_000)
                    if (inBuf >= 0) {
                        val ib = codec.getInputBuffer(inBuf)!!
                        val sz = extractor.readSampleData(ib, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inBuf, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            codec.queueInputBuffer(inBuf, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outBuf = codec.dequeueOutputBuffer(info, 10_000)
                if (outBuf >= 0) {
                    val ob = codec.getOutputBuffer(outBuf)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    while (ob.remaining() >= 2) pcm.add(ob.short)
                    codec.releaseOutputBuffer(outBuf, false)
                }
            }
            try { codec.stop() } catch (_: Exception) {}
            try { codec.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            tmp.delete()
            if (pcm.size < 4096) {
                log("[声纹] ⚠️ 基准音太短（放行模式）")
                return
            }
            val arr = ShortArray(pcm.size)
            for (i in arr.indices) arr[i] = pcm[i]
            baselineFp = fingerprintOf(arr)
            if (baselineFp != null) log("[声纹] ✅ 基准指纹就绪（${pcm.size / 44100.0}秒）")
            else log("[声纹] ⚠️ 基准指纹生成失败（放行模式）")
        } catch (e: Exception) {
            log("[声纹] 基准音解码失败: ${e.message}（放行模式）")
        }
    }

    private fun sleepMs(ms: Long) {
        try { Thread.sleep(ms) } catch (_: Exception) {}
    }

    // ============ 病6：公共通知函数 ============
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

    // ============ 病4：媒体音量自检 ============
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

    // ============ 病2：看门狗保活 + 异常自动重启 ============
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
                    scheduleWatchTick(0, 100)
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

    // ============ [声纹] 判定收口：抓到目标音特征才放行 ============
    private fun verifyVoiceprint(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        if (baselineFp == null) return true
        if (vpThread?.isAlive != true) return true
        return System.currentTimeMillis() - latestMatch < 2500
    }

    // ============ 病7：全量决策日志 ============
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
            touchHeartbeat()
            val targetPkg = sp.getString("app_pkg", null) ?: return
            val isTargetPlaying = configs.any { cfg -> uidMatches(cfg, targetPkg) }
            if (!isTargetPlaying) {
                val uidAllUnknown = configs.isNotEmpty() &&
                        configs.all { uidOf(it) == null }
                if (!uidAllUnknown) return
                log("[声音哨兵] 降级命中(反射不可用)")
            } else {
                log("[声音哨兵] 精确命中 $targetPkg")
            }

            if (!verifyVoiceprint()) {
                logDecision("拦截", "声纹不匹配")
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastSoundTrigger < 30000) return
            lastSoundTrigger = now
            logDecision("放行", "目标App出声+声纹通过，冷却窗口通过")
            onNotiFired()
        }
    }

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
        touchHeartbeat()
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
        touchHeartbeat()
        if (!isRunning || currentMode != MainActivity.MODE_SENTRY) return
        if (ticksLeft <= 0) {
            log("[哨兵] 待命结束，未等到确认弹窗，继续蹲守")
            return
        }
        if (tryClickConfirm()) return
        scheduleWatchTick(ticksLeft - 1, 200)
    }

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

    // ============ 病6：共用校验链 ============
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
