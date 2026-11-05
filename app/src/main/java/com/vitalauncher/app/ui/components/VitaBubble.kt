package com.vitalauncher.app.ui.components

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
import androidx.compose.foundation.Image


/** The circular "glossy sphere" shell shared by app icons and folder bubbles. */
@Composable
fun BubbleShell(
    modifier: Modifier = Modifier,
    isGrabbed: Boolean = false,
    showRimShade: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
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
                // Soft drop shadow faked with a cached radial gradient drawn before the clip, so it
                // falls outside the circle without the cost of a real elevation shadow per bubble.
                .drawWithCache {
                    val r = size.minDimension / 2f
                    val shadowCenter = Offset(size.width / 2f, size.height / 2f + r * 0.14f)
                    val shadowRadius = r * 1.22f
                    val shadow = Brush.radialGradient(
                        0f to Color.Transparent,
                        0.78f to VitaColors.BubbleShadow.copy(alpha = 0.40f),
                        0.88f to VitaColors.BubbleShadow.copy(alpha = 0.22f),
                        1f to Color.Transparent,
                        center = shadowCenter,
                        radius = shadowRadius,
                    )
                    onDrawBehind {
                        drawCircle(shadow, radius = shadowRadius, center = shadowCenter)
                    }
                }
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
