package com.light.grab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/** 轻量悬浮球：点按暂停/恢复，长按关闭，可拖动并吸附左右边缘 */
class FloatService : Service() {
    private lateinit var wm: WindowManager
    private var dot: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notify())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showDot()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun showDot() {
        if (dot != null) return

        // 球体加大到 52dp
        val size = dp(52)
        dot = ImageView(this).apply {
            setImageResource(icon())
            setOnLongClickListener { stopSelf(); true }
        }
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - size - dp(12)
            y = dp(200)
        }

        try { wm.addView(dot, params) } catch (e: Exception) { return }

        // 触摸：拖动跟手；轻点=暂停/恢复；长按保留关闭；松手吸附边缘
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        var longPressFired = false

        dot!!.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params!!.x; startY = params!!.y
                    moved = false
                    longPressFired = false
                    // 手动实现长按：600ms 没动就触发关闭
                    v.postDelayed({
                        if (!moved) {
                            longPressFired = true
                            stopSelf()
                        }
                    }, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    if (!moved && (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10))) {
                        moved = true
                        v.removeCallbacks(null)  // 一动就取消长按判定
                    }
                    if (moved) {
                        params!!.x = startX + dx
                        params!!.y = startY + dy
                        try { wm.updateViewLayout(dot, params) } catch (e: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(null)
                    if (longPressFired) {
                        // 长按已关闭，不处理
                    } else if (!moved) {
                        // 轻点：暂停/恢复
                        GrabService.running = !GrabService.running
                        dot!!.setImageResource(icon())
                    } else {
                        // 松手吸附左右边缘
                        val screenW = resources.displayMetrics.widthPixels
                        params!!.x = if (params!!.x + size / 2 < screenW / 2) 0
                                     else screenW - size
                        try { wm.updateViewLayout(dot, params) } catch (e: Exception) { }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun ImageView.icon() = if (GrabService.running)
        android.R.drawable.presence_online else android.R.drawable.presence_invisible

    /** 前台通知：静默低存在感 */
    private fun notify(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("fg", "后台运行",
                NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            })
        return Notification.Builder(this, "fg")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentText("运行中 · 点悬浮球暂停")
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (dot == null) showDot()
        return START_STICKY
    }

    override fun onDestroy() {
        try { dot?.let { wm.removeView(it) } } catch (e: Exception) { }
        dot = null
        super.onDestroy()
    }
}
