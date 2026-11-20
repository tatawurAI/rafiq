package ai.tatawur.rafiq.protocol

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

class EventSerializationTest {
    private fun event(payload: EventPayload) =
        Event(sessionId = "s-1", seq = 7, timestampMs = 1_767_225_600_000, payload = payload)

    private fun decode(json: String): Event = protocolJson.decodeFromString(Event.serializer(), json)

    private fun encode(event: Event): String = protocolJson.encodeToString(Event.serializer(), event)

    @Test
    fun message_event_has_the_documented_wire_shape() {
        val event =
            event(
                EventPayload.Message(
                    id = "m-1",
                    threadId = "t-1",
                    role = MessageRole.AGENT,
                    content = listOf(ContentBlock.Text("Tests pass."), ContentBlock.Markdown("**Done**")),
                    isStreaming = true,
                ),
            )

        val expected =
            """{"session_id":"s-1","seq":7,"ts":1767225600000,"payload":{"type":"message","id":"m-1",""" +
                """"thread_id":"t-1","role":"agent","content":[{"type":"text","text":"Tests pass."},""" +
                """{"type":"markdown","markdown":"**Done**"}],"streaming":true}}"""
        assertEquals(expected, encode(event))
    }

    private val session =
        Session(
            id = "s-1",
            agent = "example-agent",
            projectPath = "/srv/projects/weather-app",
            title = "Add hourly forecast",
            status = AgentState.WORKING,
            createdAtMs = 1_767_225_000_000,
            updatedAtMs = 1_767_225_600_000,
        )

    // One payload of every kind, with every optional field set.
    private val oneOfEachKind =
        listOf(
            EventPayload.SessionUpdated(session),
            EventPayload.ThreadUpdated(
                Thread(
                    id = "t-2",
                    sessionId = "s-1",
                    parentThreadId = "t-1",
                    kind = ThreadKind.SUBAGENT,
                    title = "Search the API docs",
                    status = AgentState.DONE,
                ),
            ),
            EventPayload.Message(
                id = "m-1",
                threadId = "t-1",
                role = MessageRole.USER,
                content = listOf(ContentBlock.Image("data:image/png;base64,AAAA", "image/png", "A chart")),
            ),
            EventPayload.MessageDelta(messageId = "m-1", threadId = "t-1", text = " more"),
            EventPayload.ToolCall(
                id = "c-1",
                threadId = "t-1",
                kind = ToolCallKind.EDIT,
                inputSummary = "Edit src/forecast.kt",
                output = "1 file changed",
                status = ToolCallStatus.COMPLETED,
            ),
            EventPayload.ApprovalRequest(
                id = "a-1",
                toolCallId = "c-2",
                description = "Run the test suite",
                options =
                    listOf(
                        ApprovalOption("o-1", ApprovalOptionKind.ALLOW_ONCE, "Allow once"),
                        ApprovalOption("o-2", ApprovalOptionKind.ALLOW_ALWAYS, "Always allow"),
                        ApprovalOption("o-3", ApprovalOptionKind.DENY, "Deny"),
                    ),
                expiresAtMs = 1_767_225_900_000,
            ),
            EventPayload.StatusChanged(
                threadId = "t-1",
                state = AgentState.WAITING_ON_YOU,
                detail = "Needs approval",
            ),
            EventPayload.AuthRequired(
                id = "au-1",
                provider = "example-provider",
                kind = AuthKind.DEVICE_CODE,
                url = "https://example.com/device",
                code = "ABCD-1234",
            ),
            EventPayload.PreviewAvailable(
                id = "p-1",
                url = "https://server.example.ts.net:3000",
                label = "docs-site",
                port = 3000,
            ),
            EventPayload.FileChanged(
                path = "src/forecast.kt",
                changeType = FileChangeKind.MODIFIED,
                diffRef = "d-1",
                gitStatus = " M",
            ),
        )

    // A kind missing from the known set, or a changed wire name, would decode to Unknown here
    // and fail the comparison.
    @Test
    fun every_payload_kind_survives_a_round_trip() {
        for (payload in oneOfEachKind) {
            val original = event(payload)
            assertEquals(original, decode(encode(original)), "round trip of ${payload::class.simpleName}")
        }
    }

    // Keeps the round-trip list complete: adding a payload kind without a sample fails here.
    @Test
    fun round_trip_samples_cover_every_known_payload_kind() {
        val knownTypes =
            EventPayload.Known
                .serializer()
                .descriptor
                .getElementDescriptor(1)
                .elementNames
                .toSet()
        val sampledTypes =
            oneOfEachKind
                .map { protocolJson.encodeToJsonElement(EventPayloadSerializer, it).jsonObject.getValue("type") }
                .map { it.jsonPrimitive.content }
                .toSet()

        assertEquals(knownTypes, sampledTypes)
    }

    @Test
    fun unknown_payload_type_decodes_to_unknown_and_re_encodes_unchanged() {
        val json = """{"session_id":"s-1","seq":7,"ts":1767225600000,"payload":{"type":"plan_updated","steps":3}}"""

        val event = decode(json)

        assertEquals(EventPayload.Unknown("plan_updated", buildJsonObject { put("steps", 3) }), event.payload)
        assertEquals(json, encode(event))
    }

    @Test
    fun unknown_content_block_type_decodes_to_unknown() {
        val json =
            """{"session_id":"s-1","seq":1,"ts":0,"payload":{"type":"message","id":"m-1","thread_id":"t-1",""" +
                """"content":[{"type":"audio","uri":"a.ogg"}]}}"""

        val message = decode(json).payload as EventPayload.Message

        assertEquals(
            listOf(ContentBlock.Unknown("audio", JsonObject(mapOf("uri" to JsonPrimitive("a.ogg"))))),
            message.content,
        )
    }

    @Test
    fun unknown_fields_are_ignored() {
        val json =
            """{"session_id":"s-1","seq":2,"ts":0,"origin":"elsewhere",""" +
                """"payload":{"type":"message_delta","message_id":"m-1","thread_id":"t-1","text":"hi","tokens":1}}"""

        assertEquals(EventPayload.MessageDelta("m-1", "t-1", "hi"), decode(json).payload)
    }

    @Test
    fun unknown_enum_value_falls_back_to_the_default() {
        val json = """{"session_id":"s-1","seq":3,"ts":0,"payload":{"type":"status_changed","state":"sleeping"}}"""

        assertEquals(EventPayload.StatusChanged(state = AgentState.UNKNOWN), decode(json).payload)
    }
}
