package com.light.grab

import android.Manifest
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var pkg: EditText
    private lateinit var dialogKw: EditText
    private lateinit var btnKw: EditText

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        // 初始化配置中心（读持久化设置，GrabService 和这里共用同一份）
        Config.init(applicationContext)

        pkg = findViewById(R.id.pkg)
        dialogKw = findViewById(R.id.dialog_kw)
        btnKw = findViewById(R.id.btn_kw)

        // 从 Config 恢复上次配置（不再单独存 SharedPreferences，统一走 Config）
        pkg.setText(Config.targetPkg)
        dialogKw.setText(Config.dialogKeywords.joinToString(","))
        btnKw.setText(Config.btnKeywords.joinToString(","))

        // 应用点选
        findViewById<Button>(R.id.pick).setOnClickListener { pickApp() }

        // 启动
        findViewById<Button>(R.id.go).setOnClickListener {
            saveAndApply()
            ensurePerms()
        }
    }

    private fun saveAndApply() {
        val p = pkg.text.toString().trim().ifEmpty { Config.targetPkg }
        val dText = dialogKw.text.toString().ifBlank { Config.dialogKeywords.joinToString(",") }
        val bText = btnKw.text.toString().ifBlank { Config.btnKeywords.joinToString(",") }

        // 全部写入 Config：立即生效（GrabService 实时读）+ 自动持久化
        Config.setTargetPkg(p)
        Config.setDialogKeywords(dText)
        Config.setBtnKeywords(bText)

        Toast.makeText(this,
            "目标：${Config.targetPkg}\n弹窗词：${Config.dialogKeywords.joinToString("/")}\n按钮：${Config.btnKeywords.joinToString("/")}",
            Toast.LENGTH_LONG).show()
    }

    /** 列出已安装应用，点选即填包名 */
    private fun pickApp() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { Triple(
                pm.getApplicationLabel(it).toString(),
                it.packageName,
                pm.getApplicationIcon(it)) }
            .sortedBy { it.first }

        val names = apps.map { "${it.first}  (${it.second})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择目标应用")
            .setItems(names) { _, i ->
                pkg.setText(apps[i].second)
                Toast.makeText(this, "已选：${apps[i].first}", Toast.LENGTH_SHORT).show()
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
        // 3. 悬浮窗
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请允许显示悬浮窗", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        // 4. 通知（Android 13+，前台服务通知必需）
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        startService(Intent(this, FloatService::class.java))
        Toast.makeText(this, "✅ 已启动，去目标应用等弹窗吧", Toast.LENGTH_SHORT).show()
    }

    private fun isAccOn(): Boolean {
        val expected = ComponentName(this, GrabService::class.java).flattenToString()
        return Settings.Secure.getString(contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any {
            it.equals(expected, true) || it.endsWith(":$expected", true) ||
            it.equals("$packageName/".plus(expected.substringAfter('/')), true)
        } == true
    }

    private fun hasUsage(): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0).also {
                AppOpsCheck.check(this)
            } != null && AppOpsCheck.check(this)
        } catch (e: Exception) { false }
    }

    private object AppOpsCheck {
        fun check(ctx: android.content.Context): Boolean = try {
            val mode = android.app.AppOpsManager::class.java
                .getMethod("checkOpNoThrow", Integer.TYPE, Integer.TYPE, String::class.java)
                .invoke(ctx.getSystemService(android.app.AppOpsManager::class.java),
                    43, android.os.Binder.getCallingUid(), ctx.packageName)
            mode.toString() == "MODE_ALLOWED" || mode == 0
        } catch (e: Exception) { true }
    }
}
