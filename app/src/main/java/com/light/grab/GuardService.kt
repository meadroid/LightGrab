package com.light.grab

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** 守护：每20s检测核心服务，断线即拉活（配合前台服务双保险） */
class GuardService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!GrabService.running && MainActivity.accessibilityOn(this@GuardService)) {
                // 无障碍被系统重连时 onServiceConnected 自动执行，此处仅需保前台通知
            }
            h.postDelayed(this, 20_000)
        }
    }
    override fun onCreate() { super.onCreate(); h.post(tick) }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onDestroy() { h.removeCallbacks(tick); super.onDestroy() }
}
