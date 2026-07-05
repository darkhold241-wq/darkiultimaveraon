package com.darki.os.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version 1: solo memoria. Cuando agreguemos automatizaciones o
 * preferencias de apps, se suman como entidades nuevas con una
 * migracion, no se reemplaza esta clase.
 */
@Database(entities = [MemoryEntity::class], version = 1, exportSchema = false)
abstract class DarkiDatabase : RoomDatabase() {

    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var instance: DarkiDatabase? = null

        fun getInstance(context: Context): DarkiDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DarkiDatabase::class.java,
                    "darki_memory.db"
                ).build().also { instance = it }
            }
    }
}
