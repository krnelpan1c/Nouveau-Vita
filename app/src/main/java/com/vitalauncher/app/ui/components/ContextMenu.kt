package com.vitalauncher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.ui.theme.VitaColors

@Composable
fun BubbleContextMenu(
    modifier: Modifier = Modifier,
    canShowInformation: Boolean,
    onCreateFolder: () -> Unit,
    onInformation: () -> Unit,
) {
    Column(
        modifier = modifier
            .width(180.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(VitaColors.ContextMenuBg),
    ) {
        MenuRow(text = "Create Folder", onClick = onCreateFolder)
        if (canShowInformation) {
            MenuDivider()
            MenuRow(text = "Information", onClick = onInformation)
        }
    }
}

@Composable
private fun MenuRow(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    )
}

@Composable
private fun MenuDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .width(156.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.15f)),
    )
}
