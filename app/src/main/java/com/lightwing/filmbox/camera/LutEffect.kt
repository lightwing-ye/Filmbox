package com.lightwing.filmbox.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Build
import android.view.TextureView
import java.util.concurrent.ConcurrentHashMap

/**
 * 胶片 LUT 实时预览。
 *
 * .cube（G'MIC Film LUTs, MIT）→ 打包成 2D 纹理（x = R，y = B*N + G）
 * → RuntimeShader 三分线性采样 → 直接作用在 TextureView 的图层上。
 * 需要 API 33+；低版本自动降级为不过滤。
 */
object LutEffect {

    private const val AGSL = """
        uniform shader input;
        uniform shader lut;
        uniform float lutSize;

        half4 main(float2 coord) {
            half4 src = input.eval(coord);
            float s = lutSize;
            float3 f = clamp(float3(src.rgb), 0.0, 1.0) * (s - 1.0);
            float3 i0 = floor(f);
            float b1 = min(i0.z + 1.0, s - 1.0);
            float fx = f.x + 0.5;
            float fy = f.y + 0.5;
            half4 c0 = lut.eval(float2(fx, i0.z * s + fy));
            half4 c1 = lut.eval(float2(fx, b1 * s + fy));
            half3 rgb = mix(c0.rgb, c1.rgb, half(f.z - i0.z));
            return half4(rgb, src.a);
        }
    """

    private val cache = ConcurrentHashMap<String, RenderEffect>()

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** asset 形如 "luts/kodak_portra400.cube"；null 表示清除滤镜。 */
    fun effectFor(context: Context, asset: String?): RenderEffect? {
        if (!supported || asset == null) return null
        cache[asset]?.let { return it }
        return runCatching { build(context, asset) }.getOrNull()?.also { cache[asset] = it }
    }

    private fun build(context: Context, asset: String): RenderEffect {
        val (size, bitmap) = loadCube(context, asset)
        val shader = RuntimeShader(AGSL)
        shader.setInputShader("lut", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("lutSize", size.toFloat())
        return RenderEffect.createRuntimeShaderEffect(shader, "input")
    }

    fun apply(view: TextureView, effect: RenderEffect?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(effect)
        }
    }

    /** 解析 .cube，返回 (N, 打包位图)。 */
    private fun loadCube(context: Context, asset: String): Pair<Int, Bitmap> {
        val lines = context.assets.open(asset).bufferedReader().use { it.readLines() }
        var size = 0
        val values = ArrayList<Float>(33 * 33 * 33 * 3)
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("LUT_3D_SIZE")) {
                size = line.substringAfter("LUT_3D_SIZE").trim().toInt()
                continue
            }
            if (line[0].isLetter()) continue // TITLE / DOMAIN_MIN 等
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 3) {
                values += parts[0].toFloatOrNull() ?: continue
                values += parts[1].toFloatOrNull() ?: continue
                values += parts[2].toFloatOrNull() ?: continue
            }
        }
        require(size > 0) { "LUT 缺少 LUT_3D_SIZE: $asset" }
        require(values.size == size * size * size * 3) { "LUT 数据长度不符: $asset" }

        val bmp = Bitmap.createBitmap(size, size * size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size * size)
        // .cube 顺序：红变化最快
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val idx = ((b * size + g) * size + r) * 3
            val rr = (values[idx] * 255f).toInt().coerceIn(0, 255)
            val gg = (values[idx + 1] * 255f).toInt().coerceIn(0, 255)
            val bb = (values[idx + 2] * 255f).toInt().coerceIn(0, 255)
            pixels[b * size * size + g * size + r] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size * size)
        return size to bmp
    }

    /** 胶片 id → 内置 LUT（没有对应 LUT 的卷返回 null）。 */
    fun assetFor(filmId: String): String? =
        if (filmId in LUT_IDS) "luts/$filmId.cube" else null

    private val LUT_IDS = setOf(
        "ilford_hp5", "ilford_fp4", "ilford_delta100", "ilford_delta400", "ilford_delta3200",
        "ilford_panf", "ilford_xp2", "kodak_trix", "kodak_ektar100",
        "kodak_portra160", "kodak_portra400", "kodak_portra800",
        "fuji_acros2", "fuji_superia400",
    )
}
