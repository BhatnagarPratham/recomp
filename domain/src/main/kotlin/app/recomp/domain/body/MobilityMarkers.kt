package app.recomp.domain.body

import app.recomp.domain.model.SessionKind
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** YG-04's three markers. Sit-and-reach is cm past the toes (negative when short), squat hold is seconds, toe touch is 1 or 0. */
enum class MobilityMarker { SIT_AND_REACH, SQUAT_HOLD, TOE_TOUCH }

/** Monthly mobility markers (YG-04, PRD §12.13), shown on a Body card while the setting is on. */
object MobilityMarkers {
    const val EVERY_DAYS = 28L

    /** On by default when you have a yoga or mobility extra session; your own choice wins once made. */
    fun enabled(choice: Boolean?, extras: Collection<SessionKind>): Boolean =
        choice ?: extras.any { it == SessionKind.YOGA || it == SessionKind.MOBILITY }

    /** Due when never taken, or when the latest of any marker is [EVERY_DAYS] days old. */
    fun due(lastDate: LocalDate?, today: LocalDate): Boolean =
        lastDate == null || ChronoUnit.DAYS.between(lastDate, today) >= EVERY_DAYS

    fun how(marker: MobilityMarker): String = when (marker) {
        MobilityMarker.SIT_AND_REACH -> "Sit with legs straight, feet against a box or wall. Reach forward slowly and hold for 2 seconds. Measure from your toes: past them is positive, short of them negative."
        MobilityMarker.SQUAT_HOLD -> "Feet shoulder-width, heels down, hips below knees. Start the stopwatch and stop it when your heels lift or you stand."
        MobilityMarker.TOE_TOUCH -> "Standing, knees straight, bend forward slowly. Can your fingertips touch your toes?"
    }

    /** Sit-and-reach in cm, to one decimal: a sensible range, and short of the toes is fine. */
    fun validReach(cm: Double): Boolean = cm in -60.0..60.0

    /** Squat hold in whole seconds, up to 30 minutes. */
    fun validHold(seconds: Int): Boolean = seconds in 0..1_800
}
