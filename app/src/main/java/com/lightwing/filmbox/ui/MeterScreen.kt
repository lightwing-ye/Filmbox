package com.lightwing.filmbox.ui

import android.view.HapticFeedbackConstants
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.abs

private val Bg = Color(0xFF0B0C0E)
private val Ink = Color(0xFFF2F3F5)
private val Dim = Color(0xFF8A8F98)
private val Accent = Color(0xFFE8B44A)

/** 1/3 档 EI 级数，推拉冲用得上。 */
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
    // 胶片感光度默认锁住；光圈快门自由，由测光值自动配对
    var locked by remember { mutableStateOf(setOf(Param.ISO)) }

    val ev = reading?.ev100
    val solution: Solution? = ev?.let {
        MeterSolver.solve(it, iso, locked, aperture, shutter, film)
    }

    // 未锁的参数跟随测光结果
    LaunchedEffect(solution) {
        solution?.let { s ->
            if (Param.APERTURE !in locked) aperture = s.aperture
            if (Param.SHUTTER !in locked) shutter = s.shutterSec
        }
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(film, ev, reading?.converged == true)
        Box(Modifier.weight(1f)) {
            CameraView(meter)
            ZoneBar(reading?.zoneOffsets, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        ParamBar(
            iso = iso,
            aperture = aperture,
            shutter = shutter,
            locked = locked,
            onToggle = { p -> locked = if (p in locked) locked - p else locked + p },
            onStepIso = { dir ->
                iso = stepIso(iso, dir); locked = locked + Param.ISO
            },
            onStepAperture = { dir ->
                aperture = stepOf(aperture, ExposureSolver.APERTURES, dir); locked = locked + Param.APERTURE
            },
            onStepShutter = { dir ->
                shutter = stepOf(shutter, ExposureSolver.SHUTTERS, dir); locked = locked + Param.SHUTTER
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

/** 区域条：0–X 标尺横贴取景器下缘，标出 3×3 区域的相对位置。 */
@Composable
private fun ZoneBar(offsets: List<Double>?, modifier: Modifier = Modifier) {
    Canvas(modifier.height(44.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val w = size.width
        val h = size.height
        drawLine(Color(0x66FFFFFF), Offset(0f, h), Offset(w, h), strokeWidth = 1f)
        for (i in 0..10) {
            val x = w * i / 10f
            drawLine(Color(0x44FFFFFF), Offset(x, h - 6f), Offset(x, h), strokeWidth = 1f)
        }
        offsets?.forEachIndexed { idx, off ->
            val zone = (5.0 + off).coerceIn(0.0, 10.0).toFloat()
            val x = w * zone / 10f
            drawCircle(
                if (idx == 4) Accent else Color(0xCCFFFFFF),
                radius = 3.5f, center = Offset(x, h - 12f),
            )
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
    onPickFilm: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ParamPill(
            caption = "EI",
            value = iso.toString(),
            locked = Param.ISO in locked,
            onToggle = { onToggle(Param.ISO) },
            onStep = onStepIso,
            modifier = Modifier.weight(1f),
        )
        ParamPill(
            caption = "光圈",
            value = "f/" + fmtAperture(aperture),
            locked = Param.APERTURE in locked,
            onToggle = { onToggle(Param.APERTURE) },
            onStep = onStepAperture,
            modifier = Modifier.weight(1f),
        )
        ParamPill(
            caption = "快门",
            value = fmtShutter(shutter),
            locked = Param.SHUTTER in locked,
            onToggle = { onToggle(Param.SHUTTER) },
            onStep = onStepShutter,
            modifier = Modifier.weight(1f),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Box(
            Modifier.weight(1f).height(34.dp)
                .background(Color(0xFF1A1C20), RoundedCornerShape(9.dp))
                .clickable { onPickFilm() },
            contentAlignment = Alignment.Center,
        ) { Text("换胶片", color = Dim, fontSize = 12.sp) }
    }
}

/**
 * 参数胶囊：上滑/下滑按 1/3 档步进，点按切换锁定。
 * 手动改值即视为「我要这个值」，自动锁定该参数。
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
    var acc by remember { mutableStateOf(0f) }
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
                        onStep(if (acc < 0) 1 else -1) // 上滑 = 更长/更大
                        acc += if (acc < 0) stepPx else -stepPx
                        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
