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
        uniform shader content;
        uniform shader lut;
        uniform float lutSize;

        half4 main(float2 coord) {
            half4 src = content.eval(coord);
            float s = lutSize;
            float r = clamp(float(src.r), 0.0, 1.0) * (s - 1.0);
            float g = clamp(float(src.g), 0.0, 1.0) * (s - 1.0);
            float b = clamp(float(src.b), 0.0, 1.0) * (s - 1.0);
            float r0 = floor(r);
            float g0 = floor(g);
            float b0 = floor(b);
            float b1 = min(b0 + 1.0, s - 1.0);
            float x = r0 + 0.5;
            float y = g0 + 0.5;
            half4 c0 = lut.eval(float2(x, b0 * s + y));
            half4 c1 = lut.eval(float2(x, b1 * s + y));
            float t = b - b0;
            float3 rgb = mix(float3(c0.rgb), float3(c1.rgb), t);
            return half4(half3(rgb), src.a);
        }
    """

    private val cache = ConcurrentHashMap<String, RenderEffect>()

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** asset 形如 "luts/kodak_portra400.cube"；null 表示清除滤镜。 */
    /** 最近一次失败原因，UI 用来提示；成功时为 null。 */
    @Volatile var lastError: String? = null
        private set

    fun effectFor(context: Context, asset: String?): RenderEffect? {
        if (!supported || asset == null) return null
        cache[asset]?.let { return it }
        return runCatching { build(context, asset) }
            .onSuccess { lastError = null }
            .onFailure {
                lastError = it.message ?: it::class.java.simpleName
                android.util.Log.e("LutEffect", "build $asset failed", it)
            }
            .getOrNull()
            ?.also { cache[asset] = it }
    }

    private fun build(context: Context, asset: String): RenderEffect {
        val (size, bitmap) = loadCube(context, asset)
        val shader = RuntimeShader(AGSL)
        shader.setInputShader("lut", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        shader.setFloatUniform("lutSize", size.toFloat())
        return RenderEffect.createRuntimeShaderEffect(shader, "content")
    }

    fun apply(view: TextureView, effect: RenderEffect?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.setRenderEffect(effect)
        }
    }

    /** 解析二进制 LUT（FLUT: magic + size + N³×3 bytes，红变化最快）。 */
    private fun loadCube(context: Context, asset: String): Pair<Int, Bitmap> {
        val bytes = context.assets.open(asset).use { it.readBytes() }
        require(bytes.size >= 8 && String(bytes, 0, 4, Charsets.US_ASCII) == "FLUT") { "LUT 头无效: $asset" }
        val size = bytes[4].toInt() and 0xFF
        require(bytes.size == 8 + size * size * size * 3) { "LUT 长度不符: $asset" }

        val bmp = Bitmap.createBitmap(size, size * size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(size * size * size)
        var o = 8
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            val rr = bytes[o].toInt() and 0xFF
            val gg = bytes[o + 1].toInt() and 0xFF
            val bb = bytes[o + 2].toInt() and 0xFF
            o += 3
            pixels[b * size * size + g * size + r] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size * size)
        return size to bmp
    }

    /** 胶片 id → 内置 LUT（没有对应 LUT 的卷返回 null）。 */
    fun assetFor(filmId: String): String? =
        if (filmId in LUT_IDS) "luts/$filmId.lut" else null

    private val LUT_IDS = setOf(
        "ilford_hp5", "ilford_fp4", "ilford_delta100", "ilford_delta400", "ilford_delta3200",
        "ilford_panf", "ilford_xp2",
        "kodak_trix", "kodak_tmax100", "kodak_tmax400", "kodak_ektar100",
        "kodak_portra160", "kodak_portra400", "kodak_gold200", "kodak_ultramax400",
        "kodak_doublex_5222", "kodak_ektachrome_100d",
        "cinestill_50d", "cinestill_400d", "cinestill_800t",
        "fuji_acros2", "fuji_superia400", "fuji_pro400h",
    )
}
