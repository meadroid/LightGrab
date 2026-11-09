package com.light.grab

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var sp: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        sp = getSharedPreferences("lightdot", Context.MODE_PRIVATE)

        val spinApp = findViewById<Spinner>(R.id.spinTargetApp)
        val etKeyword = findViewById<EditText>(R.id.etKeyword)
        val etSentry = findViewById<EditText>(R.id.etSentryDelay)
        val etSniper = findViewById<EditText>(R.id.etSniperDelay)
        val tvAcc = findViewById<TextView>(R.id.tvAccPerm)
        val tvNoti = findViewById<TextView>(R.id.tvNotiPerm)

        // 目标应用：常用列表 + 可自由选（第一项「全部应用」）
        val apps = listOf("全部应用", "微信", "美团", "饿了么", "滴滴", "货拉拉")
        spinApp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, apps)
        sp.getString("target_app", "全部应用")?.let { saved ->
            apps.indexOf(saved).takeIf { it >= 0 }?.let { spinApp.setSelection(it) }
        }

        etKeyword.setText(sp.getString("keyword", ""))
        etSentry.setText(sp.getInt("sentry_delay", 500).toString())
        etSniper.setText(sp.getInt("sniper_delay", 200).toString())

        val accOk = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        tvAcc.text = "无障碍权限：" + if (accOk) "✓ 已开启" else "未开启（点下方保存后去菜单开启）"

        val notiOk = Settings.Secure.getInt(
            contentResolver, "enabled_notification_listeners", 0
        ) != 0
        tvNoti.text = "通知监听权限：" + if (notiOk) "✓ 已开启" else "未开启"
        tvNoti.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val sentry = etSentry.text.toString().toIntOrNull() ?: 500
            val sniper = etSniper.text.toString().toIntOrNull() ?: 200
            sp.edit()
                .putString("target_app", spinApp.selectedItem.toString())
                .putString("keyword", etKeyword.text.toString().trim())
                .putInt("sentry_delay", sentry.coerceIn(0, 10000))
                .putInt("sniper_delay", sniper.coerceIn(0, 10000))
                .apply()
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
