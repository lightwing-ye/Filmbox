package com.lightwing.filmbox.data

import android.content.Context

/**
 * 单机校准偏移（D4：开箱即用 + 机内对照校准）。
 *
 * offset = 本机测出的 EV − 参考 EV。显示时统一减掉，所以 offset 为正表示
 * 手机测光偏高（读数比真实亮），需要往下压。
 */
class CalibrationStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("filmmeter_calibration", Context.MODE_PRIVATE)

    var offset: Double
        get() = prefs.getFloat(KEY_OFFSET, 0f).toDouble()
        set(value) {
            prefs.edit().putFloat(KEY_OFFSET, value.toFloat()).apply()
        }

    fun reset() = prefs.edit().remove(KEY_OFFSET).apply()

    val isCalibrated: Boolean get() = kotlin.math.abs(offset) > 0.01

    private companion object {
        const val KEY_OFFSET = "ev_offset"
    }
}
