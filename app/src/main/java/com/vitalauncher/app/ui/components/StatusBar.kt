package com.vitalauncher.app.ui.components

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.vitalauncher.app.model.AppInfo
import com.vitalauncher.app.ui.theme.vitaStatusBarBrush
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Height of [VitaStatusBar]; content sitting beneath the always-on-top status bar layer should
 * pad its top by this much so it doesn't render underneath it. */
val STATUS_BAR_HEIGHT = 32.dp

private const val MAX_STATUS_BAR_RECENTS = 6
private val STATUS_BAR_BUBBLE_SIZE = 20.dp
private val STATUS_BAR_HOME_SLOT_WIDTH = 26.dp
private val STATUS_BAR_APP_SLOT_WIDTH = 24.dp
private val STATUS_BAR_ITEM_GAP = 4.dp

private val STATUS_BAR_SLIDE_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

private val STATUS_BAR_POP_SPRING = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

private val STATUS_BAR_EXIT_SPEC = tween<Float>(
    durationMillis = 180,
    easing = FastOutSlowInEasing,
)

private val BatteryLeftCapShape = RoundedCornerShape(topStart = 2.dp, bottomStart = 2.dp)
private val BatteryRightCapShape = RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp)
private val BatteryNubShape = RoundedCornerShape(topEnd = 1.5.dp, bottomEnd = 1.5.dp)

private val BatteryMetalBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0xFFFFFFFF),
        0.22f to Color(0xFFE4EAEE),
        0.55f to Color(0xFFAAB4BC),
        0.85f to Color(0xFF707980),
        1.0f to Color(0xFFB8C1C8),
    )
)

private val BatteryEmptyBlackBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0xFF474E54),
        0.18f to Color(0xFF23282C),
        0.55f to Color(0xFF0A0C0E),
        0.86f to Color(0xFF040506),
        1.0f to Color(0xFF282D32),
    )
)

private val BatteryFillGreenBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0xFFCFFFC4),
        0.18f to Color(0xFF7DF063),
        0.52f to Color(0xFF39C429),
        0.84f to Color(0xFF1F8F18),
        1.0f to Color(0xFF4CD93B),
    )
)

private val BatteryFillRedBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0xFFFFB8B8),
        0.18f to Color(0xFFF25555),
        0.52f to Color(0xFFD62424),
        0.84f to Color(0xFF941111),
        1.0f to Color(0xFFC92828),
    )
)

private val BatteryGlassSheenBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0x55FFFFFF),
        0.42f to Color(0x14FFFFFF),
        0.48f to Color(0x00FFFFFF),
        1.0f to Color(0x00FFFFFF),
    )
)

private data class AnimatedRecentEntry(
    val app: AppInfo,
    val targetIndex: Int,
    val exiting: Boolean,
)

@Composable
fun VitaStatusBar(
    recentApps: List<AppInfo> = emptyList(),
    modifier: Modifier = Modifier,
    onHomeClick: (() -> Unit)? = null,
    onRecentAppClick: ((AppInfo) -> Unit)? = null,
) {
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(STATUS_BAR_HEIGHT)
            .background(vitaStatusBarBrush()),
    ) {
        CenteredRecentCluster(
            recentApps = recentApps,
            onHomeClick = onHomeClick,
            onRecentAppClick = onRecentAppClick,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = time,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Box(modifier = Modifier.width(12.dp))
            BatteryGlyph(percent = batteryPct)
        }
    }
}

/**
 * Center-aligned cluster containing the Home icon (leftmost) and up to 6 recent app bubbles
 * ordered most recent (left) to oldest (right). Every item's horizontal position is driven in the
 * draw phase via `graphicsLayer`, so new arrivals, reopened apps sliding back to slot 0, and
 * removed apps all glide smoothly while keeping the whole group centered on the display.
 */
@Composable
private fun CenteredRecentCluster(
    recentApps: List<AppInfo>,
    onHomeClick: (() -> Unit)?,
    onRecentAppClick: ((AppInfo) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val homeW = with(density) { STATUS_BAR_HOME_SLOT_WIDTH.toPx() }
    val appW = with(density) { STATUS_BAR_APP_SLOT_WIDTH.toPx() }
    val gap = with(density) { STATUS_BAR_ITEM_GAP.toPx() }

    val visibleApps = recentApps.take(MAX_STATUS_BAR_RECENTS)
    val visibleKeys = visibleApps.map { it.key }
    val entries = remember { mutableStateListOf<AnimatedRecentEntry>() }

    // Synchronize rendered entries with [visibleApps]: retain removed entries temporarily so they
    // can animate out instead of popping out abruptly.
    LaunchedEffect(visibleKeys) {
        val indexByKey = HashMap<String, Int>(visibleApps.size)
        visibleApps.forEachIndexed { idx, app -> indexByKey[app.key] = idx }

        // Update existing entries (keep in-place, update targetIndex, or mark exiting).
        for (i in entries.indices) {
            val existing = entries[i]
            val newIdx = indexByKey[existing.app.key]
            if (newIdx != null) {
                entries[i] = AnimatedRecentEntry(
                    app = visibleApps[newIdx],
                    targetIndex = newIdx,
                    exiting = false,
                )
            } else if (!existing.exiting) {
                entries[i] = existing.copy(exiting = true)
            }
        }
        // Add newly arrived apps.
        val existingKeys = entries.mapTo(HashSet()) { it.app.key }
        visibleApps.forEachIndexed { idx, app ->
            if (app.key !in existingKeys) {
                entries.add(AnimatedRecentEntry(app = app, targetIndex = idx, exiting = false))
            }
        }
    }

    val count = visibleApps.size
    val totalWidthPx = homeW + count * (gap + appW)
    val leftEdgePx = -totalWidthPx / 2f
    val homeTargetX = leftEdgePx + homeW / 2f
    val animatedHomeX by animateFloatAsState(
        targetValue = homeTargetX,
        animationSpec = STATUS_BAR_SLIDE_SPRING,
        label = "statusBarHomeX",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // Home icon (leftmost in the centered cluster).
        Box(
            modifier = Modifier
                .zIndex(2f)
                .graphicsLayer { translationX = animatedHomeX }
                .width(STATUS_BAR_HOME_SLOT_WIDTH)
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

        // Recent app bubbles, sliding smoothly between slots as apps enter, reopen, or clear.
        entries.forEach { entry ->
            key(entry.app.key) {
                val targetCenterX = leftEdgePx + homeW + gap +
                    entry.targetIndex * (appW + gap) + appW / 2f
                AnimatedStatusBarAppBubble(
                    app = entry.app,
                    targetCenterX = targetCenterX,
                    enterOffsetPx = if (entry.targetIndex == 0) -(appW + gap) * 0.55f else 0f,
                    isMostRecent = entry.targetIndex == 0 && !entry.exiting,
                    exiting = entry.exiting,
                    onExitFinished = {
                        entries.removeAll { it.app.key == entry.app.key && it.exiting }
                    },
                    onClick = if (!entry.exiting && onRecentAppClick != null) {
                        { onRecentAppClick(entry.app) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun AnimatedStatusBarAppBubble(
    app: AppInfo,
    targetCenterX: Float,
    enterOffsetPx: Float,
    isMostRecent: Boolean,
    exiting: Boolean,
    onExitFinished: () -> Unit,
    onClick: (() -> Unit)?,
) {
    val animX = remember { Animatable(targetCenterX + enterOffsetPx) }
    val animScale = remember { Animatable(0.45f) }
    val animAlpha = remember { Animatable(0f) }

    LaunchedEffect(targetCenterX, exiting) {
        if (!exiting) {
            animX.animateTo(targetCenterX, STATUS_BAR_SLIDE_SPRING)
        }
    }

    LaunchedEffect(exiting) {
        if (!exiting) {
            coroutineScope {
                launch { animScale.animateTo(1f, STATUS_BAR_POP_SPRING) }
                launch { animAlpha.animateTo(1f, tween(160)) }
            }
        } else {
            coroutineScope {
                launch { animScale.animateTo(0.35f, STATUS_BAR_EXIT_SPEC) }
                launch { animAlpha.animateTo(0f, STATUS_BAR_EXIT_SPEC) }
            }
            onExitFinished()
        }
    }

    Box(
        modifier = Modifier
            // Keep the bubble moving into slot 0 above the bubbles shifting right underneath it.
            .zIndex(if (isMostRecent) 1f else 0f)
            .graphicsLayer {
                translationX = animX.value
                scaleX = animScale.value
                scaleY = animScale.value
                alpha = animAlpha.value
            }
            .width(STATUS_BAR_APP_SLOT_WIDTH)
            .fillMaxHeight()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        BubbleShell(modifier = Modifier.size(STATUS_BAR_BUBBLE_SIZE)) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun BatteryGlyph(percent: Int) {
    val clamped = percent.coerceIn(0, 100)
    val fillFraction = clamped / 100f
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Left metallic contact cap.
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 11.dp)
                .clip(BatteryLeftCapShape)
                .background(BatteryMetalBrush),
        )
        // Main cylindrical gradient body: pure green/red/black vertical gradient with glass sheen,
        // no top or bottom white outline.
        Box(
            modifier = Modifier
                .size(width = 21.dp, height = 11.dp)
                .background(BatteryEmptyBlackBrush),
        ) {
            if (fillFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fillFraction)
                        .background(if (clamped <= 15) BatteryFillRedBrush else BatteryFillGreenBrush),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BatteryGlassSheenBrush),
            )
        }
        // Right metallic contact cap + protruding positive terminal nub.
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 11.dp)
                .clip(BatteryRightCapShape)
                .background(BatteryMetalBrush),
        )
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 5.dp)
                .clip(BatteryNubShape)
                .background(BatteryMetalBrush),
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
