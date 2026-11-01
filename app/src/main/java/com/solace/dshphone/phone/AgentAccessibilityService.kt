package com.solace.dshphone.phone

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The on-device hands of the agent: one system-bound service that reads
 * control trees and taps, types and scrolls in whatever app the task is
 * working in. Window events fan out to registered listeners.
 */
class AgentAccessibilityService : AccessibilityService() {

    interface WindowListener {
        fun onWindowChange(packageName: String, event: AccessibilityEvent)
    }

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        Companion.instance = this
        Companion.connectedListeners.forEach { it() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        val pkg = e.packageName?.toString() ?: return
        synchronized(windowListeners) {
            windowListeners.forEach { l -> runCatching { l.onWindowChange(pkg, e) } }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        Companion.instance = null
        super.onDestroy()
    }

    // -- operations used by the phone capability ----------------------------

    fun visibleText(limit: Int = 400): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (sb.length > limit * 6) return
            node.text?.let { if (it.isNotBlank()) sb.appendLine("  ".repeat(depth) + it) }
            node.contentDescription?.let { if (it.isNotBlank()) sb.appendLine("  ".repeat(depth) + "[$it]") }
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return sb.toString().take(limit * 6)
    }

    private fun findNode(query: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo) {
            val label = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            if (label.contains(query) && predicate(node) && best == null) best = node
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return best
    }

    fun tapByText(query: String): Boolean =
        findNode(query) { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true

    fun inputByText(query: String, text: String): Boolean {
        val node = findNode(query) { it.isClickable }
            ?: findNode(query) { it.isEditable }
            ?: return false
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun currentPackage(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    fun installedApps(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val pm = packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0).forEach {
            out.add(it.activityInfo.packageName to it.loadLabel(pm).toString())
        }
        return out.distinctBy { it.first }
    }

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        private val windowListeners = LinkedHashSet<WindowListener>()
        private val connectedListeners = LinkedHashSet<() -> Unit>()

        fun isAvailable(): Boolean = instance != null

        fun addWindowListener(l: WindowListener) = synchronized(windowListeners) { windowListeners.add(l) }

        fun removeWindowListener(l: WindowListener) = synchronized(windowListeners) { windowListeners.remove(l) }

        fun onFirstConnected(l: () -> Unit) = synchronized(connectedListeners) {
            instance?.let { l() } ?: connectedListeners.add(l)
        }
    }
}
