package pl.put.observationcompanion.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun ReceptionProbabilityChip(
    probability: Double,
    goodCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier
) {
    val pct = (probability.coerceIn(0.0, 1.0) * 100).roundToInt()
    val (bg, fg, border) = when {
        probability >= 0.65 -> Triple(
            Color(0x2610B981), // emerald-500/15
            Color(0xFF34D399),
            Color(0x4D10B981)
        )
        probability >= 0.40 -> Triple(
            Color(0x26F59E0B), // amber-500/15
            Color(0xFFFBBF24),
            Color(0x4DF59E0B)
        )
        else -> Triple(
            Color(0x26F43F5E), // rose-500/15
            Color(0xFFFB7185),
            Color(0x4DF43F5E)
        )
    }

    val historyLabel = if (totalCount > 0) "$goodCount/$totalCount" else "no hist."

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "RX $pct% · $historyLabel",
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false
        )
    }
}
