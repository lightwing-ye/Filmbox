package com.lightwing.filmbox.ui

import android.view.HapticFeedbackConstants
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.lightwing.filmbox.camera.CameraMeter
import com.lightwing.filmbox.camera.MeterReading
import com.lightwing.filmbox.data.CalibrationStore
import filmmeter.core.DataTier
import filmmeter.core.ExposureSolver
import filmmeter.core.Film
import filmmeter.core.FilmCatalog
import filmmeter.core.MeterSolver
import filmmeter.core.Param
import filmmeter.core.Solution
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val Bg = Color(0xFF0B0C0E)
private val Ink = Color(0xFFF2F3F5)
private val Dim = Color(0xFF8A8F98)
private val Accent = Color(0xFFE8B44A)
private val ZONE_BAR_H = 92.dp
private val BAR_PAD = 18.dp

enum class MeterMode { AVERAGE, SPOT, ZONE }

/** 一个区域测光点：归一化坐标 + 相对全画面均值的档数。 */
data class MeterPoint(val id: Int, val x: Float, val y: Float, val stops: Double?)

private val EI_STEPS = intArrayOf(
    25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320,
    400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
)

@Composable
fun MeterScreen() {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<MeterReading?>(null) }
    val meter = remember { CameraMeter(context, onReading = { reading = it }, onError = {}) }
    val calibration = remember { CalibrationStore(context) }
    DisposableEffect(Unit) { onDispose { meter.stop() } }

    var film by remember { mutableStateOf(FilmCatalog.byId("kodak_portra400") ?: FilmCatalog.films.first()) }
    var showPicker by remember { mutableStateOf(false) }
    var showCalibration by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(calibration.offset) }

    var iso by remember { mutableStateOf(film.iso) }
    var aperture by remember { mutableStateOf(8.0) }
    var shutter by remember { mutableStateOf(1 / 125.0) }
    var locked by remember { mutableStateOf(setOf(Param.ISO)) }

    var mode by remember { mutableStateOf(MeterMode.AVERAGE) }
    var spotX by remember { mutableStateOf(0.5f) }
    var spotY by remember { mutableStateOf(0.5f) }
    var spotDeg by remember { mutableStateOf(3f) }

    var points by remember { mutableStateOf(listOf<MeterPoint>()) }
    var nextId by remember { mutableIntStateOf(0) }
    var anchorId by remember { mutableStateOf<Int?>(null) }
    var anchorZone by remember { mutableIntStateOf(5) }

    val rawEv = reading?.ev100
    val sceneEv = rawEv?.let { it - offset }

    // 多点测光：各点相对档数换算出各自 EV，再取平均
    val pointEvs = points.mapNotNull { p -> sceneEv?.let { it + (p.stops ?: return@mapNotNull null) } }
    val evForSolve = if (mode == MeterMode.ZONE && pointEvs.isNotEmpty()) pointEvs.average() else sceneEv

    val solution: Solution? = evForSolve?.let { MeterSolver.solve(it, iso, locked, aperture, shutter, film) }

    LaunchedEffect(solution) {
        solution?.let { s ->
            if (Param.APERTURE !in locked) aperture = s.aperture
            if (Param.SHUTTER !in locked) shutter = s.shutterSec
        }
    }

    LaunchedEffect(reading) {
        if (points.isNotEmpty()) {
            points = points.map { it.copy(stops = meter.sampleRelativeStops(it.x, it.y)) }
        }
    }

    // 锚点：以某个测光点（或全画面均值）为参考，把它落到指定区域
    val anchorStops = points.firstOrNull { it.id == anchorId }?.stops ?: 0.0
    val zoneOf: (Double) -> Double = { stops -> anchorZone + (stops - anchorStops) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(
            film = film,
            ev = evForSolve,
            converged = reading?.converged == true,
            calibrated = calibration.isCalibrated,
            onFilmClick = { showPicker = true },
            onCalibrate = { showCalibration = true },
        )

        BoxWithConstraints(Modifier.weight(1f)) {
            val density = LocalDensity.current
            val barHeightPx = with(density) { ZONE_BAR_H.toPx() }
            val padPx = with(density) { BAR_PAD.toPx() }
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()

            CameraView(meter)

            // 交互层：点测拖动 / 区域加点
            Box(
                Modifier.fillMaxSize().pointerInput(mode) {
                    when (mode) {
                        MeterMode.ZONE -> detectTapGestures { off ->
                            val nx = (off.x / size.width).coerceIn(0f, 1f)
                            val ny = (off.y / size.height).coerceIn(0f, 1f)
                            points = points + MeterPoint(
                                id = nextId++, x = nx, y = ny,
                                stops = meter.sampleRelativeStops(nx, ny),
                            )
                        }
                        MeterMode.SPOT -> detectDragGestures(
                            onDragStart = { off ->
                                spotX = (off.x / size.width).coerceIn(0f, 1f)
                                spotY = (off.y / size.height).coerceIn(0f, 1f)
                            },
                            onDragEnd = { meter.setSpot(spotX, spotY, spotRadius(spotDeg)) },
                        ) { change, _ ->
                            change.consume()
                            spotX = (change.position.x / size.width).coerceIn(0f, 1f)
                            spotY = (change.position.y / size.height).coerceIn(0f, 1f)
                        }
                        MeterMode.AVERAGE -> Unit
                    }
                },
            )

            if (mode == MeterMode.SPOT) {
                SpotRing(spotX = spotX, spotY = spotY, deg = spotDeg, boxW = boxW, boxH = boxH)
            }
            ZoneOverlay(points, boxW, boxH, barHeightPx, padPx, zoneOf)
            ZoneBar(
                offsets = reading?.zoneOffsets,
                points = points,
                anchorId = anchorId,
                zoneOf = zoneOf,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }

        ParamBar(
            iso = iso, aperture = aperture, shutter = shutter, locked = locked,
            onToggle = { p -> locked = if (p in locked) locked - p else locked + p },
            onStepIso = { d -> iso = stepIso(iso, d); locked = locked + Param.ISO },
            onStepAperture = { d ->
                aperture = stepOf(aperture, ExposureSolver.APERTURES, d); locked = locked + Param.APERTURE
            },
            onStepShutter = { d ->
                shutter = stepOf(shutter, ExposureSolver.SHUTTERS, d); locked = locked + Param.SHUTTER
            },
        )

        ModeRow(
            mode = mode,
            spotDeg = spotDeg,
            pointCount = points.size,
            anchorZone = anchorZone,
            onMode = { m ->
                mode = m
                if (m == MeterMode.SPOT) meter.setSpot(spotX, spotY, spotRadius(spotDeg))
                if (m != MeterMode.ZONE) points = emptyList()
            },
            onSpotDeg = { d ->
                spotDeg = d
                meter.setSpot(spotX, spotY, spotRadius(d))
            },
            onClear = { points = emptyList(); anchorId = null },
            onAnchor = { z -> anchorZone = z; anchorId = points.lastOrNull()?.id },
        )

        StatusLine(
            film = film,
            iso = iso,
            solution = solution,
            ev = evForSolve,
            pointCount = points.size,
            mode = mode,
            offset = offset,
        )
    }

    if (showPicker) {
        FilmPicker(current = film, onDismiss = { showPicker = false }, onPick = {
            film = it; iso = it.iso; showPicker = false
        })
    }
    if (showCalibration) {
        CalibrationDialog(
            currentEv = sceneEv,
            currentOffset = offset,
            onApply = { ref ->
                rawEv?.let {
                    offset = it - ref
                    calibration.offset = offset
                }
                showCalibration = false
            },
            onReset = {
                offset = 0.0
                calibration.reset()
                showCalibration = false
            },
            onDismiss = { showCalibration = false },
        )
    }
}

/** 点测圈的视场角 → 归一化半径（主摄水平视场约 70°）。 */
private fun spotRadius(deg: Float): Float = (deg / 70f / 2f).coerceIn(0.005f, 0.25f)

@Composable
private fun SpotRing(spotX: Float, spotY: Float, deg: Float, boxW: Float, boxH: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(spotX * boxW, spotY * boxH)
        val r = spotRadius(deg) * boxW
        drawCircle(Color(0xCCFFFFFF), radius = r, center = c, style = Stroke(1.5f))
        drawLine(Color(0x99FFFFFF), Offset(c.x - r * 1.6f, c.y), Offset(c.x - r * 1.1f, c.y), 1.5f)
        drawLine(Color(0x99FFFFFF), Offset(c.x + r * 1.1f, c.y), Offset(c.x + r * 1.6f, c.y), 1.5f)
        drawLine(Color(0x99FFFFFF), Offset(c.x, c.y - r * 1.6f), Offset(c.x, c.y - r * 1.1f), 1.5f)
        drawLine(Color(0x99FFFFFF), Offset(c.x, c.y + r * 1.1f), Offset(c.x, c.y + r * 1.6f), 1.5f)
    }
}

/** 测光点 → 区域条的连线，逐点展开。 */
@Composable
private fun ZoneOverlay(
    points: List<MeterPoint>,
    boxW: Float,
    boxH: Float,
    barHeightPx: Float,
    padPx: Float,
    zoneOf: (Double) -> Double,
) {
    val reveal by animateFloatAsState(
        targetValue = points.size.toFloat(),
        animationSpec = tween(420),
        label = "reveal",
    )
    if (points.isEmpty()) return
    Canvas(Modifier.fillMaxSize()) {
        val barTop = boxH - barHeightPx
        val usable = boxW - padPx * 2
        points.forEachIndexed { i, p ->
            val progress = (reveal - i).coerceIn(0f, 1f)
            if (progress <= 0f) return@forEachIndexed
            val zone = zoneOf(p.stops ?: 0.0).coerceIn(0.0, 10.0).toFloat()
            val target = Offset(padPx + usable * zone / 10f, barTop + barHeightPx * 0.42f)
            val start = Offset(p.x * boxW, p.y * boxH)
            val end = Offset(
                start.x + (target.x - start.x) * progress,
                start.y + (target.y - start.y) * progress,
            )
            drawPath(
                Path().apply { moveTo(start.x, start.y); lineTo(end.x, end.y) },
                color = Accent.copy(alpha = 0.7f),
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))),
            )
            drawCircle(Accent, radius = 4.5f, center = start)
        }
    }
}

@Composable
private fun TopBar(
    film: Film,
    ev: Double?,
    converged: Boolean,
    calibrated: Boolean,
    onFilmClick: () -> Unit,
    onCalibrate: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable { onFilmClick() }) {
            Text(film.label, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                "ISO ${film.iso} · " + when (film.tier) {
                    DataTier.MANUFACTURER -> "厂商数据"
                    DataTier.COMMUNITY -> "社区数据"
                    DataTier.APPROX -> "近似值"
                },
                color = Dim, fontSize = 11.sp,
            )
        }
        Column(
            Modifier.padding(end = 12.dp).clickable { onCalibrate() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("校准", color = if (calibrated) Accent else Dim, fontSize = 12.sp)
            Text(if (calibrated) "已校" else "未校", color = Dim, fontSize = 9.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                ev?.let { String.format("%.1f", it) } ?: "—",
                color = if (converged) Ink else Dim,
                fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
            )
            Text("EV100", color = Dim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CameraView(meter: CameraMeter) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : android.view.SurfaceHolder.Callback {
                    override fun surfaceCreated(h: android.view.SurfaceHolder) = meter.start(h)
                    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) = Unit
                })
            }
        },
    )
}

/**
 * 曝光分区条：0–X 连续色调 + 游标。
 * 游标 = 竖线；锚点游标加粗，测光点按各自落点显示。
 */
@Composable
private fun ZoneBar(
    offsets: List<Double>?,
    points: List<MeterPoint>,
    anchorId: Int?,
    zoneOf: (Double) -> Double,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.height(ZONE_BAR_H).padding(horizontal = BAR_PAD, vertical = 8.dp)) {
        val w = size.width
        val h = size.height
        val barTop = h * 0.34f
        val barH = h * 0.28f

        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF050505), Color(0xFF3A3A3A), Color(0xFF8A8A8A), Color(0xFFF5F5F5)),
            ),
            topLeft = Offset(0f, barTop),
            size = Size(w, barH),
        )
        drawRect(Color(0x55FFFFFF), topLeft = Offset(0f, barTop), size = Size(w, barH), style = Stroke(1f))

        for (i in 0..10) {
            val x = w * i / 10f
            drawLine(Color(0x66FFFFFF), Offset(x, barTop), Offset(x, barTop + barH), strokeWidth = 1f)
            drawZoneLabel(measurer, i, x, barTop + barH + 4f, w)
        }

        // 3×3 区域：细游标
        offsets?.forEachIndexed { idx, off ->
            val zone = zoneOf(off).coerceIn(0.0, 10.0).toFloat()
            val x = w * zone / 10f
            val alpha = if (idx == 4) 0.95f else 0.55f
            drawLine(
                Color.White.copy(alpha = alpha),
                Offset(x, barTop + 2f), Offset(x, barTop + barH - 2f),
                strokeWidth = if (idx == 4) 2f else 1.2f,
            )
        }

        // 测光点：整条竖线游标
        points.forEach { p ->
            val zone = zoneOf(p.stops ?: 0.0).coerceIn(0.0, 10.0).toFloat()
            val x = w * zone / 10f
            val isAnchor = p.id == anchorId
            val color = if (isAnchor) Accent else Color(0xE6FFFFFF)
            val top = if (isAnchor) barTop - 16f else barTop - 12f
            drawLine(color, Offset(x, top), Offset(x, barTop + barH + 2f), strokeWidth = if (isAnchor) 2.6f else 1.8f)
            drawCircle(color, radius = if (isAnchor) 3.6f else 2.6f, center = Offset(x, top))
        }
    }
}

private fun DrawScope.drawZoneLabel(m: TextMeasurer, zone: Int, x: Float, y: Float, width: Float) {
    val label = when (zone) {
        0 -> "黑"
        5 -> "V"
        10 -> "白"
        else -> zone.toString()
    }
    val layout = m.measure(label, TextStyle(fontSize = 8.sp, color = Dim))
    val left = (x - layout.size.width / 2f).coerceIn(0f, width - layout.size.width)
    drawText(layout, topLeft = Offset(left, y))
}

@Composable
private fun ParamBar(
    iso: Int,
    aperture: Double,
    shutter: Double,
    locked: Set<Param>,
    onToggle: (Param) -> Unit,
    onStepIso: (Int) -> Unit,
    onStepAperture: (Int) -> Unit,
    onStepShutter: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ParamPill("EI", iso.toString(), Param.ISO in locked, { onToggle(Param.ISO) }, onStepIso, Modifier.weight(1f))
        ParamPill("光圈", "f/" + fmtAperture(aperture), Param.APERTURE in locked, { onToggle(Param.APERTURE) }, onStepAperture, Modifier.weight(1f))
        ParamPill("快门", fmtShutter(shutter), Param.SHUTTER in locked, { onToggle(Param.SHUTTER) }, onStepShutter, Modifier.weight(1f))
    }
}

@Composable
private fun ModeRow(
    mode: MeterMode,
    spotDeg: Float,
    pointCount: Int,
    anchorZone: Int,
    onMode: (MeterMode) -> Unit,
    onSpotDeg: (Float) -> Unit,
    onClear: () -> Unit,
    onAnchor: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ModeChip("平均", mode == MeterMode.AVERAGE) { onMode(MeterMode.AVERAGE) }
        ModeChip("点测", mode == MeterMode.SPOT) { onMode(MeterMode.SPOT) }
        ModeChip(if (mode == MeterMode.ZONE) "区域 $pointCount" else "区域", mode == MeterMode.ZONE) {
            onMode(MeterMode.ZONE)
        }
        if (mode == MeterMode.SPOT) {
            ModeChip("${spotDeg.toInt()}°", false) {
                onSpotDeg(if (spotDeg >= 10f) 1f else spotDeg * 2f)
            }
        }
        if (mode == MeterMode.ZONE) {
            ModeChip("锚III", anchorZone == 3) { onAnchor(3) }
            ModeChip("锚V", anchorZone == 5) { onAnchor(5) }
            ModeChip("锚VII", anchorZone == 7) { onAnchor(7) }
        }
    }
}

@Composable
private fun ModeChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp)
            .background(if (active) Color(0xFF2A2317) else Color(0xFF1A1C20), RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (active) Accent else Dim, fontSize = 12.sp)
    }
}

/**
 * 参数胶囊：上滑/下滑 1/3 档，带「转轮」回弹动画；点按切换锁定。
 */
@Composable
private fun ParamPill(
    caption: String,
    value: String,
    locked: Boolean,
    onToggle: () -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var acc by remember { mutableStateOf(0f) }
    val nudge = remember { Animatable(0f) }
    val stepPx = with(LocalDensity.current) { 26.dp.toPx() }

    Box(
        modifier
            .height(64.dp)
            .background(if (locked) Color(0xFF23262B) else Color(0xFF15171B), RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { acc = 0f },
                ) { _, dy ->
                    acc += dy
                    while (abs(acc) >= stepPx) {
                        val dir = if (acc < 0) 1 else -1
                        onStep(dir)
                        scope.launch {
                            nudge.snapTo(if (dir > 0) -14f else 14f)
                            nudge.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                        }
                        acc += if (acc < 0) stepPx else -stepPx
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.offset { IntOffset(0, nudge.value.roundToInt()) },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, color = if (locked) Ink else Dim, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(caption, color = Dim, fontSize = 10.sp)
                if (locked) {
                    Spacer(Modifier.width(3.dp))
                    Text("锁", color = Accent, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun StatusLine(
    film: Film,
    iso: Int,
    solution: Solution?,
    ev: Double?,
    pointCount: Int,
    mode: MeterMode,
    offset: Double,
) {
    val corrected = solution?.shutterSec?.let {
        val c = film.reciprocity.correct(it)
        if (c > ExposureSolver.SHUTTERS.last()) null else c
    }
    val text = when {
        ev == null -> "对准场景，等待 AE 收敛…"
        solution == null -> "—"
        else -> {
            val err = solution.errorStops
            val base = if (abs(err) < 1.0 / 6.0) "曝光准确"
            else if (err > 0) "过曝 +${String.format("%.2f", err)} 档"
            else "欠曝 ${String.format("%.2f", err)} 档"
            val recip = if (corrected != null && corrected > solution.shutterSec * 1.02)
                "；互易律 ${fmtShutter(corrected)}" else ""
            val ei = if (iso != film.iso) "；EI $iso" else ""
            val pts = if (mode == MeterMode.ZONE && pointCount > 0) "；$pointCount 点均值" else ""
            val cal = if (abs(offset) >= 0.01) "；校准 ${String.format("%+.2f", -offset)}" else ""
            base + recip + ei + pts + cal
        }
    }
    Text(
        text, color = Dim, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

private fun stepIso(current: Int, dir: Int): Int {
    val idx = EI_STEPS.indexOfFirst { it >= current }.let { if (it < 0) EI_STEPS.lastIndex else it }
    return EI_STEPS[(idx + dir).coerceIn(0, EI_STEPS.lastIndex)]
}

private fun stepOf(current: Double, series: DoubleArray, dir: Int): Double {
    val idx = series.indexOfFirst { it >= current * 0.999 }.let { if (it < 0) series.lastIndex else it }
    return series[(idx + dir).coerceIn(0, series.lastIndex)]
}

private fun fmtAperture(a: Double): String =
    if (a % 1.0 == 0.0) a.toInt().toString() else String.format("%.1f", a)

fun fmtShutter(t: Double): String = when {
    t >= 1.0 -> String.format("%.1fs", t)
    else -> "1/${Math.round(1.0 / t)}"
}
