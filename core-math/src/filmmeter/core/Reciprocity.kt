package filmmeter.core

import kotlin.math.ln
import kotlin.math.pow

/**
 * 互易律失效补偿：把「测光表指示时间」映射成「胶片实际需要的时间」。
 *
 * 注意：补偿只作用一次。不能把结果再喂回 [correct] 迭代——查表本来就是
 * 指示时间 → 实际时间的点对点映射，反复套用会把 60s 算成几小时。
 * 若用户锁定光圈、快门被补偿拉长，正确做法是重算另一参数或显示等效 EV 降低量。
 */
sealed interface Reciprocity {

    fun correct(indicatedSec: Double): Double

    /** 补偿带来的等效 EV 损失（正数 = 少收光）。 */
    fun stopsLost(indicatedSec: Double): Double {
        val c = correct(indicatedSec)
        return if (c <= indicatedSec) 0.0 else kotlin.math.log2(c / indicatedSec)
    }

    data object None : Reciprocity {
        override fun correct(indicatedSec: Double): Double = indicatedSec
    }

    /** 幂律 t_corr = t^p。仅当 t ≥ thresholdSec 才触发。 */
    data class Power(val p: Double, val thresholdSec: Double = 1.0) : Reciprocity {
        override fun correct(indicatedSec: Double): Double =
            if (indicatedSec < thresholdSec) indicatedSec else indicatedSec.pow(p)
    }

    /**
     * 厂商查表（如 Ilford / Kodak datasheet）。
     * 点在 log-log 空间线性插值；超出表尾按最后两点斜率外推。
     */
    data class Table(val points: List<Pair<Double, Double>>) : Reciprocity {
        init {
            require(points.size >= 2) { "查表至少需要两个点" }
            require(points.zipWithNext().all { (a, b) -> b.first > a.first }) { "查表必须按指示时间升序" }
            require(points.all { it.second >= it.first }) { "补偿时间不得短于指示时间" }
        }

        override fun correct(indicatedSec: Double): Double {
            if (indicatedSec <= points.first().first) return indicatedSec
            val x = ln(indicatedSec)
            for (i in 0 until points.size - 1) {
                val (x0, y0) = points[i]
                val (x1, y1) = points[i + 1]
                if (indicatedSec <= x1) {
                    val t = (x - ln(x0)) / (ln(x1) - ln(x0))
                    return kotlin.math.exp(ln(y0) + t * (ln(y1) - ln(y0)))
                }
            }
            val (x0, y0) = points[points.size - 2]
            val (x1, y1) = points[points.size - 1]
            val slope = (ln(y1) - ln(y0)) / (ln(x1) - ln(x0))
            return kotlin.math.exp(ln(y1) + slope * (x - ln(x1)))
        }
    }
}
