package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        @Volatile var instance: GrabService? = null
        @Volatile var running = false   // FloatService 悬浮球靠它变色
    }

    private var lastClick = 0L
    private var pending = false
    private var lastPkgToast = 0L

    // Toast 总开关：测试期 true，确认能用后改 false 全静音
    private val DEBUG = true

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        running = true
        report("服务已启动")
    }

    override fun onDestroy() {
        instance = null
        running = false
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return

        // ---- 探针1：报告每个事件来源（DEBUG期专用）----
        if (DEBUG && System.currentTimeMillis() - lastPkgToast > 5000) {
            lastPkgToast = System.currentTimeMillis()
            report("事件来源: $pkg")
        }

        if (pkg != Config.targetPkg) return   // 非顺丰，静默跳过
        if (pending) return
        if (System.currentTimeMillis() - lastClick < 3000) return

        // ---- 收集所有窗口根节点 ----
        val roots = ArrayList<AccessibilityNodeInfo?>()
        try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
        try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}

        // ---- 弹窗关键词检测 ----
        var hit: String? = null
        for (root in roots) {
            if (root == null) continue
            for (kw in Config.dialogKeywords) {
                if (findByText(root, kw)) { hit = kw; break }
            }
            if (hit != null) break
        }
        if (hit == null) return

        report("发现弹窗关键词[$hit]")
        pending = true

        val delay = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        Thread { Thread.sleep(delay); doClick() }.start()
    }

    // ---------- 点击：5次重试，节点点击+手势兜底 ----------

    private fun doClick() {
        var done = false
        var hitBtnKw = ""
        try {
            for (i in 1..5) {
                val roots = ArrayList<AccessibilityNodeInfo?>()
                try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
                try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}

                outer@ for (root in roots) {
                    if (root == null) continue
                    for (kw in Config.btnKeywords) {
                        val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null }
                        if (nodes != null && nodes.isNotEmpty()) {
                            val btn = nodes[nodes.size - 1]
                            val rect = Rect()
                            btn.getBoundsInScreen(rect)
                            lastClick = System.currentTimeMillis()
                            var ok = false
                            try { ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) {}
                            if (!ok) { try { scatterClick(rect) } catch (ex: Exception) {} }
                            done = true
                            hitBtnKw = kw
                            break@outer
                        }
                    }
                }
                if (done) break
                Thread.sleep(200)
            }
        } catch (ex: Exception) {}

        report(if (done) "点[$hitBtnKw] 完成✅" else "没找到按钮${Config.btnKeywords}")
        pending = false
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (ex: Exception) { null }
        return nodes != null && nodes.isNotEmpty()
    }

    private fun scatterClick(rect: Rect) {
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun report(msg: String) {
        if (!DEBUG) return
        try { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } catch (ex: Exception) {}
    }
}
