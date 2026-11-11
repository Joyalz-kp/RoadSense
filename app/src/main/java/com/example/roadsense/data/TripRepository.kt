package com.example.roadsense.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.text.DateFormat
import java.util.Date
import kotlin.math.max

object Extras {
    const val TRIP_ID = "com.example.roadsense.TRIP_ID"
}

@Entity(tableName = "Trip")
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTime: Long,
    val endTime: Long = 0,
    val distanceMeters: Double = 0.0,
    val bumpCount: Int = 0,
    val roadScore: Int = 100
) {
    val durationMillis: Long get() = max(0L, endTime - startTime)
    val averageSpeedKmh: Double
        get() = if (durationMillis > 0) distanceMeters / (durationMillis / 1000.0) * 3.6 else 0.0
}

@Entity(
    tableName = "Bump",
    foreignKeys = [ForeignKey(
        entity = Trip::class,
        parentColumns = ["id"],
        childColumns = ["tripId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("tripId")]
)
data class Bump(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val severity: Int
) {
    val hasLocation: Boolean get() = !(latitude == 0.0 && longitude == 0.0)
}

object RoadScore {
    /** 100 = smooth. Penalty grows with the summed bump severity per kilometre travelled. */
    fun compute(distanceMeters: Double, severitySum: Int): Int {
        val km = max(distanceMeters / 1000.0, 0.5)
        val penalty = severitySum / km * 5.0
        return (100.0 - penalty).coerceIn(0.0, 100.0).toInt()
    }
}

object Fmt {
    fun duration(millis: Long): String {
        val totalSec = millis / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    fun distance(meters: Double): String =
        if (meters < 1000) String.format("%.0f m", meters) else String.format("%.2f km", meters / 1000.0)

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    fun time(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))
}

@Dao
interface TripDao {
    @Insert
    fun insertTrip(trip: Trip): Long

    @Insert
    fun insertBump(bump: Bump): Long

    @Query("UPDATE Trip SET endTime = :endTime, distanceMeters = :distance, bumpCount = :bumpCount, roadScore = :roadScore WHERE id = :tripId")
    fun finishTrip(tripId: Long, endTime: Long, distance: Double, bumpCount: Int, roadScore: Int)

    @Query("SELECT * FROM Trip WHERE id = :tripId")
    fun getTrip(tripId: Long): Trip?

    @Query("SELECT * FROM Trip WHERE endTime > 0 ORDER BY startTime DESC")
    fun getAllTrips(): List<Trip>

    @Query("SELECT * FROM Bump WHERE tripId = :tripId ORDER BY timestamp ASC")
    fun getBumps(tripId: Long): List<Bump>

    @Query("DELETE FROM Trip")
    fun deleteAllTrips()
}

@Database(entities = [Trip::class, Bump::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tripDao(): TripDao
}

/**
 * Room-backed storage for the two tables in the proposal: Trip and Bump.
 * Queries are small and run on the main thread (allowMainThreadQueries) to keep the
 * activities simple at this milestone; moving them to a background thread is a later step.
 */
class TripRepository private constructor(context: Context) {

    private val dao: TripDao = Room.databaseBuilder(
        context.applicationContext, AppDatabase::class.java, "roadsense_room.db"
    ).allowMainThreadQueries().build().tripDao()

    fun startTrip(startTime: Long): Long = dao.insertTrip(Trip(startTime = startTime))

    fun addBump(tripId: Long, timestamp: Long, latitude: Double, longitude: Double, severity: Int) {
        dao.insertBump(Bump(tripId = tripId, timestamp = timestamp, latitude = latitude, longitude = longitude, severity = severity))
    }

    fun finishTrip(tripId: Long, endTime: Long, distanceMeters: Double, bumpCount: Int, roadScore: Int) =
        dao.finishTrip(tripId, endTime, distanceMeters, bumpCount, roadScore)

    fun getTrip(tripId: Long): Trip? = dao.getTrip(tripId)

    /** Finished trips only, newest first. */
    fun getAllTrips(): List<Trip> = dao.getAllTrips()

    fun getBumps(tripId: Long): List<Bump> = dao.getBumps(tripId)

    /** Deletes every trip; its bumps go with it (cascade). */
    fun deleteAll() = dao.deleteAllTrips()

    companion object {
        @Volatile
        private var instance: TripRepository? = null

        fun get(context: Context): TripRepository =
            instance ?: synchronized(this) {
                instance ?: TripRepository(context.applicationContext).also { instance = it }
            }
    }
}
