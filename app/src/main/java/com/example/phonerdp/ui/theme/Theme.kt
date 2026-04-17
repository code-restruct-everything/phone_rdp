package com.example.phonerdp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightScheme = lightColorScheme()
private val DarkScheme = darkColorScheme()

@Composable
fun PhoneRdpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}