package dev.whynet.core.storage

import android.content.Context
import dev.whynet.core.model.DiagnosticSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SessionRepository(context: Context) {

    private val dao = WhyNetDatabase.getInstance(context).sessionDao()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        allowSpecialFloatingPointValues = true
    }

    fun observeSessions(): Flow<List<DiagnosticSession>> =
        dao.observeRecent().map { entities ->
            entities.mapNotNull { entity ->
                runCatching { json.decodeFromString<DiagnosticSession>(entity.payloadJson) }.getOrNull()
            }
        }

    suspend fun save(session: DiagnosticSession) {
        val entity = SessionEntity(
            sessionId = session.sessionId,
            createdAtEpochMs = session.createdAtEpochMs,
            targetLabel = session.target.label,
            targetHost = session.target.hostOrUrl,
            transport = session.snapshot.transport.name,
            verdictCategory = session.verdict.category.name,
            verdictHeadline = session.verdict.headline,
            payloadJson = json.encodeToString(session),
        )
        dao.upsert(entity)
    }

    suspend fun delete(sessionId: String) = dao.deleteById(sessionId)

    suspend fun clearAll() = dao.clearAll()
}
