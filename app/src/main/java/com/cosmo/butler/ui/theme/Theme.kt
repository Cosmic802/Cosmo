package com.cosmo.butler.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CosmoColorScheme = darkColorScheme(
    primary = StudyCyan,
    background = JarvisBackground,
    surface = JarvisBackground,
    onPrimary = JarvisBackground,
    onBackground = TextWhite,
    onSurface = TextWhite
)

@Composable
fun CosmoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CosmoColorScheme,
        content = content
    )
}
