package ai.koog.multiverse.model

import kotlinx.serialization.Serializable

/**
 * Structured output the LLM is asked to produce (JSON). The Confidence Manager normalizes this into a
 * [ComputeResponse]. Kept separate from the wire response so the model schema and the client contract
 * can evolve independently.
 *
 * For UC1 the model fills [allergens]; for UC2 it fills [menuSuggestions].
 */
@Serializable
data class AssessmentResult(
    val answer: String,
    val confidence: Double,
    val detail: String = "",
    val allergens: List<AllergenFinding> = emptyList(),
    val menuSuggestions: List<MenuItem> = emptyList(),
)

/** A single allergen finding for UC1 (food-image allergen analysis). */
@Serializable
data class AllergenFinding(
    val name: String,
    val present: Presence,
    val matchesUserAllergen: Boolean,
    val evidence: String = "",
    val confidence: Double = 0.0,
)

/** A single menu item for UC2 (menu-image, allergen-aware suggestions). */
@Serializable
data class MenuItem(
    val name: String,
    val safe: Boolean,
    val containsAllergens: List<String> = emptyList(),
    val note: String = "",
)
