package com.light.grab

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.Toast
import java.io.PrintWriter
import java.io.StringWriter

/** 全局黑匣子：任何崩溃 → Toast现场 + 堆栈存档，下次开机报给你 */
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

                // 主线程用Toast报现场（可能只闪一下，但存档在）
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    toast(e.message ?: e.javaClass.simpleName)
                } else {
                    android.os.Handler(Looper.getMainLooper()).post {
                        toast(e.message ?: e.javaClass.simpleName)
                    }
                    Thread.sleep(500)   // 给Toast留口气
                }
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(t, e)   // 走系统默认流程（无障碍正常解绑）
        }
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, "崩溃现场: $msg", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {}
    }
}
