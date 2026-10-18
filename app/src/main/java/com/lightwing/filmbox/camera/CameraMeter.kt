package com.lightwing.filmbox.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import kotlin.math.log2

/** 一次测光读数。EV 由 AE 反推，区域分布来自同一帧的亮度分析。 */
data class MeterReading(
    val ev100: Double,
    val iso: Int,
    val shutterSec: Double,
    val aperture: Double,
    val converged: Boolean,
    /** 3×3 区域相对画面平均的档数（正 = 更亮），null 表示本帧未算。 */
    val zoneOffsets: List<Double>? = null,
)

/**
 * 用 Camera2 当测光表。
 *
 * 精度路线遵循规划 §2 的引擎 A：读 AE 收敛后的 (ISO, 曝光时间) 反推 EV100，
 * 再按 §2 的校准偏移修正。区域分布是相对值，因此不依赖标定即可用。
 */
class CameraMeter(
    private val context: Context,
    private val onReading: (MeterReading) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val thread = HandlerThread("camera-meter").apply { start() }
    private val handler = Handler(thread.looper)
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var cameraId: String = ""
    private var aperture = 1.8
    private var sensorWidth = 1
    private var sensorHeight = 1

    @Volatile private var lastEv: Double? = null
    @Volatile private var lastIso = 100
    @Volatile private var lastShutter = 1 / 60.0

    fun start(textureView: android.view.TextureView) {
        val st = textureView.surfaceTexture ?: run { onError("取景器未就绪"); return }
        // 选后置、物理尺寸最大的那颗（避免误用超广角/长焦）
        val (id, chars) = pickMainCamera()
        cameraId = id
        aperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.toDouble() ?: 1.8
        val size = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: Size(4000, 3000)
        sensorWidth = size.width
        sensorHeight = size.height

        val previewSize = pickPreviewSize(chars)
        st.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(st)
        openCamera(id)
    }

    private fun pickPreviewSize(chars: CameraCharacteristics): Size {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java) ?: emptyArray()
        // 4:3 优先，取不超过 1920 宽的最大档
        return sizes.filter { it.width <= 1920 && it.height * 4 == it.width * 3 }
            .maxByOrNull { it.width }
            ?: sizes.maxByOrNull { it.width } ?: Size(1280, 960)
    }

    private fun pickMainCamera(): Pair<String, CameraCharacteristics> {
        var best: Pair<String, CameraCharacteristics>? = null
        var bestArea = 0L
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val sz = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: continue
            val area = sz.width.toLong() * sz.height
            if (area > bestArea) {
                bestArea = area
                best = id to c
            }
        }
        return best ?: throw IllegalStateException("没有可用的后置相机")
    }

    private fun openCamera(id: String) {
        try {
            @Suppress("MissingPermission")
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    createSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onError("相机打开失败: $error")
                }
            }, handler)
        } catch (e: SecurityException) {
            onError("缺少相机权限")
        }
    }

    private fun createSession() {
        val cam = device ?: return
        val surface = previewSurface ?: return
        val yuvSize = Size(640, 480)
        reader?.close()
        reader = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 2).also { r ->
            r.setOnImageAvailableListener({ ir ->
                ir.acquireLatestImage()?.use { img -> analyseFrame(img) }
            }, handler)
        }

        val request = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surface)
            addTarget(reader!!.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }.build()

        cam.createCaptureSession(
            listOf(surface, reader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    s.setRepeatingRequest(request, resultCallback, handler)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    onError("相机会话配置失败")
                }
            },
            handler,
        )
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            if (exposureNs <= 0 || iso <= 0) return

            val t = exposureNs / 1e9
            // EV100 = log2(N² / t) − log2(ISO / 100)
            val ev = log2(aperture * aperture / t) - log2(iso / 100.0)
            lastEv = ev
            lastIso = iso
            lastShutter = t
            val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
            onReading(
                MeterReading(
                    ev100 = ev,
                    iso = iso,
                    shutterSec = t,
                    aperture = aperture,
                    converged = aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED,
                    zoneOffsets = lastZoneOffsets,
                ),
            )
        }
    }

    @Volatile private var lastZoneOffsets: List<Double>? = null

    /** 缩略亮度图：供 UI 任意取点（区域测光点用得上）。 */
    @Volatile private var lumaGrid: DoubleArray? = null
    @Volatile private var frameMean: Double = 0.0

    companion object {
        const val GRID_W = 48
        const val GRID_H = 36
    }

    /** 3×3 区域亮度 → 相对平均的档数。相对值不需要标定。 */
    private fun analyseFrame(img: android.media.Image) {
        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = img.width
        val h = img.height
        if (buf.remaining() < rowStride * h) return
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)

        val grid = DoubleArray(GRID_W * GRID_H)
        val counts = IntArray(GRID_W * GRID_H)
        var y = 0
        while (y < h) {
            val row = y * rowStride
            val gy = (y * GRID_H) / h
            var x = 0
            while (x < w) {
                val idx = row + x * pixelStride
                if (idx < bytes.size) {
                    val v = bytes[idx].toInt() and 0xFF
                    val gx = (x * GRID_W) / w
                    val cell = gy * GRID_W + gx
                    grid[cell] += Math.pow(v / 255.0, 2.2) // Y' → 近似线性光
                    counts[cell]++
                }
                x += 3
            }
            y += 3
        }
        var sum = 0.0
        var n = 0
        for (i in grid.indices) {
            if (counts[i] > 0) {
                grid[i] /= counts[i]
                sum += grid[i]
                n++
            }
        }
        if (n == 0) return
        frameMean = sum / n
        lumaGrid = grid
        lastZoneOffsets = zoneCells(grid)
    }

    /** 3×3 聚合，给区域条用。 */
    private fun zoneCells(grid: DoubleArray): List<Double> {
        val out = DoubleArray(9)
        val cnt = IntArray(9)
        for (gy in 0 until GRID_H) for (gx in 0 until GRID_W) {
            val c = (gy * 3 / GRID_H) * 3 + (gx * 3 / GRID_W)
            out[c] += grid[gy * GRID_W + gx]
            cnt[c]++
        }
        val mean = frameMean.takeIf { it > 0 } ?: return emptyList()
        return out.mapIndexed { i, v ->
            val m = if (cnt[i] > 0) v / cnt[i] else 0.0
            if (m <= 1e-6) -6.0 else (log2(m / mean)).coerceIn(-8.0, 8.0)
        }
    }

    /** 取画面归一化点的相对档数（正 = 比全画面均值亮）。 */
    fun sampleRelativeStops(nx: Float, ny: Float): Double? {
        val grid = lumaGrid ?: return null
        val mean = frameMean
        if (mean <= 1e-9) return null
        val cx = (nx * GRID_W).toInt().coerceIn(0, GRID_W - 1)
        val cy = (ny * GRID_H).toInt().coerceIn(0, GRID_H - 1)
        var sum = 0.0
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val x = cx + dx
            val y = cy + dy
            if (x in 0 until GRID_W && y in 0 until GRID_H) {
                sum += grid[y * GRID_W + x]
                n++
            }
        }
        if (n == 0) return null
        val v = sum / n
        return if (v <= 1e-6) -6.0 else log2(v / mean).coerceIn(-8.0, 8.0)
    }

    /** 点测：把 AE 区域收缩到画面某点，radius 为归一化半径。 */
    fun setSpot(cx: Float, cy: Float, radius: Float) {
        val s = session ?: return
        val r = android.graphics.Rect(
            ((cx - radius).coerceIn(0f, 1f) * sensorWidth).toInt(),
            ((cy - radius).coerceIn(0f, 1f) * sensorHeight).toInt(),
            ((cx + radius).coerceIn(0f, 1f) * sensorWidth).toInt(),
            ((cy + radius).coerceIn(0f, 1f) * sensorHeight).toInt(),
        )
        if (r.width() < 4 || r.height() < 4) return
        val request = device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            previewSurface?.let { addTarget(it) }
            reader?.let { addTarget(it.surface) }
            set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(
                android.hardware.camera2.params.MeteringRectangle(r, 1000),
            ))
        }.build()
        s.setRepeatingRequest(request, resultCallback, handler)
    }

    fun stop() {
        try {
            session?.close()
            device?.close()
            reader?.close()
        } catch (e: Exception) {
            Log.w("CameraMeter", "close failed", e)
        }
        session = null
        device = null
        reader = null
        thread.quitSafely()
    }
}
