package com.meetnote.recorder.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF0B6B4F)
private val GreenLight = Color(0xFF4FA98A)
private val Amber = Color(0xFFF2A93B)

private val LightColors = lightColorScheme(
    primary = Green, secondary = GreenLight, tertiary = Amber,
)
private val DarkColors = darkColorScheme(
    primary = GreenLight, secondary = Green, tertiary = Amber,
)

@Composable
fun MeetingRecorderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
