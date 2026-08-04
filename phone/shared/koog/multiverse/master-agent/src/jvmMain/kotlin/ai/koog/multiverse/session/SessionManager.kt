package ai.koog.multiverse.session

import java.util.UUID

/**
 * Creates/resumes logical sessions (grillme_version2 "Session + Context"). A null or unknown
 * sessionId creates a new session (UC1/UC2); a known one is resumed with its prior context (UC3).
 */
class SessionManager(
    private val store: SessionStore = InMemorySessionStore(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val idGen: () -> String = { UUID.randomUUID().toString() },
) {
    /** Resume [sessionId] if present, else create a new session. Returns the (possibly new) session. */
    fun getOrCreate(sessionId: String?): Session {
        if (sessionId != null) {
            store.get(sessionId)?.let { return it }
        }
        val session = Session(sessionId = sessionId ?: idGen(), createdAtEpochMs = clock())
        store.put(session)
        return session
    }

    fun get(sessionId: String?): Session? = sessionId?.let { store.get(it) }

    fun save(session: Session) = store.put(session)

    fun now(): Long = clock()
}
