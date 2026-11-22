package com.example.ui.glass

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.widthIn
import com.example.ui.theme.GlassBandBorder
import com.example.ui.theme.GlassBandFill
import com.example.ui.theme.GlassCardBorder
import com.example.ui.theme.GlassCardFill
import com.example.ui.theme.GlassPillActiveBorder
import com.example.ui.theme.GlassPillActiveFill
import com.example.ui.theme.GlassPillBorder
import com.example.ui.theme.GlassPillFill
import com.example.ui.theme.GlassRootBlack
import com.example.ui.theme.GlassSliderBorder
import com.example.ui.theme.GlassSliderBottom
import com.example.ui.theme.GlassSliderMid
import com.example.ui.theme.GlassSliderTop
import com.example.ui.theme.OverlayPillBorder
import com.example.ui.theme.OverlayPillFill
import com.example.ui.theme.OverlayPillPressedBorder
import com.example.ui.theme.OverlayPillPressedFill
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle

/**
 * Exact spring physics spec: stiffness = 380f, dampingRatio = 0.86f.
 */
fun <T> glassSpring() = spring<T>(dampingRatio = 0.86f, stiffness = 380f)

/**
 * Normalizes any accent color to HSV saturation [0.50..0.90] and value = 0.95 for rich ambient glow.
 */
fun normalizeAmbientGlowColor(color: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] = hsv[1].coerceIn(0.50f, 0.90f)
    hsv[2] = 0.95f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Full-screen pure black (#FF000000) root with elliptical 4-stop RadialGradient Ambient Glow:
 * cx = width * 0.48, cy = height * 0.34, rx = width * 0.56, ry = height * 0.62,
 * alpha stops: 110/255 (0.0), 58/255 (0.42), 18/255 (0.76), 0 (1.0), 500ms ARGB interpolation.
 * Zero real-time blur.
 */
@Composable
fun AmbientGlowBackground(
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    val normalizedTarget = remember(accentColor) { normalizeAmbientGlowColor(accentColor) }
    val animatedColor by animateColorAsState(
        targetValue = normalizedTarget,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "ambient_glow_color",
    )

    val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val matrix = remember { Matrix() }
    val shaderCache = remember {
        object {
            var width: Float = -1f
            var height: Float = -1f
            var rgb: Int = -1
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawRect(color = GlassRootBlack)
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val rgb = animatedColor.toArgb() and 0x00FFFFFF
        if (shaderCache.width != w || shaderCache.height != h || shaderCache.rgb != rgb || paint.shader == null) {
            val cx = w * 0.48f
            val cy = h * 0.34f
            val rx = (w * 0.56f).coerceAtLeast(1f)
            val ry = (h * 0.62f).coerceAtLeast(1f)
            val colors = intArrayOf(
                (110 shl 24) or rgb,
                (58 shl 24) or rgb,
                (18 shl 24) or rgb,
                0x00000000 or rgb,
            )
            val stops = floatArrayOf(0f, 0.42f, 0.76f, 1f)
            val shader = RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP)
            matrix.reset()
            matrix.setScale(rx, ry)
            matrix.postTranslate(cx, cy)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            shaderCache.width = w
            shaderCache.height = h
            shaderCache.rgb = rgb
        }

        drawContext.canvas.nativeCanvas.drawRect(0f, 0f, w, h, paint)
    }
}

/**
 * GPU-accelerated press (scale 0.96f) & hover (scale 1.03f) feedback modifier.
 */
fun Modifier.glassInteractive(
    interactionSource: MutableInteractionSource,
    enableHoverScale: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.96f
            hovered && enableHoverScale -> 1.03f
            else -> 1f
        },
        animationSpec = if (pressed) {
            tween(durationMillis = 90)
        } else {
            tween(durationMillis = 200, easing = FastOutSlowInEasing)
        },
        label = "glass_touch_scale",
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .hoverable(interactionSource = interactionSource)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
        )
}

/**
 * Main section container (Glass Band): fill #14FFFFFF, border 1dp #22FFFFFF, radius 26dp.
 */
@Composable
fun GlassBand(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassBandFill)
            .border(1.dp, GlassBandBorder, shape)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Opaque dark content card (Tiles & Cards): fill #FF1C1C22, border 1dp #22FFFFFF, radius 16dp.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    borderColor: Color = GlassCardBorder,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(GlassCardFill)
            .border(1.dp, borderColor, shape),
        content = content,
    )
}

/**
 * Rounded 12dp icon badge with 18% accent fill + 45% accent stroke for rich interactive cards.
 */
@Composable
fun AccentIconBadge(
    icon: ImageVector,
    accent: Color,
    contentDescription: String? = null,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.45f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * Recessed metric mini-pill (#12FFFFFF) for dashboard / bus telemetry cards.
 */
@Composable
fun RecessedMetricPill(
    label: String,
    value: String,
    valueColor: Color = TextPrimary,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color(0x12FFFFFF))
            .border(1.dp, Color(0x18FFFFFF), shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextSubtitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Circular vector-only glass icon button (48dp touch target, GPU rotation when spinning).
 */
@Composable
fun GlassCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highContrastOverlay: Boolean = false,
    tint: Color = TextPrimary,
    spinning: Boolean = false,
    isActive: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val fillColor = when {
        highContrastOverlay && pressed -> OverlayPillPressedFill
        highContrastOverlay -> OverlayPillFill
        isActive || pressed -> GlassPillActiveFill
        else -> GlassPillFill
    }
    val borderColor = when {
        highContrastOverlay && (pressed || hovered) -> OverlayPillPressedBorder
        highContrastOverlay -> OverlayPillBorder
        isActive || pressed || hovered -> GlassPillActiveBorder
        else -> GlassPillBorder
    }
    val borderWidth = if (pressed || isActive) 1.5.dp else 1.dp

    val rotation = if (spinning) {
        val transition = rememberInfiniteTransition(label = "icon_spin")
        val angle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spin_angle",
        )
        angle
    } else {
        0f
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .glassInteractive(interactionSource = interactionSource, onClick = onClick)
            .clip(CircleShape)
            .background(fillColor)
            .border(borderWidth, borderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier
                .size(20.dp)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * Elastic Sliding Glass Pill Segmented Control with spring physics (stiffness = 380f, dampingRatio = 0.86f).
 */
@Composable
fun <T> SlidingGlassSegmentedControl(
    items: List<T>,
    selectedIndex: Int,
    labelProvider: (T) -> String,
    onSelect: (Int, T) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "segment",
) {
    if (items.isEmpty()) return
    val clampedIndex = selectedIndex.coerceIn(0, items.lastIndex)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(GlassPillFill)
            .border(1.dp, GlassPillBorder, RoundedCornerShape(999.dp))
            .padding(4.dp),
    ) {
        val slotWidth = maxWidth / items.size
        val indicatorOffset by animateDpAsState(
            targetValue = slotWidth * clampedIndex,
            animationSpec = glassSpring(),
            label = "segment_indicator_x",
        )

        // Sliding translucent glass indicator with vertical specular reflection
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(slotWidth)
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(GlassSliderTop, GlassSliderMid, GlassSliderBottom),
                    ),
                )
                .border(1.5.dp, GlassSliderBorder, RoundedCornerShape(999.dp)),
        )

        Row(modifier = Modifier.fillMaxSize()) {
            items.forEachIndexed { idx, item ->
                val isSelected = idx == clampedIndex
                val interactionSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .testTag("${tagPrefix}_$idx")
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                        ) { onSelect(idx, item) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = labelProvider(item),
                        style = if (isSelected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                        color = if (isSelected) TextPrimary else TextSubtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * GPU-only Glass Progress Bar (3.5dp, radius 99dp, track #26FFFFFF, fill #E6FFFFFF via scaleX).
 */
@Composable
fun GlassProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.5.dp)
            .clip(RoundedCornerShape(99.dp))
            .background(Color(0x26FFFFFF)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = clamped
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0xE6FFFFFF)),
        )
    }
}

/**
 * Hero Entrance animated title (translationX +10dp -> 0dp, alpha 0 -> 1 in 260ms).
 */
@Composable
fun HeroEntranceText(
    text: String,
    key: Any,
    style: TextStyle,
    color: Color = TextPrimary,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    val density = LocalDensity.current
    val startPx = with(density) { 10.dp.toPx() }
    val progress = remember { Animatable(1f) }

    LaunchedEffect(key) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        )
    }

    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.graphicsLayer {
            translationX = startPx * (1f - progress.value)
            alpha = progress.value
        },
    )
}

/**
 * High-contrast Smoked Glass pill surface for controls floating directly over the live map.
 */
@Composable
fun MapOverlayGlassPill(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val hovered by interactionSource.collectIsHoveredAsState()

    val fill = if (pressed) OverlayPillPressedFill else OverlayPillFill
    val border = if (pressed || hovered) OverlayPillPressedBorder else OverlayPillBorder
    val borderW = if (pressed) 1.5.dp else 1.dp

    val baseMod = if (onClick != null) {
        modifier.glassInteractive(interactionSource = interactionSource, onClick = onClick)
    } else {
        modifier
    }

    Surface(
        modifier = baseMod,
        shape = RoundedCornerShape(999.dp),
        color = fill,
        border = BorderStroke(borderW, border),
    ) {
        Box(content = content)
    }
}

/**
 * Rounded tinted glass badge for Gold / Cyan route direction termini.
 * Keeps stop/terminus names crisp and scannable without ugly mid-sentence wrapping.
 */
@Composable
fun DirectionColorBadge(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    prefix: String? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.50f), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Text(
            text = if (prefix != null) "$prefix $label" else label,
            style = MaterialTheme.typography.labelSmall,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * True Dark Translucent Glass modal dialog with top specular border reflection,
 * optional ambient tint wash, and zero opaque Material AlertDialog chrome.
 */
@Composable
fun GlassModalDialog(
    onDismissRequest: () -> Unit,
    accentTint: Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val shape = RoundedCornerShape(28.dp)
        val glassSurfaceBrush = Brush.verticalGradient(
            colors = listOf(
                Color(0xEB1E212E),
                Color(0xE812141D),
                Color(0xEB0E1017),
            ),
        )
        val borderBrush = Brush.verticalGradient(
            colors = listOf(
                accentTint?.copy(alpha = 0.55f) ?: Color(0x52FFFFFF),
                Color(0x24FFFFFF),
                Color(0x14FFFFFF),
            ),
        )

        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .widthIn(max = 520.dp)
                .clip(shape)
                .background(glassSurfaceBrush)
                .then(
                    if (accentTint != null) {
                        Modifier.background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    accentTint.copy(alpha = 0.14f),
                                    Color.Transparent,
                                ),
                                endY = 260f,
                            ),
                        )
                    } else {
                        Modifier
                    },
                )
                .border(1.2.dp, borderBrush, shape),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                content = content,
            )
        }
    }
}
