package ai.tatawur.rafiq.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * The app's offline cache. It holds only what the bridge already has, so a schema change drops it and
 * the app replays from the bridge. That is why there are no migrations.
 */
@Database(entities = [EventRow::class, SummaryRow::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cache(): CacheDao
}

/**
 * One cached event. The payload stays the JSON the bridge sent, so a kind this build does not know
 * survives until a newer build can read it.
 */
@Entity(tableName = "events", primaryKeys = ["session_id", "seq"])
data class EventRow(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "seq") val seq: Long,
    @ColumnInfo(name = "ts_ms") val timestampMs: Long,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
)

/** One row of the session list as last fetched, as the `SessionSummary` JSON. */
@Entity(tableName = "summaries")
data class SummaryRow(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "summary_json") val summaryJson: String,
)

/** Reads and writes [CacheDatabase]. */
@Dao
abstract class CacheDao {
    @Query("SELECT * FROM events WHERE session_id = :sessionId ORDER BY seq")
    abstract suspend fun events(sessionId: String): List<EventRow>

    // Replays resend events, so the same (session, seq) replaces the stored row.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putEvent(row: EventRow)

    @Query("SELECT * FROM summaries")
    abstract suspend fun summaries(): List<SummaryRow>

    /** Replaces the whole session list, so sessions the bridge no longer has disappear. */
    @Transaction
    open suspend fun replaceSummaries(rows: List<SummaryRow>) {
        deleteSummaries()
        putSummaries(rows)
    }

    /** Forgets every event and summary. */
    @Transaction
    open suspend fun clear() {
        deleteEvents()
        deleteSummaries()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSummaries(rows: List<SummaryRow>)

    @Query("DELETE FROM summaries")
    abstract suspend fun deleteSummaries()

    @Query("DELETE FROM events")
    abstract suspend fun deleteEvents()
}
