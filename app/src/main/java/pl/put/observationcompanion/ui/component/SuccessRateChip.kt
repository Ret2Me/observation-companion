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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.put.observationcompanion.domain.model.SatelliteStatus

@Composable
fun SuccessRateChip(
    status: SatelliteStatus,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor, label, borderColor) = when (status) {
        SatelliteStatus.UNLIKELY -> Quadruple(
            Color(0x26F43F5E), // rose-500/15
            Color(0xFFFB7185), // rose-400
            "Unlikely",
            Color(0x4DF43F5E)  // rose-500/30
        )
        SatelliteStatus.PROMISING -> Quadruple(
            Color(0x2610B981), // emerald-500/15
            Color(0xFF34D399), // emerald-400
            "Promising",
            Color(0x4D10B981)  // emerald-500/30
        )
        SatelliteStatus.NEUTRAL -> Quadruple(
            Color(0x26F59E0B), // amber-500/15
            Color(0xFFFBBF24), // amber-400
            "Neutral",
            Color(0x4DF59E0B)  // amber-500/30
        )
        SatelliteStatus.NO_DATA -> Quadruple(
            Color(0x2664748B), // slate-500/15
            Color(0xFF94A3B8), // slate-400
            "No Data",
            Color(0x4D64748B)  // slate-500/30
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false
        )
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
