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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
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
private val BarBg = Color(0xCC0B0C0E)
private val ZONE_BAR_H = 86.dp
private val BAR_PAD = 18.dp

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
    DisposableEffect(Unit) { onDispose { meter.stop() } }

    var film by remember { mutableStateOf(FilmCatalog.byId("kodak_portra400") ?: FilmCatalog.films.first()) }
    var showPicker by remember { mutableStateOf(false) }
    var iso by remember { mutableStateOf(film.iso) }
    var aperture by remember { mutableStateOf(8.0) }
    var shutter by remember { mutableStateOf(1 / 125.0) }
    var locked by remember { mutableStateOf(setOf(Param.ISO)) }

    // 区域测光点
    var zoneMode by remember { mutableStateOf(false) }
    var points by remember { mutableStateOf(listOf<MeterPoint>()) }
    var nextId by remember { mutableIntStateOf(0) }

    val ev = reading?.ev100
    val solution: Solution? = ev?.let { MeterSolver.solve(it, iso, locked, aperture, shutter, film) }

    LaunchedEffect(solution) {
        solution?.let { s ->
            if (Param.APERTURE !in locked) aperture = s.aperture
            if (Param.SHUTTER !in locked) shutter = s.shutterSec
        }
    }

    // 每一帧刷新已有测光点的相对档数
    LaunchedEffect(reading) {
        if (points.isNotEmpty()) {
            points = points.map { it.copy(stops = meter.sampleRelativeStops(it.x, it.y)) }
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(film, ev, reading?.converged == true)
        BoxWithConstraints(Modifier.weight(1f)) {
            val barHeightPx = with(LocalDensity.current) { ZONE_BAR_H.toPx() }
            val padPx = with(LocalDensity.current) { BAR_PAD.toPx() }
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()

            CameraView(meter)
            // 点按加测光点（区域测光模式）
            Box(
                Modifier.fillMaxSize().pointerInput(zoneMode) {
                    if (!zoneMode) return@pointerInput
                    detectTapGestures { off ->
                        val nx = (off.x / size.width).coerceIn(0f, 1f)
                        val ny = (off.y / size.height).coerceIn(0f, 1f)
                        points = points + MeterPoint(
                            id = nextId++, x = nx, y = ny,
                            stops = meter.sampleRelativeStops(nx, ny),
                        )
                    }
                },
            )
            ZoneOverlay(
                points = points,
                boxW = boxW,
                boxH = boxH,
                barHeightPx = barHeightPx,
                padPx = padPx,
            )
            ZoneBar(
                offsets = reading?.zoneOffsets,
                points = points,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            )
        }
        ParamBar(
            iso = iso,
            aperture = aperture,
            shutter = shutter,
            locked = locked,
            zoneMode = zoneMode,
            onToggleZone = { zoneMode = !zoneMode; if (!zoneMode) points = emptyList() },
            onClearPoints = { points = emptyList() },
            onToggle = { p -> locked = if (p in locked) locked - p else locked + p },
            onStepIso = { d -> iso = stepIso(iso, d); locked = locked + Param.ISO },
            onStepAperture = { d ->
                aperture = stepOf(aperture, ExposureSolver.APERTURES, d); locked = locked + Param.APERTURE
            },
            onStepShutter = { d ->
                shutter = stepOf(shutter, ExposureSolver.SHUTTERS, d); locked = locked + Param.SHUTTER
            },
            onPickFilm = { showPicker = true },
        )
        StatusLine(film, iso, solution, ev)
    }

    if (showPicker) {
        FilmPicker(current = film, onDismiss = { showPicker = false }, onPick = {
            film = it; iso = it.iso; showPicker = false
        })
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
            val zone = (5.0 + (p.stops ?: 0.0)).coerceIn(0.0, 10.0).toFloat()
            val target = Offset(padPx + usable * zone / 10f, barTop + barHeightPx * 0.45f)
            val start = Offset(p.x * boxW, p.y * boxH)
            // 从测光点向下画到区域条对应分区，按 progress 逐步长出
            val end = Offset(
                start.x + (target.x - start.x) * progress,
                start.y + (target.y - start.y) * progress,
            )
            drawPath(
                Path().apply {
                    moveTo(start.x, start.y)
                    lineTo(end.x, end.y)
                },
                color = Accent.copy(alpha = 0.75f),
                style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f))),
            )
            drawCircle(Accent, radius = 5f, center = start)
        }
    }
}

@Composable
private fun TopBar(film: Film, ev: Double?, converged: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
 * 曝光分区条：0–X 连续色调，标出 3×3 区域与测光点的落点。
 * 放在取景器下缘，横向铺满。
 */
@Composable
private fun ZoneBar(offsets: List<Double>?, points: List<MeterPoint>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier.height(ZONE_BAR_H).padding(horizontal = BAR_PAD, vertical = 10.dp)) {
        val w = size.width
        val h = size.height
        val barTop = h * 0.30f
        val barH = h * 0.30f

        // 色调连续带：0 区黑 → X 区白
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color(0xFF050505), Color(0xFF3A3A3A), Color(0xFF8A8A8A), Color(0xFFF5F5F5)),
            ),
            topLeft = Offset(0f, barTop),
            size = androidx.compose.ui.geometry.Size(w, barH),
        )
        drawRect(Color(0x55FFFFFF), topLeft = Offset(0f, barTop), size = androidx.compose.ui.geometry.Size(w, barH), style = Stroke(1f))

        for (i in 0..10) {
            val x = w * i / 10f
            drawLine(Color(0x88FFFFFF), Offset(x, barTop), Offset(x, barTop + barH), strokeWidth = 1f)
            drawZoneLabel(measurer, i, x, barTop + barH + 4f, w)
        }

        // 3×3 区域：细白点标在条下方
        offsets?.forEachIndexed { idx, off ->
            val zone = (5.0 + off).coerceIn(0.0, 10.0).toFloat()
            val x = w * zone / 10f
            drawCircle(
                if (idx == 4) Accent.copy(alpha = 0.9f) else Color(0xCCFFFFFF),
                radius = if (idx == 4) 3.5f else 2.5f,
                center = Offset(x, barTop - 6f),
            )
        }

        // 测光点：三角标 + 竖线指向其在区域条上的位置
        points.forEach { p ->
            val zone = (5.0 + (p.stops ?: 0.0)).coerceIn(0.0, 10.0).toFloat()
            val x = w * zone / 10f
            val path = Path().apply {
                moveTo(x, barTop - 1f)
                lineTo(x - 5f, barTop - 9f)
                lineTo(x + 5f, barTop - 9f)
                close()
            }
            drawPath(path, Accent)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawZoneLabel(
    measurer: TextMeasurer,
    zone: Int,
    x: Float,
    y: Float,
    width: Float,
) {
    val label = when (zone) {
        0 -> "黑"
        5 -> "V"
        10 -> "白"
        else -> zone.toString()
    }
    val layout = measurer.measure(label, TextStyle(fontSize = 8.sp, color = Dim))
    val left = (x - layout.size.width / 2f).coerceIn(0f, width - layout.size.width)
    drawText(layout, topLeft = Offset(left, y))
}

@Composable
private fun ParamBar(
    iso: Int,
    aperture: Double,
    shutter: Double,
    locked: Set<Param>,
    zoneMode: Boolean,
    onToggleZone: () -> Unit,
    onClearPoints: () -> Unit,
    onToggle: (Param) -> Unit,
    onStepIso: (Int) -> Unit,
    onStepAperture: (Int) -> Unit,
    onStepShutter: (Int) -> Unit,
    onPickFilm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ParamPill("EI", iso.toString(), Param.ISO in locked, { onToggle(Param.ISO) }, onStepIso, Modifier.weight(1f))
        ParamPill("光圈", "f/" + fmtAperture(aperture), Param.APERTURE in locked, { onToggle(Param.APERTURE) }, onStepAperture, Modifier.weight(1f))
        ParamPill("快门", fmtShutter(shutter), Param.SHUTTER in locked, { onToggle(Param.SHUTTER) }, onStepShutter, Modifier.weight(1f))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.weight(1f).height(34.dp)
                .background(if (zoneMode) Color(0xFF2A2317) else Color(0xFF1A1C20), RoundedCornerShape(9.dp))
                .clickable { onToggleZone() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (zoneMode) "区域测光 · 点按画面加点" else "区域测光",
                color = if (zoneMode) Accent else Dim, fontSize = 12.sp,
            )
        }
        Box(
            Modifier.height(34.dp).width(64.dp)
                .background(Color(0xFF1A1C20), RoundedCornerShape(9.dp))
                .clickable { onClearPoints() },
            contentAlignment = Alignment.Center,
        ) { Text("清除", color = Dim, fontSize = 12.sp) }
        Box(
            Modifier.weight(1f).height(34.dp)
                .background(Color(0xFF1A1C20), RoundedCornerShape(9.dp))
                .clickable { onPickFilm() },
            contentAlignment = Alignment.Center,
        ) { Text("换胶片", color = Dim, fontSize = 12.sp) }
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
            .background(
                if (locked) Color(0xFF23262B) else Color(0xFF15171B),
                RoundedCornerShape(12.dp),
            )
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
private fun StatusLine(film: Film, iso: Int, solution: Solution?, ev: Double?) {
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
                "；互易律补偿后 ${fmtShutter(corrected)}" else ""
            val eiHint = if (iso != film.iso) "；EI $iso（推拉）" else ""
            base + recip + eiHint
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
