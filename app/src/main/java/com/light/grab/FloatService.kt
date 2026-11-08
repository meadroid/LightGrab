package com.light.grab

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/** 轻量悬浮球：单色圆点，点击切换运行状态，长按停止 */
class FloatService : Service() {
    private lateinit var wm: WindowManager
    private var dot: View? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        dot = ImageView(this).apply {
            setImageResource(if (GrabService.running)
                android.R.drawable.presence_online else android.R.drawable.presence_invisible)
            setOnLongClickListener { stopSelf(); true }
            setOnClickListener {
                GrabService.running = !GrabService.running
                setImageResource(if (GrabService.running)
                    android.R.drawable.presence_online else android.R.drawable.presence_invisible)
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

    override fun onDestroy() { dot?.let { wm.removeView(it) }; super.onDestroy() }
}
