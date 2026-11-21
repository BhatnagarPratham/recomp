package app.recomp.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord as E
import app.recomp.domain.model.SessionKind

/**
 * Health Connect's exercise types as Recomp's session kinds (AR-04, TRD §10 M13): walking, running, cycling and
 * the like are cardio; yoga and pilates are yoga; stretching is mobility; sports are sport.
 *
 * Strength types are never imported, so a watch's copy of a Recomp workout can't count twice or trigger *Rest
 * day*. Types that say nothing about what you did ("other workout", "exercise class", guided breathing) and
 * types this table doesn't know are left out too.
 */
object ExerciseTypes {
    private data class Entry(val kind: SessionKind, val label: String)

    /** Never imported, whatever else changes: listed so the test can say so by name. */
    val STRENGTH: Set<Int> = setOf(E.EXERCISE_TYPE_STRENGTH_TRAINING, E.EXERCISE_TYPE_WEIGHTLIFTING, E.EXERCISE_TYPE_CALISTHENICS)

    private val table: Map<Int, Entry> = buildMap {
        fun add(kind: SessionKind, vararg types: Pair<Int, String>) = types.forEach { (type, label) -> put(type, Entry(kind, label)) }
        add(
            SessionKind.CARDIO,
            E.EXERCISE_TYPE_WALKING to "Walk",
            E.EXERCISE_TYPE_RUNNING to "Run",
            E.EXERCISE_TYPE_RUNNING_TREADMILL to "Treadmill run",
            E.EXERCISE_TYPE_BIKING to "Cycling",
            E.EXERCISE_TYPE_BIKING_STATIONARY to "Stationary bike",
            E.EXERCISE_TYPE_HIKING to "Hike",
            E.EXERCISE_TYPE_ELLIPTICAL to "Elliptical",
            E.EXERCISE_TYPE_ROWING to "Rowing",
            E.EXERCISE_TYPE_ROWING_MACHINE to "Rowing machine",
            E.EXERCISE_TYPE_STAIR_CLIMBING to "Stair climbing",
            E.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "Stair machine",
            E.EXERCISE_TYPE_SWIMMING_POOL to "Swim",
            E.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "Open-water swim",
            E.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "HIIT",
            E.EXERCISE_TYPE_BOOT_CAMP to "Boot camp",
            E.EXERCISE_TYPE_DANCING to "Dancing",
            E.EXERCISE_TYPE_PADDLING to "Paddling",
            E.EXERCISE_TYPE_SKATING to "Skating",
            E.EXERCISE_TYPE_ICE_SKATING to "Ice skating",
            E.EXERCISE_TYPE_SNOWSHOEING to "Snowshoeing",
            E.EXERCISE_TYPE_WHEELCHAIR to "Wheelchair",
        )
        add(SessionKind.YOGA, E.EXERCISE_TYPE_YOGA to "Yoga", E.EXERCISE_TYPE_PILATES to "Pilates")
        add(SessionKind.MOBILITY, E.EXERCISE_TYPE_STRETCHING to "Stretching")
        add(
            SessionKind.SPORT,
            E.EXERCISE_TYPE_BADMINTON to "Badminton",
            E.EXERCISE_TYPE_BASEBALL to "Baseball",
            E.EXERCISE_TYPE_BASKETBALL to "Basketball",
            E.EXERCISE_TYPE_BOXING to "Boxing",
            E.EXERCISE_TYPE_CRICKET to "Cricket",
            E.EXERCISE_TYPE_FENCING to "Fencing",
            E.EXERCISE_TYPE_FOOTBALL_AMERICAN to "American football",
            E.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "Australian football",
            E.EXERCISE_TYPE_FRISBEE_DISC to "Frisbee",
            E.EXERCISE_TYPE_GOLF to "Golf",
            E.EXERCISE_TYPE_GYMNASTICS to "Gymnastics",
            E.EXERCISE_TYPE_HANDBALL to "Handball",
            E.EXERCISE_TYPE_ICE_HOCKEY to "Ice hockey",
            E.EXERCISE_TYPE_MARTIAL_ARTS to "Martial arts",
            E.EXERCISE_TYPE_PARAGLIDING to "Paragliding",
            E.EXERCISE_TYPE_RACQUETBALL to "Racquetball",
            E.EXERCISE_TYPE_ROCK_CLIMBING to "Climbing",
            E.EXERCISE_TYPE_ROLLER_HOCKEY to "Roller hockey",
            E.EXERCISE_TYPE_RUGBY to "Rugby",
            E.EXERCISE_TYPE_SAILING to "Sailing",
            E.EXERCISE_TYPE_SCUBA_DIVING to "Scuba diving",
            E.EXERCISE_TYPE_SKIING to "Skiing",
            E.EXERCISE_TYPE_SNOWBOARDING to "Snowboarding",
            E.EXERCISE_TYPE_SOCCER to "Football",
            E.EXERCISE_TYPE_SOFTBALL to "Softball",
            E.EXERCISE_TYPE_SQUASH to "Squash",
            E.EXERCISE_TYPE_SURFING to "Surfing",
            E.EXERCISE_TYPE_TABLE_TENNIS to "Table tennis",
            E.EXERCISE_TYPE_TENNIS to "Tennis",
            E.EXERCISE_TYPE_VOLLEYBALL to "Volleyball",
            E.EXERCISE_TYPE_WATER_POLO to "Water polo",
        )
        STRENGTH.forEach { remove(it) }
    }

    /** The session kind [type] is imported as, or null when it's never imported. */
    fun kind(type: Int): SessionKind? = table[type]?.kind

    /** "Walk", "Yoga": what Today and History call it. */
    fun label(type: Int): String = table[type]?.label ?: "Workout"
}
