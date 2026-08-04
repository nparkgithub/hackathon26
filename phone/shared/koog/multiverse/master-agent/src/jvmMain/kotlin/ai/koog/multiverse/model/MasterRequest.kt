package ai.koog.multiverse.model

/**
 * Internal request object flowing through the Master Agent graph. Built by the API layer from the
 * multipart `POST /v1/compute` request. The image is the raw JPEG bytes captured by the phone app.
 *
 * Not serialized over the wire as-is (the JPEG arrives as a multipart part, not JSON).
 */
data class MasterRequest(
    val sessionId: String?,
    val query: String,
    val useCase: UseCase,
    val imageBytes: ByteArray,
    val imageFormat: String = "jpg",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MasterRequest) return false
        return sessionId == other.sessionId &&
            query == other.query &&
            useCase == other.useCase &&
            imageFormat == other.imageFormat &&
            imageBytes.contentEquals(other.imageBytes)
    }

    override fun hashCode(): Int {
        var result = sessionId?.hashCode() ?: 0
        result = 31 * result + query.hashCode()
        result = 31 * result + useCase.hashCode()
        result = 31 * result + imageFormat.hashCode()
        result = 31 * result + imageBytes.contentHashCode()
        return result
    }
}
