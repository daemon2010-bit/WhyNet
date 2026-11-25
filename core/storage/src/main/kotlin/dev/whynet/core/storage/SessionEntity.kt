package dev.whynet.core.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "diagnostic_sessions")
data class SessionEntity(
    @PrimaryKey val sessionId: String,
    val createdAtEpochMs: Long,
    val targetLabel: String,
    val targetHost: String,
    val transport: String,
    val verdictCategory: String,
    val verdictHeadline: String,
    val payloadJson: String,
)
