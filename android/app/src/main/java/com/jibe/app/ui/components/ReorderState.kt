package com.jibe.app.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * Lightweight drag-reorder state for a LazyColumn where a subset of items (identified by key)
 * can be long-pressed on a drag handle and reordered.
 *
 * Usage:
 * 1. Create with [rememberReorderState].
 * 2. On each reorderable item: `Modifier.reorderableItem(state, key)`.
 *    Only call `.animateItem()` when `!state.isDragged(key)`.
 * 3. On the drag handle / drag surface: `Modifier.dragHandle(state, key)`.
 */
@Stable
class ReorderState internal constructor(
        val lazyListState: LazyListState,
        private val reorderableKeys: () -> List<Any>,
        private val onSwap: (from: Int, to: Int) -> Unit,
        private val onDone: () -> Unit,
) {
    var draggedKey: Any? by mutableStateOf(null)
        private set

    var dragOffset by mutableFloatStateOf(0f)
        private set

    val isDragging: Boolean
        get() = draggedKey != null

    fun isDragged(key: Any): Boolean = draggedKey == key

    internal var haptic: HapticFeedback? = null

    internal fun start(key: Any) {
        draggedKey = key
        dragOffset = 0f
    }

    internal fun drag(delta: Float) {
        if (!isDragging) return
        dragOffset += delta
        checkSwap()
    }

    internal fun end() {
        if (isDragging) {
            draggedKey = null
            dragOffset = 0f
            onDone()
        }
    }

    private fun checkSwap() {
        val dk = draggedKey ?: return
        val items = lazyListState.layoutInfo.visibleItemsInfo
        val dragged = items.find { it.key == dk } ?: return
        val draggedCenter = dragged.offset + dragged.size / 2f + dragOffset

        val keys = reorderableKeys()
        val draggedListIdx = keys.indexOf(dk)
        if (draggedListIdx < 0) return

        for (item in items) {
            if (item.key == dk) continue
            val targetListIdx = keys.indexOf(item.key)
            if (targetListIdx < 0) continue

            val itemCenter = item.offset + item.size / 2f
            val shouldSwap =
                    (targetListIdx > draggedListIdx && draggedCenter > itemCenter) ||
                            (targetListIdx < draggedListIdx && draggedCenter < itemCenter)

            if (shouldSwap) {
                dragOffset -= (item.offset - dragged.offset)
                onSwap(draggedListIdx, targetListIdx)
                haptic?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                return
            }
        }
    }
}

@Composable
fun rememberReorderState(
        lazyListState: LazyListState,
        reorderableKeys: () -> List<Any>,
        onSwap: (from: Int, to: Int) -> Unit,
        onDone: () -> Unit,
): ReorderState {
    val state = androidx.compose.runtime.remember {
        ReorderState(lazyListState, reorderableKeys, onSwap, onDone)
    }
    state.haptic = LocalHapticFeedback.current
    return state
}

/** Apply to the whole item row — handles z-order, translation, and elevation while dragging. */
@Composable
fun Modifier.reorderableItem(state: ReorderState, key: Any): Modifier {
    val isDragged = state.isDragged(key)
    val elevation by animateDpAsState(if (isDragged) 6.dp else 0.dp, label = "drag_elev")
    return this
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                if (isDragged) {
                    translationY = state.dragOffset
                    shadowElevation = elevation.toPx()
                    scaleX = 1.015f
                    scaleY = 1.015f
                }
            }
}

/** Apply to the drag handle / drag surface — detects long-press + drag gestures. */
@Composable
fun Modifier.dragHandle(state: ReorderState, key: Any): Modifier {
    val haptic = LocalHapticFeedback.current
    return this.pointerInput(key) {
        detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    state.start(key)
                },
                onDrag = { change, offset ->
                    change.consume()
                    state.drag(offset.y)
                },
                onDragEnd = { state.end() },
                onDragCancel = { state.end() },
        )
    }
}
