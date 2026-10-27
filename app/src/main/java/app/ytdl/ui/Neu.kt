package app.ytdl.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ------------------------------------------------------------------ AER palette
val Bg = Color(0xFFE8EEF2)
val Shade = Color(0xFFC3CCD4)
val Light = Color(0xFFFFFFFF)
val Accent = Color(0xFF4DB8E8)
val Ink = Color(0xFF4A5A66)
val InkSoft = Color(0xFF7D8B96)
val Warn = Color(0xFFE0A24D)
val Bad = Color(0xFFE06D6D)
val Ok = Color(0xFF5CC28F)
val Field = Color(0xFFEEF3F6)

@Composable
fun YtdlTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Accent, onPrimary = Color.White, background = Bg, surface = Bg,
            onBackground = Ink, onSurface = Ink, surfaceVariant = Bg, outline = Shade,
        ),
        content = content,
    )
}

// ------------------------------------------------------------------ soft shadows
/** Raised neumorphic surface: light shadow top-left, dark shadow bottom-right. */
fun Modifier.neuRaised(radius: Dp = 16.dp, elevation: Dp = 7.dp, color: Color = Bg) = this.drawBehind {
    val r = radius.toPx(); val e = elevation.toPx()
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val fp = paint.asFrameworkPaint()
        fp.isAntiAlias = true
        fp.color = color.toArgb()
        fp.setShadowLayer(e * 1.6f, -e * 0.7f, -e * 0.7f, Light.toArgb())
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, fp)
        fp.setShadowLayer(e * 1.6f, e * 0.7f, e * 0.7f, Shade.toArgb())
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, fp)
        fp.clearShadowLayer()
        canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, r, r, fp)
    }
}

/** Pressed-in neumorphic surface (fields, wells, selected states). */
fun Modifier.neuInset(radius: Dp = 12.dp, depth: Dp = 5.dp, color: Color = Bg) = this.drawBehind {
    val r = radius.toPx(); val d = depth.toPx()
    val path = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r, r))
    }
    drawRoundRect(color, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
    clipPath(path) {
        drawIntoCanvas { canvas ->
            val fp = Paint().asFrameworkPaint()
            fp.isAntiAlias = true
            fp.style = android.graphics.Paint.Style.STROKE
            fp.strokeWidth = d * 2
            fp.color = color.toArgb()
            fp.setShadowLayer(d * 1.4f, d * 0.6f, d * 0.6f, Shade.toArgb())
            canvas.nativeCanvas.drawRoundRect(-d, -d, size.width + d, size.height + d, r + d, r + d, fp)
            fp.setShadowLayer(d * 1.4f, -d * 0.6f, -d * 0.6f, Light.toArgb())
            canvas.nativeCanvas.drawRoundRect(-d, -d, size.width + d, size.height + d, r + d, r + d, fp)
        }
    }
}

// ------------------------------------------------------------------ text styles
val H1 = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Ink)
val H2 = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
val Body = TextStyle(fontSize = 15.sp, color = Ink)
val Small = TextStyle(fontSize = 13.sp, color = InkSoft)
val LabelStyle = TextStyle(fontSize = 11.sp, color = InkSoft, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)

@Composable fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = LabelStyle, modifier = modifier)

// ------------------------------------------------------------------ components
@Composable
fun NeuCard(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable () -> Unit) {
    Box(modifier.neuRaised().padding(padding)) { content() }
}

@Composable
fun NeuWell(modifier: Modifier = Modifier, padding: Dp = 14.dp, content: @Composable () -> Unit) {
    Box(modifier.neuInset(16.dp).padding(padding)) { content() }
}

@Composable
fun NeuButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val color = when {
        !enabled -> Shade
        danger -> Bad
        accent -> Accent
        else -> Ink
    }
    Box(
        modifier
            .then(if (pressed && enabled) Modifier.neuInset(16.dp, 4.dp) else Modifier.neuRaised(16.dp, 5.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Body.copy(color = color, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun NeuToggle(checked: Boolean, onChange: (Boolean) -> Unit, label: String, sub: String? = null, modifier: Modifier = Modifier) {
    val knobX by animateDpAsState(if (checked) 24.dp else 0.dp, tween(220), label = "knob")
    val knobColor by animateColorAsState(if (checked) Accent else Bg, tween(220), label = "knobColor")
    Row(
        modifier.clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Body)
            if (sub != null) Text(sub, style = Small)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(52.dp, 28.dp).neuInset(14.dp, 3.dp).padding(4.dp)) {
            Box(
                Modifier.offset(x = knobX).size(20.dp)
                    .then(if (checked) Modifier.background(knobColor, CircleShape) else Modifier.neuRaised(10.dp, 3.dp))
            )
        }
    }
}

@Composable
fun NeuField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    onDone: (() -> Unit)? = null,
) {
    Box(modifier.neuInset(12.dp, 4.dp).padding(horizontal = 14.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(placeholder, style = Body.copy(color = InkSoft))
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = singleLine,
            textStyle = Body, cursorBrush = SolidColor(Accent),
            keyboardOptions = KeyboardOptions(imeAction = if (onDone != null) ImeAction.Go else ImeAction.Default),
            keyboardActions = KeyboardActions(onGo = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun Pill(text: String, accent: Boolean = false, flat: Boolean = false, color: Color? = null) {
    Box(
        Modifier.then(if (flat) Modifier.neuInset(12.dp, 2.dp) else Modifier.neuRaised(12.dp, 3.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(text, style = Small.copy(
            fontSize = 12.sp,
            color = color ?: if (accent) Accent else Ink,
            fontWeight = if (accent) FontWeight.SemiBold else FontWeight.Normal))
    }
}

@Composable
fun NeuCheck(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.size(22.dp).then(
            if (checked) Modifier.background(Accent, RoundedCornerShape(5.dp)) else Modifier.neuInset(5.dp, 2.dp)
        ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", style = Small.copy(color = Color.White, fontWeight = FontWeight.Bold))
    }
}

/**
 * The AER dial: raised outer ring, inset track, accent arc showing progress toward
 * the next check, with a glowing knob.
 */
@Composable
fun Dial(progress: Float, busy: Boolean, paused: Boolean, warn: Boolean, modifier: Modifier = Modifier, center: @Composable () -> Unit) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), tween(800), label = "dial")
    val arcColor = when { warn -> Warn; paused -> InkSoft; else -> Accent }
    Box(modifier.size(250.dp).neuRaised(125.dp, 9.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(206.dp).neuInset(103.dp, 7.dp))
        val spin = if (busy) rememberSpin() else 0f
        Canvas(Modifier.size(190.dp)) {
            val stroke = 9.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            if (busy) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, arcColor)),
                    startAngle = spin, sweepAngle = 300f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                )
            } else if (p > 0f) {
                drawArc(arcColor.copy(alpha = 0.25f), -90f, 360f * p, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke * 2.2f, cap = StrokeCap.Round))
                drawArc(arcColor, -90f, 360f * p, false, Offset(inset, inset), arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round))
                val a = Math.toRadians((360.0 * p) - 90.0)
                val rr = arcSize.width / 2
                val c = Offset(size.width / 2 + (rr * Math.cos(a)).toFloat(), size.height / 2 + (rr * Math.sin(a)).toFloat())
                drawCircle(arcColor.copy(alpha = 0.4f), stroke * 1.1f, c)
                drawCircle(Color.White, stroke * 0.55f, c)
            }
        }
        Box(
            Modifier.size(140.dp).neuRaised(70.dp, 4.dp)
                .background(Brush.linearGradient(listOf(Color(0xFFF2F6F8), Color(0xFFDDE4E9))), CircleShape),
            contentAlignment = Alignment.Center,
        ) { center() }
    }
}

@Composable
private fun rememberSpin(): Float {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val v by t.animateFloat(
        0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(1400, easing = androidx.compose.animation.core.LinearEasing)),
        label = "spinv",
    )
    return v
}

@Composable
fun SectionGap() = Spacer(Modifier.padding(top = 18.dp))

@Composable
fun RowGap(content: @Composable () -> Unit) =
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { content() }
