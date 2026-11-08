package com.light.grab

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

    private fun ImageView.icon() = if (GrabService.running)
        android.R.drawable.presence_online else android.R.drawable.presence_invisible

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int = START_STICKY
    override fun onDestroy() { dot?.let { wm.removeView(it) }; super.onDestroy() }
}
