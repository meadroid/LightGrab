package com.light.grab

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter

/** 全局黑匣子：崩溃 → 存档 + 尽力Toast，下次开机报给你。本文件只许有这一个类。 */
class CrashGuard : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val stack = "$t\n${sw.toString().take(3000)}"
                getSharedPreferences("lightdot", Context.MODE_PRIVATE)
                    .edit().putString("last_crash", stack).apply()

                val msg = e.message ?: e.javaClass.simpleName
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    toast(msg)
                } else {
                    android.os.Handler(Looper.getMainLooper()).post { toast(msg) }
                    Thread.sleep(500)
                }
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(t, e)
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, "崩溃现场: $msg", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }
}
