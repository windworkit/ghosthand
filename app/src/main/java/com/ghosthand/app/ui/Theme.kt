package com.ghosthand.app.ui

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Paper     = Color(0xFF14120E)
val CardC     = Color(0xFF1D1A14)
val CardHi    = Color(0xFF272318)
val LineC     = Color(0xFF2E2A20)
val Ink       = Color(0xFFEDE4D3)
val Muted     = Color(0xFF918573)
val Amber     = Color(0xFFF2AE3C)
val AmberDeep = Color(0xFFB57C1F)
val Danger    = Color(0xFFE36952)
val GoodGreen = Color(0xFFA3BE7A)

val Mono = FontFamily.Monospace

fun ghColors() = darkColorScheme(
    primary = Amber, onPrimary = Color(0xFF171309),
    secondary = AmberDeep,
    background = Paper, onBackground = Ink,
    surface = Paper, onSurface = Ink,
    surfaceVariant = CardC, onSurfaceVariant = Muted,
    surfaceContainer = CardC, surfaceContainerHigh = CardHi,
    outline = LineC, error = Danger
)

fun ghType() = Typography(
    titleLarge = androidx.compose.ui.text.TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 34.sp
    )
)
