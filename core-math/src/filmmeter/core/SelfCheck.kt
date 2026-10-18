package filmmeter.core

import kotlin.math.abs

/**
 * 无框架自检：见 core-math/run.sh（kotlinc 编译后 java -jar 直接跑）。
 * 只覆盖非平凡逻辑（EV↔亮度、区域换算、互易律插值、组合求解）。
 */
private var failures = 0

private fun check(name: String, expected: Double, actual: Double, tol: Double = 1e-3) {
    val ok = abs(expected - actual) <= tol
    if (!ok) failures++
    println("${if (ok) "PASS" else "FAIL"}  $name   expected=${fmt(expected)} actual=${fmt(actual)}")
}

private fun fmt(x: Double) = String.format("%.4f", x)

fun main() {
    // 1. 阳光十六法则：f/16 + 1/125s ≈ EV 15
    check("EV100(16, 1/125)", 14.9658, Metering.ev100(16.0, 1 / 125.0))
    // 2. EV 与亮度互为反函数
    check("L(EV15)", 4096.0, Metering.luminanceFromEv(15.0))
    check("EV(L=4096)", 15.0, Metering.ev100FromLuminance(4096.0))
    check("round-trip EV 8.3", 8.3, Metering.ev100FromLuminance(Metering.luminanceFromEv(8.3)))
    // 3. ISO 换算：ISO400 比 ISO100 少 2 档
    check("EV@ISO400 (EV100=12)", 14.0, Metering.evAtIso(12.0, 400.0))
    // 4. 区域：锚点 V 区，亮 2 档 → VII 区
    check("zone(+2EV)", 7.0, Metering.zone(evRegion = 12.0, evAnchor = 10.0))
    check("zone(-2EV)", 3.0, Metering.zone(evRegion = 8.0, evAnchor = 10.0))

    // 5. 幂律互易律：8^1.31
    check("Power p=1.31 t=8", 15.2422, Reciprocity.Power(1.31).correct(8.0), tol = 1e-3)
    check("Power 低于阈值不补偿", 0.5, Reciprocity.Power(1.31).correct(0.5))
    check("stopsLost(8s)", 0.9299, Reciprocity.Power(1.31).stopsLost(8.0), tol = 1e-3)

    // 6. 查表插值：表内点必须落在两点之间，表尾外推必须单调增长
    val table = Reciprocity.Table(listOf(1.0 to 1.0, 2.0 to 2.5, 4.0 to 6.0, 8.0 to 20.0, 15.0 to 60.0))
    val t3 = table.correct(3.0)
    val inRange = t3 in 2.5..6.0
    if (!inRange) failures++
    println("${if (inRange) "PASS" else "FAIL"}  Table(3s) 落在 2.5–6s：${fmt(t3)}")
    check("Table(1s) 阈值点不补偿", 1.0, table.correct(1.0))
    check("Table(0.5s) 短于表头不补偿", 0.5, table.correct(0.5))
    val t60 = table.correct(60.0)
    val monotonic = t60 > table.correct(30.0)
    if (!monotonic) failures++
    println("${if (monotonic) "PASS" else "FAIL"}  Table 外推单调：60s→${fmt(t60)}")

    // 7. 组合求解：EV12、f/8 → 1/64s（表内无 1/64，应给出相邻档并报告误差）
    val pairs = ExposureSolver.pairs(12.0)
    val f8 = pairs.first { it.aperture == 8.0 }
    check("EV12 f/8 indicated", 1 / 64.0, f8.indicatedSec, tol = 1e-9)
    val snapOk = abs(f8.errorStops) <= 1.0 / 6.0 + 1e-9
    if (!snapOk) failures++
    println("${if (snapOk) "PASS" else "FAIL"}  EV12 f/8 取档误差 ${"%.3f".format(f8.errorStops)} 档 ≤1/6")

    // 8. 组合覆盖：EV15 至少给出 10 组，且全部误差 ≤ 0.5 档
    val bright = ExposureSolver.pairs(15.0)
    val okCount = bright.size >= 10 && bright.all { abs(it.errorStops) <= 0.5 }
    if (!okCount) failures++
    println("${if (okCount) "PASS" else "FAIL"}  EV15 组合数=${bright.size}")

    val duskPairs = ExposureSolver.pairs(6.0)
    // 9. 长曝光：EV2 在任何光圈下都超出补偿后机身 30s 上限 → 必须报"拍不了"
    val night = ExposureSolver.best(2.0)!!
    val tooLong = ExposureSolver.withReciprocity(night, table) == null
    if (!tooLong) failures++
    println("${if (tooLong) "PASS" else "FAIL"}  EV2 f/${night.aperture} ${fmt(night.shutterSec)}s → 补偿 ${fmt(table.correct(night.shutterSec))}s 超 30s 上限")
    // 9b. 阈值内不补偿：EV6 f/8 = 1s，表头即 1s → 原样
    val dusk = duskPairs.first { it.aperture == 8.0 }
    check("EV6 f/8 1s 不补偿", 1.0, ExposureSolver.withReciprocity(dusk, table)!!)

    // 10. N± 判定
    check("span 9 stops → N-2 判定", 1.0, if (Metering.contrastSuggestion(9.0) == "N-2") 1.0 else 0.0)
    val normal = Metering.contrastSuggestion(6.5) == null
    if (!normal) failures++
    println("${if (normal) "PASS" else "FAIL"}  span 6.5 → 不提示")

    // 11. 胶片库完整性：id 唯一、条目数达标、来源可追溯
    val ids = FilmCatalog.films.map { it.id }
    val libOk = ids.size == ids.toSet().size && ids.size >= 25 && FilmCatalog.films.all { it.source.isNotBlank() && it.iso > 0 }
    if (!libOk) failures++
    println("${if (libOk) "PASS" else "FAIL"}  胶片库 ${ids.size} 支，id 唯一且来源非空")
    val approxOk = FilmCatalog.films.filter { it.tier == DataTier.APPROX }
        .all { it.reciprocity is Reciprocity.Power && it.reciprocity.p == 1.30 }
    if (!approxOk) failures++
    println("${if (approxOk) "PASS" else "FAIL"}  APPROX 条目统一走 p=1.30 兜底")

    // 12. 真实链路：HP5+ 8s → 15.24s；Acros 长曝不补偿
    check("HP5+ 8s", 15.2422, FilmCatalog.byId("ilford_hp5")!!.reciprocity.correct(8.0))
    check("Acros 100s 不补偿", 100.0, FilmCatalog.byId("fuji_acros2")!!.reciprocity.correct(100.0))

    println("\n${if (failures == 0) "ALL PASS" else "$failures FAILURE(S)"}")
    if (failures != 0) kotlin.system.exitProcess(1)
}
