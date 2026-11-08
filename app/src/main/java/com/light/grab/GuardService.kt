package com.light.grab

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** 守护：每20s自检，维持服务存活状态 */
class GuardService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { h.postDelayed(this, 20_000) }
    }
    override fun onCreate() { super.onCreate(); h.post(tick) }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }
}
