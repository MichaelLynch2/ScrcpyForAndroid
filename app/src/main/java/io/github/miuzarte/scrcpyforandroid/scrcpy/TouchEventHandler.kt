package io.github.miuzarte.scrcpyforandroid.scrcpy

import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.miuzarte.scrcpyforandroid.services.BluetoothHidKeyboard

/**
 * TouchEventHandler
 *
 * Purpose:
 * - Handles touch event processing for fullscreen control screen
 * - Manages pointer tracking, coordinate mapping, and touch injection
 */
class TouchEventHandler(
    private val coroutineScope: CoroutineScope,
    private val session: Scrcpy.Session.SessionInfo,
    private val touchAreaSize: IntSize,
    private val activePointerIds: LinkedHashSet<Int>,
    private val activePointerPositions: LinkedHashMap<Int, Offset>,
    private val activePointerDevicePositions: LinkedHashMap<Int, Pair<Int, Int>>,
    private val pointerLabels: LinkedHashMap<Int, Int>,
    private var nextPointerLabel: Int,
    private val mouseHoverEnabled: Boolean,
    private val onInjectTouch: suspend (
        action: Int,
        pointerId: Long,
        x: Int,
        y: Int,
        pressure: Float,
        actionButton: Int,
        buttons: Int,
    ) -> Unit,
    private val onInjectScroll: suspend (
        x: Int,
        y: Int,
        hScroll: Float,
        vScroll: Float,
        buttons: Int,
    ) -> Unit = { _, _, _, _, _ -> },
    private val onBackOrScreenOn: suspend (action: Int) -> Unit,
    private val onActiveTouchCountChanged: (Int) -> Unit,
    private val onActiveTouchDebugChanged: (String) -> Unit,
    private val onNextPointerLabelChanged: (Int) -> Unit,
) {
    companion object {
        private const val FULLSCREEN_TOUCH_LOG_TAG = "FullscreenTouch"
        private const val POINTER_ID_MOUSE = -1L
    }

    private object UiMotionActions {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }

    private val eventPointerIds = HashSet<Int>(10)
    private var lastMouseButtons = 0
    private val eventPositions = HashMap<Int, Offset>(10)
    private val eventPressures = HashMap<Int, Float>(10)
    private val justPressedPointerIds = HashSet<Int>(10)
    private val pendingMoveJobs = HashMap<Int, Job>(10)

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (BluetoothHidKeyboard.handleMouseEvent(event)) {
    return true
}
        if (touchAreaSize.width == 0 || touchAreaSize.height == 0) {
            return true
        }

        val bounds = calculateContentBounds()

        if (isMouseLikeEvent(event)) {
            return handleMouseEvent(event, bounds)
        }

        if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
            return handleCancelAction(bounds)
        }

        extractEventData(event)
        handleDisappearedPointers(eventPointerIds, bounds)

        val endedPointerId = getEndedPointerId(event)
        handlePointerDown(event, endedPointerId, bounds)
        handlePointerMove(event, endedPointerId, bounds)
        handlePointerUp(endedPointerId, bounds)

        onActiveTouchCountChanged(activePointerIds.size)
        refreshTouchDebug()
        return true
    }

    private fun isMouseLikeEvent(event: MotionEvent): Boolean {
        return event.isFromSource(InputDevice.SOURCE_MOUSE) ||
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private data class ContentBounds(
        val width: Float,
        val height: Float,
        val left: Float,
        val top: Float,
    )

    private fun calculateContentBounds(): ContentBounds {
        val sessionAspect = if (session.height == 0) {
            16f / 9f
        } else {
            session.width.toFloat() / session.height.toFloat()
        }
        val containerWidth = touchAreaSize.width.toFloat()
        val containerHeight = touchAreaSize.height.toFloat()
        val containerAspect = containerWidth / containerHeight

        val contentWidth: Float
        val contentHeight: Float
        if (sessionAspect > containerAspect) {
            contentWidth = containerWidth
            contentHeight = containerWidth / sessionAspect
        } else {
            contentHeight = containerHeight
            contentWidth = containerHeight * sessionAspect
        }
        val contentLeft = (containerWidth - contentWidth) / 2f
        val contentTop = (containerHeight - contentHeight) / 2f

        return ContentBounds(contentWidth, contentHeight, contentLeft, contentTop)
    }

    private fun isInsideContent(rawX: Float, rawY: Float, bounds: ContentBounds): Boolean {
        return rawX in bounds.left..(bounds.left + bounds.width) &&
                rawY in bounds.top..(bounds.top + bounds.height)
    }

    private fun mapToDevice(rawX: Float, rawY: Float, bounds: ContentBounds): Pair<Int, Int> {
        val normalizedX = ((rawX - bounds.left) / bounds.width).coerceIn(0f, 1f)
        val normalizedY = ((rawY - bounds.top) / bounds.height).coerceIn(0f, 1f)
        val x = (normalizedX * (session.width - 1).coerceAtLeast(0)).roundToInt()
            .coerceIn(0, (session.width - 1).coerceAtLeast(0))
        val y = (normalizedY * (session.height - 1).coerceAtLeast(0)).roundToInt()
            .coerceIn(0, (session.height - 1).coerceAtLeast(0))
        return x to y
    }

    private fun getPointerLabel(pointerId: Int): Int {
        val existing = pointerLabels[pointerId]
        if (existing != null) {
            return existing
        }
        val assigned = nextPointerLabel
        nextPointerLabel += 1
        onNextPointerLabelChanged(nextPointerLabel)
        pointerLabels[pointerId] = assigned
        return assigned
    }

    private fun refreshTouchDebug() {
        if (activePointerIds.isEmpty()) {
            onActiveTouchDebugChanged("")
            return
        }
        val debug = activePointerIds
            .sortedBy { getPointerLabel(it) }
            .joinToString(separator = "\n") { pointerId ->
                val label = getPointerLabel(pointerId)
                val pos = activePointerDevicePositions[pointerId]
                if (pos == null) {
                    "#$label(id=$pointerId):?"
                } else {
                    "#$label(id=$pointerId):${pos.first},${pos.second}"
                }
            }
        onActiveTouchDebugChanged(debug)
    }

private fun handleMouseEvent(
    event: MotionEvent,
    bounds: ContentBounds,
): Boolean {
    val rawX = event.getX(0)
    val rawY = event.getY(0)
    val (x, y) = mapToDevice(rawX, rawY, bounds)
    val pressure = event.getPressure(0).coerceIn(0f, 1f)
    val buttons = event.buttonState

    // Trackpad two-finger scrolling arrives as ACTION_SCROLL.
    if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
        val hScroll = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        val vScroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)

        coroutineScope.launch {
            runCatching {
                onInjectScroll(
                    x,
                    y,
                    hScroll,
                    vScroll,
                    buttons,
                )
            }.onFailure { e ->
                Log.w(
                    FULLSCREEN_TOUCH_LOG_TAG,
                    "mouse scroll failed",
                    e,
                )
            }
        }

        return true
    }

    val isHoverMotion = when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER,
        MotionEvent.ACTION_HOVER_MOVE,
        MotionEvent.ACTION_HOVER_EXIT,
            -> true

        MotionEvent.ACTION_MOVE -> buttons == 0
        else -> false
    }

    if (!mouseHoverEnabled && isHoverMotion) {
        return true
    }

    /*
     * Android sends ACTION_BUTTON_PRESS/RELEASE in addition to
     * ACTION_DOWN/UP for mouse buttons. scrcpy's server generates
     * those button events itself from DOWN/UP, so ignore the
     * duplicates here.
     */
    if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
        event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    ) {
        return true
    }

    val injectAction = when (event.actionMasked) {
        MotionEvent.ACTION_HOVER_ENTER -> MotionEvent.ACTION_HOVER_ENTER
        MotionEvent.ACTION_HOVER_MOVE -> MotionEvent.ACTION_HOVER_MOVE
        MotionEvent.ACTION_HOVER_EXIT -> MotionEvent.ACTION_HOVER_EXIT
        MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
        MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
        MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
        else -> return true
    }

    /*
     * actionButton is normally only populated on Android's
     * ACTION_BUTTON_PRESS/RELEASE events. Since we deliberately
     * consume those duplicate events above, infer which button
     * changed from buttonState.
     */
    val changedButtons = lastMouseButtons xor buttons
    val actionButton = when (injectAction) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_UP,
            -> if (event.actionButton != 0) {
                event.actionButton
            } else {
                changedButtons
            }

        else -> 0
    }

    coroutineScope.launch {
        runCatching {
            onInjectTouch(
                injectAction,
                POINTER_ID_MOUSE,
                x,
                y,
                pressure,
                actionButton,
                buttons,
            )
        }.onFailure { e ->
            Log.w(
                FULLSCREEN_TOUCH_LOG_TAG,
                "handleMouseEvent failed",
                e,
            )
        }
    }

    lastMouseButtons = buttons
    return true
}
    private fun releasePointer(pointerId: Int, bounds: ContentBounds) {
        if (!activePointerIds.contains(pointerId)) return
        pendingMoveJobs.remove(pointerId)?.cancel()
        val pos = activePointerPositions[pointerId] ?: Offset.Zero
        val (x, y) = mapToDevice(pos.x, pos.y, bounds)
        coroutineScope.launch {
            runCatching {
                onInjectTouch(UiMotionActions.UP, pointerId.toLong(), x, y, 0f, 0, 0)
            }.onFailure { e ->
                Log.w(FULLSCREEN_TOUCH_LOG_TAG, "releasePointer failed for pointerId=$pointerId", e)
            }
        }
        activePointerIds -= pointerId
        activePointerPositions.remove(pointerId)
        activePointerDevicePositions.remove(pointerId)
        pointerLabels.remove(pointerId)
    }

    private fun handleCancelAction(bounds: ContentBounds): Boolean {
        val toCancel = activePointerIds.toList()
        for (pointerId in toCancel) {
            releasePointer(pointerId, bounds)
        }
        onActiveTouchCountChanged(activePointerIds.size)
        refreshTouchDebug()
        return true
    }

    private fun extractEventData(event: MotionEvent) {
        eventPointerIds.clear()
        eventPositions.clear()
        eventPressures.clear()
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            eventPointerIds += pointerId
            eventPositions[pointerId] = Offset(event.getX(i), event.getY(i))
            eventPressures[pointerId] = event.getPressure(i).coerceIn(0f, 1f)
        }
    }

    private fun handleDisappearedPointers(eventPointerIds: Set<Int>, bounds: ContentBounds) {
        val disappearedPointers = activePointerIds.filter { it !in eventPointerIds }
        for (pointerId in disappearedPointers) {
            releasePointer(pointerId, bounds)
        }
    }

    private fun getEndedPointerId(event: MotionEvent): Int? {
        return when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> event.getPointerId(event.actionIndex)
            else -> null
        }
    }

    private fun handlePointerDown(
        event: MotionEvent,
        endedPointerId: Int?,
        bounds: ContentBounds,
    ) {
        justPressedPointerIds.clear()
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            if (pointerId == endedPointerId) continue
            val raw = eventPositions[pointerId] ?: continue
            val pressure = eventPressures[pointerId] ?: 0f
            if (!activePointerIds.contains(pointerId)) {
                if (!isInsideContent(raw.x, raw.y, bounds)) continue
                val (x, y) = mapToDevice(raw.x, raw.y, bounds)
                activePointerIds += pointerId
                activePointerPositions[pointerId] = raw
                activePointerDevicePositions[pointerId] = x to y
                justPressedPointerIds += pointerId
                coroutineScope.launch {
                    runCatching {
                        onInjectTouch(
                            UiMotionActions.DOWN,
                            pointerId.toLong(),
                            x,
                            y,
                            pressure,
                            0,
                            0,
                        )
                    }.onFailure { e ->
                        Log.w(
                            FULLSCREEN_TOUCH_LOG_TAG,
                            "handlePointerDown failed for pointerId=$pointerId",
                            e,
                        )
                    }
                }
            }
        }
    }

    private fun handlePointerMove(
        event: MotionEvent,
        endedPointerId: Int?,
        bounds: ContentBounds,
    ) {
        for (i in 0 until event.pointerCount) {
            val pointerId = event.getPointerId(i)
            if (!activePointerIds.contains(pointerId)) continue
            if (pointerId == endedPointerId) continue
            if (pointerId in justPressedPointerIds) continue
            val raw = eventPositions[pointerId] ?: continue
            val pressure = eventPressures[pointerId] ?: 0f
            activePointerPositions[pointerId] = raw
            val (x, y) = mapToDevice(raw.x, raw.y, bounds)
            activePointerDevicePositions[pointerId] = x to y
            pendingMoveJobs[pointerId]?.cancel()
            pendingMoveJobs[pointerId] = coroutineScope.launch {
                runCatching {
                    onInjectTouch(UiMotionActions.MOVE, pointerId.toLong(), x, y, pressure, 0, 0)
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(
                        FULLSCREEN_TOUCH_LOG_TAG,
                        "handlePointerMove failed for pointerId=$pointerId",
                        e,
                    )
                }
            }
        }
    }

    private fun handlePointerUp(
        endedPointerId: Int?,
        bounds: ContentBounds,
    ) {
        if (endedPointerId != null) {
            val endPos = eventPositions[endedPointerId]
            if (endPos != null) {
                activePointerPositions[endedPointerId] = endPos
            }
            releasePointer(endedPointerId, bounds)
        }
    }

}
