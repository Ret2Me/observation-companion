package pl.put.observationcompanion.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import pl.put.observationcompanion.domain.model.GroundTrackPoint
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.abs

private const val SELECTED_TRACK_COLOR = "#A5B4FC"   // indigo-300
private const val PREVIOUS_TRACK_COLOR = "#FBBF24"   // amber-400
private const val NEXT_TRACK_COLOR = "#34D399"       // emerald-400
private const val FULL_TRACK_COLOR = "#22D3EE"       // cyan-400 (matches live marker)
private const val HALO_COLOR = "#020617"             // slate-950
private const val OBSERVER_COLOR = 0xFF10B981.toInt() // emerald-500
private const val LIVE_COLOR = 0xFF22D3EE.toInt()     // cyan-400

@Composable
fun GroundTrackMap(
    track: List<GroundTrackPoint>,
    observerLat: Double,
    observerLon: Double,
    livePosition: GroundTrackPoint?,
    modifier: Modifier = Modifier,
    previousTrack: List<GroundTrackPoint> = emptyList(),
    nextTrack: List<GroundTrackPoint> = emptyList(),
    fullTrack: List<GroundTrackPoint> = emptyList()
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        org.osmdroid.config.Configuration.getInstance().load(
            context,
            context.getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE)
        )
        org.osmdroid.config.Configuration.getInstance().userAgentValue =
            "Observation-Companion/1.2 (Android student project, PUT)"
    }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            // Zoom 4.0 puts the observer dot near the centre with ~3000 km of
            // visible ground on either side - close enough to read the local
            // pass arc, still wide enough for the full-orbit cyan trace to
            // peek in from the limbs.
            controller.setZoom(4.0)
            controller.setCenter(GeoPoint(observerLat, observerLon))
            // Desaturate + invert the bright OSM tiles into a dark basemap that
            // matches the app's slate theme - no external dark tile server needed.
            overlayManager.tilesOverlay.setColorFilter(darkMapFilter())
        }
    }

    // Live satellite marker is kept across track rebuilds and only repositioned.
    val liveMarker = remember {
        Marker(mapView).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = dotIcon(context, LIVE_COLOR, ringPx = 5f, radiusPx = 13f, glow = true)
            title = "Satellite (now)"
            isEnabled = false
        }
    }

    // The MapView is created in `remember` with whatever observer location the
    // VM has at first composition. SatelliteDetailViewModel.load() seeds the
    // state with observerLat/Lon = 0.0 and overwrites them only after reading
    // settings asynchronously - so the first paint of the map is centred on
    // the Gulf of Guinea. Whenever the observer position actually changes,
    // animate the map back to it (this fires once on initial settings load,
    // and again if the user picks a new preset and returns).
    LaunchedEffect(observerLat, observerLon) {
        mapView.controller.animateTo(GeoPoint(observerLat, observerLon))
    }

    LaunchedEffect(track, previousTrack, nextTrack, fullTrack, observerLat, observerLon) {
        mapView.overlays.clear()

        // Layer order (back -> front): full orbit (cyan), prev/next dashed,
        // selected solid. The selected pass should never be obscured.
        mapView.drawTrack(fullTrack, FULL_TRACK_COLOR, dashed = false)
        mapView.drawTrack(previousTrack, PREVIOUS_TRACK_COLOR, dashed = true)
        mapView.drawTrack(nextTrack, NEXT_TRACK_COLOR, dashed = true)
        mapView.drawTrack(track, SELECTED_TRACK_COLOR, dashed = false)

        mapView.overlays.add(
            Marker(mapView).apply {
                position = GeoPoint(observerLat, observerLon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = dotIcon(context, OBSERVER_COLOR, ringPx = 5f, radiusPx = 10f, glow = false)
                title = "Ground station"
            }
        )
        mapView.overlays.add(liveMarker)
        mapView.invalidate()
    }

    LaunchedEffect(livePosition) {
        if (livePosition == null) {
            liveMarker.isEnabled = false
        } else {
            liveMarker.position = GeoPoint(livePosition.latitude, livePosition.longitude)
            liveMarker.isEnabled = true
        }
        mapView.invalidate()
    }

    DisposableEffect(Unit) {
        onDispose { mapView.onDetach() }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
    ) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
    }
}

internal fun darkMapFilter(): ColorMatrixColorFilter {
    val m = ColorMatrix().apply { setSaturation(0f) }
    m.postConcat(
        ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
        )
    )
    return ColorMatrixColorFilter(m)
}

private fun MapView.drawTrack(
    track: List<GroundTrackPoint>,
    colorHex: String,
    dashed: Boolean
) {
    if (track.size < 2) return
    var segment = ArrayList<GeoPoint>()
    for (i in track.indices) {
        if (i > 0 && abs(track[i].longitude - track[i - 1].longitude) > 180.0) {
            if (segment.size >= 2) addTrackLine(segment, colorHex, dashed)
            segment = ArrayList()
        }
        segment.add(GeoPoint(track[i].latitude, track[i].longitude))
    }
    if (segment.size >= 2) addTrackLine(segment, colorHex, dashed)
}

private fun MapView.addTrackLine(pts: List<GeoPoint>, colorHex: String, dashed: Boolean) {
    // Halo: solid even under a dashed line, so the segment stays legible on the
    // dark basemap. Otherwise dashed-on-dark vanishes.
    val halo = Polyline().apply {
        setPoints(pts)
        outlinePaint.color = android.graphics.Color.parseColor(HALO_COLOR)
        outlinePaint.strokeWidth = 9f
    }
    val line = Polyline().apply {
        setPoints(pts)
        outlinePaint.color = android.graphics.Color.parseColor(colorHex)
        outlinePaint.strokeWidth = 5f
        if (dashed) {
            outlinePaint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
    }
    overlays.add(halo)
    overlays.add(line)
}

// Flat circular marker (optionally with a soft glow ring) instead of the
// default teardrop pin.
internal fun dotIcon(
    context: Context,
    fill: Int,
    ringPx: Float,
    radiusPx: Float,
    glow: Boolean
): BitmapDrawable {
    val pad = if (glow) radiusPx else ringPx
    val size = ((radiusPx + pad) * 2).toInt().coerceAtLeast(4)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val cx = size / 2f
    val cy = size / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    if (glow) {
        paint.color = (fill and 0x00FFFFFF) or 0x40000000
        canvas.drawCircle(cx, cy, radiusPx + pad, paint)
    }
    // White ring for contrast on the dark basemap.
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(cx, cy, radiusPx + ringPx, paint)
    paint.color = fill
    canvas.drawCircle(cx, cy, radiusPx, paint)
    return BitmapDrawable(context.resources, bmp)
}

internal const val OBSERVER_DOT_COLOR = OBSERVER_COLOR
