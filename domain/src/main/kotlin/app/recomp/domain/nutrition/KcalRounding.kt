package app.recomp.domain.nutrition

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Calories are shown and stored in steps of 10 (TRD §5.3). One rule, so the targets and the band never disagree. */
internal object KcalRounding {
    fun nearest(kcal: Double): Int = (kcal / 10).roundToInt() * 10

    /** For floors, which must never round below the true value. */
    fun up(kcal: Double): Int = (ceil(kcal / 10) * 10).toInt()
}
