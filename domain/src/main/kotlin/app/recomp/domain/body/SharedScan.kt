package app.recomp.domain.body

/**
 * A shared InBody screenshot and Health Connect's InBody drafts (IB-03, IB-07, PRD §12.11): the screenshot is usually
 * shared soon after the scan, while the InBody app has written the same measurement to Health Connect. The draft from
 * the same time is the newest one measured within [WINDOW_MS] before the share, or up to [AFTER_MS] after it (a clock
 * a little apart). Its weight, body fat % and BMR then fill the form.
 */
object SharedScan {
    const val WINDOW_MS = 12 * 3_600_000L
    const val AFTER_MS = 10 * 60_000L

    /** The id of the draft from the same time as a share at [sharedAt], from (id, measured-at) pairs, or null. */
    fun draftFor(drafts: List<Pair<Long, Long>>, sharedAt: Long): Long? = drafts
        .filter { (_, at) -> at <= sharedAt + AFTER_MS && sharedAt - at <= WINDOW_MS }
        .maxByOrNull { it.second }?.first
}
