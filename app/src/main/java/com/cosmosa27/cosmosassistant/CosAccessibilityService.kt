package com.cosmosa27.cosmosassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicLong

class CosAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CosAccessibilityService? = null
            private set
    }

    private val screenVersion = AtomicLong(0)

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
        val eventType = event?.eventType ?: return

        when (eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SELECTED -> {
                screenVersion.incrementAndGet()
            }
        }
    }

    override fun onInterrupt() {
        // Система прервала работу службы.
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun getScreenVersion(): Long = screenVersion.get()

    fun getActivePackageName(): String =
        rootInActiveWindow?.packageName?.toString().orEmpty()

    fun click(text: String): Boolean = clickAny(listOf(text))

    /**
     * Ищет текст или описание элемента без учёта регистра,
     * затем нажимает на ближайший кликабельный родительский элемент.
     */
    fun clickAny(targets: List<String>): Boolean {
        val root = rootInActiveWindow ?: return false
        val normalizedTargets = targets
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }

        if (normalizedTargets.isEmpty()) return false

        data class Candidate(
            val node: AccessibilityNodeInfo,
            val score: Int
        )

        val candidates = mutableListOf<Candidate>()

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val labels = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).map { normalizeForMatch(it) }.filter { it.isNotBlank() }

            for (label in labels) {
                for (target in normalizedTargets) {
                    val score = when {
                        label == target -> 0
                        label.contains(target) -> 1
                        target.length >= 4 && target.contains(label) -> 2
                        else -> continue
                    }
                    candidates.add(Candidate(node, score))
                }
            }

            for (index in 0 until node.childCount) {
                collect(node.getChild(index))
            }
        }

        collect(root)

        for (candidate in candidates.sortedBy { it.score }) {
            var current: AccessibilityNodeInfo? = candidate.node
            var depth = 0

            while (current != null && depth < 8) {
                if (current.isClickable && current.isEnabled) {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                current = current.parent
                depth++
            }

            if (candidate.node.isEnabled &&
                candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
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

    /**
     * Вводит текст в текущее поле ввода. Если поле ещё не в фокусе,
     * сначала пытается активировать его и повторно получить узел экрана.
     */
    fun enterTextInFocusedField(text: String): Boolean {
        var root = rootInActiveWindow ?: return false
        var field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false

        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            root = rootInActiveWindow ?: return false
            field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditable(root)
                ?: return false
        }

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }

        return field.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            arguments
        )
    }

    fun hasEditableField(): Boolean {
        val root = rootInActiveWindow ?: return false
        return findFirstEditable(root) != null
    }

    /**
     * Отправляет Enter в активное поле поиска.
     * На старых версиях Android пытается нажать кнопку поиска.
     */
    fun submitSearch(): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
                return true
            }
        }

        return clickAny(listOf("найти", "search", "перейти", "готово"))
    }

    private fun findFirstEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (root.isEditable && root.isEnabled) return root

        for (index in 0 until root.childCount) {
            val found = findFirstEditable(root.getChild(index) ?: continue)
            if (found != null) return found
        }

        return null
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
        val result = linkedSetOf<String>()

        fun collect(node: AccessibilityNodeInfo?) {
            if (node == null) return

            node.text?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { result.add(it) }

            node.contentDescription?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { result.add(it) }

            for (index in 0 until node.childCount) {
                collect(node.getChild(index))
            }
        }

        collect(root)
        return result.joinToString("\n")
    }

    private fun normalizeForMatch(value: String): String =
        value.lowercase()
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()
}
