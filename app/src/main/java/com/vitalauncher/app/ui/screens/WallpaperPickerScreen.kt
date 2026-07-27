package com.vitalauncher.app.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.LauncherViewModel
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.BackgroundMode
import com.vitalauncher.app.ui.components.STATUS_BAR_HEIGHT
import com.vitalauncher.app.ui.theme.VitaColorThemes
import com.vitalauncher.app.ui.theme.vitaSkyBrush

private enum class WallpaperTab { COLOR, WALLPAPER }

/**
 * The fullscreen "change background" window opened from edit mode: a Color tab for picking one
 * of the built-in gradient themes, and a Wallpaper tab that hands off to the system's own
 * wallpaper picker for a static image or live wallpaper.
 */
@Composable
fun WallpaperPickerScreen(viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(WallpaperTab.COLOR.ordinal) }

    BackHandler(enabled = true) { viewModel.closeWallpaperPicker() }

    val blockTouchesInteractionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xE6101018))
            // A plain Box with just a background doesn't consume touches in Compose, so without
            // this a tap on any blank area of the picker falls straight through to the edit-mode
            // grid underneath and can grab/move/merge icons the user never meant to touch.
            .clickable(indication = null, interactionSource = blockTouchesInteractionSource) {},
    ) {
        Column(Modifier.fillMaxSize().padding(top = STATUS_BAR_HEIGHT)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier.clickable {
                        SoundManager.playItemTap()
                        viewModel.closeWallpaperPicker()
                    },
                )
                Text(
                    text = "  Background",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                WallpaperTab.entries.forEach { entry ->
                    TabChip(
                        label = if (entry == WallpaperTab.COLOR) "Color" else "Wallpaper",
                        selected = tab == entry.ordinal,
                        onClick = {
                            SoundManager.playItemTap()
                            tab = entry.ordinal
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            when (WallpaperTab.entries[tab]) {
                WallpaperTab.COLOR -> ColorThemeGrid(viewModel)
                WallpaperTab.WALLPAPER -> WallpaperTabContent(
                    onOpenSystemPicker = {
                        SoundManager.playItemTap()
                        viewModel.useSystemWallpaper()
                        val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                        if (intent.resolveActivity(context.packageManager) != null) {
                            context.startActivity(Intent.createChooser(intent, "Set wallpaper"))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, Color.White.copy(alpha = if (selected) 0.6f else 0.2f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ColorThemeGrid(viewModel: LauncherViewModel) {
    val selectedIndex = if (viewModel.backgroundMode == BackgroundMode.GRADIENT) {
        viewModel.colorThemeIndex
    } else {
        -1
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(VitaColorThemes.size) { index ->
            val theme = VitaColorThemes[index]
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(vitaSkyBrush(index))
                        .border(
                            width = if (selectedIndex == index) 3.dp else 1.dp,
                            color = if (selectedIndex == index) Color.White else Color.White.copy(alpha = 0.3f),
                            shape = CircleShape,
                        )
                        .clickable {
                            SoundManager.playItemTap()
                            viewModel.setColorTheme(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selectedIndex == index) {
                        Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = Color.White)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(text = theme.label, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun WallpaperTabContent(onOpenSystemPicker: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Use a photo or a live wallpaper from your device as the home background instead of a solid color.",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 20.dp),
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White.copy(alpha = 0.85f))
                .clickable(onClick = onOpenSystemPicker)
                .padding(horizontal = 24.dp, vertical = 14.dp),
        ) {
            Text(text = "Choose Wallpaper", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}
