package ai.koog.http.client.tquic

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * [KoogHttpClient] backed by the multipath-QUIC transport via the Rust `tquic-jni` bridge
 * (grillme_version2 remote route). Injected DIRECTLY into the remote `OpenAILLMClient` constructor,
 * bypassing `HttpClientFactoryResolver` (which hard-errors on != 1 ServiceLoader provider) so the
 * local Ktor client and this TQUIC client can coexist.
 *
 * The Rust native library is a separate task and is not built yet; every network operation calls
 * [TquicNative.ensureLoaded], which throws a clear "not yet implemented" error. The mapping onto H3
 * (post -> one bidi stream; sse/lines -> incremental body reads) is expressed here so the wiring is
 * complete the moment the .so lands.
 */
public class TquicKoogHttpClient(
    override val clientName: String,
    private val baseUrl: String,
    private val params: TquicSessionParams,
    private val json: Json = Json,
    private val defaultHeaders: Map<String, String> = emptyMap(),
) : KoogHttpClient {

    private val notReady: Nothing
        get() = throw NotImplementedError(
            "TQUIC transport ('$clientName' -> $baseUrl) is scaffolded but the native tquic-jni " +
                "library is not built yet. See http-client-tquic/TquicNative."
        )

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        TquicNative.ensureLoaded()
        notReady
    }

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R {
        TquicNative.ensureLoaded()
        notReady
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> = flow {
        TquicNative.ensureLoaded()
        notReady
    }

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = flow {
        TquicNative.ensureLoaded()
        notReady
    }

    override fun close() {
        // No live session to close until the native bridge is implemented.
    }

    /** Whether the native transport is actually available (false until tquic-jni is built). */
    public fun isNativeAvailable(): Boolean = TquicNative.tryLoad()
}
