package filmmeter.core

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** 可被用户单独锁定的参数。 */
enum class Param { ISO, APERTURE, SHUTTER }

data class Solution(
    val iso: Int,
    val aperture: Double,
    val shutterSec: Double,
    /** 实际组合相对正确曝光的偏差（正 = 过曝）。 */
    val errorStops: Double,
    /** 套互易律后实际需要的时间；null = 超出机身上限。 */
    val reciprocitySec: Double? = null,
)

/**
 * 按测光 EV 求解曝光参数。
 *
 * 规则（D8）：
 *  - 锁光圈 → 解快门；锁快门 → 解光圈；
 *  - 两个都锁 → 不动参数，只报偏差；此时若 ISO 未锁，反算该用多少 EI 才能曝光正确；
 *  - 都不锁 → 在全部组合里取误差最小，同误差取靠近 1/125 的快门（手持安全）。
 */
object MeterSolver {

    private val PROGRAM_SHUTTER = 1 / 125.0

    /** 程序线的偏好光圈：胶片机最常见的工作光圈。 */
    private val PROGRAM_APERTURE = 8.0

    fun solve(
        ev100: Double,
        iso: Int,
        locked: Set<Param>,
        aperture: Double,
        shutterSec: Double,
        film: Film? = null,
    ): Solution {
        val lockA = Param.APERTURE in locked
        val lockT = Param.SHUTTER in locked
        val lockIso = Param.ISO in locked

        val evFilm = Metering.evAtIso(ev100, iso.toDouble())
        val e = 2.0.pow(evFilm)

        var outIso = iso
        var outA = aperture
        var outT = shutterSec

        when {
            lockA && lockT && !lockIso -> {
                // 机身参数不动，反算 EI：EV_setting = log2(N²/t)
                val evSetting = log2(aperture * aperture / shutterSec)
                val ei = 100.0 * 2.0.pow(evSetting - ev100)
                outIso = ei.roundToInt().coerceIn(25, 6400)
            }
            lockA && lockT -> {
                // 全部锁定：什么都不改
            }
            lockA -> {
                val ideal = aperture * aperture / e
                outT = snap(ideal, ExposureSolver.SHUTTERS) ?: ideal
            }
            lockT -> {
                val idealA = kotlin.math.sqrt(e * shutterSec)
                outA = snap(idealA, ExposureSolver.APERTURES) ?: idealA
            }
            else -> {
                val best = pickProgram(evFilm)
                outA = best.aperture
                outT = best.shutterSec
            }
        }

        // 用最终参数复算误差
        val achievedEv = log2(outA * outA / outT)
        val error = achievedEv - Metering.evAtIso(ev100, outIso.toDouble())

        val reciprocity = film?.let {
            val c = it.reciprocity.correct(outT)
            if (c > ExposureSolver.SHUTTERS.last()) null else snap(c, ExposureSolver.SHUTTERS)
        }

        return Solution(outIso, outA, outT, error, reciprocity)
    }

    private fun pickProgram(evFilm: Double): ExposurePair {
        val pairs = ExposureSolver.pairs(evFilm)
        if (pairs.isEmpty()) {
            // 极端亮度：贴着可用范围端点给一组
            val t = (1.0 / 2.0.pow(evFilm) * 64).coerceIn(
                ExposureSolver.SHUTTERS.first(), ExposureSolver.SHUTTERS.last(),
            )
            return ExposurePair(8.0, t, t, 0.0)
        }
        // 先保证曝光准确（1/6 档内），再取接近 f/8 + 1/125 的「顺手」组合
        val accurate = pairs.filter { abs(it.errorStops) <= 1.0 / 6.0 }
        val pool = accurate.ifEmpty { pairs }
        return pool.minWith(
            compareBy(
                { abs(log2(it.aperture / PROGRAM_APERTURE)) },
                { abs(log2(it.shutterSec / PROGRAM_SHUTTER)) },
            ),
        )
    }

    private fun snap(value: Double, series: DoubleArray): Double? =
        series.minByOrNull { abs(log2(it / value)) }
}
