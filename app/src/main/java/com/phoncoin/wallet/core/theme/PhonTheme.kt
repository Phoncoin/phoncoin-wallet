package com.phoncoin.wallet.core.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PhonColorScheme = darkColorScheme(
    background = PhonColors.Bg,
    surface = PhonColors.Card,
    primary = PhonColors.Accent,
    secondary = PhonColors.Accent2,
    onBackground = PhonColors.Text,
    onSurface = PhonColors.Text,
    onPrimary = Color(0xFF00120A) // texte sur bouton vert
)

@Composable
fun PHONTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PhonColorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
