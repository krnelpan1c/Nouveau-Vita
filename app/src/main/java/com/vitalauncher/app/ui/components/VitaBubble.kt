package com.vitalauncher.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.model.AppInfo
import com.vitalauncher.app.model.FolderInfo
import com.vitalauncher.app.ui.theme.VitaColors
import kotlin.random.Random
import androidx.compose.foundation.Image

private const val MAX_BOB_OFFSET_DP = 14f
private const val MAX_TILT_ANGLE = 6f

/**
 * The circular "glossy sphere" shell shared by app icons and folder bubbles. Bubbles bob
 * vertically and tilt slightly in the direction of an in-progress vertical page swipe, each with
 * a bit of per-bubble lag so the whole row doesn't move in perfect lockstep.
 *
 * @param swipeProgress the pager's current drag offset (roughly -0.5..0.5); 0 when settled.
 */
@Composable
fun BubbleShell(
    modifier: Modifier = Modifier,
    isGrabbed: Boolean = false,
    swipeProgress: Float = 0f,
    wiggleSeed: Int = 0,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val random = remember(wiggleSeed) { Random(wiggleSeed) }
    val magnitudeFactor = remember(wiggleSeed) { 0.7f + random.nextFloat() * 0.6f }
    val springStiffness = remember(wiggleSeed) { 220f + random.nextFloat() * 380f }

    val animatedOffset by animateFloatAsState(
        targetValue = swipeProgress * magnitudeFactor * MAX_BOB_OFFSET_DP,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = springStiffness),
        label = "bubbleBob",
    )
    val animatedAngle by animateFloatAsState(
        targetValue = swipeProgress * magnitudeFactor * MAX_TILT_ANGLE,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = springStiffness),
        label = "bubbleTilt",
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .offset(y = animatedOffset.dp)
            .rotate(animatedAngle)
            .then(
                if (onClick != null || onLongClick != null) {
                    Modifier.combinedClickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onClick?.invoke() },
                        onLongClick = { onLongClick?.invoke() },
                    )
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(1f)
                .clip(CircleShape)
                .then(
                    if (isGrabbed) {
                        Modifier.border(3.dp, VitaColors.GrabbedRing, CircleShape)
                    } else Modifier
                ),
        ) {
            content()
            // Glossy highlight overlay: bright arc top-left fading out, classic sphere sheen.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                            center = Offset(0.32f, 0.28f).let { Offset(it.x * 200f, it.y * 200f) },
                            radius = 140f,
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f)),
                        )
                    )
            )
        }
    }
}

@Composable
fun AppBubble(
    app: AppInfo,
    size: Dp,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    isGrabbed: Boolean = false,
    swipeProgress: Float = 0f,
    wiggleSeed: Int = app.key.hashCode(),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BubbleShell(
            modifier = Modifier.size(size),
            isGrabbed = isGrabbed,
            swipeProgress = swipeProgress,
            wiggleSeed = wiggleSeed,
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            Image(
                bitmap = app.icon,
                contentDescription = app.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (showLabel) {
            Text(
                text = app.label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(width = size + 16.dp, height = 16.dp),
            )
        }
    }
}

@Composable
fun FolderBubble(
    folder: FolderInfo,
    size: Dp,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    isGrabbed: Boolean = false,
    swipeProgress: Float = 0f,
    wiggleSeed: Int = folder.id.hashCode(),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BubbleShell(
            modifier = Modifier.size(size),
            isGrabbed = isGrabbed,
            swipeProgress = swipeProgress,
            wiggleSeed = wiggleSeed,
            onClick = onClick,
            onLongClick = onLongClick,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.12f)),
            )
            FolderPreviewGrid(folder = folder, modifier = Modifier.fillMaxSize().padding(size * 0.14f))
        }
        if (showLabel) {
            Text(
                text = folder.name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .size(width = size + 16.dp, height = 16.dp),
            )
        }
    }
}

/** Shows up to 4 of the folder's app icons in a mini 2x2 grid as a preview of its contents. */
@Composable
private fun FolderPreviewGrid(folder: FolderInfo, modifier: Modifier = Modifier) {
    val preview = folder.items.filterNotNull().take(4)
    if (preview.isEmpty()) return
    BoxWithConstraints(modifier = modifier) {
        val cell = maxWidth / 2
        preview.forEachIndexed { index, app ->
            val row = index / 2
            val col = index % 2
            Box(
                modifier = Modifier
                    .size(cell)
                    .padding(1.dp)
                    .align(Alignment.TopStart)
                    .offset(x = cell * col, y = cell * row)
                    .clip(CircleShape),
            ) {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
