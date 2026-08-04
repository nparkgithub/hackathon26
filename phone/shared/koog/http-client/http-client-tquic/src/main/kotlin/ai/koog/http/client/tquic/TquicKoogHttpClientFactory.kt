package ai.koog.http.client.tquic

import ai.koog.http.client.KoogHttpClient
import kotlinx.serialization.json.Json

/**
 * [KoogHttpClient.Factory] for the TQUIC transport. Intended to be passed EXPLICITLY to the remote
 * LLM client (not registered via ServiceLoader), so it can coexist with the local Ktor factory.
 *
 * The [params] carry the multipath/PATFB/CC settings derived from the app's TquicConfig.
 */
public class TquicKoogHttpClientFactory(
    private val params: TquicSessionParams,
) : KoogHttpClient.Factory {

    override fun create(
        clientName: String,
        baseUrl: String,
        headers: Map<String, String>,
        queryParameters: Map<String, String>,
        requestTimeoutMillis: Long,
        connectTimeoutMillis: Long,
        socketTimeoutMillis: Long,
        json: Json,
    ): KoogHttpClient = TquicKoogHttpClient(
        clientName = clientName,
        baseUrl = baseUrl,
        params = params.copy(serverName = params.serverName.ifBlank { hostOf(baseUrl) }),
        json = json,
        defaultHeaders = headers,
    )

    private fun hostOf(baseUrl: String): String =
        baseUrl.substringAfter("://", baseUrl).substringBefore("/").substringBefore(":")
}
