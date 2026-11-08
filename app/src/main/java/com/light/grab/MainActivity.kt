package com.light.grab

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
        val pkg = findViewById<EditText>(R.id.pkg).apply {
            setText(cfg.getString("pkg", GrabService.targetPkg))
        }

        findViewById<Button>(R.id.go).setOnClickListener {
            val p = pkg.text.toString().trim()
            cfg.edit().putString("pkg", p).apply()
            GrabService.targetPkg = p

            when {
                !accessibilityOn(this) ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                !usageOn() ->
                    startActivity(Intent("android.settings.USAGE_ACCESS_SETTINGS"))
                !Settings.canDrawOverlays(this) ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                else -> {
                    startForegroundService(Intent(this, FloatService::class.java))
                    Toast.makeText(this, "✅ 就绪：仅拦截弹窗确认键",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Android 10+ 用新 API，9 及以下用旧 API，全版本兼容不闪退 */
    private fun usageOn(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        fun accessibilityOn(c: Context): Boolean {
            val am = c.getSystemService(AccessibilityManager::class.java)
            return am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { it.resolveInfo.serviceInfo.packageName == c.packageName }
        }
    }
}
