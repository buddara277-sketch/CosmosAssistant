
package com.cosmosa27.cosmosassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class CosAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CosAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        serviceInfo = serviceInfo.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Здесь позже Кос будет получать сведения об изменениях экрана.
    }

    override fun onInterrupt() {
        // Система прервала работу службы.
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun click(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)

        for (node in nodes) {
            var current: AccessibilityNodeInfo? = node

            while (current != null) {
                if (current.isClickable) {
                    return current.performAction(
                        AccessibilityNodeInfo.ACTION_CLICK
                    )
                }
                current = current.parent
            }
        }
        return false
    }

    fun enterText(viewId: String, text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)

        if (nodes.isEmpty()) return false

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        return nodes.first().performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )
    }

    fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        )
    }

    fun scrollBackward(): Boolean {
        val root = rootInActiveWindow ?: return false
        return root.performAction(
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun getScreenText(): String {
        val root = rootInActiveWindow ?: return ""
        val result = StringBuilder()

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return

            node.text?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { result.append(it).append('\n') }

            node.contentDescription?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { result.append(it).append('\n') }

            for (i in 0 until node.childCount) {
                collect(node.getChild(i))
            }
        }

        collect(root)
        return result.toString()
    }
}
