package com.light.grab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val MODE_SHADOW = 0
        const val MODE_SENTRY = 1
        const val MODE_SNIPER = 2
        const val MODE_SILENT = 3

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
                log(if (running) "开启监听 · ${MODE_NAMES[GrabService.currentMode]}模式" else "已停止监听")
                refreshStatus()
            }
        }

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
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
            .format(java.util.Date())
        val today = sp.getInt("hit_$day", 0)
        val total = sp.getInt("total_hit", 0)
        val last = sp.getLong("last_hit_time", 0L)
        val lastStr = if (last > 0)
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date(last))
        else "无"

        val line1 = if (running && isAccOn()) "♟️ 模式：${modeName} 运行中"
                     else "♟️ 模式：${modeName} 待启动"
        val line2 = "🕐 今日：命中 $today 发  $lastStr"
        val line3 = "🏆 累计：命中 $total 发"
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

    // ===== 武器库菜单 =====
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_target -> { askApp(); true }
            R.id.action_keyword -> { askKeyword(); true }
            R.id.action_sentry_delay -> { askDelay("哨兵延时（最低~最高）", "sentry_delay_min", "sentry_delay_max"); true }
            R.id.action_sniper_delay -> { askDelay("狙击延时（最低~最高）", "sniper_delay_min", "sniper_delay_max"); true }
            R.id.action_permission -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** 弹窗关闭后重新打开菜单，停留在武器库列表 */
    private fun reopenMenu() {
        runOnUiThread { openOptionsMenu() }
    }

    /** 三级：选择目标应用 */
    private fun askApp() {
        val pm = packageManager
        val apps = pm.getInstalledPackages(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map {
                val name = it.applicationInfo?.loadLabel(pm)?.toString() ?: it.packageName
                name to it.packageName
            }
            .sortedBy { it.first }

        val names = apps.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择目标应用")
            .setItems(names) { _, which ->
                sp.edit()
                    .putString("app_pkg", apps[which].second)
                    .putString("app_name", apps[which].first)
                    .apply()
                Toast.makeText(this, "已选择 ${apps[which].first}", Toast.LENGTH_SHORT).show()
                reopenMenu()
            }
            .setNegativeButton("取消") { _, _ -> reopenMenu() }
            .setOnCancelListener { reopenMenu() }
            .show()
    }

    /** 三级：关键词 */
    private fun askKeyword() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(sp.getString("keyword", ""))
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("关键词（点中按钮上的文字）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                sp.edit().putString("keyword", input.text.toString().trim()).apply()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                reopenMenu()
            }
            .setNegativeButton("取消") { _, _ -> reopenMenu() }
            .setOnCancelListener { reopenMenu() }
            .show()
    }

    /** 三级：延时区间 */
    private fun askDelay(title: String, keyMin: String, keyMax: String) {
        val minInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "最低值（毫秒）"
            setText(sp.getInt(keyMin, 0).toString())
        }
        val maxInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "最高值（毫秒）"
            setText(sp.getInt(keyMax, 500).toString())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, (10 * resources.displayMetrics.density).toInt(), pad, 0)
            addView(minInput)
            addView(maxInput)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val min = minInput.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 0
                var max = maxInput.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 500
                if (max < min) max = min
                sp.edit().putInt(keyMin, min).putInt(keyMax, max).apply()
                Toast.makeText(this, "已保存 ${min}~${max}ms", Toast.LENGTH_SHORT).show()
                reopenMenu()
            }
            .setNegativeButton("取消") { _, _ -> reopenMenu() }
            .setOnCancelListener { reopenMenu() }
            .show()
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
