package app.recomp.data.health

import app.recomp.domain.export.OutKind
import app.recomp.domain.export.OutRecord

/**
 * What Recomp writes to Health Connect (DA-04, TRD §6.3), so tests can stand in for it. Only Recomp's own records:
 * writes and deletes go by `clientRecordId`, and a delete by time range removes only this app's records.
 */
interface HealthWriter {
    /** Health Connect is there and up to date. */
    fun available(): Boolean = true

    /** Health Connect is there and every write permission is granted. */
    suspend fun canWrite(): Boolean

    /** Writes or replaces [records]. Each carries [version] (the write time, ms) as its `clientRecordVersion`, so it always wins. */
    suspend fun upsert(records: List<OutRecord>, version: Long)

    /** Deletes these records by `clientRecordId`. One that's already gone isn't an error. */
    suspend fun delete(kind: OutKind, ids: List<String>)

    /** Deletes every record of [kind] this app ever wrote, for *Resync everything*. */
    suspend fun deleteAll(kind: OutKind)
}
