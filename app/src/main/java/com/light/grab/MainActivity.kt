package com.light.grab

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (isAccOn()) {
                Toast.makeText(this, "✅ 服务已开，去顺丰等单吧", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "请开启「轻单助手」无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请在无障碍页关闭本服务", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        GrabService.logHook = { msg -> runOnUiThread { log(msg) } }
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        GrabService.logHook = null
    }

    private fun refreshStatus() {
        if (isAccOn()) {
            tvStatus.text = "● 运行中"
            tvStatus.setTextColor(0xFF2E7D32.toInt())
        } else {
            tvStatus.text = "● 已停止"
            tvStatus.setTextColor(0xFFC62828.toInt())
        }
    }

    private fun isAccOn(): Boolean {
        val expected = ComponentName(this, GrabService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any {
            it.equals(expected, true) ||
            it.equals("$packageName/." + expected.substringAfterLast('/'), true)
        }
    }

    private fun log(msg: String) {
        tvLog.append("\n[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())}] $msg")
    }
}
