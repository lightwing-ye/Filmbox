package filmmeter.core

import kotlin.math.log2
import kotlin.math.abs
import kotlin.math.pow

/**
 * 一组可实际设定的曝光参数。快门取自标准 1/3 档级数，
 * [errorStops] 表示取档后相对理想曝光的偏差（正数 = 少曝）。
 */
data class ExposurePair(
    val aperture: Double,
    val shutterSec: Double,
    val indicatedSec: Double,
    val errorStops: Double,
)

object ExposureSolver {

    /** f/1.4 – f/22，1/3 档级数（每档 f 系数 ×2^(1/6)）。 */
    val APERTURES = doubleArrayOf(
        1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6,
        6.3, 7.1, 8.0, 9.0, 10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0,
    )

    /** 1/8000 – 30s，1/3 档级数。 */
    val SHUTTERS = doubleArrayOf(
        1 / 8000.0, 1 / 6400.0, 1 / 5000.0, 1 / 4000.0, 1 / 3200.0, 1 / 2500.0,
        1 / 2000.0, 1 / 1600.0, 1 / 1250.0, 1 / 1000.0, 1 / 800.0, 1 / 640.0,
        1 / 500.0, 1 / 400.0, 1 / 320.0, 1 / 250.0, 1 / 200.0, 1 / 160.0,
        1 / 125.0, 1 / 100.0, 1 / 80.0, 1 / 60.0, 1 / 50.0, 1 / 40.0,
        1 / 30.0, 1 / 25.0, 1 / 20.0, 1 / 15.0, 1 / 13.0, 1 / 10.0,
        1 / 8.0, 1 / 6.0, 1 / 5.0, 1 / 4.0, 1 / 3.0, 0.4, 0.5, 0.6, 0.8,
        1.0, 1.3, 1.6, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 13.0,
        15.0, 20.0, 25.0, 30.0,
    )

    /**
     * 由 EV 反推全部可设定组合。t = N² / 2^EV。
     * 光圈表可替换（手机镜头固定光圈时传单个值即可）。
     */
    fun pairs(
        ev: Double,
        apertures: DoubleArray = APERTURES,
        shutters: DoubleArray = SHUTTERS,
    ): List<ExposurePair> {
        val e = 2.0.pow(ev)
        val out = ArrayList<ExposurePair>(apertures.size)
        for (n in apertures) {
            val t = n * n / e
            if (t < shutters.first() || t > shutters.last()) continue
            val snapped = shutters.minBy { abs(log2(it / t)) }
            out += ExposurePair(n, snapped, t, log2(t / snapped))
        }
        return out
    }

    /** 在可选组合里挑最接近理想曝光的一组（|误差| 最小，同误差取小光圈）。 */
    fun best(ev: Double, apertures: DoubleArray = APERTURES, shutters: DoubleArray = SHUTTERS): ExposurePair? =
        pairs(ev, apertures, shutters).minWithOrNull(compareBy({ abs(it.errorStops) }, { -it.aperture }))

    /**
     * 给一组组合套上互易律补偿：返回实际需要的时间。
     * 若补偿后超出快门表，返回 null 表示这台机器拍不了（需要换光圈或加 ND）。
     */
    fun withReciprocity(pair: ExposurePair, film: Reciprocity): Double? {
        val corrected = film.correct(pair.shutterSec)
        return if (corrected > SHUTTERS.last() * 1.0001) null else corrected
    }
}
