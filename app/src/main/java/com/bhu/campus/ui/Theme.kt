package com.bhu.campus.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
fun CampusTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MiuixTheme(
        colors = if (dark) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}

/** 课程配色（MIUI 系统色）。索引 1..7。 */
object CoursePalette {
    val accents = listOf(
        Color(0xFF3482FF),
        Color(0xFF5E5CE6),
        Color(0xFFA855D8),
        Color(0xFFF0910C),
        Color(0xFFF0433F),
        Color(0xFF14B3A6),
        Color(0xFF34B45A),
    )
    val softs = listOf(
        Color(0xFFE8F0FF),
        Color(0xFFECEBFF),
        Color(0xFFF6E9FD),
        Color(0xFFFDF0DD),
        Color(0xFFFDECEA),
        Color(0xFFE2F7F5),
        Color(0xFFE6F7EA),
    )

    fun accent(index: Int): Color = accents[(index - 1).coerceIn(0, accents.lastIndex)]
    fun soft(index: Int): Color = softs[(index - 1).coerceIn(0, softs.lastIndex)]
}
