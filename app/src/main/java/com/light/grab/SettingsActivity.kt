package com.light.grab

import android.content.Intent
import android.content.SharedPreferences
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

    private val appNames = listOf("顺丰骑士", "美团骑手", "全部应用")
    private val appKeys = MainActivity.APPS.values.toList() + "all"
    private var appIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sp = getSharedPreferences("lightdot", MODE_PRIVATE)
        appIndex = sp.getInt("app_index", appKeys.size - 1) // 默认全部应用

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(20))
            setBackgroundColor(getColor(R.color.bgDark))
        }

        val title = TextView(this).apply {
            text = "设置"
            textSize = 22f
            setTextColor(getColor(R.color.mint))
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(title)

        tvApp = makeRow(root, "目标应用")
        tvKeyword = makeRow(root, "关键词")
        tvSentryDelay = makeRow(root, "哨兵延时")
        tvSniperDelay = makeRow(root, "狙击延时")
        val tvPerm = makeRow(root, "无障碍权限")

        refreshRows()

        tvApp.setOnClickListener {
            appIndex = (appIndex + 1) % appKeys.size
            sp.edit().putInt("app_index", appIndex).apply()
            refreshRows()
        }

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

        tvSentryDelay.setOnClickListener { askDelay("哨兵延时（毫秒）", "sentry_delay") }
        tvSniperDelay.setOnClickListener { askDelay("狙击延时（毫秒）", "sniper_delay") }

        tvPerm.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        setContentView(root)
    }

    private fun askDelay(title: String, key: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "0 - 10000 毫秒"
            setText(sp.getInt(key, 0).toString())
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text.toString().toIntOrNull()?.coerceIn(0, 10000) ?: 0
                sp.edit().putInt(key, v).apply()
                refreshRows()
                Toast.makeText(this, "已保存 ${v}ms", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshRows() {
        tvApp.text = "目标应用\n${appNames.getOrElse(appIndex) { "全部应用" }}"
        val kw = sp.getString("keyword", "")?.takeIf { it.isNotEmpty() } ?: "抢单 / 接单 / 立即抢"
        tvKeyword.text = "关键词\n$kw"
        tvSentryDelay.text = "哨兵延时\n${sp.getInt("sentry_delay", 500)} 毫秒"
        tvSniperDelay.text = "狙击延时\n${sp.getInt("sniper_delay", 0)} 毫秒"
        tvSniperDelay.post { } // noop
    }

    private fun makeRow(root: LinearLayout, label: String): TextView {
        val tv = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.grayText))
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = getDrawable(android.R.drawable.dialog_halo_light)
            setBackgroundColor(getColor(R.color.grayChip))
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
