/*
 * Copyright (C) 2026 WisadelZ
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.j2pmobile.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 主题模式：取值与 conf.yml 的 `app.theme_mode` 一致（light / dark / system）。
 *
 * 桌面版把主题存在配置里并即时生效，安卓端保持同样的语义：配置仍是唯一权威来源，
 * 这里只负责把配置值翻译成 Material 的明暗配色。
 */
object ThemeMode {
    const val LIGHT = "light"
    const val DARK = "dark"
    const val SYSTEM = "system"

    val all = listOf(LIGHT, DARK, SYSTEM)
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6FEB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4FF),
    onPrimaryContainer = Color(0xFF0A2A5E),
    secondary = Color(0xFF4F6180),
    background = Color(0xFFF6F7F9),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EAF0),
    onSurfaceVariant = Color(0xFF4A5462),
    error = Color(0xFFC0392B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6EA8FE),
    onPrimary = Color(0xFF08234A),
    primaryContainer = Color(0xFF1B3A66),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFB2BFD6),
    background = Color(0xFF111316),
    surface = Color(0xFF191C20),
    surfaceVariant = Color(0xFF2A2F36),
    onSurfaceVariant = Color(0xFFC2CAD6),
    error = Color(0xFFE57373),
)

/** 按 [mode] 套用明暗主题；`system` 跟随系统。 */
@Composable
fun Jm2pdfTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        content = content,
    )
}