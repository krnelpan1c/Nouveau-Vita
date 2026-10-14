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
    VitaColorTheme("Aurora", Color(0xFF062B3A), Color(0xFF14B8A6), Color(0xFFC8FFE9)),
    VitaColorTheme("Ember", Color(0xFF2A0505), Color(0xFFC62828), Color(0xFFFFB199)),
    VitaColorTheme("Lagoon", Color(0xFF003B46), Color(0xFF07A8C4), Color(0xFFD2F6FF)),
    VitaColorTheme("Lavender", Color(0xFF241B4B), Color(0xFF8E7CE0), Color(0xFFEDE6FF)),
    VitaColorTheme("Citrus", Color(0xFF4A3B00), Color(0xFFF2B300), Color(0xFFFFF7C2)),
    VitaColorTheme("Mint", Color(0xFF0B3B35), Color(0xFF3DCCA4), Color(0xFFE4FFF5)),
    VitaColorTheme("Rose Gold", Color(0xFF4A2331), Color(0xFFE08A9A), Color(0xFFFFE7DA)),
    VitaColorTheme("Steel", Color(0xFF1B232B), Color(0xFF5E7488), Color(0xFFD5DEE6)),
    VitaColorTheme("Crimson Night", Color(0xFF0D0214), Color(0xFF6E1038), Color(0xFFE5486F)),
    VitaColorTheme("Tropical", Color(0xFF0B3D91), Color(0xFF12C2A5), Color(0xFFFFF29E)),
    VitaColorTheme("Cotton Candy", Color(0xFF5B3FA6), Color(0xFFFF7EB6), Color(0xFFB9F1FF)),
    VitaColorTheme("Desert", Color(0xFF3D1F0B), Color(0xFFC77D3A), Color(0xFFF7E1B5)),
    VitaColorTheme("Glacier", Color(0xFF16405E), Color(0xFF7FC4E8), Color(0xFFFFFFFF)),
    VitaColorTheme("Graphite", Color(0xFF0B0B0D), Color(0xFF2B2B30), Color(0xFF767680)),
    VitaColorTheme("Emerald", Color(0xFF02281A), Color(0xFF0E8F5B), Color(0xFF7DF2B8)),
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
