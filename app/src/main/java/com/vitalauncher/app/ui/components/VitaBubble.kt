package com.vitalauncher.app.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vitalauncher.app.model.AppInfo
import com.vitalauncher.app.model.FolderInfo
import com.vitalauncher.app.ui.theme.VitaColors
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Process-wide cache of pre-rendered bubble drop shadows and circular glass-sphere gloss overlays,
 * keyed by pixel diameter. Every bubble on screen shares the exact same 2–3 cached bitmaps,
 * replacing 5 per-bubble Skia gradient shader passes (100 shaders during a 2-page swipe) with
 * two lightweight GPU texture blits.
 */
private object BubbleOverlayCache {
    class CachedShadow(val bitmap: ImageBitmap, val padPx: Int)

    private val shadows = ConcurrentHashMap<Int, CachedShadow>()
    private val glossWithRim = ConcurrentHashMap<Int, ImageBitmap>()
    private val glossWithoutRim = ConcurrentHashMap<Int, ImageBitmap>()

    fun shadow(sizePx: Int): CachedShadow = shadows.getOrPut(sizePx) { buildShadow(sizePx) }

    fun gloss(sizePx: Int, showRimShade: Boolean): ImageBitmap {
        val map = if (showRimShade) glossWithRim else glossWithoutRim
        return map.getOrPut(sizePx) { buildGloss(sizePx, showRimShade) }
    }

    private fun buildShadow(sizePx: Int): CachedShadow {
        val r = sizePx / 2f
        val pad = ceil(r * 0.40f).toInt().coerceAtLeast(1)
        val dim = sizePx + pad * 2
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cx = pad + r
        val cy = pad + r + r * 0.14f
        val shadowRadius = (r * 1.22f).coerceAtLeast(1f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy,
                shadowRadius,
                intArrayOf(
                    0x00000000,
                    VitaColors.BubbleShadow.copy(alpha = 0.40f).toArgb(),
                    VitaColors.BubbleShadow.copy(alpha = 0.22f).toArgb(),
                    0x00000000,
                ),
                floatArrayOf(0f, 0.78f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawCircle(cx, cy, shadowRadius, paint)
        bmp.prepareToDraw()
        return CachedShadow(bmp.asImageBitmap(), pad)
    }

    private fun buildGloss(sizePx: Int, showRimShade: Boolean): ImageBitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val w = sizePx.toFloat()
        val h = sizePx.toFloat()
        val minDim = sizePx.toFloat()
        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = minDim * 0.5f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw each glass-sphere gradient directly as an anti-aliased circle so shading and shine
        // are strictly bounded to the bubble silhouette with zero corner bleed.
        if (showRimShade) {
            paint.shader = RadialGradient(
                cx,
                cy,
                (minDim * 0.52f).coerceAtLeast(1f),
                intArrayOf(
                    0x00000000,
                    0x00000000,
                    VitaColors.BubbleShadow.copy(alpha = 0.28f).toArgb(),
                ),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, radius, paint)
        }

        paint.shader = LinearGradient(
            0f,
            h * 0.45f,
            0f,
            h,
            0x00000000,
            VitaColors.BubbleShadow.copy(alpha = 0.18f).toArgb(),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)

        paint.shader = RadialGradient(
            w * 0.32f,
            h * 0.26f,
            (minDim * 0.42f).coerceAtLeast(1f),
            intArrayOf(
                VitaColors.BubbleHighlight.copy(alpha = 0.9f).toArgb(),
                VitaColors.BubbleHighlight.copy(alpha = 0.32f).toArgb(),
                0x00FFFFFF,
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)

        paint.shader = RadialGradient(
            w * 0.25f,
            h * 0.19f,
            (minDim * 0.16f).coerceAtLeast(1f),
            VitaColors.BubbleHighlight.copy(alpha = 0.95f).toArgb(),
            0x00FFFFFF,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, radius, paint)

        bmp.prepareToDraw()
        return bmp.asImageBitmap()
    }
}

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
                .drawWithContent {
                    val sizePx = size.minDimension.roundToInt()
                    if (sizePx > 0) {
                        val cachedShadow = BubbleOverlayCache.shadow(sizePx)
                        drawImage(
                            image = cachedShadow.bitmap,
                            dstOffset = IntOffset(-cachedShadow.padPx, -cachedShadow.padPx),
                            dstSize = IntSize(cachedShadow.bitmap.width, cachedShadow.bitmap.height),
                        )
                    }
                    drawContent()
                    if (sizePx > 0) {
                        val cachedGloss = BubbleOverlayCache.gloss(sizePx, showRimShade)
                        drawImage(
                            image = cachedGloss,
                            dstSize = IntSize(sizePx, sizePx),
                        )
                    }
                }
                .then(
                    if (isGrabbed) {
                        Modifier.border(3.dp, VitaColors.GrabbedRing, CircleShape)
                    } else Modifier
                ),
        ) {
            content()
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
                    .drawBehind {
                        drawCircle(color = Color.White.copy(alpha = 0.12f))
                    },
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
                    .offset(x = cell * col, y = cell * row),
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
