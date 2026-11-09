package com.light.grab

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.Rect
import kotlin.random.Random

class GrabService : AccessibilityService() {

    companion object {
        @Volatile var instance: GrabService? = null
        @Volatile var logHook: ((String) -> Unit)? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("服务已启动 ✅ 纯事件驱动")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ==================== 纯事件驱动：系统叫一声，看一眼 ====================

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        // 只关心顺丰的界面变化事件
        if (e?.packageName?.toString() != Config.targetPkg) return
        when (e.eventType) {
            // 窗口出现/变化/内容变化 —— 弹窗弹出时必发这几种
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_APPEARED -> detect()
        }
    }

    // ==================== 检测 + 点击 ====================

    private fun detect() {
        val root = try { rootInActiveWindow } catch (ex: Exception) { null } ?: return
        if (root.packageName != Config.targetPkg) return

        // 弹窗关键词必须在屏幕上
        if (findVisible(root, Config.dialogKeywords) == null) return
        // 按钮关键词也必须在屏幕上
        if (findVisible(root, Config.btnKeywords) == null) return

        val delay = Random.nextLong(Config.delayMin.toLong(), Config.delayMax.toLong() + 1)
        log("🔥 发现弹窗，${delay}ms 后点击...")
        Thread {
            try { Thread.sleep(delay) } catch (ex: Exception) {}
            clickButton()
        }.start()
    }

    private fun clickButton() {
        val root = try { rootInActiveWindow } catch (ex: Exception) { null } ?: return
        for (kw in Config.btnKeywords) {
            val nodes = try { root.findAccessibilityNodeInfosByText(kw) } catch (ex: Exception) { null }
            if (nodes != null) {
                for (n in nodes) {
                    if (isVisibleOnScreen(n)) {
                        tapButton(n)
                        log("点[$kw] 完成 ✅")
                        return
                    }
                }
            }
        }
        log("⚠️ 未找到可见按钮")
    }

    /** 节点自点 → 爬可点击的父节点 → 坐标手势保底 */
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

    private fun log(msg: String) {
        val hook = logHook
        if (hook != null) { try { hook(msg) } catch (ex: Exception) {} }
    }
}
