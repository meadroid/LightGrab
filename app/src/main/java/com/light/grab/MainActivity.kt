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
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val sp by lazy { getSharedPreferences("qd", Context.MODE_PRIVATE) }
    private lateinit var logBox: TextView
    private lateinit var stateMode: TextView
    private lateinit var stateToday: TextView
    private lateinit var stateLock: TextView
    private val logs = StringBuilder()
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {   // 仅刷新UI数字，非保活
        override fun run() {
            stateToday.text = "🕐 今日：命中 ${GrabService.hit} 发"
            h.postDelayed(this, 2000)
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        GrabService.feature = sp.getString("feature", "立即上门单")!!
        GrabService.btnWord = sp.getString("btn", "确认抢单")!!
        GrabService.lockRect = parseLock(sp.getString("lock", "") ?: "")

        buildUi()
        h.post(tick)
        askBattery()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        // 标题行 + 齿轮
        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "轻点"; textSize = 28f
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(this@MainActivity).apply {
                text = "⚙"; setOnClickListener { showSettings() }
            })
        }

        // 模式四选
        val modeRow = LinearLayout(this).apply {
            listOf("影子", "哨兵", "狙击", "静默").forEach { m ->
                addView(Button(this@MainActivity).apply {
                    text = m; setOnClickListener {
                        stateMode.text = "♟ 模式：$m 待启动"
                        toast("已选 $m（当前版本统一影子逻辑）")
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        val toggle = Button(this).apply {
            text = "开启监听"; textSize = 18f
            setOnClickListener {
                if (!svcOn()) {
                    toast("请先开启无障碍服务")
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } else {
                    stateMode.text = "♟ 模式：影子 监听中"
                    toast("服务在岗")
                }
            }
        }

        stateMode = TextView(this).apply { text = "♟ 模式：影子 待启动"; textSize = 16f }
        stateToday = TextView(this).apply { text = "🕐 今日：命中 ${GrabService.hit} 发"; textSize = 16f }
        stateLock = TextView(this).apply { textSize = 16f; refreshLock() }
        logBox = TextView(this).apply {
            textSize = 12f
            setPadding(20, 20, 20, 20)
            setBackgroundColor(0xFF122211.toInt())
        }

        root.addView(titleRow)
        root.addView(modeRow)
        root.addView(toggle)
        root.addView(stateMode)
        root.addView(stateToday)
        root.addView(stateLock)
        root.addView(logBox)
        setContentView(root)
    }

    private fun refreshLock() {
        stateLock.text = "🏆 累计 ${GrabService.hit} 发  🎯锁区：" +
            if (GrabService.lockRect != null) "已配" else "未配"
    }

    private fun svcOn() =
        (getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
            .any { it.resolveInfo.serviceInfo.name == GrabService::class.java.name }

    // ── 设置弹窗：特征词/按钮词/锁区px ──
    private fun showSettings() {
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 30, 60, 0)
        }
        fun label(s: String) = box.addView(TextView(ctx).apply { text = s })
        val in1 = EditText(ctx).apply { setText(sp.getString("feature", "立即上门单")) }
        val in2 = EditText(ctx).apply { setText(sp.getString("btn", "确认抢单")) }
        val in3 = EditText(ctx).apply {
            setText(sp.getString("lock", ""))
            hint = "px 绝对坐标 左,上,宽,高 如 565,1425,470,110"
        }
        label("特征词"); box.addView(in1)
        label("按钮词"); box.addView(in2)
        label("锁区(px 屏幕绝对坐标)"); box.addView(in3)

        AlertDialog.Builder(ctx)
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
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) { /* 部分ROM不支持，忽略 */ }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        // 服务→界面日志通道
        fun pushLog(s: String) { /* 界面刷新由 tick 轮询统计行，日志靠Logcat */ }
    }
}
