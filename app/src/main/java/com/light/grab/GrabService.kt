package com.light.grab   // ← 和你的 Config.kt 第一行保持一致

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
    }

    private var lastClick = 0L
    private var pending = false
    private var lastDebugReport = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        report("服务已启动")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return

        // ---- 包名过滤 ----
        if (pkg != Config.targetPkg) {
            report("[$pkg] 不匹配")
            return
        }

        val now = System.currentTimeMillis()

        // ===== 诊断1：顺丰事件进来了就报，3秒限一次 =====
        if (now - lastDebugReport > 3000) {
            lastDebugReport = now
            report("✅顺丰事件 type=${e.eventType}")
        }
        // ===== 诊断1结束 =====

        if (pending) return
        if (now - lastClick < 3000) return   // 点击后3秒内防抖

        // ---- 收集所有窗口根节点 ----
        val roots = ArrayList<AccessibilityNodeInfo?>()
        try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
        try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}

        // ---- 弹窗关键词命中检测（列表里任意一个词命中即触发）----
        var hitDialogKw: String? = null
        for (root in roots) {
            if (root == null) continue
            for (kw in Config.dialogKeywords) {
                if (findByText(root, kw)) {
                    hitDialogKw = kw
                    break
                }
            }
            if (hitDialogKw != null) break
        }

        if (hitDialogKw == null) return

        // ===== 诊断2：报告哪个窗口命中了 =====
        try {
            val sb = StringBuilder("窗口:")
            roots.forEachIndexed { i, r ->
                val hit = r?.let { rr ->
                    Config.dialogKeywords.any { findByText(rr, it) }
                } ?: false
                sb.append("$i=${if (hit) "✓" else if (r == null) "空" else "×"};")
            }
            report(sb.toString())
        } catch (ex: Exception) {}
        // ===== 诊断2结束 =====

        report("发现弹窗关键词[$hitDialogKw]")
        pending = true

        // ---- 随机延迟后点击 ----
        val delay = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        Thread {
            Thread.sleep(delay)
            doClick()
        }.start()
    }

    // ---------- 点击：5次重试，节点点击+手势双保险 ----------

    private fun doClick() {
        var done = false
        var hitBtnKw = ""
        try {
            val roots = ArrayList<AccessibilityNodeInfo?>()
            try { roots.add(rootInActiveWindow) } catch (ex: Exception) {}
            try { windows?.forEach { w -> roots.add(w.root) } } catch (ex: Exception) {}

            for (i in 1..5) {
                outer@ for (root in roots) {
                    if (root == null) continue
                    for (kw in Config.btnKeywords) {
                        val nodes = root.findAccessibilityNodeInfosByText(kw)
                        if (nodes != null && nodes.isNotEmpty()) {
                            val btn = nodes[nodes.size - 1]   // 按钮一般在列表最后
                            val rect = Rect()
                            btn.getBoundsInScreen(rect)
                            lastClick = System.currentTimeMillis()
                            var ok = false
                            try { ok = btn.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) {}
                            if (!ok) { try { scatterClick(rect) } catch (ex: Exception) {} }
                            try { btn.recycle() } catch (ex: Exception) {}
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

        if (done) {
            report("点[$hitBtnKw] 完成")
        } else {
            report("没找到按钮${Config.btnKeywords}")
        }
        pending = false
    }

    private fun findByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val nodes = root.findAccessibilityNodeInfosByText(text)
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
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
