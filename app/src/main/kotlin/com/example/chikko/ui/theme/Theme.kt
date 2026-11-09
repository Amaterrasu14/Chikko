package com.example.chikko.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = lightColorScheme(
    primary = Color(0xFFB3261E),
    onPrimary = Color.White,
    secondary = Color(0xFF6B5E3C),
    background = Color(0xFFFFFBFF),
)

@Composable
fun ChikkoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
