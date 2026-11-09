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
        private var lastProbeLog = 0L
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        log("🩺 探针版启动 ✅")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg != Config.targetPkg) return
        val type = when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "窗口变化"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "内容变化"
            else -> return
        }

        // ★ 探针1：事件进来了没有？限流5秒一条，防刷屏
        val now = System.currentTimeMillis()
        if (now - lastProbeLog > 5000) {
            lastProbeLog = now
            val root = try { rootInActiveWindow } catch (ex: Exception) { null }
            log("📡 事件[$type] 根节点=${root?.packageName ?: "NULL❌"}")
        }

        detect()
    }

    private fun detect() {
        val root = try { rootInActiveWindow } catch (ex: Exception) { null } ?: run {
            // ★ 探针2：事件来了但拿不到根 —— 这是B病实锤
            log("🕳️ 事件在，但活跃窗口取不到（rootInActiveWindow=NULL）")
            return
        }
        if (root.packageName != Config.targetPkg) {
            // ★ 探针3：活跃窗口是别的包 —— 顺丰后台，窗口失焦
            log("🙈 活跃窗口是[${root.packageName}]，不是顺丰")
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
