package ai.koog.multiverse.model

import kotlinx.serialization.Serializable

/** Which documented use case a request targets. UC1 = food-allergen; UC2 = menu suggestions. */
@Serializable
enum class UseCase { UC1, UC2 }

/** Outcome status of a compute request. */
@Serializable
enum class ResultStatus { ok, partial, no_answer }

/** Bucketed confidence label derived from the numeric confidence, for UIs that avoid raw floats. */
@Serializable
enum class ConfidenceLabel { high, medium, low }

/** Whether an allergen is present in the analyzed food. */
@Serializable
enum class Presence { present, possible, absent }
