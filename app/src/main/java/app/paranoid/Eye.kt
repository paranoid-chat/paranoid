package app.paranoid

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

private val EASE = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

private val BLINK = listOf(
    0f to 1f, 2f to 1f, 3.5f to 0.05f, 5f to 1f, 30f to 1f, 31.5f to 0.05f, 33f to 1f,
    34.5f to 1f, 36f to 0.05f, 37.5f to 1f, 66f to 1f, 67.5f to 0.05f, 69f to 1f, 100f to 1f,
)
private val GLANCE = listOf(
    0f to 0f, 6f to 0f, 14f to -46f, 28f to -46f, 40f to 46f, 56f to 46f, 66f to -18f, 72f to -18f, 82f to 0f, 100f to 0f,
)

private fun keyframes(ms: Long, delay: Long, duration: Long, stops: List<Pair<Float, Float>>, ease: Easing): Float {
    val t = ((ms - delay).coerceAtLeast(0) % duration) * 100f / duration
    for (i in 0 until stops.size - 1) {
        val (a, va) = stops[i]
        val (b, vb) = stops[i + 1]
        if (t <= b) {
            if (b <= a) return vb
            return va + (vb - va) * ease.transform(((t - a) / (b - a)).coerceIn(0f, 1f))
        }
    }
    return stops.last().second
}

private fun gradient(x: Float, y: Float, w: Float) = Brush.linearGradient(
    0f to Color(0xFFF40C18), 0.5f to Color(0xFFC50512), 1f to Color(0xFF8A000B),
    start = Offset(x + 0.15f * w, y + 0.08f * w),
    end = Offset(x + 0.85f * w, y + 0.95f * w),
)

private val EYE = Path().apply {
    moveTo(89f, 200f)
    quadraticBezierTo(200f, 82f, 311f, 200f)
    quadraticBezierTo(200f, 318f, 89f, 200f)
    close()
}

@Composable
fun EyeLogo(modifier: Modifier = Modifier) {
    var ms by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { ms = it - start }
    }
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = "Paranoid" }) {
        val lids = keyframes(ms, 1000, 9000, BLINK, EASE)
        val iris = keyframes(ms, 1500, 11000, GLANCE, EASE_IN_OUT)
        scale(size.minDimension / 400f, pivot = Offset.Zero) { drawEye(lids, iris) }
    }
}

private fun DrawScope.drawEye(lids: Float, iris: Float) {
    drawCircle(gradient(0f, 0f, 400f), radius = 200f, center = Offset(200f, 200f))
    scale(scaleX = 1f, scaleY = lids, pivot = Offset(200f, 200f)) {
        drawPath(EYE, Color.White)
        clipPath(EYE) {
            translate(left = iris) {
                drawCircle(gradient(156f, 156f, 88f), radius = 44f, center = Offset(200f, 200f))
                drawCircle(Color.White, radius = 14.8f, center = Offset(223.6f, 178.8f))
            }
        }
    }
}
