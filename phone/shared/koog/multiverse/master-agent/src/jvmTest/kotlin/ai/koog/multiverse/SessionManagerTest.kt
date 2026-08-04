package ai.koog.multiverse

import ai.koog.multiverse.model.ComputeResponse
import ai.koog.multiverse.model.ConfidenceLabel
import ai.koog.multiverse.model.ResultStatus
import ai.koog.multiverse.model.UseCase
import ai.koog.multiverse.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionManagerTest {

    private fun response(sid: String) = ComputeResponse(
        sessionId = sid, useCase = UseCase.UC1, status = ResultStatus.ok,
        answer = "ok", confidence = 0.9, confidenceLabel = ConfidenceLabel.high,
    )

    @Test
    fun createsNewSessionWhenIdNull() {
        var id = 0
        val mgr = SessionManager(clock = { 100 }, idGen = { "sess-${id++}" })
        val s = mgr.getOrCreate(null)
        assertEquals("sess-0", s.sessionId)
        assertEquals(100, s.createdAtEpochMs)
    }

    @Test
    fun resumesExistingSessionWithPriorContext() {
        val mgr = SessionManager(clock = { 1 })
        val created = mgr.getOrCreate("user-1")
        created.record("what allergens?", UseCase.UC1, response("user-1"), 2)
        mgr.save(created)

        val resumed = mgr.getOrCreate("user-1")
        assertEquals("user-1", resumed.sessionId)
        assertEquals(1, resumed.turns.size)
        assertTrue(resumed.priorContextSummary().contains("what allergens?"))
    }

    @Test
    fun getReturnsNullForUnknown() {
        val mgr = SessionManager()
        assertEquals(null, mgr.get("nope"))
        assertNotNull(mgr.getOrCreate("x"))
    }
}
