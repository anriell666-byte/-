package com.team.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

// Служба специальных возможностей: делает нажатия в игре
class ClickService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: ClickService? = null

        // Нажатие в точке экрана. Возвращает false, если служба не включена.
        fun tap(x: Int, y: Int): Boolean {
            val s = instance ?: return false
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat() + 1f, y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            return s.dispatchGesture(gesture, null, null)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}
