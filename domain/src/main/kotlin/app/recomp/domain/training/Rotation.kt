package app.recomp.domain.training

/**
 * Rotation, not calendar (RT-02): the next routine is the one after the last strength routine you
 * actually did, so a missed day never breaks the plan and doing Legs when Pull was due moves the
 * sequence on from Legs.
 */
object Rotation {
    /**
     * @param rotation strength routine ids in rotation order.
     * @param recentRoutineIds routine ids of completed strength sessions, newest first. Ad-hoc sessions
     *   (null) and routines no longer in the rotation, such as an old split's, are skipped.
     */
    fun next(rotation: List<Long>, recentRoutineIds: List<Long?>): Long? {
        if (rotation.isEmpty()) return null
        val last = recentRoutineIds.firstOrNull { it != null && it in rotation } ?: return rotation.first()
        return rotation[(rotation.indexOf(last) + 1) % rotation.size]
    }
}
