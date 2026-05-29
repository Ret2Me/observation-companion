package pl.put.observationcompanion.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.put.observationcompanion.domain.model.DopplerPoint
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun DopplerChart(
    dopplerPoints: List<DopplerPoint>,
    modifier: Modifier = Modifier
) {
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0F172A)) // Cosmic Blue-Slate background
            .padding(12.dp)
    ) {
        Text(
            text = "DOPPLER DRIFT CURVE (Δf = f_nom - f_obs)",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Render Canvas Plotting Space
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp)
        ) {
            val width = size.width
            val height = size.height
            val paddingLeft = 45f
            val paddingRight = 45f
            val paddingTop = 15f
            val paddingBottom = 15f

            val plotWidth = width - paddingLeft - paddingRight
            val plotHeight = height - paddingTop - paddingBottom

            val midY = paddingTop + plotHeight / 2f

            // 1. Draw grid axes
            // Center Nominal line (Offset = 0)
            drawLine(
                color = Color(0x3394A3B8),
                start = Offset(paddingLeft, midY),
                end = Offset(paddingLeft + plotWidth, midY),
                strokeWidth = 1f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
            )

            // Start (AOS) & End (LOS) boundaries
            drawLine(
                color = Color(0x1994A3B8),
                start = Offset(paddingLeft, paddingTop),
                end = Offset(paddingLeft, paddingTop + plotHeight),
                strokeWidth = 1f
            )
            drawLine(
                color = Color(0x1994A3B8),
                start = Offset(paddingLeft + plotWidth, paddingTop),
                end = Offset(paddingLeft + plotWidth, paddingTop + plotHeight),
                strokeWidth = 1f
            )

            if (dopplerPoints.isEmpty()) {
                // Return fallback message
                return@Canvas
            }

            // Find min/max values to scale the offsets properly
            val maxAbsOffset = dopplerPoints.maxOfOrNull { abs(it.frequencyOffset) }?.coerceAtLeast(100.0) ?: 1000.0

            // Helper to map index & offset to plot space
            fun getX(index: Int, total: Int): Float {
                return paddingLeft + (index.toFloat() / (total - 1).coerceAtAtLeast(1)) * plotWidth
            }

            fun getY(offsetHz: Double): Float {
                val normalized = offsetHz / maxAbsOffset // between -1 and 1
                return midY + (normalized.toFloat() * (plotHeight / 2f)) // inverted to match screen (0 is top)
            }

            // 2. Plot the S-curve line (approaching delta < 0 is blue-shifted, receding delta > 0 is red-shifted)
            val path = Path()
            for (i in dopplerPoints.indices) {
                val pt = dopplerPoints[i]
                val x = getX(i, dopplerPoints.size)
                val y = getY(pt.frequencyOffset)

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = Color(0xFF818CF8), // Indigo-400 stroke
                style = Stroke(width = 3.5f)
            )

            // 3. Highlight AOS point (start) and LOS point (end) with dots
            val xAos = getX(0, dopplerPoints.size)
            val yAos = getY(dopplerPoints.first().frequencyOffset)
            drawCircle(
                color = Color(0xFF10B981), // Emerald-500 dot
                radius = 5f,
                center = Offset(xAos, yAos)
            )

            val xLos = getX(dopplerPoints.size - 1, dopplerPoints.size)
            val yLos = getY(dopplerPoints.last().frequencyOffset)
            drawCircle(
                color = Color(0xFFF43F5E), // Intense Rose dot for receding / LOS
                radius = 5f,
                center = Offset(xLos, yLos)
            )

            // 4. Draw labels using Native Canvas
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#64748B")
                textSize = 24f
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }

            // Max scale labels on left
            val khzOffset = maxAbsOffset / 1000.0
            drawContext.canvas.nativeCanvas.drawText(
                String.format("+%.1fk", khzOffset),
                2f,
                paddingTop + 15f,
                paint
            )
            drawContext.canvas.nativeCanvas.drawText(
                "  0 Hz",
                2f,
                midY + 8f,
                paint
            )
            drawContext.canvas.nativeCanvas.drawText(
                String.format("-%.1fk", khzOffset),
                2f,
                paddingTop + plotHeight - 5f,
                paint
            )

            // Timing labels at the bottom corners
            paint.textAlign = android.graphics.Paint.Align.LEFT
            drawContext.canvas.nativeCanvas.drawText(
                "AOS: " + dopplerPoints.first().timestamp.atZone(java.time.ZoneId.systemDefault()).format(formatter),
                paddingLeft,
                height,
                paint
            )

            paint.textAlign = android.graphics.Paint.Align.RIGHT
            drawContext.canvas.nativeCanvas.drawText(
                "LOS: " + dopplerPoints.last().timestamp.atZone(java.time.ZoneId.systemDefault()).format(formatter),
                width,
                height,
                paint
            )
        }
    }
}

private fun Int.coerceAtAtLeast(minimumValue: Int): Int {
    return if (this < minimumValue) minimumValue else this
}
