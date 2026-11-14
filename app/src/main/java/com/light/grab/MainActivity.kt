package com.light.grab

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("qingdan", MODE_PRIVATE) }
    private lateinit var statusText: TextView
    private lateinit var statsText: TextView
    private lateinit var logText: TextView

    // 四模式胶囊
    private lateinit var btnShadow: Button
    private lateinit var btnSentry: Button
    private lateinit var btnSniper: Button
    private lateinit var btnSilent: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        // 状态栏
        statusText = TextView(this).apply { textSize = 15f }
        root.addView(statusText)

        // 四模式胶囊
        val capsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun capBtn(label: String, mode: String): Button =
            Button(this).apply {
                text = label
                setOnClickListener { setMode(mode) }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        btnShadow = capBtn("影子", "shadow")
        btnSentry = capBtn("哨兵", "sentry")
        btnSniper = capBtn("狙击", "sniper")
        btnSilent = capBtn("静默", "silent")
        listOf(btnShadow, btnSentry, btnSniper, btnSilent).forEach { capsRow.addView(it) }
        root.addView(capsRow)

        // 统计
        statsText = TextView(this).apply { textSize = 14f; setPadding(0, 30, 0, 30) }
        root.addView(statsText)

        // 日志区
        logText = TextView(this).apply {
            textSize = 12f
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        root.addView(logText)

        // 复制日志按钮
        root.addView(Button(this).apply {
            text = "📋 复制日志"
            setOnClickListener { copyLogs() }
        })

        setContentView(root)

        requestNotiPermission()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    // ---------- 模式切换 ----------

    private fun setMode(mode: String) {
        prefs.edit().putString("mode", mode).apply()
        refreshUi()
        GrabService.instance?.refreshNotification()
    }

    private fun refreshUi() {
        val mode = prefs.getString("mode", "shadow") ?: "shadow"
        listOf(btnShadow to "shadow", btnSentry to "sentry",
            btnSniper to "sniper", btnSilent to "silent").forEach { (b, m) ->
            b.alpha = if (m == mode) 1f else 0.4f
        }

        val lock = prefs.getString("lock_rect", null)
        val lockTxt = if (!lock.isNullOrBlank() && lock.split(",").size == 4)
            "锁区✅($lock)" else "锁区未配(回退双词)"
        val calib = if (prefs.getBoolean("calib_mode", false)) "标定开" else "标定关"
        statusText.text = "轻点·在岗 | $lockTxt | $calib"

        val todayKey = "hits_" + SimpleDateFormat("MM-dd", Locale.CHINA).format(Date())
        val today = prefs.getInt(todayKey, 0)
        val total = prefs.getInt("hits_total", 0)
        statsText.text = "今日命中: $today    累计命中: $total"

        GrabService.instance?.let { logText.text = it.getLogs() }
    }

    private fun copyLogs() {
        val txt = GrabService.instance?.getLogs() ?: "（服务未启动）"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", txt))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_target -> {
                // 原有：响铃线目标App选择（包名）
                val input = EditText(this).apply { setText(prefs.getString("target_pkg", "")) }
                AlertDialog.Builder(this)
                    .setTitle("中标人")
                    .setMessage("响铃线的目标App包名（抓单线不受此限制）。留空=响铃线关闭。")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        prefs.edit().putString("target_pkg", input.text.toString().trim()).apply()
                        refreshUi()
                    }
                    .setNegativeButton("取消", null).show()
            }
            R.id.action_feature_word -> {
                // 原有：特征词
                val input = EditText(this).apply { setText(prefs.getString("feature_word", "")) }
                AlertDialog.Builder(this)
                    .setTitle("特征词")
                    .setMessage("页面特征文字，认脸用（身份证）")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        prefs.edit().putString("feature_word", input.text.toString().trim()).apply()
                        refreshUi()
                    }
                    .setNegativeButton("取消", null).show()
            }
            R.id.action_keyword -> {
                // 原有：关键词
                val input = EditText(this).apply { setText(prefs.getString("keyword", "")) }
                AlertDialog.Builder(this)
                    .setTitle("关键词")
                    .setMessage("按钮文字，双词回退路线用")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        prefs.edit().putString("keyword", input.text.toString().trim()).apply()
                        refreshUi()
                    }
                    .setNegativeButton("取消", null).show()
            }
            R.id.action_lock_rect -> {
                // 新增：锁区词
                val cur = prefs.getString("lock_rect", "")
                val input = EditText(this).apply { setText(cur); hint = "左,上,宽,高（dp，可负值）" }
                AlertDialog.Builder(this)
                    .setTitle("锁区词")
                    .setMessage("特征词矩形左上角 + 四数偏移 = 按钮区域。格式: 12,86,340,148。没配走双词回退，不会瞎。")
                    .setView(input)
                    .setPositiveButton("保存") { _, _ ->
                        val s = input.text.toString().trim()
                        val p = s.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (p.size == 4) {
                            prefs.edit().putString("lock_rect", s).apply()
                            Toast.makeText(this, "锁区已配 ✅", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "格式错，须4个数", Toast.LENGTH_SHORT).show()
                        }
                        refreshUi()
                        GrabService.instance?.refreshNotification()
                    }
                    .setNegativeButton("取消", null).show()
            }
            R.id.action_calibration -> {
                // 新增：标定词
                val on = prefs.getBoolean("calib_mode", false)
                prefs.edit().putBoolean("calib_mode", !on).apply()
                Toast.makeText(this,
                    if (on) "标定已关" else "标定已开（只打日志不点击）",
                    Toast.LENGTH_SHORT).show()
                refreshUi()
            }
            R.id.action_calib_help -> {
                // 新增：标助词
                AlertDialog.Builder(this).setTitle("标助词").setMessage(
                    "标定流程：\n" +
                    "1️⃣ 标定词→开\n" +
                    "2️⃣ 去目标App，等真实弹窗来\n" +
                    "3️⃣ 回这里 📋复制日志，找 [标定] 行，抄参考答案\n" +
                    "4️⃣ 锁区词→粘贴保存\n" +
                    "5️⃣ 重复3次以上：长地址、短地址各来一次，数值稳定才填；波动大取最外圈（宁大勿小）\n" +
                    "6️⃣ 标定词→关，正常跑单"
                ).setPositiveButton("懂了", null).show()
            }
            R.id.action_ringtone -> {
                // 原有：铃声库自选
                try {
                    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "选响铃声")
                    }
                    startActivityForResult(intent, 2001)
                } catch (t: Throwable) {
                    Toast.makeText(this, "打不开铃声选择器", Toast.LENGTH_SHORT).show()
                }
            }
            R.id.action_sentry_delay -> {
                // 原有：哨兵岗延时
                delayDialog("哨兵岗", "sentry_min", "sentry_max", 200, 800)
            }
            R.id.action_sniper_delay -> {
                // 原有：狙击岗延时
                delayDialog("狙击岗", "sniper_min", "sniper_max", 100, 500)
            }
            R.id.action_permission -> {
                // 原有：开权限
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (t: Throwable) {
                    Toast.makeText(this, "打不开无障碍设置", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return super.onOptionsItemSelected(item)
    }

    // 延时区间设置弹窗（原有逻辑）
    private fun delayDialog(title: String, minKey: String, maxKey: String, defMin: Int, defMax: Int) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etMin = EditText(this).apply {
            hint = "最低(ms)"
            setText(prefs.getInt(minKey, defMin).toString())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val etMax = EditText(this).apply {
            hint = "最高(ms)"
            setText(prefs.getInt(maxKey, defMax).toString())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        layout.addView(etMin); layout.addView(etMax)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("延时随机区间（毫秒）")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                val mn = etMin.text.toString().toIntOrNull()
                val mx = etMax.text.toString().toIntOrNull()
                if (mn != null && mx != null && mn in 0 until mx) {
                    prefs.edit().putInt(minKey, mn).putInt(maxKey, mx).apply()
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "格式错：须 0 ≤ 最低 < 最高", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null).show()
    }

    // 铃声选择回执
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK && data != null) {
            val uri = data.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                prefs.edit().putString("alert_uri", uri.toString()).apply()
                Toast.makeText(this, "铃声已换", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 通知权限（原样）
    private fun requestNotiPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }
}
