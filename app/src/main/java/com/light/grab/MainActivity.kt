package com.light.grab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_SHADOW = 0    // 影子：只看不点
        const val MODE_SENTRY = 1    // 哨兵：发现弹窗
        const val MODE_SNIPER = 2    // 狙击：自动点击抢单
        const val MODE_SILENT = 3    // 静默：仅记录

        val MODE_NAMES = listOf("影子", "哨兵", "狙击", "静默")
        val CHIP_IDS = listOf(
            R.id.chipShadow, R.id.chipSentry,
            R.id.chipSniper, R.id.chipSilent
        )
    }

    private lateinit var sp: SharedPreferences
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnMain: Button
    private val logBuf = StringBuilder()
    private var running = false

    private val refresh = object : Runnable {
        override fun run() {
            refreshStatus()
            tvStatus.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sp = getSharedPreferences("lightdot", Context.MODE_PRIVATE)
        tvLog = findViewById(R.id.tvLog)
        tvStatus = findViewById(R.id.tvStatus)
        logScroll = findViewById(R.id.logScroll)
        btnMain = findViewById(R.id.btnMain)

        CHIP_IDS.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener { setMode(index) }
        }

        btnMain.setOnClickListener {
            if (!isAccOn()) {
                Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                running = !running
                GrabService.isRunning = running
                btnMain.text = if (running) "停止监听" else "开启监听"
                log(if (running) "开始监听 · ${MODE_NAMES[GrabService.currentMode]}模式" else "已停止监听")
                refreshStatus()
            }
        }

        // 日志区右上角小图标（⎘）：点击复制日志
        findViewById<TextView>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", tvLog.text))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }

        setMode(sp.getInt("mode", MODE_SHADOW))
        log("轻点已启动")
        tvStatus.post(refresh)
    }

    private fun setMode(mode: Int) {
        GrabService.currentMode = mode
        sp.edit().putInt("mode", mode).apply()
        CHIP_IDS.forEachIndexed { index, id ->
            val chip = findViewById<TextView>(id)
            if (index == mode) {
                chip.setBackgroundResource(R.color.mint)
                chip.setTextColor(ContextCompat.getColor(this, R.color.mintTextOn))
            } else {
                chip.setBackgroundResource(R.color.grayChip)
                chip.setTextColor(ContextCompat.getColor(this, R.color.grayText))
            }
        }
        refreshStatus()
        log("模式切换 → ${MODE_NAMES[mode]}")
    }

    private fun refreshStatus() {
        val modeName = MODE_NAMES[GrabService.currentMode]
        val last = sp.getLong("last_hit_time", 0L)
        val lastStr = if (last > 0)
            java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date(last))
        else "无"
        val total = sp.getInt("total_hit", 0)

        // 三行统一 emoji 前缀，等宽字体下自然对齐
        val line1 = if (running && isAccOn()) "🟢 模式：${modeName} 运行中"
                     else "⚪ 模式：${modeName} 待启动"
        val line2 = "🕐 最后命中：$lastStr"
        val line3 = "🏆 累计：共命中 $total 发"
        tvStatus.text = "$line1\n$line2\n$line3"
    }

    private fun isAccOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(packageName)
    }

    private fun log(msg: String) {
        runOnUiThread {
            logBuf.insert(0, "${SimpleTime.now()}  $msg\n")
            if (logBuf.length > 8000) logBuf.setLength(8000)
            tvLog.text = logBuf.toString()
            logScroll.scrollTo(0, 0)
        }
    }

    // ===== 武器库菜单：二级直连各自设置 =====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_target -> openSettings("app")
            R.id.action_keyword -> openSettings("keyword")
            R.id.action_sentry_delay -> openSettings("sentry")
            R.id.action_sniper_delay -> openSettings("sniper")
            R.id.action_permission -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun openSettings(which: String): Boolean {
        startActivity(
            Intent(this, SettingsActivity::class.java).putExtra("open", which)
        )
        return true
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
