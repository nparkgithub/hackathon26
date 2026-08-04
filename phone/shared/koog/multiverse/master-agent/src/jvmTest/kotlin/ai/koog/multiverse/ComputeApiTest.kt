package ai.koog.multiverse

import ai.koog.multiverse.api.module
import ai.koog.multiverse.confidence.ConfidenceManager
import ai.koog.multiverse.config.TquicConfig
import ai.koog.multiverse.discovery.StaticConfigDiscovery
import ai.koog.multiverse.execute.LlmBackend
import ai.koog.multiverse.model.ComputeResponse
import ai.koog.multiverse.model.DevicesBody
import ai.koog.multiverse.model.ErrorBody
import ai.koog.multiverse.model.HealthBody
import ai.koog.multiverse.model.RoutingPolicyBody
import ai.koog.multiverse.model.UseCase
import ai.koog.multiverse.registry.CapabilityRegistry
import ai.koog.multiverse.routing.RoutingEngine
import ai.koog.multiverse.routing.SimulatedNetworkStatus
import ai.koog.multiverse.session.SessionManager
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComputeApiTest {

    private fun agentWithMock(): MasterAgent = MasterAgent(
        discovery = StaticConfigDiscovery.fromResource("registry.json") { 0 },
        registry = CapabilityRegistry(clock = { 0 }),
        sessionManager = SessionManager(clock = { 0 }, idGen = { "test-session" }),
        routingEngine = RoutingEngine(),
        network = SimulatedNetworkStatus(true, true),
        confidenceManager = ConfidenceManager(),
        backend = LlmBackend.Mock,
        tquicConfig = TquicConfig(),
    )

    private fun jpeg() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun computeReturnsAssessmentJson() = testApplication {
        val agent = agentWithMock()
        application { module(agent) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val resp = client.post("/v1/compute") {
            setBody(MultiPartFormDataContent(formData {
                append("query", "Identify allergens; I'm allergic to peanuts")
                append("useCase", "UC1")
                append("image", jpeg(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"food.jpg\"")
                })
            }))
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body: ComputeResponse = resp.body()
        assertEquals(UseCase.UC1, body.useCase)
        assertEquals("test-session", body.sessionId)
        assertTrue(body.answer.contains("peanut", ignoreCase = true))
        assertTrue(body.allergens.isNotEmpty())
    }

    @Test
    fun computeRejectsMissingImage() = testApplication {
        application { module(agentWithMock()) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val resp = client.post("/v1/compute") {
            setBody(MultiPartFormDataContent(formData { append("query", "hello") }))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val err: ErrorBody = resp.body()
        assertEquals("bad_request", err.error)
    }

    @Test
    fun healthReportsRegistryDevices() = testApplication {
        application { module(agentWithMock()) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val body: HealthBody = client.get("/v1/health").body()
        assertEquals("ok", body.status)
        assertEquals(2, body.registryDevices)
    }

    @Test
    fun registryDevicesReturnsFixtureDevicesWithExpectedFields() = testApplication {
        application { module(agentWithMock()) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val body: DevicesBody = client.get("/v1/registry/devices").body()
        assertEquals(2, body.devices.size)

        val remote = body.devices.single { it.deviceId == "aws-remote-01" }
        assertEquals("remote_agent", remote.role)
        assertEquals("10.0.3.2", remote.host)
        assertEquals(8443, remote.port)
        assertEquals("supported", remote.multipath)
        assertTrue(remote.models.contains("llama3.2-vision"))
        assertTrue(remote.reachable)

        val local = body.devices.single { it.deviceId == "xelite-local" }
        assertEquals("local_agent", local.role)
        assertEquals("unsupported", local.multipath)
    }

    @Test
    fun routingPolicyReflectsPreferRemoteDecisionForFixture() = testApplication {
        application { module(agentWithMock()) }
        val client = createClient { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }

        val body: RoutingPolicyBody = client.get("/v1/routing/policy").body()
        assertEquals("REMOTE", body.decisionTarget)
        assertEquals("aws-remote-01", body.decisionDeviceId)
        assertEquals(3, body.tiers.size)

        val tier1 = body.tiers.single { it.tier == 1 }
        assertTrue(tier1.candidates.single { it.deviceId == "aws-remote-01" }.selected)
    }
}
