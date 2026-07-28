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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
private const val MAX_TILT_ANGLE = 38f
private const val TILT_CAMERA_DISTANCE = 10f

/**
 * The circular "glossy sphere" shell shared by app icons and folder bubbles. Bubbles bob
 * vertically and lean in real 3D (rotationX, not a flat in-plane spin) in reaction to an
 * in-progress vertical page swipe — like they're physically lagging behind the page's motion —
 * each with a bit of per-bubble lag so the whole row doesn't move in perfect lockstep. Swiping/
 * moving to the next page (content sliding up) noses the bubbles down; swiping/moving to the
 * previous page (content sliding down) tips them up.
 *
 * @param swipeProgress the pager's current drag offset (roughly -0.5..0.5); 0 when settled.
 */
@Composable
fun BubbleShell(
    modifier: Modifier = Modifier,
    isGrabbed: Boolean = false,
    swipeProgress: Float = 0f,
    wiggleSeed: Int = 0,
    showRimShade: Boolean = true,
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
    // Positive swipeProgress = advancing to the next page (content sliding up), so a positive
    // rotationX here noses the bubble down/back, away from the viewer, to sell the lag; negative
    // swipeProgress = going back to the previous page (content sliding down) tips it up/forward.
    val animatedTiltX by animateFloatAsState(
        targetValue = swipeProgress * magnitudeFactor * MAX_TILT_ANGLE,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = springStiffness),
        label = "bubbleTilt",
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .offset(y = animatedOffset.dp)
            .graphicsLayer {
                rotationX = animatedTiltX
                cameraDistance = TILT_CAMERA_DISTANCE * density
            }
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
                // Drop shadow rendered before the clip so it falls outside the circle's bounds
                // instead of being cut off by it — lifts the whole bubble off the background.
                .shadow(
                    elevation = 7.dp,
                    shape = CircleShape,
                    ambientColor = VitaColors.BubbleShadow.copy(alpha = 0.55f),
                    spotColor = VitaColors.BubbleShadow.copy(alpha = 0.55f),
                )
                .clip(CircleShape)
                .then(
                    if (isGrabbed) {
                        Modifier.border(3.dp, VitaColors.GrabbedRing, CircleShape)
                    } else Modifier
                ),
        ) {
            content()
            // Glass-sphere shading, sized off the actual draw bounds (rather than a fixed pixel
            // radius) so it reads correctly at every bubble size the app uses. Layered darkest to
            // brightest so each later gradient lightens over the one below it: a rim/base shadow
            // for roundness, then a broad soft sheen, then a small crisp glint for the "glossy"
            // pinpoint reflection real glass/plastic spheres show.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        val w = size.width
                        val h = size.height
                        val minDim = size.minDimension

                        // Held off (transparent) until 72% of the radius so it only darkens the
                        // rim itself, not the icon underneath it. Skipped for folder bubbles,
                        // whose 2x2 icon preview already reaches close to the rim on its own.
                        val rimShade = Brush.radialGradient(
                            0f to Color.Transparent,
                            0.72f to Color.Transparent,
                            1f to VitaColors.BubbleShadow.copy(alpha = 0.28f),
                            center = Offset(w * 0.5f, h * 0.5f),
                            radius = minDim * 0.52f,
                        )
                        val baseShade = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, VitaColors.BubbleShadow.copy(alpha = 0.18f)),
                            startY = h * 0.45f,
                            endY = h,
                        )
                        // Tighter radius + a hot center held further before falling off, for a
                        // more concentrated, glossier-looking sheen.
                        val sheen = Brush.radialGradient(
                            0f to VitaColors.BubbleHighlight.copy(alpha = 0.9f),
                            0.35f to VitaColors.BubbleHighlight.copy(alpha = 0.32f),
                            1f to Color.Transparent,
                            center = Offset(w * 0.32f, h * 0.26f),
                            radius = minDim * 0.42f,
                        )
                        val glint = Brush.radialGradient(
                            colors = listOf(VitaColors.BubbleHighlight.copy(alpha = 0.95f), Color.Transparent),
                            center = Offset(w * 0.25f, h * 0.19f),
                            radius = minDim * 0.16f,
                        )

                        onDrawBehind {
                            if (showRimShade) drawRect(rimShade)
                            drawRect(baseShade)
                            drawRect(sheen)
                            drawRect(glint)
                        }
                    },
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
            showRimShade = false,
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
