package com.light.grab

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val sp by lazy { getSharedPreferences("qd", Context.MODE_PRIVATE) }
    private lateinit var logBox: TextView
    private lateinit var stateMode: TextView
    private lateinit var stateToday: TextView
    private lateinit var stateLock: TextView
    private val modeBtns = mutableListOf<Button>()
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            stateToday.text = "🕐 今日：命中 ${GrabService.hit} 发"
            h.postDelayed(this, 2000)
        }
    }

    // 色板
    private val ink = -0x22181F1F      // 前景文字
    private val grayText = -0x755C6D71
    private val mint = -0x85B94E2A
    private val chipBg = -0xF0E6ECDD.toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        try { build() } catch (e: Throwable) {
            AlertDialog.Builder(this)
                .setTitle("启动失败·原因")
                .setMessage(e.toString())
                .setPositiveButton("好", null).show()
        }
    }

    private fun chip(text: String): Button = Button(this).apply {
        this.text = text
        textSize = 13f
        setBackgroundColor(chipBg)
        setTextColor(grayText)
        setPadding(0, 20, 0, 20)
    }

    private fun selectMode(sel: Button, name: String) {
        modeBtns.forEach { setTextColor(grayText) }
        sel.setTextColor(mint)
        stateMode.text = "♟ 模式：$name 待启动"
        log("模式切换 → $name")
    }

    private fun build() {
        GrabService.feature = sp.getString("feature", "立即上门单")!!
        GrabService.btnWord = sp.getString("btn", "确认抢单")!!
        GrabService.lockRect = parseLock(sp.getString("lock", "") ?: "")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(-0xF1EAEEDF.toInt())
            setPadding(44, 90, 44, 44)
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "轻点"; textSize = 30f; setTextColor(grayText)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "⚙"; textSize = 26f
                setPadding(30, 10, 10, 10)
                setOnClickListener { showSettings() }
            })
        }

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("影子", "哨兵", "狙击", "静默").forEachIndexed { i, m ->
            val b = chip(m)
            b.setOnClickListener { selectMode(b, m) }
            b.layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { if (i < 3) marginEnd = 14 }
            modeBtns.add(b)
            modeRow.addView(b)
        }

        val toggle = chip("开启监听").apply {
            textSize = 17f
            setPadding(0, 34, 0, 34)
            setOnClickListener {
                if (!svcOn()) {
                    toast("请先开启无障碍服务")
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    stateMode.text = "♟ 模式：影子 监听中"
                    log("轻点已启动 · 监听中")
                    toast("服务在岗")
                }
            }
        }

        stateMode = TextView(this).apply {
            text = "♟ 模式：影子 待启动"; textSize = 16f; setTextColor(ink); setPadding(0, 40, 0, 8)
        }
        stateToday = TextView(this).apply {
            text = "🕐 今日：命中 0 发"; textSize = 16f; setTextColor(ink); setPadding(0, 8, 0, 8)
        }
        stateLock = TextView(this).apply { textSize = 16f; setTextColor(ink); setPadding(0, 8, 0, 8) }
        refreshLock()

        logBox = TextView(this).apply {
            textSize = 13f
            setTextColor(grayText)
            setPadding(28, 28, 28, 28)
            setBackgroundColor(-0xEDDFF5E6.toInt())
        }

        root.addView(titleRow)
        root.addView(modeRow)
        root.addView(toggle, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 30 })
        root.addView(stateMode)
        root.addView(stateToday)
        root.addView(stateLock)
        root.addView(ScrollView(this).apply { addView(logBox) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                .apply { topMargin = 20 })

        setContentView(root)
        h.post(tick)
        askBattery()
    }

    private fun log(s: String) {
        if (!this::logBox.isInitialized) return
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())
        logBox.append("$t  $s\n")
    }

    private fun refreshLock() {
        stateLock.text = "🏆 累计 ${GrabService.hit} 发  🎯锁区：" +
            (if (GrabService.lockRect != null) "已配" else "未配")
    }

    private fun svcOn() =
        (getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.name == GrabService::class.java.name }

    private fun showSettings() {
        val in1 = EditText(this).apply { setText(sp.getString("feature", "立即上门单")) }
        val in2 = EditText(this).apply { setText(sp.getString("btn", "确认抢单")) }
        val in3 = EditText(this).apply {
            setText(sp.getString("lock", ""))
            hint = "px 绝对坐标 左,上,宽,高 如 565,1425,470,110"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 0)
            addView(TextView(context).apply { text = "特征词" }); addView(in1)
            addView(TextView(context).apply { text = "按钮词" }); addView(in2)
            addView(TextView(context).apply { text = "锁区(px 屏幕绝对坐标)" }); addView(in3)
        }
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val f = in1.text.toString().trim()
                val w = in2.text.toString().trim()
                val l = in3.text.toString().trim()
                sp.edit().putString("feature", f).putString("btn", w).putString("lock", l).apply()
                GrabService.feature = f
                GrabService.btnWord = w
                GrabService.lockRect = parseLock(l)
                refreshLock()
                log("配置已保存")
                toast("已保存并生效")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun parseLock(s: String): Rect? = try {
        val p = s.split(",").map { it.trim().toInt() }
        if (p.size == 4) Rect(p[0], p[1], p[0] + p[2], p[1] + p[3]) else null
    } catch (e: Exception) { null }

    private fun askBattery() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
        } catch (e: Exception) { }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
