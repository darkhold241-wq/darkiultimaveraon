package com.darki.os.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkiColorScheme = darkColorScheme(
    primary = DarkiCyan,
    secondary = DarkiPurple,
    background = DarkiBackground,
    surface = DarkiBackground
)

@Composable
fun DarkiOSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkiColorScheme,
        content = content
    )
}
