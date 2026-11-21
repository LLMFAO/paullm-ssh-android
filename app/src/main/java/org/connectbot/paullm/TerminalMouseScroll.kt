package org.connectbot.paullm

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connectbot.service.TerminalBridge
import kotlin.math.abs

data class TerminalInteractionModes(
    val mouseTracking: Boolean = false,
    val sgrMouse: Boolean = false,
    val alternateScreen: Boolean = false,
) {
    val shouldForwardTouchScroll: Boolean
        get() = mouseTracking || alternateScreen
}

/**
 * Lightweight streaming DEC private-mode scanner (`ESC [ ? ... h / l`) that tracks
 * mouse-reporting and alternate-screen states across arbitrary SSH packet boundaries.
 */
class TerminalModeTracker {
    private enum class ParseState {
        NORMAL,
        ESC,
        CSI,
        DEC_PRIVATE_PARAMS,
    }

    private var state = ParseState.NORMAL
    private val paramBuf = StringBuilder(32)
    private val enabledMouseModes = mutableSetOf<Int>()
    private val enabledAltModes = mutableSetOf<Int>()
    private var sgrEnabled = false

    private val _modes = MutableStateFlow(TerminalInteractionModes())
    val modes: StateFlow<TerminalInteractionModes> = _modes.asStateFlow()

    @Synchronized
    fun reset() {
        state = ParseState.NORMAL
        paramBuf.setLength(0)
        enabledMouseModes.clear()
        enabledAltModes.clear()
        sgrEnabled = false
        _modes.value = TerminalInteractionModes()
    }

    @Synchronized
    fun feed(bytes: ByteArray) {
        var changed = false
        for (raw in bytes) {
            val b = raw.toInt() and 0xFF
            when (state) {
                ParseState.NORMAL -> {
                    if (b == 0x1B) state = ParseState.ESC
                }

                ParseState.ESC -> {
                    state = when (b) {
                        '['.code -> ParseState.CSI
                        0x1B -> ParseState.ESC
                        else -> ParseState.NORMAL
                    }
                }

                ParseState.CSI -> {
                    if (b == '?'.code) {
                        paramBuf.setLength(0)
                        state = ParseState.DEC_PRIVATE_PARAMS
                    } else if (b == 0x1B) {
                        state = ParseState.ESC
                    } else {
                        state = ParseState.NORMAL
                    }
                }

                ParseState.DEC_PRIVATE_PARAMS -> {
                    when {
                        b in '0'.code..'9'.code || b == ';'.code -> {
                            if (paramBuf.length < 64) {
                                paramBuf.append(b.toChar())
                            } else {
                                state = ParseState.NORMAL
                            }
                        }

                        b == 'h'.code || b == 'l'.code -> {
                            val enable = (b == 'h'.code)
                            if (applyPrivateModes(paramBuf.toString(), enable)) {
                                changed = true
                            }
                            state = ParseState.NORMAL
                        }

                        b == 0x1B -> {
                            state = ParseState.ESC
                        }

                        else -> {
                            state = ParseState.NORMAL
                        }
                    }
                }
            }
        }
        if (changed) {
            _modes.value = TerminalInteractionModes(
                mouseTracking = enabledMouseModes.isNotEmpty(),
                sgrMouse = sgrEnabled,
                alternateScreen = enabledAltModes.isNotEmpty(),
            )
        }
    }

    private fun applyPrivateModes(params: String, enable: Boolean): Boolean {
        if (params.isEmpty()) return false
        var changed = false
        for (part in params.split(';')) {
            val code = part.toIntOrNull() ?: continue
            when (code) {
                1000, 1002, 1003 -> {
                    val didChange = if (enable) enabledMouseModes.add(code) else enabledMouseModes.remove(code)
                    if (didChange) changed = true
                }

                1006 -> {
                    if (sgrEnabled != enable) {
                        sgrEnabled = enable
                        changed = true
                    }
                }

                47, 1047, 1049 -> {
                    val didChange = if (enable) enabledAltModes.add(code) else enabledAltModes.remove(code)
                    if (didChange) changed = true
                }
            }
        }
        return changed
    }
}

fun sendTuiScrollStep(
    bridge: TerminalBridge,
    modes: TerminalInteractionModes,
    scrollUp: Boolean,
    col: Int,
    row: Int,
) {
    val isTmuxTab = bridge.tmuxSessionName != null
    if (modes.mouseTracking || isTmuxTab) {
        val button = if (scrollUp) 64 else 65
        if (modes.sgrMouse || isTmuxTab) {
            bridge.writeToTransport("\u001b[<$button;$col;${row}M".toByteArray(Charsets.US_ASCII))
        } else {
            val cx = (col.coerceIn(1, 223) + 32).toByte()
            val cy = (row.coerceIn(1, 223) + 32).toByte()
            bridge.writeToTransport(byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), (button + 32).toByte(), cx, cy))
        }
    } else if (modes.alternateScreen) {
        val seq = if (scrollUp) "\u001b[A" else "\u001b[B"
        bridge.writeToTransport(seq.toByteArray(Charsets.US_ASCII))
    }
}

fun Modifier.tuiTouchScroll(
    bridge: TerminalBridge,
    modes: TerminalInteractionModes,
    selectionActive: Boolean,
    onInteraction: () -> Unit,
): Modifier {
    val active = (modes.shouldForwardTouchScroll || bridge.tmuxSessionName != null) && !selectionActive
    if (!active) return this

    return pointerInput(bridge.sessionUuid, selectionActive) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val pointerId = down.id
            var totalX = 0f
            var totalY = 0f
            var verticalLocked = false
            var horizontalLocked = false
            var wheelAccumulator = 0f

            val dims = bridge.terminalDimensions.value
            val cols = (dims?.columns ?: 80).coerceAtLeast(1)
            val rows = (dims?.rows ?: 24).coerceAtLeast(1)
            val stepPx = if (size.height > 0) {
                (size.height.toFloat() / rows.toFloat()).coerceIn(18f, 38f)
            } else {
                26f
            }

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                // Abort if multi-touch (e.g. pinch-to-zoom)
                if (event.changes.count { it.pressed } > 1) {
                    break
                }
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) break

                val delta = change.positionChange()
                totalX += delta.x
                totalY += delta.y

                if (!verticalLocked && !horizontalLocked) {
                    val absX = abs(totalX)
                    val absY = abs(totalY)
                    if (absY > viewConfiguration.touchSlop && absY > absX * 1.15f) {
                        verticalLocked = true
                        onInteraction()
                    } else if (absX > viewConfiguration.touchSlop && absX > absY * 1.3f) {
                        horizontalLocked = true
                    }
                }

                if (verticalLocked) {
                    val liveModes = bridge.terminalModeTracker.modes.value
                    change.consume()
                    wheelAccumulator += delta.y

                    val col = if (size.width > 0) {
                        ((change.position.x / size.width.toFloat()) * cols).toInt().coerceIn(0, cols - 1) + 1
                    } else {
                        (cols / 2).coerceAtLeast(1)
                    }
                    val row = if (size.height > 0) {
                        ((change.position.y / size.height.toFloat()) * rows).toInt().coerceIn(0, rows - 1) + 1
                    } else {
                        (rows / 2).coerceAtLeast(1)
                    }

                    // Natural scrolling: finger moves DOWN -> pull older content down (wheel UP)
                    while (wheelAccumulator >= stepPx) {
                        sendTuiScrollStep(bridge, liveModes, scrollUp = true, col = col, row = row)
                        wheelAccumulator -= stepPx
                    }
                    while (wheelAccumulator <= -stepPx) {
                        sendTuiScrollStep(bridge, liveModes, scrollUp = false, col = col, row = row)
                        wheelAccumulator += stepPx
                    }
                }
            }
        }
    }
}
