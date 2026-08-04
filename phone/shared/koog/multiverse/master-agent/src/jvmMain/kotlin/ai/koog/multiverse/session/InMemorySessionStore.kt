package ai.koog.multiverse.session

import java.util.concurrent.ConcurrentHashMap

/** In-memory [SessionStore] (v1 default; lost on restart). */
class InMemorySessionStore : SessionStore {
    private val sessions = ConcurrentHashMap<String, Session>()
    override fun get(sessionId: String): Session? = sessions[sessionId]
    override fun put(session: Session) { sessions[session.sessionId] = session }
    override fun remove(sessionId: String) { sessions.remove(sessionId) }
    override fun all(): List<Session> = sessions.values.toList()
}
