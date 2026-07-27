package com.vitalauncher.app.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

object VitaColors {
    val SkyTop = Color(0xFF0A1E6E)
    val SkyMid = Color(0xFF1C5FCB)
    val SkyBottom = Color(0xFFBFE3FF)

    val StatusBarEdge = Color(0xFF000000)
    val StatusBarInner = Color(0xFF1A1A1A)

    val BubbleHighlight = Color(0xFFFFFFFF)
    val BubbleShadow = Color(0xFF000000)
    val GrabbedRing = Color(0xFF39E8FF)

    val OverviewPanel = Color(0x33FFFFFF)
    val ContextMenuBg = Color(0xE6151515)
}

/** A selectable gradient color theme for the home background. */
data class VitaColorTheme(val label: String, val top: Color, val mid: Color, val bottom: Color)

/** The set of gradient themes offered in the wallpaper picker's Color tab. Index 0 is the
 * original Vita sky-blue default. */
val VitaColorThemes = listOf(
    VitaColorTheme("Ocean", Color(0xFF0A1E6E), Color(0xFF1C5FCB), Color(0xFFBFE3FF)),
    VitaColorTheme("Dusk", Color(0xFF2B0A4E), Color(0xFF7A2CBF), Color(0xFFFFC9E3)),
    VitaColorTheme("Sunset", Color(0xFF4E1A0A), Color(0xFFE0642C), Color(0xFFFFE3A8)),
    VitaColorTheme("Forest", Color(0xFF0A3E1E), Color(0xFF1F9B4E), Color(0xFFD8F5C7)),
    VitaColorTheme("Midnight", Color(0xFF05050F), Color(0xFF1A1A38), Color(0xFF3D3D5C)),
    VitaColorTheme("Blossom", Color(0xFF4E0A2E), Color(0xFFCB1C6F), Color(0xFFFFD9EC)),
)

/** The Vita's signature vertical sky gradient background, from deep blue to pale horizon, or
 * whichever [VitaColorThemes] entry [themeIndex] selects. */
fun vitaSkyBrush(themeIndex: Int = 0): Brush {
    val theme = VitaColorThemes.getOrElse(themeIndex) { VitaColorThemes[0] }
    return Brush.verticalGradient(
        colorStops = arrayOf(
            0.0f to theme.top,
            0.45f to theme.mid,
            1.0f to theme.bottom,
        )
    )
}

/** Black at the top edge of the display, easing to dark grey by the bottom of the status bar. */
fun vitaStatusBarBrush(): Brush = Brush.verticalGradient(
    colors = listOf(VitaColors.StatusBarEdge, VitaColors.StatusBarInner)
)
