package com.light.grab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/** 轻量悬浮球：点按暂停/恢复，长按关闭 */
class FloatService : Service() {
    private lateinit var wm: WindowManager
    private var dot: View? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notify())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dot = ImageView(this).apply {
            setImageResource(icon())
            setOnLongClickListener { stopSelf(); true }
            setOnClickListener {
                GrabService.running = !GrabService.running
                setImageResource(icon())
            }
        }
        wm.addView(dot, WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.END or Gravity.TOP
            x = 12; y = 200
        })
    }

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

    private fun ImageView.icon() = if (GrabService.running)
        android.R.drawable.presence_online else android.R.drawable.presence_invisible

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY

    override fun onDestroy() {
        dot?.let { wm.removeView(it) }
        super.onDestroy()
    }
}
