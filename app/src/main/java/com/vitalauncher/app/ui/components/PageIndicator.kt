package com.vitalauncher.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun PageIndicatorDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    Column(modifier = modifier) {
        repeat(count) { index ->
            val isCurrent = index == current
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .size(if (isCurrent) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (isCurrent) 0.95f else 0.45f)),
            )
        }
    }
}
