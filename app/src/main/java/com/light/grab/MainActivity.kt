package com.light.grab

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_SHADOW = 0   // 影子：只看不点
        const val MODE_SENTRY = 1   // 哨兵：发现弹窗
        const val MODE_SNIPER = 2   // 狙击：自动点击抢单
        const val MODE_SILENT = 3   // 静默：仅记录

        val APPS = mapOf(
            "顺丰骑士" to "com.sfexpress.sfapp",
            "美团骑手" to "com.sankuai.meituan.takeoutnew.rider"
        )
    }

    private lateinit var tvLog: TextView
    private lateinit var logScroll: ScrollView
    private val logBuf = StringBuilder()
    var currentMode = MODE_SHADOW
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        logScroll = findViewById(R.id.logScroll)

        findViewById<TextView>(R.id.chipShadow).setOnClickListener { setMode(MODE_SHADOW) }
        findViewById<TextView>(R.id.chipSentry).setOnClickListener { setMode(MODE_SENTRY) }
        findViewById<TextView>(R.id.chipSniper).setOnClickListener { setMode(MODE_SNIPER) }
        findViewById<TextView>(R.id.chipSilent).setOnClickListener { setMode(MODE_SILENT) }

        val btn = findViewById<Button>(R.id.btnMain)
        btn.setOnClickListener {
            if (isAccOn()) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        setMode(MODE_SHADOW)
        log("轻单已启动")
    }

    private fun setMode(mode: Int) {
        currentMode = mode
        val names = listOf("影子", "哨兵", "狙击", "静默")
        log("模式切换 → ${names[mode]}")
    }

    private fun isAccOn(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val list = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return list.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logBuf.insert(0, "${SimpleTime.now()}  $msg\n")
            if (logBuf.length > 8000) logBuf.setLength(8000)
            tvLog.text = logBuf.toString()
            logScroll.scrollTo(0, 0)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_acc -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); true
            }
            R.id.action_clear_log -> {
                logBuf.setLength(0); tvLog.text = ""; true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}

object SimpleTime {
    fun now(): String {
        val c = java.util.Calendar.getInstance()
        return String.format(
            "%02d:%02d:%02d",
            c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            c.get(java.util.Calendar.SECOND)
        )
    }
}
