package com.vitalauncher.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.LauncherViewModel
import com.vitalauncher.app.audio.SoundManager
import com.vitalauncher.app.model.AppRepository
import com.vitalauncher.app.model.FolderInfo
import com.vitalauncher.app.model.SlotLocation
import com.vitalauncher.app.ui.components.AppBubble
import com.vitalauncher.app.ui.components.FOLDER_PITCH_SCALE
import com.vitalauncher.app.ui.components.PageGrid
import com.vitalauncher.app.ui.components.STATUS_BAR_HEIGHT

@Composable
fun FolderScreen(viewModel: LauncherViewModel, folder: FolderInfo, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    BackHandler(enabled = true) {
        if (viewModel.isEditMode) viewModel.cancelGrabOrExit() else viewModel.closeFolder()
    }

    var isRenaming by remember(folder.id) { mutableStateOf(false) }
    var nameField by remember(folder.id) {
        mutableStateOf(TextFieldValue(folder.name, selection = TextRange(folder.name.length)))
    }
    var hasFocusedOnce by remember(folder.id) { mutableStateOf(false) }
    val focusRequester = remember(folder.id) { FocusRequester() }

    fun commitRename() {
        viewModel.renameFolder(folder, nameField.text)
        isRenaming = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = STATUS_BAR_HEIGHT, bottom = 56.dp)) {
            if (isRenaming) {
                BasicTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                    cursorBrush = SolidColor(Color.White),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { commitRename() }),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 20.dp, bottom = 8.dp)
                        .focusRequester(focusRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) hasFocusedOnce = true else if (hasFocusedOnce) commitRename()
                        },
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            } else {
                Text(
                    text = folder.name,
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 20.dp, bottom = 8.dp)
                        .clickable {
                            SoundManager.playItemTap()
                            nameField = TextFieldValue(folder.name, selection = TextRange(folder.name.length))
                            isRenaming = true
                        },
                )
            }
            val focused = viewModel.focusedSlot
            val focusedSlotIndex = if (viewModel.showFocusRing && focused is SlotLocation.InFolder && focused.folderId == folder.id) {
                focused.slotIndex
            } else null
            PageGrid(
                modifier = Modifier.padding(horizontal = 24.dp),
                focusedSlotIndex = focusedSlotIndex,
                focusRingDiameter = 94.dp,
                horizontalPitchScale = FOLDER_PITCH_SCALE,
            ) { slotIndex ->
                val app = folder.items.getOrNull(slotIndex)
                if (app != null) {
                    AppBubble(
                        app = app,
                        size = 92.dp,
                        onClick = {
                            SoundManager.notifyAppLaunched()
                            AppRepository.launch(context, app)
                        },
                        onLongClick = {
                            viewModel.enterEditModeAndGrab(SlotLocation.InFolder(folder.id, slotIndex))
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 24.dp, bottom = 20.dp)
                .clickable {
                    SoundManager.playItemTap()
                    viewModel.closeFolder()
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            Text(text = "  Back", color = Color.White, fontSize = 16.sp)
        }
    }
}
