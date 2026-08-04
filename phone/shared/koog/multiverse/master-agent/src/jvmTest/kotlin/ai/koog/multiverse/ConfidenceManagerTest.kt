package ai.koog.multiverse

import ai.koog.multiverse.confidence.ConfidenceManager
import ai.koog.multiverse.model.AllergenFinding
import ai.koog.multiverse.model.AssessmentResult
import ai.koog.multiverse.model.ConfidenceLabel
import ai.koog.multiverse.model.MenuItem
import ai.koog.multiverse.model.Presence
import ai.koog.multiverse.model.ResultStatus
import ai.koog.multiverse.model.UseCase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfidenceManagerTest {

    private val cm = ConfidenceManager()

    @Test
    fun highConfidenceMapsToHighLabelAndOkStatus() {
        val r = cm.evaluate("s", UseCase.UC1, AssessmentResult(answer = "contains peanuts", confidence = 0.9), 10)
        assertEquals(ConfidenceLabel.high, r.confidenceLabel)
        assertEquals(ResultStatus.ok, r.status)
        assertEquals(10, r.totalMs)
    }

    @Test
    fun lowConfidenceIsPartialWithWarning() {
        val r = cm.evaluate("s", UseCase.UC1, AssessmentResult(answer = "maybe", confidence = 0.2))
        assertEquals(ConfidenceLabel.low, r.confidenceLabel)
        assertEquals(ResultStatus.partial, r.status)
        assertTrue(r.warnings.isNotEmpty())
    }

    @Test
    fun blankAnswerIsNoAnswer() {
        val r = cm.evaluate("s", UseCase.UC1, AssessmentResult(answer = "", confidence = 0.9))
        assertEquals(ResultStatus.no_answer, r.status)
    }

    @Test
    fun confidenceClampedToRange() {
        val r = cm.evaluate("s", UseCase.UC1, AssessmentResult(answer = "x", confidence = 1.7))
        assertEquals(1.0, r.confidence)
    }

    @Test
    fun uc1KeepsAllergensAndDropsMenu() {
        val res = AssessmentResult(
            answer = "x", confidence = 0.8,
            allergens = listOf(AllergenFinding("peanut", Presence.present, true)),
            menuSuggestions = listOf(MenuItem("pizza", true)),
        )
        val r = cm.evaluate("s", UseCase.UC1, res)
        assertEquals(1, r.allergens.size)
        assertTrue(r.menuSuggestions.isEmpty())
    }

    @Test
    fun uc2KeepsMenuAndDropsAllergens() {
        val res = AssessmentResult(
            answer = "x", confidence = 0.8,
            allergens = listOf(AllergenFinding("peanut", Presence.present, true)),
            menuSuggestions = listOf(MenuItem("pizza", true)),
        )
        val r = cm.evaluate("s", UseCase.UC2, res)
        assertEquals(1, r.menuSuggestions.size)
        assertTrue(r.allergens.isEmpty())
    }
}
