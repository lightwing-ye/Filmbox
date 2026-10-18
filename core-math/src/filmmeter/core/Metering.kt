package filmmeter.core

import kotlin.math.log2
import kotlin.math.pow

/** 反射式测光常数。ISO 2720 推荐 K=12.5；佳能机内取 14，故允许覆盖。 */
const val K_REFLECTED = 12.5

object Metering {

    /** EV100 = log2(N² / t)，t 单位秒。f/16 × 1/125 → ≈14.97 */
    fun ev100(aperture: Double, shutterSec: Double): Double =
        log2(aperture * aperture / shutterSec)

    /** EV_S = EV100 + log2(S / 100)。 */
    fun evAtIso(ev100: Double, iso: Double): Double = ev100 + log2(iso / 100.0)

    /** 亮度 L (cd/m²) → EV100：L = 2^EV · K / S。 */
    fun ev100FromLuminance(luminance: Double, k: Double = K_REFLECTED): Double =
        log2(luminance * 100.0 / k)

    /** EV100 → 亮度 L (cd/m²)。EV15 → 4096。 */
    fun luminanceFromEv(ev100: Double, k: Double = K_REFLECTED): Double =
        2.0.pow(ev100) * k / 100.0

    /**
     * 区域号：锚点区域 evAnchor 被放到 [anchorZone]（默认 V 区 = 18% 灰）。
     * 亮一档 = 高一区，故 z = anchorZone + (evRegion - evAnchor)。
     */
    fun zone(evRegion: Double, evAnchor: Double, anchorZone: Int = 5): Double =
        anchorZone + (evRegion - evAnchor)

    /**
     * 反差跨度（档）：区域最高最低之差。黑白负片常规显影可容纳约 7 档
     * （II 区到 VIII 区），超出部分按「每超 1 档 = N-1」建议缩短显影。
     */
    fun contrastSuggestion(spanStops: Double, normalSpan: Double = 7.0): String? {
        val over = spanStops - normalSpan
        return when {
            over >= 0.75 -> "N-${formatStops(over)}"
            over <= -0.75 -> "N+${formatStops(-over)}"
            else -> null
        }
    }

    private fun formatStops(x: Double): String {
        val thirds = Math.round(x * 3.0) / 3.0
        return if (thirds % 1.0 == 0.0) thirds.toInt().toString()
        else String.format("%.2f", thirds).trimEnd('0').trimEnd('.')
    }
}
