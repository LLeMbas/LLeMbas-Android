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
    /** The device link's protocol: 3 from LLeMbas 2.0 on, which is what this app needs. */
    val protocol: Int = 0,
    val protocols: List<Int> = emptyList(),
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
    /** Whether the model's server stops thinking when asked: only then is `off` a level. */
    @SerialName("thinking_switch") val thinkingSwitch: Boolean = false,
    /** The levels a chat offers, in order (harness spec 3.2.0): `off` first when switchable. */
    val levels: List<String> = emptyList(),
    /** The level a chat starts at when none was chosen. */
    val effort: String = "",
    /** How the level is chosen: `none`, `switch` (Thinking on or off) or `slider`. */
    val control: String = "",
    /** How many requests the model's server answers at once; null for no stated limit. */
    val parallel: Int? = null,
    val context: Int = 0,
    /** The picture's path on the instance (`/api/v2/models/images/…`), read with the token. */
    val image: String? = null,
    val default: Boolean = false,
    /** Whether a new chat on this model is offered subagents (server 2.0.0-beta.7); null from an older one. */
    val subagents: SubagentsOffer? = null,
) {
    /**
     * The levels, for a server that says them; an older one said only `efforts`, offered after
     * an `off` that meant "the model's default" -- as the app showed it before.
     */
    val offered: List<String> get() = levels.ifEmpty { if (efforts.isEmpty()) emptyList() else listOf("off") + efforts }

    /** Which control the composer draws, by the server's word or, from an older one, by the count. */
    val levelControl: String
        get() = control.takeIf { it in CONTROLS } ?: when {
            offered.size <= 1 -> "none"
            offered.size == 2 && "off" in offered -> "switch"
            else -> "slider"
        }

    /** The level in force: [chosen] when this model offers it, else its start level. */
    fun level(chosen: String?): String =
        chosen?.takeIf { it in offered } ?: effort.takeIf { it in offered } ?: offered.firstOrNull { it != "off" } ?: offered.firstOrNull().orEmpty()

    /** For the Thinking switch: the level that is "on" (the one that is not `off`). */
    val onLevel: String get() = offered.firstOrNull { it != "off" } ?: effort

    /**
     * Whether a new chat on this model is offered subagents: the server's word
     * (`subagents.offered`) when it says it. An older server did not, and then the app guesses
     * from the model alone: it serves two requests at once (`parallel` ≥ 2, or no stated
     * limit) and takes tools.
     */
    val mayHaveTeam: Boolean get() = subagents?.offered ?: (tools && (parallel == null || parallel >= 2))

    companion object {
        val CONTROLS = setOf("none", "switch", "slider")
    }
}

@Serializable
data class ModelList(
    val models: List<ModelInfo> = emptyList(),
    /** The instance's cap on a chat's subagents (server 2.0.0-beta.7); null from an older one. */
    @SerialName("subagents_cap") val subagentsCap: Int? = null,
)

/**
 * Whether subagents are offered (harness spec 3.3.0, `subagents.md` §4): [offered] when
 * subagents are on and at least one model could serve; [namedOnly] when only a model the person
 * names in the chat could -- the chat's own serves one request at a time -- and the setting then
 * says so. [cap] is the instance's most, on a chat (not on a model).
 */
@Serializable
data class SubagentsOffer(val offered: Boolean = false, @SerialName("named_only") val namedOnly: Boolean = false, val cap: Int? = null)

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
    /** An agent chat's session on its device (`lembas -s <id>` continues it); null until opened. */
    @SerialName("device_session_id") val deviceSessionId: String? = null,
    @SerialName("parent_chat_id") val parentChatId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    val plan: JsonObject? = null,
    /** The team's size: how many agents, its own included, the chat may have. */
    val agents: AgentsSetting? = null,
    /** Whether this chat is offered subagents, and the cap (server 2.0.0-beta.7); null from an older one. */
    val subagents: SubagentsOffer? = null,
) {
    val isAgent: Boolean get() = kind == "agent"
}

/** A chat's `agents {min, max}` (harness spec 3.2.0): `1 ≤ min ≤ max ≤` the instance's cap. */
@Serializable
data class AgentsSetting(val min: Int = 1, val max: Int = 4) {
    companion object {
        /** The spec's default cap; an instance may set it lower, and then refuses past it (422). */
        const val CAP = 32
    }
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
    /** The `show` tool's card: a file put in front of the person. Never its text. */
    val show: ShowCard? = null,
    val block: ToolBlock = ToolBlock(),
)

/**
 * What a `show` call put in front of the person (`event["show"]`, harness spec 3.0.0): the
 * file's name and where it is, never its contents -- the viewer fetches those. A file from the
 * chat's own tree names its node and version; a file a linked device sent names the copy the
 * server kept (`shown_id`), with the tree's two ids empty.
 */
@Serializable
data class ShowCard(
    val path: String = "",
    val name: String = "",
    /** `markdown`, `code`, `text` or `html`; anything else is read as `text`. */
    val kind: String = "text",
    val language: String = "",
    val line: Int = 0,
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("version_id") val versionId: String = "",
    val sha: String = "",
    val size: Long = 0,
    val mime: String = "",
    val source: String = "",
    @SerialName("shown_id") val shownId: String = "",
) {
    /** The kind the viewer draws: an unknown one is plain text, never something richer. */
    val viewKind: String get() = if (kind in VIEW_KINDS) kind else "text"

    /** Which file, as the server's viewer routes take it: the device's copy, or node and version. */
    val query: List<Pair<String, String>>
        get() = if (shownId.isNotEmpty()) listOf("shown" to shownId) else listOf("node" to nodeId, "version" to versionId)

    companion object {
        val VIEW_KINDS = setOf("markdown", "code", "text", "html")
    }
}

// --- The person's files (`GET /files`, `GET /files/content`) ----------------------------------

/** One entry of a directory: `kind` is `file`, `dir` or `mount`. */
@Serializable
data class FileEntry(
    val name: String = "",
    val path: String = "",
    val kind: String = "file",
    val size: Long = 0,
    val mime: String = "",
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** A directory or a mount: something to open as a listing, not in the viewer. */
    val isFolder: Boolean get() = kind == "dir" || kind == "mount"
}

/** A directory, through the person's own access. `parent` is null at `/`. */
@Serializable
data class FileListing(val path: String = "/", val parent: String? = null, val entries: List<FileEntry> = emptyList())

/** Why a file's text did not come: `reason` is `not_text` or `too_large`; `message` says it. */
@Serializable
data class Withheld(val reason: String = "", val message: String = "")

/**
 * A file's text, as the explorer's viewer reads it. An HTML file is its source, never rendered;
 * a file that is not text or is over 1 MB has an empty [text] and says why in [withheld].
 */
@Serializable
data class FileContent(
    val path: String = "",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val kind: String = "text",
    val language: String = "",
    val sha: String = "",
    val text: String = "",
    val withheld: Withheld? = null,
) {
    /** The kind the viewer draws, by the `show` card's rule: anything unknown is plain text. */
    val viewKind: String get() = if (kind in ShowCard.VIEW_KINDS) kind else "text"
}

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
    /** The tree subagent asking (spec 3.4.0), for a call nobody in the tree may allow; null otherwise. */
    val subagent: CardSubagent? = null,
)

@Serializable
data class CardSubagent(val id: String = "", val name: String = "", val position: String = "")

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

/**
 * The `status` frame: the free [text] every server sends, and beside it from harness spec 3.4.0
 * the structured [progress] (`progress.md`) -- null when the session is doing nothing, or from
 * an older server. A client that reads [progress] draws it and ignores [text].
 */
@Serializable
data class StatusFrame(val text: String = "", val progress: Progress? = null)

/** The `tree` frame (spec 3.4.0): the subagent tree, once there is one, whenever it changes. */
@Serializable
data class TreeFrame(val tree: SubagentTree? = null)

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
    /**
     * A scheduled run's result or failure (`kind` `schedule` or `schedule_failed`,
     * services/schedule/notify.py): the ids, the run's [status], `private` [visibility] (the
     * text stays off the lock screen), the [actions] the notification may offer (`open`,
     * `pause`), and [ask] `pause` when it failed so often that it asks whether to pause it.
     */
    @SerialName("schedule_id") val scheduleId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val status: String? = null,
    val visibility: String? = null,
    val actions: List<String> = emptyList(),
)

// --- The activity panel (`GET /chats/{chat}/activity`) -------------------------------------

/** How many background agents are in each state, and the three sums the strip shows. */
@Serializable
data class ActivitySummary(
    val queued: Int = 0,
    val working: Int = 0,
    val waiting: Int = 0,
    val done: Int = 0,
    val stopped: Int = 0,
    val error: Int = 0,
    val active: Int = 0,
    val finished: Int = 0,
    val total: Int = 0,
)

/** An agent's latest step: the tool it called and on what. */
@Serializable
data class AgentStep(val tool: String = "", val label: String = "", val title: String = "", val error: Boolean = false, val running: Boolean = false)

/**
 * One background agent: a team's member (`kind` member, [id] its name) or a job (`kind` job;
 * [type] `subagent` or `command`), with its own subagents in [children]. [role], [brief] and
 * [text] are a model's words: shown as text, never as anything richer.
 */
@Serializable
data class ActivityAgent(
    val kind: String = "",
    val id: String = "",
    val type: String = "",
    val name: String = "",
    val role: String = "",
    val brief: String = "",
    val state: String = "",
    val seconds: Int = 0,
    val started: String = "",
    val model: String = "",
    val agent: String = "",
    val manager: String? = null,
    val depth: Int = 0,
    val lead: Boolean = false,
    val step: AgentStep? = null,
    val text: String = "",
    val steps: Int = 0,
    val tools: Int = 0,
    val stoppable: Boolean = false,
    val transcript: Boolean = false,
    val children: List<ActivityAgent> = emptyList(),
) {
    /** Still to come back: queued, working or waiting. */
    val isActive: Boolean get() = state in ACTIVE

    companion object {
        val ACTIVE = setOf("queued", "working", "waiting")
    }
}

/** One message in a team's room. [text] is a model's: rendered as untrusted markdown. */
@Serializable
data class RoomMessage(
    val n: Int = 0,
    val from: String = "",
    val to: List<String> = emptyList(),
    val text: String = "",
    val report: Boolean = false,
    val created: String = "",
)

@Serializable
data class ActivityTeam(
    val min: Int = 1,
    val max: Int = 1,
    val size: Int = 0,
    val goal: String = "",
    val room: List<RoomMessage> = emptyList(),
    val cursor: Int = 0,
)

/** One category of the context: `messages`, `system`, `memory`, … and `free`. */
@Serializable
data class ContextPart(val key: String = "", val tokens: Int = 0, val percent: Double? = null)

@Serializable
data class ContextUse(
    val used: Int = 0,
    val limit: Int = 0,
    val percent: Double? = null,
    val measured: Boolean = false,
    val threshold: Int = 0,
    /**
     * The compaction facts (server 2.0.0-beta.7): whether the chat compacts by itself, how many
     * tokens before it does (null: never, or not known), and whether Compact can run now.
     * Null from an older server; the app then works the first two out from [threshold].
     */
    @SerialName("auto_compact") val autoCompact: Boolean? = null,
    @SerialName("until_compact") val untilCompact: Int? = null,
    val compactable: Boolean? = null,
    @SerialName("tools_count") val toolsCount: Int = 0,
    @SerialName("mcp_count") val mcpCount: Int = 0,
    val categories: List<ContextPart> = emptyList(),
)

/** A file the chat or its agents wrote: lines added and removed when known. */
@Serializable
data class Output(
    val path: String = "",
    val name: String = "",
    val writes: Int = 0,
    val created: Boolean = false,
    val added: Int? = null,
    val removed: Int? = null,
    val at: String = "",
)

/** Everything the activity panel shows. [version] changes whenever any of it but the elapsed time does. */
@Serializable
data class Activity(
    val version: String = "",
    val summary: ActivitySummary = ActivitySummary(),
    val agents: List<ActivityAgent> = emptyList(),
    val team: ActivityTeam? = null,
    @SerialName("team_offered") val teamOffered: Boolean = false,
    /** The same fact by the word a person reads (server 2.0.0-beta.7). */
    @SerialName("subagents_offered") val subagentsOffered: Boolean? = null,
    val context: ContextUse? = null,
    val outputs: List<Output> = emptyList(),
    val plan: JsonObject? = null,
)

@Serializable
data class ActivityEnvelope(val activity: Activity)

/** The events route's frame: what changed is fetched with `GET /activity`. */
@Serializable
data class ActivityFrame(val version: String = "", val summary: ActivitySummary = ActivitySummary())

/** A tool call in an agent's conversation: its name and what it was on. */
@Serializable
data class TurnTool(val name: String = "", val title: String = "", val isError: Boolean = false)

/** One turn of a background agent's conversation. `machine`: a message the system gave it. */
@Serializable
data class AgentTurn(
    val role: String = "",
    val text: String = "",
    val machine: Boolean = false,
    val tools: List<TurnTool> = emptyList(),
    val error: String = "",
    val running: Boolean = false,
)

@Serializable
data class AgentDetail(val agent: ActivityAgent, val transcript: List<AgentTurn> = emptyList())

@Serializable
data class Stopped(val stopped: List<String> = emptyList())

/** One agent of a team's tree (`GET /chats/{chat}/team`). */
@Serializable
data class TeamAgent(
    val name: String = "",
    val manager: String? = null,
    val role: String = "",
    val state: String = "",
    val model: String = "",
    val agent: String = "",
    val lead: Boolean = false,
    val depth: Int = 0,
)

@Serializable
data class TeamView(
    val offered: Boolean = false,
    val min: Int = 1,
    val max: Int = 1,
    val size: Int = 0,
    val parallel: Int? = null,
    val goal: String = "",
    val agents: List<TeamAgent> = emptyList(),
    val room: List<RoomMessage> = emptyList(),
    val cursor: Int = 0,
)

@Serializable
data class TeamEnvelope(val team: TeamView)
