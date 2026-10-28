package com.battscan.leadacid.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Insert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "ride_session")
data class RideSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long? = null,
    @ColumnInfo(name = "protocol_id") val protocolId: String,
    @ColumnInfo(name = "device_address") val deviceAddress: String? = null,
    @ColumnInfo(name = "start_soc") val startSoc: Double = 0.0,
    @ColumnInfo(name = "end_soc") val endSoc: Double = 0.0,
    @ColumnInfo(name = "discharge_ah") val dischargeAh: Double = 0.0,
    @ColumnInfo(name = "recovered_ah") val recoveredAh: Double = 0.0,
    @ColumnInfo(name = "discharge_wh") val dischargeWh: Double = 0.0,
    @ColumnInfo(name = "recovered_wh") val recoveredWh: Double = 0.0,
    @ColumnInfo(name = "min_voltage") val minVoltage: Double = 0.0,
    @ColumnInfo(name = "max_current") val maxCurrent: Double = 0.0,
    @ColumnInfo(name = "sample_count") val sampleCount: Int = 0,
    @ColumnInfo(name = "note") val note: String = "",
)

@Entity(
    tableName = "battery_sample",
    foreignKeys = [
        ForeignKey(
            entity = RideSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id"), Index("wall_clock_ms")],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "wall_clock_ms") val wallClockMs: Long,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "raw_current_a") val rawCurrentA: Double,
    @ColumnInfo(name = "current_a") val currentA: Double,
    @ColumnInfo(name = "voltage_v") val voltageV: Double,
    @ColumnInfo(name = "power_w") val powerW: Double,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "temp_c") val tempC: Double? = null,
    @ColumnInfo(name = "device_soc") val deviceSoc: Double? = null,
    @ColumnInfo(name = "soc_percent") val socPercent: Double,
    @ColumnInfo(name = "frame_hex") val frameHex: String,
)

@Entity(
    tableName = "raw_frame",
    foreignKeys = [
        ForeignKey(
            entity = RideSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class RawFrameEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "wall_clock_ms") val wallClockMs: Long,
    @ColumnInfo(name = "outbound") val outbound: Boolean,
    @ColumnInfo(name = "hex") val hex: String,
)

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: RideSessionEntity): Long

    @Query("UPDATE ride_session SET ended_at_ms = :endedAtMs, end_soc = :endSoc, discharge_ah = :dischargeAh, " +
        "recovered_ah = :recoveredAh, discharge_wh = :dischargeWh, recovered_wh = :recoveredWh, " +
        "min_voltage = :minVoltage, max_current = :maxCurrent, sample_count = :sampleCount WHERE id = :id")
    suspend fun finish(id: Long, endedAtMs: Long, endSoc: Double, dischargeAh: Double, recoveredAh: Double, dischargeWh: Double, recoveredWh: Double, minVoltage: Double, maxCurrent: Double, sampleCount: Int)

    @Query("UPDATE ride_session SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String)

    @Query("SELECT * FROM ride_session ORDER BY started_at_ms DESC")
    fun observeAll(): Flow<List<RideSessionEntity>>

    @Query("SELECT * FROM ride_session WHERE id = :id")
    suspend fun byId(id: Long): RideSessionEntity?

    @Query("DELETE FROM ride_session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM ride_session")
    suspend fun count(): Int
}

@Dao
interface SampleDao {
    @Insert
    suspend fun insertAll(samples: List<SampleEntity>)

    @Insert
    suspend fun insert(sample: SampleEntity): Long

    @Query("SELECT * FROM battery_sample WHERE session_id = :sessionId ORDER BY elapsed_ms ASC")
    suspend fun forSession(sessionId: Long): List<SampleEntity>

    @Query("SELECT * FROM battery_sample WHERE session_id = :sessionId ORDER BY elapsed_ms ASC LIMIT :limit")
    fun streamRecent(sessionId: Long, limit: Int): Flow<List<SampleEntity>>

    @Query("SELECT COUNT(*) FROM battery_sample WHERE session_id = :sessionId")
    suspend fun countFor(sessionId: Long): Int

    @Query("DELETE FROM battery_sample WHERE session_id = :sessionId")
    suspend fun clearSession(sessionId: Long)
}

@Dao
interface RawFrameDao {
    @Insert
    suspend fun insertAll(frames: List<RawFrameEntity>)

    @Query("SELECT * FROM raw_frame WHERE session_id = :sessionId ORDER BY wall_clock_ms ASC")
    suspend fun forSession(sessionId: Long): List<RawFrameEntity>

    @Query("DELETE FROM raw_frame WHERE session_id = :sessionId")
    suspend fun clearSession(sessionId: Long)

    @Query("DELETE FROM raw_frame WHERE id IN (SELECT id FROM raw_frame ORDER BY id ASC LIMIT :n)")
    suspend fun trimOldest(n: Int)
}

@androidx.room.Database(
    entities = [RideSessionEntity::class, SampleEntity::class, RawFrameEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun sampleDao(): SampleDao
    abstract fun rawFrameDao(): RawFrameDao
}

/** Session plus its samples, assembled by the replay screen. */
data class SessionDetail(
    val session: RideSessionEntity,
    val samples: List<SampleEntity>,
)
