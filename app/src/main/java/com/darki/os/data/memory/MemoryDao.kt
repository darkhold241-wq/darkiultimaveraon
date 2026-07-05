package com.darki.os.data.memory

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories ORDER BY createdAt DESC")
    suspend fun getAll(): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE normalizedContent LIKE '%' || :keyword || '%' ORDER BY createdAt DESC")
    suspend fun search(keyword: String): List<MemoryEntity>

    @Delete
    suspend fun delete(memory: MemoryEntity)

    @Query("DELETE FROM memories")
    suspend fun deleteAll()
}
