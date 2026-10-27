package io.github.webtvremote.input

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import io.github.webtvremote.RemoteService
import io.github.webtvremote.RemoteState

/**
 * Optional fallback used when ADB is not available: global actions (back, home, power dialog,
 * D-pad on Android 13+) and Unicode text input into the focused field.
 */
class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        RemoteState.changed()
        // The system binds accessibility services right after boot, well before BOOT_COMPLETED
        // reaches third-party apps (~30 s later on slow boxes), so start the remote from here too.
        runCatching { RemoteService.start(this) }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        RemoteState.changed()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun focusedEditable(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    fun setText(text: String): Boolean {
        val node = focusedEditable() ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, text.length)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length)
        })
        return true
    }

    fun imeEnter(): Boolean =
        focusedEditable()?.performAction(AccessibilityAction.ACTION_IME_ENTER.id) ?: false

    /** Maps a remote key to a global action, if the platform has one. */
    fun key(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            else -> if (Build.VERSION.SDK_INT >= 33) when (name) {
                "up" -> GLOBAL_ACTION_DPAD_UP
                "down" -> GLOBAL_ACTION_DPAD_DOWN
                "left" -> GLOBAL_ACTION_DPAD_LEFT
                "right" -> GLOBAL_ACTION_DPAD_RIGHT
                "ok" -> GLOBAL_ACTION_DPAD_CENTER
                else -> null
            } else null
        } ?: return false
        return performGlobalAction(action)
    }

    companion object {
        @Volatile var instance: RemoteAccessibilityService? = null
            private set
    }
}
