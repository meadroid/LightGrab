package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class GrabService : AccessibilityService() {

    companion object {
        @Volatile var logHook: ((String) -> Unit)? = null
    }

    private var lastBeat = 0L
    private var lastClick = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        log("✅ 服务启动")
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg != "com.sfexpress.knight") return

        // 心跳：5秒限流，证明事件在流动
        val now = System.currentTimeMillis()
        if (now - lastBeat > 5000) { lastBeat = now; log("👁 收到顺丰事件") }

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.sfexpress.knight") return

        val nodes = try {
            root.findAccessibilityNodeInfosByText("确认抢单")
        } catch (ex: Exception) { return }

        for (n in nodes) {
            if (n?.text != "确认抢单" || !n.isVisibleToUser) continue
            if (System.currentTimeMillis() - lastClick < 1000) return
            lastClick = System.currentTimeMillis()
            log("🔥 发现按钮，点击")
            val ok = clickIt(n)
            log(if (ok) "✅ 已点击" else "⚠️ 点击失败")
            return
        }
    }

    private fun clickIt(n: AccessibilityNodeInfo): Boolean {
        if (n.isClickable) {
            return try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) { false }
        }
        var p = n.parent
        while (p != null) {
            if (p.isClickable) {
                return try { p.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) { false }
            }
            p = p.parent
        }
        return false
    }

    private fun log(msg: String) {
        try { logHook?.invoke(msg) } catch (ex: Exception) {}
    }
}
