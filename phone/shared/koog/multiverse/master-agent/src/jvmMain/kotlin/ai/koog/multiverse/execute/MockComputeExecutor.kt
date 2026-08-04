package ai.koog.multiverse.execute

import ai.koog.multiverse.model.AllergenFinding
import ai.koog.multiverse.model.AssessmentResult
import ai.koog.multiverse.model.MasterRequest
import ai.koog.multiverse.model.MenuItem
import ai.koog.multiverse.model.Presence
import ai.koog.multiverse.model.UseCase
import ai.koog.multiverse.routing.RouteDecision

/**
 * Deterministic, offline [ComputeExecutor] used when no LLM backend/network is configured (default
 * for tests and local demo runs). Produces a canned but structurally valid [AssessmentResult] keyed
 * on the use case, so the whole graph + API + routing can run end-to-end with no API key.
 */
class MockComputeExecutor : ComputeExecutor {

    override suspend fun run(
        request: MasterRequest,
        decision: RouteDecision,
        priorContext: String,
    ): AssessmentResult = when (request.useCase) {
        UseCase.UC1 -> AssessmentResult(
            answer = "Contains peanuts - not safe for you.",
            confidence = 0.87,
            detail = "Detected peanut sauce and crushed peanuts as garnish (mock analysis).",
            allergens = listOf(
                AllergenFinding(
                    name = "peanut",
                    present = Presence.present,
                    matchesUserAllergen = true,
                    evidence = "crushed peanuts as garnish; peanut-sauce sheen",
                    confidence = 0.87,
                ),
            ),
        )
        UseCase.UC2 -> AssessmentResult(
            answer = "2 of 3 items look peanut-free.",
            confidence = 0.72,
            detail = "Filtered the menu for peanut content (mock analysis).",
            menuSuggestions = listOf(
                MenuItem(name = "Margherita Pizza", safe = true),
                MenuItem(name = "Kung Pao Chicken", safe = false, containsAllergens = listOf("peanut"), note = "contains peanuts"),
                MenuItem(name = "Garden Salad", safe = true, note = "ask for no peanut dressing"),
            ),
        )
    }
}
