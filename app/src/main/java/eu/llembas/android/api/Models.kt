package eu.llembas.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The server's `/api/v2` shapes. Mirrors `api/v2/serialise.py` on the server; every class here
 * is exercised against the pinned `contract/` files by `ContractTest`, so a field the
 * server renamed fails a test here rather than on a phone.
 *
 * Unknown fields are ignored: adding a field is not a new API version, and an older app must
 * keep reading a newer server.
 */
val LembasJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = false
}

@Serializable
data class Discovery(
    val service: String = "",
    val version: String = "",
    val name: String = "",
    @SerialName("base_url") val baseUrl: String = "",
    @SerialName("app_api") val appApi: Int = 0,
    val login: Login = Login(),
    val scopes: List<String> = emptyList(),
) {
    @Serializable
    data class Login(val device: DeviceLogin = DeviceLogin())

    @Serializable
    data class DeviceLogin(val code: String = "", val token: String = "", val verify: String = "")
}

@Serializable
data class DeviceCode(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String = "",
    val interval: Int = 5,
    @SerialName("expires_in") val expiresIn: Int = 600,
)

@Serializable
data class DeviceToken(
    @SerialName("access_token") val accessToken: String? = null,
    val scope: String = "",
    val error: String? = null,
    val account: Account? = null,
)

@Serializable
data class Account(val id: String = "", val name: String = "", val email: String = "")

@Serializable
data class Me(
    val api: Int = 0,
    val version: String = "",
    val account: Account = Account(),
    val language: String = "",
    val theme: String = "",
    @SerialName("default_model") val defaultModel: String? = null,
    val permissions: Map<String, Boolean> = emptyMap(),
    val services: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class ModelInfo(
    val id: String,
    val name: String = "",
    val vision: Boolean = false,
    val tools: Boolean = false,
    val efforts: List<String> = emptyList(),
    val context: Int = 0,
    val default: Boolean = false,
)

@Serializable
data class ModelList(val models: List<ModelInfo> = emptyList())

@Serializable
data class Folder(val id: String, val name: String = "", @SerialName("parent_id") val parentId: String? = null, val kind: String = "", val position: Int = 0)

@Serializable
data class FolderList(val folders: List<Folder> = emptyList())

@Serializable
data class Device(@SerialName("token_id") val tokenId: String? = null, val directory: String = "")

@Serializable
data class LinkedDevice(val id: String, val name: String = "", val host: String = "", val platform: String = "")

@Serializable
data class DeviceList(val devices: List<LinkedDevice> = emptyList())

@Serializable
data class Chat(
    val id: String,
    val kind: String = "chat",
    val title: String = "",
    val model: String = "",
    @SerialName("folder_id") val folderId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val temporary: Boolean = false,
    val unread: Boolean = false,
    val running: Boolean = false,
    val mode: String = "",
    val effort: String? = null,
    val device: Device? = null,
    @SerialName("parent_chat_id") val parentChatId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    val plan: JsonObject? = null,
) {
    val isAgent: Boolean get() = kind == "agent"
}

@Serializable
data class ChatPage(val chats: List<Chat> = emptyList(), val more: Boolean = false)

@Serializable
data class ChatDetail(val chat: Chat, val messages: List<Message> = emptyList(), val more: Boolean = false, val live: String? = null)

@Serializable
data class MessagePage(val messages: List<Message> = emptyList(), val more: Boolean = false)

@Serializable
data class Unread(val unread: List<String> = emptyList())

@Serializable
data class Attachment(
    val id: String,
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val kind: String = "",
    val image: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class AttachmentEnvelope(val attachment: Attachment)

@Serializable
data class Metrics(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("context_tokens") val contextTokens: Int = 0,
    @SerialName("context_limit") val contextLimit: Int = 0,
    val estimated: Boolean = false,
    @SerialName("elapsed_ms") val elapsedMs: Long = 0,
    val rounds: Int = 1,
    @SerialName("thinking_ms") val thinkingMs: Long = 0,
    val percent: Int = 0,
    @SerialName("tok_s") val tokPerSecond: Double = 0.0,
)

@Serializable
data class ToolBlock(val kind: String = "tool", val title: String = "", val chips: List<String> = emptyList(), val added: Int = 0, val removed: Int = 0)

/** One tool call. What the tool wrote varies by tool, so the free-form parts stay JSON. */
@Serializable
data class ToolCall(
    val id: String? = null,
    val name: String = "",
    val kind: String? = null,
    val status: String? = null,
    val args: JsonElement? = null,
    val query: String? = null,
    val purpose: String? = null,
    val why: String? = null,
    val text: String? = null,
    val error: String? = null,
    val detail: JsonElement? = null,
    val results: JsonElement? = null,
    val diff: String? = null,
    val exit: Int? = null,
    val hits: Int? = null,
    val path: String? = null,
    val truncated: Boolean = false,
    val job: JsonElement? = null,
    val children: List<ToolCall>? = null,
    val block: ToolBlock = ToolBlock(),
)

@Serializable
data class SteerNote(val id: String = "", val text: String = "")

/** One step of a reply: `thinking`, `text`, `tools` or `steer`. */
@Serializable
data class Step(
    val index: Int = 0,
    val kind: String,
    val open: Boolean = false,
    val text: String? = null,
    val label: String? = null,
    val markdown: String? = null,
    val calls: List<ToolCall> = emptyList(),
    val messages: List<SteerNote> = emptyList(),
)

@Serializable
data class Message(
    val id: String,
    val role: String,
    @SerialName("created_at") val createdAt: String? = null,
    val complete: Boolean = true,
    val stopped: Boolean = false,
    val error: String? = null,
    val queued: Boolean = false,
    val machine: Boolean = false,
    val origin: String = "",
    val model: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val text: String? = null,
    val steps: List<Step> = emptyList(),
    val plan: JsonObject? = null,
    val metrics: Metrics? = null,
) {
    val isUser: Boolean get() = role == "user"
}

@Serializable
data class Turn(val message: Message? = null, val reply: Message? = null)

@Serializable
data class NewChat(val chat: Chat, val message: Message? = null, val reply: Message? = null)

@Serializable
data class ChatEnvelope(val chat: Chat)

@Serializable
data class Option(val label: String, val description: String = "", val recommended: Boolean = false)

@Serializable
data class CardItem(
    val index: Int = 0,
    val key: String,
    val kind: String,
    val tool: String = "",
    val title: String = "",
    val header: String = "",
    val detail: String = "",
    val reason: String = "",
    val purpose: String = "",
    val options: List<Option> = emptyList(),
    val multiple: Boolean = false,
    @SerialName("free_text") val freeText: Boolean = true,
    val editable: Boolean = false,
    val plan: JsonObject? = null,
    @SerialName("mode_choice") val modeChoice: Boolean = false,
    val job: String = "",
)

@Serializable
data class Card(
    val id: String,
    val kind: String,
    val why: String = "",
    val dismissable: Boolean = false,
    @SerialName("expires_in") val expiresIn: Int? = null,
    val items: List<CardItem> = emptyList(),
)

@Serializable
data class Plan(val title: String = "", val summary: String = "", val phases: List<Phase> = emptyList(), val steps: List<String> = emptyList()) {
    @Serializable
    data class Phase(val id: String = "", val title: String = "", val status: String = "", val tasks: List<Task> = emptyList())

    @Serializable
    data class Task(val id: String = "", val text: String = "", val status: String = "todo", val note: String = "")
}

@Serializable
data class Job(
    val id: String,
    val kind: String = "",
    val state: String = "",
    val description: String = "",
    val agent: String = "",
    val seconds: Int = 0,
    val command: String = "",
)

/** The answer to a card, as the page's form says it (see `api/v2/chats.py:answer`). */
@Serializable
data class CardAnswer(
    val verdict: String = "",
    val reason: String = "",
    val choices: Map<String, List<String>> = emptyMap(),
    val texts: Map<String, String> = emptyMap(),
    val asks: Map<String, String> = emptyMap(),
)

@Serializable
data class PushKey(val key: String = "", val registered: Boolean = false)

@Serializable
data class Transcript(val text: String = "")

// --- The stream's frames -----------------------------------------------------------------
@Serializable
data class Tail(val thinking: String = "", val text: String = "", val label: String = "")

@Serializable
data class StepsFrame(val steps: List<Step> = emptyList(), val tail: Tail = Tail())

@Serializable
data class SteeredFrame(val ids: List<String> = emptyList())

@Serializable
data class StatusFrame(val text: String = "")

@Serializable
data class AskFrame(val cards: List<Card> = emptyList())

@Serializable
data class JobsFrame(val jobs: List<Job> = emptyList())

@Serializable
data class DoneFrame(val message: Message, val chat: DoneChat = DoneChat(), val next: String? = null) {
    @Serializable
    data class DoneChat(val id: String = "", val title: String = "")
}

/** The push payload the server sends (services/push.py), as the app reads it. */
@Serializable
data class PushPayload(
    val title: String = "",
    val body: String = "",
    val url: String = "",
    val kind: String = "",
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("interaction_id") val interactionId: String? = null,
    val ask: String? = null,
)
