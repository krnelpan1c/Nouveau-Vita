package com.vitalauncher.app.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.ui.theme.vitaStatusBarBrush
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Height of [VitaStatusBar]; content sitting beneath the always-on-top status bar layer should
 * pad its top by this much so it doesn't render underneath it. */
val STATUS_BAR_HEIGHT = 32.dp

@Composable
fun VitaStatusBar(modifier: Modifier = Modifier, onHomeClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    var time by remember { mutableStateOf(currentTimeString()) }
    var batteryPct by remember { mutableStateOf(readBatteryPercent(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            time = currentTimeString()
            batteryPct = readBatteryPercent(context)
            delay(15_000)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(STATUS_BAR_HEIGHT)
            .background(vitaStatusBarBrush()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(56.dp)
                .fillMaxHeight()
                .then(if (onHomeClick != null) Modifier.clickable { onHomeClick() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = "Home",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Box(modifier = Modifier.weight(1f))
        Text(
            text = time,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Box(modifier = Modifier.width(12.dp))
        BatteryGlyph(percent = batteryPct)
        Box(modifier = Modifier.width(12.dp))
    }
}

@Composable
private fun BatteryGlyph(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    Box(
        modifier = Modifier
            .size(width = 22.dp, height = 11.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.25f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width((22 * (clamped / 100f)).dp)
                .background(if (clamped <= 15) Color(0xFFE04040) else Color(0xFF7CE86B)),
        )
    }
}

private fun currentTimeString(): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())

private fun readBatteryPercent(context: Context): Int {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val batteryStatus: Intent? = context.registerReceiver(null, filter)
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (level < 0 || scale <= 0) return 100
    return (level * 100 / scale)
}
