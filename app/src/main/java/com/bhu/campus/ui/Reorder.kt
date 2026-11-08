package com.bhu.campus.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * 一列可以长按拖动换位置的卡片。
 *
 * 手感上的三条约束（都是踩过坑才加的）：
 * 1. 每个子项都按 key 复用，否则顺序一变 Compose 就按位置重新认节点，
 *    正在进行的拖动手势会随 pointerInput 重启而断掉，看着像「松手就刷新」。
 * 2. 拖动过程中只改内存里的顺序，**不落盘**；落盘会改 settings，父页面整页重组，
 *    内部列表再被回灌一次，卡片会瞬间弹回原位又跳回来。
 * 3. 松手后才提交一次，并用 emitted 记住自己提交的顺序，挡住那次回灌。
 */
@Composable
fun ReorderableColumn(
    keys: List<String>,
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    onOrderChange: (List<String>) -> Unit,
    content: @Composable (String) -> Unit,
) {
    val items = remember { mutableStateListOf<String>().apply { addAll(keys) } }

    // 上一次由本组件提交出去的顺序：外部把它回灌回来时不要再重置内部列表
    var emitted by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(keys) {
        if (keys == emitted) return@LaunchedEffect
        if (items.toList() != keys) {
            items.clear()
            items.addAll(keys)
        }
    }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    // 松手后正在做「落进槽位」的动画：这期间别再插一段补位动画，否则会怼一下
    var settlingKey by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }
    val haptic = LocalHapticFeedback.current
    val spacingPx = with(LocalDensity.current) { spacing.roundToPx() }
    val scope = rememberCoroutineScope()

    fun commit() {
        val next = items.toList()
        emitted = next
        onOrderChange(next)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        items.forEachIndexed { index, itemKey ->
            key(itemKey) {
                val dragging = itemKey == draggingKey
                val slide = remember { Animatable(0f) }
                val scale by animateFloatAsState(
                    targetValue = if (dragging) 1.015f else 1f,
                    animationSpec = tween(120),
                )
                // 上一次所在的位置，用来算这次该从哪儿滑过来
                var lastIndex by remember { mutableStateOf(index) }

                LaunchedEffect(index) {
                    if (index == lastIndex) return@LaunchedEffect
                    val from = lastIndex
                    val to = index
                    lastIndex = to
                    // 拖动中的那张跟手走，不做补位动画
                    if (dragging || settlingKey == itemKey) return@LaunchedEffect
                    var span = 0
                    for (i in minOf(from, to) until maxOf(from, to)) {
                        val k = items.getOrNull(i) ?: continue
                        span += (heights[k] ?: 0) + spacingPx
                    }
                    if (span <= 0) return@LaunchedEffect
                    // 先假装还在旧位置，再滑到新位置，避免一帧瞬移
                    slide.snapTo(if (to > from) -span.toFloat() else span.toFloat())
                    slide.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else slide.value
                            scaleX = scale
                            scaleY = scale
                        }
                        .onSizeChanged { heights[itemKey] = it.height }
                        .pointerInput(itemKey) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = itemKey
                                    dragOffset = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    if (draggingKey == itemKey) {
                                        dragOffset += amount.y
                                        var guard = 0
                                        while (guard++ < 8) {
                                            val i = items.indexOf(itemKey)
                                            if (i < 0) break
                                            if (dragOffset > 0 && i < items.lastIndex) {
                                                val h = (heights[items[i + 1]] ?: 0).toFloat() + spacingPx
                                                if (h > 0 && dragOffset > h * 0.45f) {
                                                    items.removeAt(i)
                                                    items.add(i + 1, itemKey)
                                                    dragOffset -= h
                                                    continue
                                                }
                                            } else if (dragOffset < 0 && i > 0) {
                                                val h = (heights[items[i - 1]] ?: 0).toFloat() + spacingPx
                                                if (h > 0 && -dragOffset > h * 0.45f) {
                                                    items.removeAt(i)
                                                    items.add(i - 1, itemKey)
                                                    dragOffset += h
                                                    continue
                                                }
                                            }
                                            break
                                        }
                                    }
                                },
                                onDragEnd = {
                                    // 从手指停的位置滑进槽位，再落盘（只此一次）
                                    scope.launch {
                                        slide.snapTo(dragOffset)
                                        settlingKey = itemKey
                                        draggingKey = null
                                        dragOffset = 0f
                                        commit()
                                        slide.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                                        if (settlingKey == itemKey) settlingKey = null
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        slide.snapTo(dragOffset)
                                        settlingKey = itemKey
                                        draggingKey = null
                                        dragOffset = 0f
                                        commit()
                                        slide.animateTo(0f, tween(180, easing = FastOutSlowInEasing))
                                        if (settlingKey == itemKey) settlingKey = null
                                    }
                                },
                            )
                        },
                ) {
                    content(itemKey)
                }
            }
        }
    }
}
