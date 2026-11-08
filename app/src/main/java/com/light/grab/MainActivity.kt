package com.light.grab

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var pkg: EditText
    private lateinit var dialogKw: EditText
    private lateinit var btnKw: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvPkg: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        // 初始化配置中心（读持久化设置，GrabService 共用）
        Config.init(applicationContext)

        pkg = findViewById(R.id.etPkg)
        dialogKw = findViewById(R.id.etDialog)
        btnKw = findViewById(R.id.etBtn)
        tvStatus = findViewById(R.id.tvStatus)
        tvPkg = findViewById(R.id.tvPkg)
        tvLog = findViewById(R.id.tvLog)
        tvLog.movementMethod = ScrollingMovementMethod()

        // 恢复上次配置
        pkg.setText(Config.targetPkg)
        dialogKw.setText(Config.dialogKeywords.joinToString(","))
        btnKw.setText(Config.btnKeywords.joinToString(","))
        tvPkg.text = "目标：${Config.targetPkg}"

        // 应用点选
        findViewById<Button>(R.id.btnPick).setOnClickListener { pickApp() }

        // 启动
        findViewById<ImageButton>(R.id.btnStart).setOnClickListener {
            saveAndApply()
            ensurePerms()
        }

        // 停止：引导用户去无障碍设置页关闭服务（系统不允许 App 静默关闭无障碍）
        findViewById<ImageButton>(R.id.btnStop).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请在无障碍页关闭本应用服务", Toast.LENGTH_LONG).show()
        }

        // 一键授权
        findViewById<ImageButton>(R.id.btnAuth).setOnClickListener { ensurePerms() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        // 注册日志钩子：服务日志实时显示到界面日志区
        GrabService.logHook = { msg -> runOnUiThread { log(msg) } }
        refreshStatus()
    }

    override fun onPause() {
        super.onPause()
        // 退出界面时撤钩子，服务自动退回 Toast 提示
        GrabService.logHook = null
    }

    private fun refreshStatus() {
        val on = isAccOn()
        if (on) {
            tvStatus.text = "● 运行中"
            tvStatus.setTextColor(0xFF2E7D32.toInt())
            findViewById<View>(R.id.statusCard).setBackgroundColor(0xFFE8F5E9.toInt())
        } else {
            tvStatus.text = "● 已停止"
            tvStatus.setTextColor(0xFFC62828.toInt())
            findViewById<View>(R.id.statusCard).setBackgroundColor(0xFFFBE9E7.toInt())
        }
        tvPkg.text = "目标：${Config.targetPkg}"
    }

    private fun saveAndApply() {
        val p = pkg.text.toString().trim().ifEmpty { Config.targetPkg }
        val dText = dialogKw.text.toString().ifBlank { Config.dialogKeywords.joinToString(",") }
        val bText = btnKw.text.toString().ifBlank { Config.btnKeywords.joinToString(",") }

        // 写入 Config：立即生效（GrabService 实时读）+ 自动持久化
        Config.setTargetPkg(p)
        Config.setDialogKeywords(dText)
        Config.setBtnKeywords(bText)

        log("设置已保存 → ${Config.targetPkg}")
    }

    /** 列出已安装应用，点选即填包名 */
    private fun pickApp() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { Pair(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.first }

        val names = apps.map { "${it.first}  (${it.second})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择目标应用")
            .setItems(names) { _, i ->
                pkg.setText(apps[i].second)
                saveAndApply()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun ensurePerms() {
        // 1. 无障碍
        if (!isAccOn()) {
            Toast.makeText(this, "请开启「轻单助手」无障碍服务", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        // 2. 使用情况
        if (!hasUsage()) {
            Toast.makeText(this, "请授予使用情况访问权限", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        // 3. 通知（Android 13+）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refreshStatus()
        Toast.makeText(this, "✅ 已就绪，去目标应用等弹窗吧", Toast.LENGTH_SHORT).show()
    }

    private fun isAccOn(): Boolean {
        val expected = ComponentName(this, GrabService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any {
            it.equals(expected, true) ||
            it.equals("$packageName/." + expected.substringAfter('/'), true)
        } == true
    }

    private fun hasUsage(): Boolean = try {
        AppOpsCheck.check(this)
    } catch (e: Exception) { false }

    private object AppOpsCheck {
        fun check(ctx: android.content.Context): Boolean = try {
            val mode = android.app.AppOpsManager::class.java
                .getMethod("checkOpNoThrow", Integer.TYPE, Integer.TYPE, String::class.java)
                .invoke(ctx.getSystemService(android.app.AppOpsManager::class.java),
                    43, android.os.Binder.getCallingUid(), ctx.packageName)
            mode.toString() == "MODE_ALLOWED" || mode == 0
        } catch (e: Exception) { true }
    }

    private fun log(msg: String) {
        tvLog.append("\n[$now] $msg")
    }

    private val now: String
        get() = java.text.SimpleDateFormat("HH:mm:ss",
            java.util.Locale.CHINA).format(java.util.Date())
}
