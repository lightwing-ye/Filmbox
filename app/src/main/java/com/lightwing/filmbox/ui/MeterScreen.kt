package com.lightwing.filmbox.ui

import android.view.HapticFeedbackConstants
import android.view.SurfaceView
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
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
import com.lightwing.filmbox.camera.LutEffect
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
import kotlin.math.pow
import kotlin.math.roundToInt

private val Bg = Color(0xFF0B0C0E)
private val Ink = Color(0xFFF2F3F5)
private val Dim = Color(0xFF8A8F98)
private val Accent = Color(0xFFE8B44A)
private val ZONE_BAR_H = 104.dp
private val BAR_PAD = 14.dp

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
    var previewView by remember { mutableStateOf<android.view.TextureView?>(null) }
    var showCalibration by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(calibration.offset) }

    var iso by remember { mutableStateOf(film.iso) }
    var aperture by remember { mutableStateOf(8.0) }
    var shutter by remember { mutableStateOf(1 / 125.0) }
    var locked by remember { mutableStateOf(setOf(Param.ISO)) }
    var comp by remember { mutableFloatStateOf(0f) }   // 曝光补偿 −1…+1 档

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
    // 补偿 +1 档 = 多给一档曝光 = 当作场景暗一档来解
    val meteredEv = sceneEv?.minus(comp.toDouble())

    val pointEvs = points.mapNotNull { p -> meteredEv?.let { it + (p.stops ?: return@mapNotNull null) } }
    val evForSolve = if (mode == MeterMode.ZONE && pointEvs.isNotEmpty()) pointEvs.average() else meteredEv

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

    var lutNote by remember { mutableStateOf<String?>(null) }
    var lutApprox by remember { mutableStateOf(false) }
    LaunchedEffect(film, previewView) {
        val ref = LutEffect.lutFor(film.id)
        lutApprox = ref?.approx == true
        previewView?.let { v ->
            val fx = LutEffect.effectFor(context, ref?.asset)
            LutEffect.apply(v, fx)
            lutNote = when {
                ref == null -> null
                fx == null -> "滤镜不可用：${LutEffect.lastError ?: "未知"}"
                else -> null
            }
        }
    }

    // 切后台停相机，回来重开（否则 TextureView 会卡死在最后一帧）
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> meter.stop()
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    previewView?.let { if (it.isAvailable) meter.start(it) }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val anchorStops = points.firstOrNull { it.id == anchorId }?.stops ?: 0.0
    val zoneOf: (Double) -> Double = { stops -> anchorZone + (stops - anchorStops) + comp.toDouble() }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(
            film = film,
            approxFilter = lutApprox,
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

            CameraView(meter) { previewView = it }

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
                        MeterMode.SPOT -> detectTapGestures { off ->
                            spotX = (off.x / size.width).coerceIn(0f, 1f)
                            spotY = (off.y / size.height).coerceIn(0f, 1f)
                            meter.setSpot(spotX, spotY, spotRadius(spotDeg))
                        }
                        MeterMode.AVERAGE -> Unit
                    }
                },
            )

            // 每个测光点的分区位置用弹簧动画过渡，避免切换锚点时硬跳
            val zones: List<Float> = points.map { p ->
                androidx.compose.runtime.key(p.id) {
                    animateFloatAsState(
                        targetValue = zoneOf(p.stops ?: 0.0).coerceIn(0.0, 10.0).toFloat(),
                        animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
                        label = "zone${p.id}",
                    ).value
                }
            }

            if (mode == MeterMode.SPOT) {
                SpotRing(spotX, spotY, spotDeg, boxW, boxH)
            }
            ZoneOverlay(points, zones, boxW, boxH, barHeightPx, padPx)
            ZoneBar(
                points = points,
                zones = zones,
                selectedId = anchorId,
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

        CompBar(
            comp = comp,
            onStep = { d -> comp = ((comp + d / 3f) * 3f).roundToInt() / 3f },
            onReset = { comp = 0f },
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

        lutNote?.let {
            Text(it, color = Color(0xFFE0574B), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
        StatusLine(
            film = film,
            iso = iso,
            solution = solution,
            ev = evForSolve,
            pointCount = points.size,
            mode = mode,
            offset = offset,
            comp = comp,
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

/** 点测圈：圈外压暗，圈内保持原亮度。 */
@Composable
private fun SpotRing(spotX: Float, spotY: Float, deg: Float, boxW: Float, boxH: Float) {
    val ax by animateFloatAsState(spotX, spring(dampingRatio = 0.75f, stiffness = 600f), label = "spotX")
    val ay by animateFloatAsState(spotY, spring(dampingRatio = 0.75f, stiffness = 600f), label = "spotY")
    val ar by animateFloatAsState(spotRadius(deg), tween(180), label = "spotR")

    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(ax * boxW, ay * boxH)
        val r = (ar * boxW).coerceAtLeast(18f)

        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
            addOval(androidx.compose.ui.geometry.Rect(c.x - r, c.y - r, c.x + r, c.y + r))
        }
        drawPath(scrim, Color(0x66000000))

        drawCircle(Color.White, radius = r, center = c, style = Stroke(2.5f))
        drawCircle(Color(0x66FFFFFF), radius = r + 7f, center = c, style = Stroke(1f))
        drawLine(Color.White, Offset(c.x - r * 1.75f, c.y), Offset(c.x - r * 1.12f, c.y), 2f)
        drawLine(Color.White, Offset(c.x + r * 1.12f, c.y), Offset(c.x + r * 1.75f, c.y), 2f)
        drawLine(Color.White, Offset(c.x, c.y - r * 1.75f), Offset(c.x, c.y - r * 1.12f), 2f)
        drawLine(Color.White, Offset(c.x, c.y + r * 1.12f), Offset(c.x, c.y + r * 1.75f), 2f)
    }
}

/** 测光点 → 分区条的连线，逐点展开；点位用编号圆标。 */
@Composable
private fun ZoneOverlay(
    points: List<MeterPoint>,
    zones: List<Float>,
    boxW: Float,
    boxH: Float,
    barHeightPx: Float,
    padPx: Float,
) {
    val reveal by animateFloatAsState(
        targetValue = points.size.toFloat(),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "reveal",
    )
    if (points.isEmpty()) return
    val measurer = rememberTextMeasurer()

    Canvas(Modifier.fillMaxSize()) {
        val barTop = boxH - barHeightPx
        val cellW = (boxW - padPx * 2) / 11f
        points.forEachIndexed { i, p ->
            val progress = (reveal - i).coerceIn(0f, 1f)
            if (progress <= 0f) return@forEachIndexed
            val zone = zones.getOrElse(i) { 5f }
            val target = Offset(padPx + cellW * zone + cellW / 2f, barTop + 4f)
            val start = Offset(p.x * boxW, p.y * boxH)
            drawPath(
                Path().apply {
                    moveTo(start.x, start.y)
                    lineTo(start.x + (target.x - start.x) * progress, start.y + (target.y - start.y) * progress)
                },
                color = Accent.copy(alpha = 0.5f * progress),
                style = Stroke(width = 1.6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 7f))),
            )
            val r = 16f * progress
            drawCircle(Color(0xD90B0C0E), radius = r, center = start)
            drawCircle(Accent.copy(alpha = progress), radius = r, center = start, style = Stroke(2.5f))
            val label = measurer.measure(
                "${i + 1}",
                TextStyle(fontSize = 12.sp, color = Accent.copy(alpha = progress), fontWeight = FontWeight.Bold),
            )
            drawText(
                label,
                topLeft = Offset(start.x - label.size.width / 2f, start.y - label.size.height / 2f),
            )
        }
    }
}

@Composable
private fun TopBar(
    film: Film,
    approxFilter: Boolean,
    ev: Double?,
    converged: Boolean,
    calibrated: Boolean,
    onFilmClick: () -> Unit,
    onCalibrate: () -> Unit,
) {
    val evShown by animateFloatAsState(
        targetValue = (ev ?: 0.0).toFloat(),
        animationSpec = tween(160),
        label = "ev",
    )
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clickable { onFilmClick() }) {
            Text(film.label, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(
                "ISO ${film.iso} · " + when (film.tier) {
                    DataTier.MANUFACTURER -> "厂商数据"
                    DataTier.COMMUNITY -> "社区数据"
                    DataTier.APPROX -> "近似值"
                } + if (approxFilter) " · 滤镜近似" else "",
                color = Dim, fontSize = 10.sp,
            )
        }
        CalibrationChip(calibrated, onCalibrate)
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (ev == null) "—" else String.format("%.1f", evShown),
                color = if (converged) Ink else Dim,
                fontSize = 38.sp,
                fontWeight = FontWeight.Light,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            Text("EV100", color = Dim, fontSize = 9.sp, letterSpacing = 1.2.sp)
        }
    }
}

@Composable
private fun CalibrationChip(calibrated: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (calibrated) Color(0x1FE8B44A) else Color(0x14FFFFFF), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(
            if (calibrated) "已校准" else "校准",
            color = if (calibrated) Accent else Dim,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun CameraView(meter: CameraMeter, onReady: (android.view.TextureView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            android.view.TextureView(ctx).apply {
                val tv = this
                surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                        meter.start(tv)
                        onReady(tv)
                    }
                    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) = Unit
                    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean = false
                    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) = Unit
                }
            }
        },
    )
}

/** 0 区…X 区的真实灰阶：分区线性反射率 18% × 2^(z−5)，再转 sRGB。 */
private fun zoneGray(zone: Int): Color {
    val linear = 0.18 * 2.0.pow(zone - 5.0)
    val c = linear.coerceIn(0.0, 1.0)
    val srgb = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
    val v = (srgb * 255).toInt().coerceIn(0, 255)
    return Color(v, v, v)
}

/**
 * 曝光分区条：11 格线框色阶 + 顶部可滑动的小三角游标。
 * 只有被选中的测光点才会出现游标（1:1 对应）。
 */
private val ROMAN = arrayOf("0", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")

@Composable
private fun ZoneBar(
    points: List<MeterPoint>,
    zones: List<Float>,
    selectedId: Int?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.height(ZONE_BAR_H).padding(horizontal = BAR_PAD, vertical = 6.dp)) {
        val w = size.width
        val h = size.height
        val cellW = w / 11f
        val barTop = h * 0.28f
        val barH = h * 0.46f

        for (i in 0..10) {
            val left = cellW * i
            drawRect(zoneGray(i), topLeft = Offset(left, barTop), size = Size(cellW, barH))
            drawRect(
                Color(0xF2FFFFFF),
                topLeft = Offset(left + 1f, barTop + 1f),
                size = Size(cellW - 2f, barH - 2f),
                style = Stroke(2.2f),
            )
            val layout = measurer.measure(
                ROMAN[i],
                TextStyle(
                    fontSize = 12.sp,
                    color = if (i <= 2 || i >= 9) Color(0xE6E8B44A) else Color(0xE6F2F3F5),
                    fontWeight = FontWeight.Medium,
                ),
            )
            drawText(
                layout,
                topLeft = Offset(left + cellW / 2f - layout.size.width / 2f, barTop + barH + 2f),
            )
        }

        points.forEachIndexed { i, p ->
            val zone = zones.getOrElse(i) { 5f }
            val x = cellW * zone + cellW / 2f
            val selected = p.id == selectedId
            val color = if (selected) Accent else Color(0xD9FFFFFF)
            val base = cellW * 2f / 3f
            val triH = (cellW * 0.5f).coerceIn(6f, 15f)
            drawPath(
                Path().apply {
                    moveTo(x - base / 2f, barTop + 1f)
                    lineTo(x + base / 2f, barTop + 1f)
                    lineTo(x, barTop + 1f + triH)
                    close()
                },
                color,
            )
            if (selected) {
                drawLine(color, Offset(x, barTop - 14f), Offset(x, barTop + 1f), strokeWidth = 1.5f)
            }
        }
    }
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
        ParamPill(
            caption = "ISO",
            value = iso.toString(),
            prev = stepIso(iso, -1).toString(),
            next = stepIso(iso, 1).toString(),
            locked = Param.ISO in locked,
            onToggle = { onToggle(Param.ISO) },
            onStep = onStepIso,
            modifier = Modifier.weight(1f),
        )
        ParamPill(
            caption = "光圈",
            value = "f/" + fmtAperture(aperture),
            prev = "f/" + fmtAperture(stepOf(aperture, ExposureSolver.APERTURES, -1)),
            next = "f/" + fmtAperture(stepOf(aperture, ExposureSolver.APERTURES, 1)),
            locked = Param.APERTURE in locked,
            onToggle = { onToggle(Param.APERTURE) },
            onStep = onStepAperture,
            modifier = Modifier.weight(1f),
        )
        ParamPill(
            caption = "快门",
            value = fmtShutter(shutter),
            prev = fmtShutter(stepOf(shutter, ExposureSolver.SHUTTERS, -1)),
            next = fmtShutter(stepOf(shutter, ExposureSolver.SHUTTERS, 1)),
            locked = Param.SHUTTER in locked,
            onToggle = { onToggle(Param.SHUTTER) },
            onStep = onStepShutter,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CompBar(comp: Float, onStep: (Int) -> Unit, onReset: () -> Unit) {
    val view = LocalView.current
    val stepPx = with(LocalDensity.current) { 26.dp.toPx() }
    var acc by remember { mutableStateOf(0f) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("曝光补偿", color = Dim, fontSize = 10.sp)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.height(26.dp)
                .background(if (abs(comp) > 0.01f) Color(0x1FE8B44A) else Color(0x12FFFFFF), RoundedCornerShape(7.dp))
                .clickable { onReset() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f },
                    ) { _, dy ->
                        acc += dy
                        while (abs(acc) >= stepPx) {
                            onStep(if (acc < 0) 1 else -1)
                            acc += if (acc < 0) stepPx else -stepPx
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                    }
                }
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    abs(comp) < 0.01f -> "±0"
                    comp > 0 -> String.format("+%.1f", comp)
                    else -> String.format("%.1f", comp)
                },
                color = if (abs(comp) > 0.01f) Accent else Dim,
                fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("上下滑调整 · 点按归零", color = Dim, fontSize = 9.sp)
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
            ModeChip("清空", false) { onClear() }
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
 * 参数胶囊：上下滑 1/3 档，滑动时上下浮出相邻档位数字；点按切换锁定。
 */
@Composable
private fun ParamPill(
    caption: String,
    value: String,
    prev: String,
    next: String,
    locked: Boolean,
    onToggle: () -> Unit,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var acc by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val nudge = remember { Animatable(0f) }
    val ghost by animateFloatAsState(if (dragging) 0.75f else 0f, tween(140), label = "ghost")
    val stepPx = with(LocalDensity.current) { 26.dp.toPx() }

    Box(
        modifier
            .height(64.dp)
            .background(if (locked) Color(0xFF23262B) else Color(0xFF15171B), RoundedCornerShape(12.dp))
            .clickable { onToggle() }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f; dragging = true },
                    onDragEnd = { acc = 0f; dragging = false },
                    onDragCancel = { acc = 0f; dragging = false },
                ) { _, dy ->
                    acc += dy
                    while (abs(acc) >= stepPx) {
                        val dir = if (acc < 0) 1 else -1
                        onStep(dir)
                        scope.launch {
                            nudge.snapTo(if (dir > 0) -16f else 16f)
                            nudge.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = Spring.StiffnessMedium))
                        }
                        acc += if (acc < 0) stepPx else -stepPx
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 滑动时浮出的上下档位
        if (ghost > 0.01f) {
            Text(
                prev,
                color = Dim.copy(alpha = ghost * 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.offset(y = (-26).dp),
            )
            Text(
                next,
                color = Dim.copy(alpha = ghost * 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.offset(y = 26.dp),
            )
        }
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
    comp: Float,
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
            val ei = if (iso != film.iso) "；ISO $iso" else ""
            val pts = if (mode == MeterMode.ZONE && pointCount > 0) "；$pointCount 点均值" else ""
            val cmp = if (abs(comp) >= 0.01f) "；补偿 ${String.format("%+.1f", comp)}" else ""
            val cal = if (abs(offset) >= 0.01) "；校准 ${String.format("%+.2f", -offset)}" else ""
            base + recip + ei + pts + cmp + cal
        }
    }
    androidx.compose.animation.AnimatedContent(
        targetState = text,
        transitionSpec = {
            (androidx.compose.animation.fadeIn(tween(160)) +
                androidx.compose.animation.slideInVertically(tween(160)) { it / 3 })
                .togetherWith(androidx.compose.animation.fadeOut(tween(120)))
        },
        label = "status",
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp),
    ) { t ->
        Text(t, color = Dim, fontSize = 12.sp)
    }
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
