package filmmeter.core

enum class FilmType { COLOR_NEG, BW_NEG, SLIDE }

/** 数据可信度。UI 必须区分显示，不拿估算冒充实测。 */
enum class DataTier { MANUFACTURER, COMMUNITY, APPROX }

data class Film(
    val id: String,
    val brand: String,
    val name: String,
    val iso: Int,
    val type: FilmType,
    val reciprocity: Reciprocity,
    val source: String,
    val tier: DataTier,
    val note: String = "",
) {
    val label get() = "$brand $name"
}

/**
 * 首发胶片库（D5：主流 30–40 支，不做 App 内编辑）。
 *
 * 录入规则：
 *  - MANUFACTURER：数值直接来自厂商 datasheet / 官方 FAQ；
 *  - COMMUNITY：有公开公式或表格但非官方发布，已注明来源；
 *  - APPROX：查无官方数据，用 Schwarzschild 幂律 p=1.3 兜底，UI 必须提示。
 */
object FilmCatalog {

    private const val ILFORD_RECIPROCITY =
        "Ilford 互易律补偿表拟合（Schwarzschild 指数）"
    private const val RETRO_PIXEL = "retro-pixel.com 胶片互易律表整理"
    private const val CINESTILL_FAQ =
        "CineStill 官方 FAQ: reciprocity failure details"

    val films: List<Film> = listOf(
        // ── Ilford ──────────────────────────────────────────────
        Film("ilford_hp5", "Ilford", "HP5 Plus", 400, FilmType.BW_NEG,
            Reciprocity.Power(1.31), ILFORD_RECIPROCITY, DataTier.MANUFACTURER),
        Film("ilford_fp4", "Ilford", "FP4 Plus", 125, FilmType.BW_NEG,
            Reciprocity.Power(1.26), ILFORD_RECIPROCITY, DataTier.MANUFACTURER),
        Film("ilford_delta100", "Ilford", "Delta 100", 100, FilmType.BW_NEG,
            Reciprocity.Power(1.26), ILFORD_RECIPROCITY, DataTier.MANUFACTURER),
        Film("ilford_delta400", "Ilford", "Delta 400", 400, FilmType.BW_NEG,
            Reciprocity.Power(1.41), ILFORD_RECIPROCITY, DataTier.MANUFACTURER),
        Film("ilford_delta3200", "Ilford", "Delta 3200", 3200, FilmType.BW_NEG,
            Reciprocity.Power(1.33), ILFORD_RECIPROCITY, DataTier.MANUFACTURER),
        Film("ilford_panf", "Ilford", "Pan F Plus", 50, FilmType.BW_NEG,
            Reciprocity.Power(1.33), ILFORD_RECIPROCITY, DataTier.MANUFACTURER),
        Film("ilford_xp2", "Ilford", "XP2 Super", 400, FilmType.BW_NEG,
            Reciprocity.Power(1.31), ILFORD_RECIPROCITY, DataTier.MANUFACTURER,
            "C-41 染料型黑白"),
        Film("ilford_sfx", "Ilford", "SFX 200", 200, FilmType.BW_NEG,
            Reciprocity.Power(1.43), ILFORD_RECIPROCITY, DataTier.MANUFACTURER,
            "红外敏感，需配滤镜"),
        Film("kentmere_100", "Kentmere", "Pan 100", 100, FilmType.BW_NEG,
            Reciprocity.Power(1.26), ILFORD_RECIPROCITY, DataTier.MANUFACTURER,
            "Ilford 旗下"),
        Film("kentmere_400", "Kentmere", "Pan 400", 400, FilmType.BW_NEG,
            Reciprocity.Power(1.30), ILFORD_RECIPROCITY, DataTier.MANUFACTURER,
            "Ilford 旗下"),

        // ── Kodak ───────────────────────────────────────────────
        Film("kodak_trix", "Kodak", "Tri-X 400", 400, FilmType.BW_NEG,
            Reciprocity.Power(1.54), RETRO_PIXEL, DataTier.COMMUNITY),
        Film("kodak_tmax100", "Kodak", "T-Max 100", 100, FilmType.BW_NEG,
            Reciprocity.Power(1.15), RETRO_PIXEL, DataTier.COMMUNITY),
        Film("kodak_tmax400", "Kodak", "T-Max 400", 400, FilmType.BW_NEG,
            Reciprocity.Power(1.24), RETRO_PIXEL, DataTier.COMMUNITY),
        Film("kodak_ektar100", "Kodak", "Ektar 100", 100, FilmType.COLOR_NEG,
            Reciprocity.Table(listOf(
                1.0 to 1.3, 2.0 to 2.9, 4.0 to 6.4, 8.0 to 14.4,
                16.0 to 32.5, 32.0 to 74.5, 64.0 to 172.3,
            )),
            "社区拟合 0.759t + 0.555t^1.3 采样", DataTier.COMMUNITY),
        Film("kodak_portra160", "Kodak", "Portra 160", 160, FilmType.COLOR_NEG,
            Reciprocity.Table(listOf(
                1.0 to 1.1, 2.0 to 2.9, 4.0 to 7.5, 8.0 to 19.3,
                16.0 to 49.5, 32.0 to 127.0,
            )),
            "社区拟合 1.14t^1.36 采样", DataTier.COMMUNITY,
            "低可信：来源为论坛讨论，仅作参考"),
        Film("kodak_portra400", "Kodak", "Portra 400", 400, FilmType.COLOR_NEG,
            Reciprocity.Table(listOf(
                1.0 to 1.1, 2.0 to 2.9, 4.0 to 7.5, 8.0 to 19.3,
                16.0 to 49.5, 32.0 to 127.0,
            )),
            "社区拟合 1.14t^1.36 采样", DataTier.COMMUNITY,
            "低可信：来源为论坛讨论，仅作参考"),
        Film("kodak_gold200", "Kodak", "Gold 200", 200, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX),
        Film("kodak_ultramax400", "Kodak", "Ultramax 400（全能400）", 400, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX,
            "国内常称全能400；暖调、颗粒明显"),

        // ── CineStill ───────────────────────────────────────────
        Film("cinestill_50d", "CineStill", "50D", 50, FilmType.COLOR_NEG,
            Reciprocity.Power(1.3), CINESTILL_FAQ, DataTier.MANUFACTURER),
        Film("cinestill_400d", "CineStill", "400D", 400, FilmType.COLOR_NEG,
            Reciprocity.Power(1.3), CINESTILL_FAQ, DataTier.MANUFACTURER),
        Film("cinestill_800t", "CineStill", "800T", 800, FilmType.COLOR_NEG,
            Reciprocity.Power(1.3), CINESTILL_FAQ, DataTier.MANUFACTURER,
            "钨平衡；去 remjet 层"),

        // ── Fujifilm ────────────────────────────────────────────
        Film("fuji_acros2", "Fujifilm", "Acros 100 II", 100, FilmType.BW_NEG,
            Reciprocity.None,
            "官方称 120s 内无需互易律补偿", DataTier.MANUFACTURER,
            "超 120s 本库不补偿，需自行加档"),
        Film("fuji_c200", "Fujifilm", "C200", 200, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX),
        Film("fuji_superia400", "Fujifilm", "Superia X-TRA 400", 400, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX),
        Film("fuji_pro400h", "Fujifilm", "Pro 400H", 400, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX,
            "已停产，库存卷"),

        // ── Kodak 电影卷（ECN-2，remjet 未除需按 ECN-2 冲洗）──────
        Film("kodak_vision3_50d", "Kodak", "Vision3 50D (5203)", 50, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "厂商未发布静照互易律数据，幂律兜底", DataTier.APPROX,
            "ECN-2；日光平衡；remjet 背层需 ECN-2 或预洗"),
        Film("kodak_vision3_250d", "Kodak", "Vision3 250D (5207)", 250, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "厂商未发布静照互易律数据，幂律兜底", DataTier.APPROX,
            "ECN-2；日光平衡；宽容度大，适合分区控制"),
        Film("kodak_vision3_500t", "Kodak", "Vision3 500T (5219)", 500, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "厂商未发布静照互易律数据，幂律兜底", DataTier.APPROX,
            "ECN-2；钨平衡 3200K，日光下加 85B 约 -2/3 档"),
        Film("kodak_vision3_200t", "Kodak", "Vision3 200T (5213)", 200, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "厂商未发布静照互易律数据，幂律兜底", DataTier.APPROX,
            "ECN-2；钨平衡 3200K，日光下加 85B"),
        Film("kodak_vision3_500t_85b", "Kodak", "Vision3 500T + 85B", 320, FilmType.COLOR_NEG,
            Reciprocity.Power(1.30), "厂商未发布静照互易律数据，幂律兜底", DataTier.APPROX,
            "已按 85B 折算 EI 320（500 × 2/3 档）；实际以机身滤镜为准"),
        Film("kodak_doublex_5222", "Kodak", "Double-X 5222", 250, FilmType.BW_NEG,
            Reciprocity.Power(1.30), "厂商未发布静照互易律数据，幂律兜底", DataTier.APPROX,
            "黑白电影卷；日光 EI250 / 钨丝灯 EI200"),
        Film("kodak_ektachrome_100d", "Kodak", "Ektachrome 100D (5294)", 100, FilmType.SLIDE,
            Reciprocity.Power(1.30), "厂商未发布静照互易律数据，幂律兜底", DataTier.APPROX,
            "反转片；宽容度约 ±1 档，反差超 5 档需提醒"),

        // ── Foma ────────────────────────────────────────────────
        Film("foma_100", "Foma", "Fomapan 100", 100, FilmType.BW_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX),
        Film("foma_400", "Foma", "Fomapan 400", 400, FilmType.BW_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX),
        Film("foma_200", "Foma", "Fomapan 200", 200, FilmType.BW_NEG,
            Reciprocity.Power(1.30), "无官方数据，幂律兜底", DataTier.APPROX),
    )

    fun byId(id: String): Film? = films.firstOrNull { it.id == id }
}
