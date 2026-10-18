package com.lightwing.filmbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import android.view.SurfaceView
import com.lightwing.filmbox.camera.CameraMeter
import com.lightwing.filmbox.camera.MeterReading
import filmmeter.core.DataTier
import filmmeter.core.ExposurePair
import filmmeter.core.ExposureSolver
import filmmeter.core.Film
import filmmeter.core.FilmCatalog
import filmmeter.core.Metering

private val Bg = Color(0xFF0B0C0E)
private val Ink = Color(0xFFF2F3F5)
private val Dim = Color(0xFF8A8F98)
private val Accent = Color(0xFFE8B44A)
private val Warn = Color(0xFFE0574B)

@Composable
fun MeterScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var reading by remember { mutableStateOf<MeterReading?>(null) }
    val meter = remember { CameraMeter(context, onReading = { reading = it }, onError = {}) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { meter.stop() } }
    var film by remember { mutableStateOf(FilmCatalog.byId("kodak_portra400") ?: FilmCatalog.films.first()) }
    var showFilmPicker by remember { mutableStateOf(false) }
    var lockedShutter by remember { mutableStateOf<Double?>(null) }

    val ev100 = reading?.ev100
    val filmEv = ev100?.let { Metering.evAtIso(it, film.iso.toDouble()) }

    // 推荐组合：按胶片 ISO 换算后套互易律，超出机身上限则提示
    val recommended: ExposurePair? = filmEv?.let { ExposureSolver.best(it) }
    val corrected: Double? = recommended?.let { p ->
        val c = film.reciprocity.correct(p.shutterSec)
        if (c > ExposureSolver.SHUTTERS.last()) null else c
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopBar(film, ev100, reading?.converged == true)
        Box(Modifier.weight(1f)) {
            CameraView(meter)
            ZoneBar(reading?.zoneOffsets, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
        }
        ReadoutBlock(
            film = film,
            ev = ev100,
            recommended = recommended,
            reciprocitySec = corrected,
            onPickFilm = { showFilmPicker = true },
        )
    }

    if (showFilmPicker) {
        FilmPicker(current = film, onDismiss = { showFilmPicker = false }, onPick = {
            film = it; showFilmPicker = false
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
                    override fun surfaceCreated(h: android.view.SurfaceHolder) {
                        meter.start(h)
                    }
                    override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, ht: Int) = Unit
                    override fun surfaceDestroyed(h: android.view.SurfaceHolder) = Unit
                })
            }
        },
    )
}

/** 区域条：0–X 区标尺，标出 3×3 区域的相对位置。 */
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
            // 以 V 区为锚，1 档 = 1 区
            val zone = (5f + off).coerceIn(0f, 10f)
            val x = w * zone / 10f
            drawCircle(if (idx == 4) Accent else Color(0xCCFFFFFF), radius = 3.5f, center = Offset(x, h - 12f))
        }
    }
}

@Composable
private fun ReadoutBlock(
    film: Film,
    ev: Double?,
    recommended: ExposurePair?,
    reciprocitySec: Double?,
    onPickFilm: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(
                    recommended?.let { "f/${fmtAperture(it.aperture)}" } ?: "—",
                    color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Light,
                )
                Text("推荐光圈", color = Dim, fontSize = 10.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    recommended?.let { fmtShutter(it.shutterSec) } ?: "—",
                    color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Light,
                )
                Text("推荐快门", color = Dim, fontSize = 10.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    reciprocitySec?.let { fmtShutter(it) } ?: "—",
                    color = if (reciprocitySec != null) Accent else Warn,
                    fontSize = 20.sp, fontWeight = FontWeight.Medium,
                )
                Text("补偿后", color = Dim, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                ev == null -> "对准场景，等待 AE 收敛…"
                reciprocitySec == null -> "当前胶片需要的时间超出机身上限，请开大光圈或加 ND 减光"
                recommended?.errorStops?.let { Math.abs(it) > 1.0 / 6.0 } == true ->
                    "取档后偏差 ${String.format("%.2f", recommended!!.errorStops)} 档"
                else -> "曝光组合可用"
            },
            color = Dim, fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("换胶片", onPickFilm, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.height(44.dp).background(Color(0xFF1A1C20), androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.TextButton(onClick = onClick) {
            Text(text, color = Ink, fontSize = 14.sp)
        }
    }
}

private fun fmtAperture(a: Double): String =
    if (a % 1.0 == 0.0) a.toInt().toString() else String.format("%.1f", a)

fun fmtShutter(t: Double): String = when {
    t >= 1.0 -> String.format("%.1fs", t)
    else -> "1/${Math.round(1.0 / t)}"
}
