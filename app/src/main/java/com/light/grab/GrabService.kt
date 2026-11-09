package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        @Volatile var instance: GrabService? = null
        @Volatile var logHook: ((String) -> Unit)? = null
        private var lastProbeLog = 0L
        private var lastClick = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("服务已启动 ✅ 纯事件驱动+窗口搜索")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ==================== 纯事件驱动 ====================

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // 多包名：列表里任何一个命中都算
        if (!Config.isTarget(e?.packageName?.toString())) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> detect()
        }
    }

    // ==================== 检测：不靠焦点窗口，靠"找到顺丰的窗口" ====================

    private fun detect() {
        val now = System.currentTimeMillis()
        if (now - lastClick < 300) return

        val root = findSfRoot() ?: run {
            probe("🙈 顺丰窗口不在窗口列表中（可能已退出）")
            return
        }

        if (findVisible(root, Config.dialogKeywords) == null) return
        if (findVisible(root, Config.btnKeywords) == null) return

        val delay = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        log("🔥 发现弹窗，${delay}ms 后点击...")
        Thread {
            try { Thread.sleep(delay) } catch (ex: Exception) {}
            clickButton()
        }.start()
    }

    /**
     * ★ 核心：遍历系统窗口列表，挑出包名=目标的窗口根节点。
     * 不管目标 App 在前台还是后台、焦点在谁手上，只要它的窗口还活着就能读到。
     */
    private fun findSfRoot(): AccessibilityNodeInfo? {
        // 优先：活跃窗口正好是目标，直接用（最快路径）
        try {
            val active = rootInActiveWindow
            if (active != null && Config.isTarget(active.packageName?.toString())) return active
        } catch (ex: Exception) {}
        // 兜底：在全部窗口里搜目标的
        try {
            windows?.forEach { w: AccessibilityWindowInfo ->
                val r = w.root
                if (r != null && Config.isTarget(r.packageName?.toString())) return r
            }
        } catch (ex: Exception) {}
        return null
    }

    private fun clickButton() {
        val root = findSfRoot() ?: return
        for (kw in Config.btnKeywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null }
            if (nodes != null) {
                for (n in nodes) {
                    if (isVisibleOnScreen(n)) {
                        lastClick = System.currentTimeMillis()
                        tapButton(n)
                        log("点[$kw] 完成 ✅")
                        return
                    }
                }
            }
        }
        log("⚠️ 未找到可见按钮")
    }

    private fun tapButton(btn: AccessibilityNodeInfo) {
        val rect = Rect()
        btn.getBoundsInScreen(rect)
        var target: AccessibilityNodeInfo? = if (btn.isClickable) btn else null
        if (target == null) {
            var p = btn.parent
            var hops = 0
            while (p != null && hops < 5) {
                if (p.isClickable) { target = p; break }
                p = p.parent; hops++
            }
        }
        var ok = false
        if (target != null) {
            try { ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (ex: Exception) {}
        }
        if (!ok && rect.width() > 0 && rect.height() > 0) {
            try { scatterClick(rect) } catch (ex: Exception) {}
        }
    }

    // ==================== 工具 ====================

    private fun findVisible(root: AccessibilityNodeInfo, keywords: List<String>): String? {
        for (kw in keywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null }
            if (nodes != null) {
                for (n in nodes) if (isVisibleOnScreen(n)) return kw
            }
        }
        return null
    }

    private fun isVisibleOnScreen(node: AccessibilityNodeInfo): Boolean {
        try {
            if (!node.isVisibleToUser) return false
            val r = Rect()
            node.getBoundsInScreen(r)
            return r.width() > 0 && r.height() > 0
        } catch (ex: Exception) { return false }
    }

    private fun scatterClick(rect: Rect) {
        val cx = rect.centerX().toFloat()
        val cy = rect.centerY().toFloat()
        val path = android.graphics.Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        dispatchGesture(
            android.accessibilityservice.GestureDescription.Builder()
                .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 50))
                .build(),
            null, null
        )
    }

    /** 探针限流：同一句话 5 秒内最多一条 */
    private fun probe(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastProbeLog > 5000) {
            lastProbeLog = now
            log(msg)
        }
    }

    private fun log(msg: String) {
        val hook = logHook
        if (hook != null) { try { hook(msg) } catch (ex: Exception) {} }
    }
}
