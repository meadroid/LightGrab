package com.light.grab

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
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
        private const val REQ_NOTI = 9001
        private const val ID_LOCK = 101
        private const val ID_CALIB = 102
    }

    private lateinit var sp: SharedPreferences
    private lateinit var tvLog: TextView
    private lateinit var tvStatus: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var btnMain: Button
    private lateinit var btnArsenal: TextView
    private val logBuf = StringBuilder()
    private var running = false
    private var notiAsked = false

    private val refresh = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            refreshStatus()
            syncServiceLogs()
            tvStatus.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sp = getSharedPreferences("lightdot", Context.MODE_PRIVATE)
        showLastCrash()
        tvLog = findViewById(R.id.tvLog)
        tvStatus = findViewById(R.id.tvStatus)
        logScroll = findViewById(R.id.logScroll)
        btnMain = findViewById(R.id.btnMain)
        btnArsenal = findViewById(R.id.btnArsenal)
        CHIP_IDS.forEachIndexed { index, id ->
            findViewById<TextView>(id).setOnClickListener { setMode(index) }
        }
        btnArsenal.setOnClickListener { showArsenalMenu() }
        btnMain.setOnClickListener {
            if (GrabService.instance == null) {
                Toast.makeText(this, "无障碍服务未连接！去 设置→辅助功能 重新打开轻点", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            running = !running
            GrabService.isRunning = running
            log(if (running) "开启监听 · ${MODE_NAMES[GrabService.currentMode.coerceIn(0, 3)]}模式" else "已停止监听")
            updateMainBtn()
            refreshStatus()
        }
        findViewById<TextView>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", tvLog.text))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }
        running = GrabService.isRunning
        updateMainBtn()
        if (GrabService.instance != null) {
            setMode(GrabService.currentMode.coerceIn(0, 3))
        } else {
            setMode(sp.getInt("mode", MODE_SHADOW).coerceIn(0, 3))
        }
        log("轻点已启动 · 边缘丝带版")
        tvStatus.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                if (notiAsked) GrabService.instance?.onModeChanged()
            } else if (!notiAsked) {
                notiAsked = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTI)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTI) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                GrabService.instance?.onModeChanged()
                Toast.makeText(this, "通知权限已批，常驻通知补挂中", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "未授权通知：请去 设置→应用→轻点→通知 打开开关，否则常驻通知不显示",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showLastCrash() {
        val crash = sp.getString("last_crash", null) ?: return
        sp.edit().remove("last_crash").apply()
        AlertDialog.Builder(this)
            .setTitle("检测到上次崩溃")
            .setMessage(crash)
            .setPositiveButton("复制全文") { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("crash", crash))
                Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ================= 军火库菜单（9项定稿：无重复无遗漏） =================

    private fun showArsenalMenu() {
        val popup = PopupMenu(this, btnArsenal)
        val menu = popup.menu
        menu.add(0, ID_LOCK, 0, "🎯 锁区设置")
        menu.add(0, ID_CALIB, 0,
            if (sp.getBoolean("calib_mode", false)) "🧪 标定开关（当前：开）"
            else "🧪 标定开关（当前：关）")
        menu.add(0, R.id.action_feature_word, 0, "🔑 特征词（认单）")
        menu.add(0, R.id.action_keyword, 0, "✒️ 关键词（点按钮）")
        menu.add(0, R.id.action_target, 0, "📱 目标应用")
        menu.add(0, R.id.action_sentry_delay, 0, "⏱ 哨兵延时")
        menu.add(0, R.id.action_sniper_delay, 0, "⏱ 狙击延时")
        menu.add(0, R.id.action_ringtone, 0, "🔔 抢单铃声")
        menu.add(0, R.id.action_permission, 0, "♿ 无障碍权限")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_LOCK -> { askLockRect(); true }
                ID_CALIB -> {
                    val now = !sp.getBoolean("calib_mode", false)
                    sp.edit().putBoolean("calib_mode", now).apply()
                    Toast.makeText(this,
                        if (now) "标定已开：出现弹窗只打坐标日志，绝不点击"
                        else "标定已关：恢复正常抢单",
                        Toast.LENGTH_LONG).show()
                    true
                }
                else -> onOptionsItemSelected(item)
            }
        }
        popup.show()
    }

    private fun updateMainBtn() {
        btnMain.text = if (running) "停止监听" else "开启监听"
        val bg = if (running) R.color.mint else R.color.grayChip
        val fg = if (running) R.color.mintTextOn else R.color.grayText
        btnMain.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, bg))
        btnMain.setTextColor(ContextCompat.getColor(this, fg))
    }

    private fun setMode(mode: Int) {
        val m = mode.coerceIn(0, 3)
        GrabService.currentMode = m
        sp.edit().putInt("mode", m).apply()
        CHIP_IDS.forEachIndexed { index, id ->
            val chip = findViewById<TextView>(id)
            if (index == m) {
                chip.setBackgroundResource(R.color.mint)
                chip.setTextColor(ContextCompat.getColor(this, R.color.mintTextOn))
            } else {
                chip.setBackgroundResource(R.color.grayChip)
                chip.setTextColor(ContextCompat.getColor(this, R.color.grayText))
            }
        }
        GrabService.instance?.onModeChanged()
        refreshStatus()
        log("模式切换 → ${MODE_NAMES[m]}")
        syncServiceLogs()
    }

    private fun refreshStatus() {
        val modeName = MODE_NAMES[GrabService.currentMode.coerceIn(0, 3)]
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.CHINA)
            .format(java.util.Date())
        val today = sp.getInt("hit_$day", 0)
        val total = sp.getInt("total_hit", 0)
        val last = sp.getLong("last_hit_time", 0L)
        val lastStr = if (last > 0)
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
                .format(java.util.Date(last))
        else "无"
        val lr = sp.getString("lock_rect", null)
        val lockStr = if (!lr.isNullOrBlank() && lr.split(",").size == 4) "已配" else "未配"
        val line1 = if (running && GrabService.instance != null) "♟️ 模式：${modeName} 运行中"
                     else "♟️ 模式：${modeName} 待启动"
        val line2 = "🕐 今日：命中 $today 发  $lastStr"
        val line3 = "🏆 累计：命中 $total 发  🎯锁区：$lockStr"
        tvStatus.text = "$line1\n$line2\n$line3"
    }

    private fun syncServiceLogs() {
        val m = GrabService.currentMode.coerceIn(0, 3)
        val svc = sp.getString("logs_m$m", "") ?: ""
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val local = logBuf.toString()
            tvLog.text = if (local.isEmpty()) svc else "$local\n──── 服务日志 ────\n$svc"
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun log(msg: String) {
        runOnUiThread {
            logBuf.append("${SimpleTime.now()}  $msg\n")
            if (logBuf.length > 4000) logBuf.delete(0, logBuf.length - 4000)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_target -> { askApp(); true }
            R.id.action_feature_word -> { askFeatureWord(); true }
            R.id.action_keyword -> { askKeyword(); true }
            R.id.action_ringtone -> { pickRingtone(); true }
            R.id.action_sentry_delay -> { askDelay("哨兵岗（延时最低~最高）", "sentry_delay_min", "sentry_delay_max"); true }
            R.id.action_sniper_delay -> { askDelay("狙击岗（延时最低~最高）", "sniper_delay_min", "sniper_delay_max"); true }
            R.id.action_permission -> {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------- 锁区设置（特征矩形右下角为原点：dx, dy, 宽, 高，单位 dp） ----------

    private fun askLockRect() {
        val cur = sp.getString("lock_rect", null)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = "格式：dx,dy,宽,高  如：-120,-80,110,44"
            setText(cur ?: "")
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("锁区设置（dp，相对特征词矩形右下角）\n先开标定模式抄参考答案，再填这里")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val txt = input.text.toString().trim()
                if (txt.isEmpty()) {
                    sp.edit().remove("lock_rect").apply()
                    Toast.makeText(this, "锁区已清除（回退双词路线）", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val p = txt.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (p.size != 4) {
                    Toast.makeText(this, "格式不对！要4个数字用逗号隔开", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                sp.edit().putString("lock_rect", p.joinToString(",")).apply()
                Toast.makeText(this, "锁区已保存：${p.joinToString(",")}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
            .setTitle("选择中标人（目标应用）")
            .setItems(names) { _, which ->
                sp.edit()
                    .putString("app_pkg", apps[which].second)
                    .putString("app_name", apps[which].first)
                    .apply()
                Toast.makeText(this, "已选择 ${apps[which].first}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askFeatureWord() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(sp.getString("feature_word", ""))
            setSingleLine(true)
            hint = "如：立即上门单，留空=不过滤"
        }
        AlertDialog.Builder(this)
            .setTitle("特征词（弹窗里必须出现的字，多个用逗号隔开）")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                sp.edit().putString("feature_word", input.text.toString().trim()).apply()
                Toast.makeText(this, "已保存，留空=不过滤弹窗", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun pickRingtone() {
        val intent = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                     android.media.RingtoneManager.TYPE_ALARM)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "选铃声库")
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            sp.getString("alert_uri", null)?.let {
                try {
                    putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                             android.net.Uri.parse(it))
                } catch (_: Exception) { }
            }
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 777)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 777 && resultCode == RESULT_OK) {
            val uri = data?.getParcelableExtra<android.net.Uri>(
                android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                sp.edit().putString("alert_uri", uri.toString()).apply()
                Toast.makeText(this, "铃声已保存", Toast.LENGTH_SHORT).show()
            }
        }
    }

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
            }
            .setNegativeButton("取消", null)
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
