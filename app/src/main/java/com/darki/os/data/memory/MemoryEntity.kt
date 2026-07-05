package com.darki.os.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un recuerdo guardado cuando Jostin dice "Darki recuerda que..." o
 * "Darki aprende que...". Vive en Room, 100% local, sobrevive a
 * reinicios del telefono y nunca sale del dispositivo.
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val content: String,
    val normalizedContent: String,
    val createdAt: Long = System.currentTimeMillis()
)
