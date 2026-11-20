package com.cosmic802.cosmo

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

val CosmoPitchBlack = Color(0xFF05080D)
val CosmoSurfaceDark = Color(0xFF0A101A)
val CosmoSurfaceElevated = Color(0xFF101928)
val CosmoCyan = Color(0xFF00E5FF)
val CosmoCyanDim = Color(0xFF006B78)
val CosmoAmber = Color(0xFFFFB300)
val CosmoCrimson = Color(0xFFFF1744)
val CosmoEmerald = Color(0xFF00E676)
val CosmoTextPrimary = Color(0xFFE6F7FF)
val CosmoTextSecondary = Color(0xFF8BA8B8)
val CosmoTextMuted = Color(0xFF4F6978)

private val CosmoDarkColorScheme = darkColorScheme(
    primary = CosmoCyan,
    onPrimary = CosmoPitchBlack,
    primaryContainer = CosmoSurfaceElevated,
    onPrimaryContainer = CosmoCyan,
    secondary = CosmoAmber,
    onSecondary = CosmoPitchBlack,
    secondaryContainer = CosmoSurfaceElevated,
    onSecondaryContainer = CosmoAmber,
    tertiary = CosmoEmerald,
    onTertiary = CosmoPitchBlack,
    error = CosmoCrimson,
    onError = CosmoTextPrimary,
    background = CosmoPitchBlack,
    onBackground = CosmoTextPrimary,
    surface = CosmoSurfaceDark,
    onSurface = CosmoTextPrimary,
    surfaceVariant = CosmoSurfaceElevated,
    onSurfaceVariant = CosmoTextSecondary,
    outline = CosmoCyanDim
)

val CosmoTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = 2.5.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 2.0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 1.4.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 1.2.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.3.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.2.sp
    )
)

@Composable
fun CosmoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CosmoDarkColorScheme,
        typography = CosmoTypography,
        content = content
    )
}

fun Modifier.hudGlowBorder(
    color: Color = CosmoCyan,
    cornerRadius: Dp = 12.dp,
    borderWidth: Dp = 1.dp,
    glowWidth: Dp = 6.dp,
    showCornerBrackets: Boolean = true
): Modifier = this.drawWithContent {
    drawContent()

    val radiusPx = cornerRadius.toPx()
    val strokePx = borderWidth.toPx()
    val glowPx = glowWidth.toPx()

    drawRoundRect(
        color = color.copy(alpha = 0.14f),
        topLeft = Offset(-glowPx / 2f, -glowPx / 2f),
        size = Size(size.width + glowPx, size.height + glowPx),
        cornerRadius = CornerRadius(radiusPx + glowPx / 2f, radiusPx + glowPx / 2f),
        style = Stroke(width = glowPx)
    )

    drawRoundRect(
        color = color.copy(alpha = 0.45f),
        size = size,
        cornerRadius = CornerRadius(radiusPx, radiusPx),
        style = Stroke(width = strokePx)
    )

    if (showCornerBrackets) {
        val bracketLen = minOf(size.width, size.height) * 0.14f
        val bracketStroke = strokePx * 2.2f

        // Top-Left
        drawLine(color, Offset(0f, bracketLen), Offset(0f, 0f), bracketStroke, StrokeCap.Round)
        drawLine(color, Offset(0f, 0f), Offset(bracketLen, 0f), bracketStroke, StrokeCap.Round)

        // Top-Right
        drawLine(color, Offset(size.width - bracketLen, 0f), Offset(size.width, 0f), bracketStroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(size.width, bracketLen), bracketStroke, StrokeCap.Round)

        // Bottom-Left
        drawLine(color, Offset(0f, size.height - bracketLen), Offset(0f, size.height), bracketStroke, StrokeCap.Round)
        drawLine(color, Offset(0f, size.height), Offset(bracketLen, size.height), bracketStroke, StrokeCap.Round)

        // Bottom-Right
        drawLine(color, Offset(size.width - bracketLen, size.height), Offset(size.width, size.height), bracketStroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, size.height - bracketLen), Offset(size.width, size.height), bracketStroke, StrokeCap.Round)
    }
}

fun Modifier.hudPanel(
    borderColor: Color = CosmoCyan,
    backgroundColor: Color = CosmoSurfaceDark,
    cornerRadius: Dp = 12.dp,
    showCornerBrackets: Boolean = true
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(backgroundColor)
    .hudGlowBorder(
        color = borderColor,
        cornerRadius = cornerRadius,
        showCornerBrackets = showCornerBrackets
    )

@Composable
fun JarvisCoreVisualizer(
    modifier: Modifier = Modifier,
    coreColor: Color = CosmoCyan,
    accentColor: Color = CosmoAmber,
    isListening: Boolean = false,
    isSpeaking: Boolean = false,
    isProcessing: Boolean = false,
    statusLabel: String = "ONLINE",
    subLabel: String = "SYSTEM NOMINAL",
    size: Dp = 196.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "jarvis_core_transition")

    val outerRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isProcessing) 3500 else 14000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "outer_ring_rotation"
    )

    val innerRotation by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeaking || isListening) 4000 else 10000,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "inner_ring_rotation"
    )

    val pulseFactor by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = if (isSpeaking || isListening || isProcessing) 1.08f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (isSpeaking) 450 else 1400,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core_pulse"
    )

    val activeAccent = when {
        isProcessing -> accentColor
        isSpeaking -> CosmoEmerald
        isListening -> CosmoCyan
        else -> coreColor
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val maxRadius = this.size.minDimension / 2f

            // Ambient radial reactor aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        activeAccent.copy(alpha = 0.24f * pulseFactor),
                        activeAccent.copy(alpha = 0.06f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = maxRadius
                ),
                radius = maxRadius,
                center = center
            )

            // Outer telemetry tick ring
            val tickRadiusOuter = maxRadius * 0.94f
            val tickRadiusInner = maxRadius * 0.88f
            for (i in 0 until 36) {
                val angleDeg = i * 10.0 + outerRotation * 0.35
                val rad = Math.toRadians(angleDeg)
                val isMajor = i % 3 == 0
                val startR = if (isMajor) maxRadius * 0.85f else tickRadiusInner
                val start = Offset(
                    x = center.x + (startR * cos(rad)).toFloat(),
                    y = center.y + (startR * sin(rad)).toFloat()
                )
                val end = Offset(
                    x = center.x + (tickRadiusOuter * cos(rad)).toFloat(),
                    y = center.y + (tickRadiusOuter * sin(rad)).toFloat()
                )
                drawLine(
                    color = activeAccent.copy(alpha = if (isMajor) 0.75f else 0.28f),
                    start = start,
                    end = end,
                    strokeWidth = if (isMajor) 2.5f else 1.2f,
                    cap = StrokeCap.Round
                )
            }

            // Rotating segmented outer arc-reactor ring
            rotate(degrees = outerRotation, pivot = center) {
                val arcRadius = maxRadius * 0.78f
                val arcRect = Size(arcRadius * 2f, arcRadius * 2f)
                val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                val segments = listOf(0f to 65f, 90f to 55f, 180f to 70f, 275f to 50f)
                segments.forEach { (startAngle, sweep) ->
                    drawArc(
                        color = activeAccent.copy(alpha = 0.85f),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcRect,
                        style = Stroke(width = 4.5f, cap = StrokeCap.Round)
                    )
                }
            }

            // Counter-rotating inner containment ring
            rotate(degrees = innerRotation, pivot = center) {
                val innerRadius = maxRadius * 0.63f
                val innerRect = Size(innerRadius * 2f, innerRadius * 2f)
                val topLeft = Offset(center.x - innerRadius, center.y - innerRadius)
                val innerSegments = listOf(20f to 80f, 140f to 80f, 260f to 80f)
                innerSegments.forEach { (startAngle, sweep) ->
                    drawArc(
                        color = accentColor.copy(alpha = 0.75f),
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = topLeft,
                        size = innerRect,
                        style = Stroke(width = 3.2f, cap = StrokeCap.Round)
                    )
                }
            }

            // Inner core housing circle
            val coreRadius = maxRadius * 0.49f * pulseFactor
            drawCircle(
                color = CosmoSurfaceDark.copy(alpha = 0.92f),
                radius = coreRadius,
                center = center
            )
            drawCircle(
                color = activeAccent.copy(alpha = 0.65f),
                radius = coreRadius,
                center = center,
                style = Stroke(width = 2f)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "COSMO",
                style = CosmoTypography.labelSmall,
                color = activeAccent.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = statusLabel,
                style = CosmoTypography.titleMedium,
                color = CosmoTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subLabel,
                style = CosmoTypography.labelSmall,
                color = CosmoTextSecondary
            )
        }
    }
}
