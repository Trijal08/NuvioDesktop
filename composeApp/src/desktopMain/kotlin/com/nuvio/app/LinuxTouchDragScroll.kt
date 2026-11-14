package com.nuvio.app

import java.awt.AWTEvent
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

// A touchscreen on X11 reaches a Java application as plain pointer events:
// XWayland emulates button 1 from a finger unless a client selects XI2 touch,
// which AWT never does. A tap therefore works, because it is a press and a
// release in the same place, but a drag does not scroll anything. Compose reads
// those drags as mouse drags, and on desktop a list scrolls from the wheel
// rather than from the pointer, so a finger drag lands on a list that ignores
// it.
//
// Nothing in the event tells us a finger produced it, so the fix cannot be to
// mark these events as touch. What it can do is give Compose the gesture in the
// shape it already understands: a drag becomes the precise wheel events
// LinuxExtendedMouseButtons already synthesizes for the horizontal wheel.
//
// The press has to be held back rather than passed straight through. Dispatching
// it immediately and releasing later would leave Compose with a press and a
// release inside the same list row, which is a click, so every scroll would also
// open whatever sat under the finger. Holding it costs a tap the press-state
// ripple until the finger lifts, and buys a scroll that opens nothing.
//
// This is gated on a touchscreen being present, because a mouse produces
// identical events and a pointer user still needs to drag sliders.

// How far a finger travels before the gesture counts as a scroll instead of a
// tap, in logical pixels.
private const val DRAG_SLOP = 10.0

// Logical pixels of travel per wheel unit. Compose multiplies the rotation it
// receives by its own scroll factor, so this is calibration rather than a
// physical quantity.
private const val PIXELS_PER_WHEEL_UNIT = 48.0

private const val WHEEL_SCROLL_AMOUNT = 3

@Volatile
private var touchDragScrollInstalled = false

fun installLinuxTouchDragScroll() {
    if (!System.getProperty("os.name", "").lowercase().contains("linux")) return
    if (!touchDragScrollEnabled()) return
    synchronized(LinuxTouchDragScrollLock) {
        if (touchDragScrollInstalled) return
        touchDragScrollInstalled = true
    }
    // An input convenience must never take the app down with it.
    runCatching {
        Toolkit.getDefaultToolkit().systemEventQueue.push(LinuxTouchDragScrollQueue())
    }
}

// NUVIO_TOUCH_DRAG_SCROLL forces the translation on or off; otherwise it follows
// whether the kernel reports a touchscreen at all. The name match is a
// heuristic, which is why the environment variable exists: a touchscreen whose
// driver names it something else needs the override.
private fun touchDragScrollEnabled(): Boolean {
    when (System.getenv("NUVIO_TOUCH_DRAG_SCROLL")?.trim()?.lowercase()) {
        "1", "true", "yes", "on" -> return true
        "0", "false", "no", "off" -> return false
    }
    return runCatching {
        File("/proc/bus/input/devices").useLines { lines ->
            lines.any { it.startsWith("N: Name=") && it.contains("touch", ignoreCase = true) }
        }
    }.getOrDefault(false)
}

private class LinuxTouchDragScrollQueue : EventQueue() {
    // The press waiting to learn whether it belongs to a tap or a scroll.
    private var pendingPress: MouseEvent? = null
    private var originX = 0
    private var originY = 0
    private var lastX = 0
    private var lastY = 0
    private var scrolling = false
    private var horizontal = false

    // AWT emits MOUSE_CLICKED after the release it belongs to, so the decision
    // to drop it has to outlive the gesture that caused it.
    private var suppressNextClick = false

    override fun dispatchEvent(event: AWTEvent) {
        val translated = runCatching { translate(event) }.getOrDefault(event)
        if (translated != null) {
            super.dispatchEvent(translated)
        }
    }

    private fun translate(event: AWTEvent): AWTEvent? {
        if (event !is MouseEvent || event is MouseWheelEvent) return event
        if (event.button != MouseEvent.BUTTON1 && event.id != MouseEvent.MOUSE_DRAGGED) return event
        val component = event.component ?: return event
        return when (event.id) {
            MouseEvent.MOUSE_PRESSED -> {
                reset()
                suppressNextClick = false
                pendingPress = event
                originX = event.x
                originY = event.y
                lastX = event.x
                lastY = event.y
                null
            }
            MouseEvent.MOUSE_DRAGGED -> onDragged(event, component)
            MouseEvent.MOUSE_RELEASED -> onReleased(event)
            MouseEvent.MOUSE_CLICKED -> if (suppressNextClick) {
                suppressNextClick = false
                null
            } else {
                event
            }
            else -> event
        }
    }

    private fun onDragged(event: MouseEvent, component: Component): AWTEvent? {
        if (pendingPress == null && !scrolling) return event
        if (!scrolling) {
            val travelled = hypot((event.x - originX).toDouble(), (event.y - originY).toDouble())
            if (travelled < DRAG_SLOP) return null
            // The gesture is a scroll: the withheld press is dropped so Compose
            // never sees a click, and the axis is fixed for the rest of it.
            scrolling = true
            horizontal = abs(event.x - originX) > abs(event.y - originY)
            pendingPress = null
        }
        val deltaX = event.x - lastX
        val deltaY = event.y - lastY
        lastX = event.x
        lastY = event.y
        // Content follows the finger, which is the opposite sign to a wheel tick.
        val travel = if (horizontal) -deltaX.toDouble() else -deltaY.toDouble()
        if (travel == 0.0) return null
        return wheelEvent(event, component, travel / PIXELS_PER_WHEEL_UNIT, horizontal)
    }

    private fun onReleased(event: MouseEvent): AWTEvent? {
        val press = pendingPress
        return when {
            // The scroll ends here, and both its release and the click AWT
            // raises afterwards would land on whatever sat under the finger.
            scrolling -> {
                reset()
                suppressNextClick = true
                null
            }
            // A tap: the press was never dispatched, so it goes out now and the
            // release follows it as the return value.
            press != null -> {
                reset()
                super.dispatchEvent(press)
                event
            }
            else -> event
        }
    }

    private fun wheelEvent(
        source: MouseEvent,
        component: Component,
        rotation: Double,
        horizontal: Boolean,
    ): MouseWheelEvent {
        // Compose reads a horizontal scroll as a wheel tick with shift held.
        val modifiers = if (horizontal) {
            source.modifiersEx or InputEvent.SHIFT_DOWN_MASK
        } else {
            source.modifiersEx
        }
        return MouseWheelEvent(
            component,
            MouseEvent.MOUSE_WHEEL,
            source.getWhen(),
            modifiers and InputEvent.BUTTON1_DOWN_MASK.inv(),
            source.x,
            source.y,
            source.xOnScreen,
            source.yOnScreen,
            0,
            false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL,
            WHEEL_SCROLL_AMOUNT,
            rotation.toInt(),
            rotation,
        )
    }

    private fun reset() {
        pendingPress = null
        scrolling = false
        horizontal = false
    }
}

private object LinuxTouchDragScrollLock
