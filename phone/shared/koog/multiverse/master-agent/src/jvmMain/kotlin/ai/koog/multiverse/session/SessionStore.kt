package ai.koog.multiverse.session

/**
 * Persistence seam for sessions. v1 ships [InMemorySessionStore]; JDBC/persistence can implement this
 * later without touching the [SessionManager] (grillme_version2 Decision 6).
 */
interface SessionStore {
    fun get(sessionId: String): Session?
    fun put(session: Session)
    fun remove(sessionId: String)
    fun all(): List<Session>
}
