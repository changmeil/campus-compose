package com.bhu.campus.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.unit.dp

/** 电费图标：一道闪电（Miuix 内置图标库里没有合适的，手绘一个路径）。 */
val BoltIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Bolt",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = listOf(
            PathNode.MoveTo(13.2f, 2f),
            PathNode.LineTo(4.6f, 13.4f),
            PathNode.LineTo(10.4f, 13.4f),
            PathNode.LineTo(9.6f, 22f),
            PathNode.LineTo(19.4f, 10.2f),
            PathNode.LineTo(13.2f, 10.2f),
            PathNode.Close,
        ),
        fill = SolidColor(Color.Black),
    ).build()
}
