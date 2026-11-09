package com.light.grab

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sp: SharedPreferences
    private lateinit var tvApp: TextView
    private lateinit var tvKeyword: TextView
    private lateinit var tvSentryDelay: TextView
    private lateinit var tvSniperDelay: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sp = getSharedPreferences("lightdot", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(Color.parseColor("#121A16"))
        }

        val title = TextView(this).apply {
            text = "设置"
            textSize = 22f
            setTextColor(Color.parseColor("#6FD6A8"))
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(title)

        tvApp = makeRow(root, "目标应用")
        tvKeyword = makeRow(root, "关键词")
        tvSentryDelay = makeRow(root, "哨兵延时")
        tvSniperDelay = makeRow(root, "狙击延时")
        val tvPerm = makeRow(root, "无障碍权限")

        refreshRows()

        // 自选手机上的应用
        tvApp.setOnClickListener { askApp() }

        tvKeyword.setOnClickListener {
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
                    refreshRows()
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        tvSentryDelay.setOnClickListener {
            askDelay("哨兵延时（最低~最高）", "sentry_delay_min", "sentry_delay_max")
        }
        tvSniperDelay.setOnClickListener {
            askDelay("狙击延时（最低~最高）", "sniper_delay_min", "sniper_delay_max")
        }

        tvPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setContentView(root)

        // 菜单二级直连：自动弹出对应设置
        when (intent.getStringExtra("open")) {
            "app"     -> askApp()
            "keyword" -> tvKeyword.performClick()
            "sentry"  -> tvSentryDelay.performClick()
            "sniper"  -> tvSniperDelay.performClick()
        }
    }

    /** 列出手机上所有可启动的应用，供选择 */
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
                refreshRows()
                Toast.makeText(this, "已选择 ${apps[which].first}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 双输入框：最低值 + 最高值，区间内随机延时 */
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
            setPadding(dp(24), dp(10), dp(24), 0)
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
                refreshRows()
                Toast.makeText(this, "已保存 ${min}~${max}ms", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshRows() {
        val appName = sp.getString("app_name", null) ?: "全部应用"
        tvApp.text = "目标应用\n$appName"
        val kw = sp.getString("keyword", "")?.takeIf { it.isNotEmpty() } ?: "抢单 / 接单 / 立即抢"
        tvKeyword.text = "关键词\n$kw"
        tvSentryDelay.text = "哨兵延时\n${sp.getInt("sentry_delay_min", 300)} ~ ${sp.getInt("sentry_delay_max", 800)} 毫秒"
        tvSniperDelay.text = "狙击延时\n${sp.getInt("sniper_delay_min", 0)} ~ ${sp.getInt("sniper_delay_max", 200)} 毫秒"
    }

    private fun makeRow(root: LinearLayout, label: String): TextView {
        val tv = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#B8C4BD"))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = ColorDrawable(Color.parseColor("#1E2A24"))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(0, 0, 0, dp(12))
        tv.layoutParams = lp
        root.addView(tv)
        return tv
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
