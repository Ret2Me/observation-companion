package pl.put.observationcompanion.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import pl.put.observationcompanion.data.local.dao.*
import pl.put.observationcompanion.data.local.entity.*

@Database(
    entities = [
        SatelliteEntity::class,
        TleEntity::class,
        SatelliteFtsEntity::class,
        TransmitterEntity::class,
        ObservationEntity::class,
        PassEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun satelliteDao(): SatelliteDao
    abstract fun tleDao(): TleDao
    abstract fun transmitterDao(): TransmitterDao
    abstract fun observationDao(): ObservationDao
    abstract fun passCacheDao(): PassCacheDao
}
