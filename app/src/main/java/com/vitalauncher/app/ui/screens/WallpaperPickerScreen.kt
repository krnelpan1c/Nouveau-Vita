package com.vitalauncher.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.LauncherViewModel
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.BackgroundMode
import com.vitalauncher.app.ui.components.STATUS_BAR_HEIGHT
import com.vitalauncher.app.ui.theme.VitaColorThemes
import com.vitalauncher.app.ui.theme.vitaSkyBrush
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class CustomizePage(val depth: Int, val title: String) {
    ROOT(0, "Customize"),
    BACKGROUND(1, "Background"),
    GRADIENT(2, "Gradient"),
    MUSIC(1, "Music"),
}

/**
 * The fullscreen "Customize" window opened from edit mode, laid out like the PS Vita's system
 * settings: a centered title and a list of icon + label rows with dividers and right-pointing
 * arrows, drawn over the launcher's dark translucent backdrop.
 *
 * Customize -> Background (Gradient list / system Wallpaper) and Music (Default / custom / Add).
 */
@Composable
fun WallpaperPickerScreen(viewModel: LauncherViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(CustomizePage.ROOT) }

    fun goBack() {
        when (page) {
            CustomizePage.ROOT -> viewModel.closeWallpaperPicker()
            CustomizePage.GRADIENT -> page = CustomizePage.BACKGROUND
            else -> page = CustomizePage.ROOT
        }
    }

    BackHandler(enabled = true) { goBack() }

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
            // Vita-style header: centered title with a back arrow on the left.
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 20.dp)
                        .clickable {
                            SoundManager.playItemTap()
                            goBack()
                        },
                )
                Text(
                    text = page.title,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.25f)))

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState.depth >= initialState.depth
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally { it / 4 * dir } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it / 4 * dir } + fadeOut())
                },
                label = "customizePage",
                modifier = Modifier.fillMaxSize(),
            ) { current ->
                when (current) {
                    CustomizePage.ROOT -> ListColumn {
                        item {
                            SettingsRow(Icons.Filled.Image, "Background", showArrow = true) {
                                page = CustomizePage.BACKGROUND
                            }
                        }
                        item {
                            SettingsRow(Icons.Filled.LibraryMusic, "Music", showArrow = true) {
                                page = CustomizePage.MUSIC
                            }
                        }
                    }

                    CustomizePage.BACKGROUND -> BackgroundList(
                        viewModel = viewModel,
                        onOpenGradients = { page = CustomizePage.GRADIENT },
                        onOpenSystemPicker = {
                            viewModel.useSystemWallpaper()
                            val intent = Intent(Intent.ACTION_SET_WALLPAPER)
                            if (intent.resolveActivity(context.packageManager) != null) {
                                context.startActivity(Intent.createChooser(intent, "Set wallpaper"))
                            }
                        },
                    )

                    CustomizePage.GRADIENT -> GradientList(viewModel)
                    CustomizePage.MUSIC -> MusicList()
                }
            }
        }
    }
}

/** A centered, width-limited list area so rows don't stretch edge to edge on wide screens. */
@Composable
private fun ListColumn(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.fillMaxHeight().fillMaxWidth(0.88f),
            content = content,
        )
    }
}

@Composable
private fun BackgroundList(
    viewModel: LauncherViewModel,
    onOpenGradients: () -> Unit,
    onOpenSystemPicker: () -> Unit,
) {
    ListColumn {
        item {
            SettingsRow(
                icon = Icons.Filled.Palette,
                label = "Gradient",
                showArrow = true,
                checked = viewModel.backgroundMode == BackgroundMode.GRADIENT,
                onClick = onOpenGradients,
            )
        }
        item {
            SettingsRow(
                icon = Icons.Filled.Wallpaper,
                label = "Wallpaper",
                showArrow = true,
                checked = viewModel.backgroundMode == BackgroundMode.SYSTEM_WALLPAPER,
                onClick = onOpenSystemPicker,
            )
        }
    }
}

@Composable
private fun GradientList(viewModel: LauncherViewModel) {
    val selectedIndex = if (viewModel.backgroundMode == BackgroundMode.GRADIENT) {
        viewModel.colorThemeIndex
    } else {
        -1
    }
    ListColumn {
        items(VitaColorThemes.size) { index ->
            SettingsRow(
                label = VitaColorThemes[index].label,
                leading = {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(vitaSkyBrush(index))
                            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(8.dp)),
                    )
                },
                checked = selectedIndex == index,
                showCheckbox = true,
                onClick = { viewModel.setColorTheme(index) },
            )
        }
    }
}

@Composable
private fun MusicList() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isCustom by remember { mutableStateOf(SoundManager.isCustomMusic(context)) }
    var customName by remember { mutableStateOf(SoundManager.customMusicName(context)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val name = queryDisplayName(context, uri) ?: "Custom track"
            val ok = withContext(Dispatchers.IO) { SoundManager.setCustomMusic(context, uri, name) }
            if (ok) {
                isCustom = true
                customName = name
            } else {
                Toast.makeText(context, "Couldn't play that audio file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    ListColumn {
        item {
            SettingsRow(
                icon = Icons.Filled.MusicNote,
                label = "Default",
                checked = !isCustom,
                showCheckbox = true,
                onClick = {
                    SoundManager.useDefaultMusic(context)
                    isCustom = false
                },
            )
        }
        val saved = customName
        if (saved != null) {
            item {
                SettingsRow(
                    icon = Icons.Filled.MusicNote,
                    label = saved,
                    checked = isCustom,
                    showCheckbox = true,
                    onClick = {
                        SoundManager.useCustomMusic(context)
                        isCustom = SoundManager.isCustomMusic(context)
                    },
                )
            }
        }
        item {
            SettingsRow(
                icon = Icons.Filled.Add,
                label = "Add",
                showArrow = true,
                onClick = { picker.launch(arrayOf("audio/*")) },
            )
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

/** One Vita-style settings row: icon, label, then a checkbox or a right-pointing arrow, with a
 * thin divider underneath and a soft highlight while pressed. */
@Composable
private fun SettingsRow(
    icon: ImageVector? = null,
    label: String,
    showArrow: Boolean = false,
    showCheckbox: Boolean = false,
    checked: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (pressed) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                .clickable(interactionSource = interaction, indication = null) {
                    SoundManager.playItemTap()
                    onClick()
                }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                leading != null -> leading()
                icon != null -> Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showCheckbox) {
                VitaCheckbox(checked)
            } else {
                if (checked) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp).padding(end = 0.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                if (showArrow) ArrowTriangle()
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.2f)))
    }
}

@Composable
private fun VitaCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f))
            .border(1.5.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Filled.Check, contentDescription = null, tint = Color(0xFF101018), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ArrowTriangle() {
    Canvas(Modifier.size(width = 10.dp, height = 14.dp)) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, size.height / 2f)
            lineTo(0f, size.height)
            close()
        }
        drawPath(path, Color.White.copy(alpha = 0.85f))
    }
}
