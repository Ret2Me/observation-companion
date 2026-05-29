package pl.put.observationcompanion.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import pl.put.observationcompanion.domain.model.SkyPoint
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Polar sky-view of a satellite pass.
 *
 * Horizon = outer ring (elevation 0°). Zenith = centre (elevation 90°). North
 * is at the top, azimuth increases clockwise (so East at right, South at
 * bottom, West at left). The pass arc is drawn from AOS through TCA to LOS;
 * AOS is green, TCA is amber, LOS is red.
 *
 * Falls back to a flat horizon-only chart when [points] is empty (lazy
 * compute hasn't landed yet) so the layout doesn't jump.
 */
@Composable
fun SkyMapChart(
    points: List<SkyPoint>,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color(0xFF0B1437),
    ringColor: Color = Color(0xFF334155),
    cardinalColor: Color = Color(0xFF94A3B8),
    pathColor: Color = Color(0xFF818CF8),
    aosColor: Color = Color(0xFF10B981),
    tcaColor: Color = Color(0xFFFBBF24),
    losColor: Color = Color(0xFFFB7185)
) {
    Box(modifier = modifier.fillMaxWidth().aspectRatio(1f)) {
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val radius = min(w, h) / 2f * 0.88f

            // Dark backdrop circle (sky disc).
            drawCircle(color = backgroundColor, radius = radius, center = Offset(cx, cy))

            // Elevation rings: horizon (full radius), 30°, 60°.
            val r30 = (1f - 30f / 90f) * radius
            val r60 = (1f - 60f / 90f) * radius
            drawCircle(color = ringColor, radius = radius, center = Offset(cx, cy), style = Stroke(1.2f))
            drawCircle(color = ringColor, radius = r30, center = Offset(cx, cy), style = Stroke(1f))
            drawCircle(color = ringColor, radius = r60, center = Offset(cx, cy), style = Stroke(1f))

            // Cardinal cross.
            drawLine(ringColor, Offset(cx - radius, cy), Offset(cx + radius, cy), strokeWidth = 1f)
            drawLine(ringColor, Offset(cx, cy - radius), Offset(cx, cy + radius), strokeWidth = 1f)

            // Cardinal labels (N/E/S/W).
            val labelPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#94A3B8")
                textSize = 26f
                isAntiAlias = true
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.MONOSPACE,
                    android.graphics.Typeface.BOLD
                )
                textAlign = android.graphics.Paint.Align.CENTER
            }
            drawContext.canvas.nativeCanvas.apply {
                drawText("N", cx, cy - radius - 8f, labelPaint)
                drawText("S", cx, cy + radius + 26f, labelPaint)
                drawText("E", cx + radius + 14f, cy + 9f, labelPaint)
                drawText("W", cx - radius - 14f, cy + 9f, labelPaint)
            }

            if (points.size < 2) return@Canvas

            // Walk the sample list and emit contiguous above-horizon polyline
            // segments. A `null` slot represents a below-horizon dip, which
            // *splits* the arc rather than connecting endpoints across the
            // chart (the old behaviour drew a chord between visible regions
            // that looked like a spurious second arc).
            val projected: List<Offset?> = points.map { p ->
                if (p.elevation < 0.0) null
                else project(p.azimuth, p.elevation, cx, cy, radius)
            }

            var first: Offset? = null
            var last: Offset? = null
            run {
                var i = 0
                while (i < projected.size) {
                    if (projected[i] == null) { i++; continue }
                    val segStart = i
                    while (i < projected.size && projected[i] != null) i++
                    val segEnd = i  // exclusive
                    if (segEnd - segStart >= 2) {
                        val path = Path().apply {
                            moveTo(projected[segStart]!!.x, projected[segStart]!!.y)
                            for (k in (segStart + 1) until segEnd) {
                                lineTo(projected[k]!!.x, projected[k]!!.y)
                            }
                        }
                        drawPath(path = path, color = pathColor, style = Stroke(width = 3f))
                    }
                    if (first == null) first = projected[segStart]
                    last = projected[segEnd - 1]
                }
            }
            if (first == null || last == null) return@Canvas

            // TCA = highest-elevation sample that is actually above the horizon.
            val tcaProj = points.withIndex()
                .filter { it.value.elevation >= 0.0 }
                .maxByOrNull { it.value.elevation }
                ?.let { project(it.value.azimuth, it.value.elevation, cx, cy, radius) }

            drawCircle(color = aosColor, radius = 7f, center = first!!)
            tcaProj?.let { drawCircle(color = tcaColor, radius = 8f, center = it) }
            drawCircle(color = losColor, radius = 7f, center = last!!)
        }
    }
}

private fun project(
    azimuthDeg: Double,
    elevationDeg: Double,
    cx: Float,
    cy: Float,
    radius: Float
): Offset {
    val r = ((1.0 - elevationDeg.coerceIn(0.0, 90.0) / 90.0) * radius).toFloat()
    // Convert compass az (0=N, clockwise) to math angle (0=E, CCW): math = 90 - az.
    val theta = Math.toRadians(90.0 - azimuthDeg)
    val x = cx + (r * cos(theta)).toFloat()
    val y = cy - (r * sin(theta)).toFloat()
    return Offset(x, y)
}
