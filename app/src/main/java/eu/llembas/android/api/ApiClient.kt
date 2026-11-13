package eu.llembas.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Where a new agent chat runs: a linked device ([LinkedDevice.id]) and a folder on it. */
data class AgentTarget(val device: String, val directory: String = "")

/** A refusal from the server, in its own words (`{"detail": "…"}`). */
class ApiException(val status: Int, message: String) : IOException(message)

private val JSON = "application/json; charset=utf-8".toMediaType()

/** One HTTP client for the whole app: one connection pool, one TLS setup. */
val SharedHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) = cont.resumeWith(Result.success(response))

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        },
    )
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** The address a person typed, as the instance's base URL: https assumed, no trailing slash. */
fun normaliseBase(typed: String): String {
    var text = typed.trim().trimEnd('/')
    if (!text.startsWith("http://") && !text.startsWith("https://")) text = "https://$text"
    return text.toHttpUrl().toString().trimEnd('/')
}

/** The calls that need no token: finding the instance and signing in. */
class Doorstep(private val http: OkHttpClient = SharedHttp) {
    suspend fun discover(base: String): Discovery {
        val response = http.newCall(Request.Builder().url("$base/.well-known/lembas.json").build()).await()
        return response.use {
            if (!it.isSuccessful) throw ApiException(it.code, "Not a LLeMbas instance (${it.code}).")
            LembasJson.decodeFromString(Discovery.serializer(), it.body!!.string())
        }
    }

    suspend fun deviceCode(url: String, deviceName: String, model: String): DeviceCode {
        val body = buildJsonObject {
            put("scope", "app models")
            put("client_id", "lembas-android")
            put("device_name", deviceName)
            put("device_host", model)
            put("device_platform", "android")
        }
        val response = http.newCall(post(url, body)).await()
        return response.use {
            if (!it.isSuccessful) throw ApiException(it.code, detail(it))
            LembasJson.decodeFromString(DeviceCode.serializer(), it.body!!.string())
        }
    }

    /** One poll. A pending login is a 400 with `authorization_pending` -- not a failure. */
    suspend fun deviceToken(url: String, deviceCode: String): DeviceToken {
        val body = buildJsonObject {
            put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            put("device_code", deviceCode)
        }
        val response = http.newCall(post(url, body)).await()
        return response.use { LembasJson.decodeFromString(DeviceToken.serializer(), it.body!!.string()) }
    }

    private fun post(url: String, body: JsonObject) = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
}

private fun agentsJson(agents: AgentsSetting) = buildJsonObject {
    put("min", agents.min)
    put("max", agents.max)
}

internal fun detail(response: Response): String {
    val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
    val said = runCatching {
        LembasJson.parseToJsonElement(text).let { it as JsonObject }["detail"]?.jsonPrimitive?.content
    }.getOrNull()
    return said ?: "The server answered ${response.code}."
}

/**
 * Everything the app asks one instance, with its token. Every call is the server's
 * `/api/v2` (or `/v1` for speech), and every refusal comes back as an [ApiException]
 * carrying the server's own sentence, which is what the app shows.
 */
class ApiClient(val base: String, private val token: String, val http: OkHttpClient = SharedHttp) {
    private val v2 = "$base/api/v2"

    fun authed(url: String): Request.Builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")

    private suspend fun <T> call(request: Request, serializer: KSerializer<T>): T = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, detail(response))
            LembasJson.decodeFromString(serializer, response.body!!.string())
        }
    }

    private suspend fun empty(request: Request) = withContext(Dispatchers.IO) {
        http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, detail(response))
        }
    }

    private suspend inline fun <reified T> get(path: String): T = call(authed("$v2$path").get().build(), serializer())

    private suspend inline fun <reified T> send(method: String, path: String, body: JsonObject?): T =
        call(authed("$v2$path").method(method, (body ?: JsonObject(emptyMap())).toString().toRequestBody(JSON)).build(), serializer())

    // --- Account -----------------------------------------------------------------------------
    suspend fun me(): Me = get("/me")

    suspend fun models(chatId: String? = null): List<ModelInfo> = modelList(chatId).models

    /** The models with the instance's subagent cap; with [chatId], whether each is offered subagents on that chat. */
    suspend fun modelList(chatId: String? = null): ModelList = get(if (chatId != null) "/models?chat=$chatId" else "/models")

    /** The models for a new agent chat on [deviceId], with whether it may have subagents (`agent_subagents`). */
    suspend fun agentModelList(deviceId: String?): ModelList =
        get("/models?kind=agent" + deviceId?.let { "&device=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty())

    suspend fun folders(): List<Folder> = get<FolderList>("/folders").folders

    /** A new folder, at the top or inside [parentId] (`folder.manage`; nesting is capped). */
    suspend fun createFolder(name: String, parentId: String? = null): Folder = send<FolderEnvelope>(
        "POST",
        "/folders",
        buildJsonObject {
            put("name", name)
            if (parentId != null) put("parent_id", parentId)
        },
    ).folder

    /** Any of `name`, `parent_id`, `collapsed`. */
    suspend fun updateFolder(id: String, changes: JsonObject): Folder = send<FolderEnvelope>("PATCH", "/folders/${segment(id)}", changes).folder

    /** The folder and the folders inside it; their chats fall back to no folder. */
    suspend fun deleteFolder(id: String) = empty(authed("$v2/folders/${segment(id)}").delete().build())

    /** The app releases this server recommends; 404 from a server older than the route. */
    suspend fun apps(): AppsInfo = get("/apps")

    /** What `@` offers from the server for [q]: the chat's earlier attachments, files of the tree, a web address. */
    suspend fun mentions(q: String, chatId: String? = null): Mentions = get("/mentions?q=${segment(q)}" + (chatId?.let { "&chat=${segment(it)}" } ?: ""))

    /**
     * Attach without uploading: a copy of an earlier attachment, a file of the tree as it is now,
     * or a web page's text -- whichever [mention] is. 404 for what cannot be reached, 400 for a
     * page that cannot be fetched.
     */
    suspend fun attachFrom(mention: Mention, chatId: String? = null): Attachment = send<AttachmentEnvelope>(
        "POST",
        "/attachments/from",
        buildJsonObject {
            when (mention.kind) {
                "attachment" -> put("attachment", mention.id)
                "url" -> put("url", mention.url)
                else -> put("path", mention.path)
            }
            chatId?.let { put("chat", it) }
        },
    ).attachment

    /** The web's search in JSON: chats by their words (and meaning, where indexed), files, memories. */
    suspend fun search(q: String, within: String = "all", limit: Int = 20): SearchResults = get("/search?q=${segment(q)}&in=${segment(within)}&limit=$limit")

    // --- Memory (`library.use`) -----------------------------------------------------------------

    /** The person's memories, every data group, and the groups they may file one in. */
    suspend fun memories(): MemoryList = get("/memories")

    suspend fun addMemory(content: String, groupId: String? = null): Memory = send<MemoryEnvelope>(
        "POST",
        "/memories",
        buildJsonObject {
            put("content", content)
            if (groupId != null) put("data_group_id", groupId)
        },
    ).memory

    suspend fun editMemory(id: String, content: String): Memory =
        send<MemoryEnvelope>("PATCH", "/memories/${segment(id)}", buildJsonObject { put("content", content) }).memory

    suspend fun forgetMemory(id: String) = empty(authed("$v2/memories/${segment(id)}").delete().build())

    suspend fun devices(): List<LinkedDevice> = get<DeviceList>("/devices").devices

    /** The token revokes itself (`lembas logout`'s route). */
    suspend fun signOut() = empty(authed("$base/api/v1/token").delete().build())

    // --- Chats -------------------------------------------------------------------------------
    suspend fun chats(kind: String = "", archived: Boolean = false, before: String? = null): ChatPage {
        val url = "$v2/chats".toHttpUrl().newBuilder()
            .addQueryParameter("archived", archived.toString())
            .apply { if (kind.isNotEmpty()) addQueryParameter("kind", kind) }
            .apply { before?.let { addQueryParameter("before", it) } }
            .build()
        return call(authed(url.toString()).get().build(), ChatPage.serializer())
    }

    suspend fun unread(): List<String> = get<Unread>("/chats/unread").unread

    suspend fun chat(id: String): ChatDetail = get("/chats/$id")

    suspend fun messages(chatId: String, before: String): MessagePage = get("/chats/$chatId/messages?before=$before")

    suspend fun createChat(
        text: String,
        attachments: List<String> = emptyList(),
        model: String? = null,
        temporary: Boolean = false,
        effort: String? = null,
        mode: String? = null,
        agent: AgentTarget? = null,
        agents: AgentsSetting? = null,
        forceTool: String? = null,
    ): NewChat = send(
        "POST",
        "/chats",
        buildJsonObject {
            put("text", text)
            put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
            model?.let { put("model", it) }
            effort?.let { put("effort", it) }
            mode?.let { put("mode", it) }
            agents?.let { put("agents", agentsJson(it)) }
            put("temporary", temporary)
            forceTool?.let { put("force_tool", it) }
            put("client", CAPABILITIES)
            // An agent chat runs on a linked device; an empty directory is the device's first
            // allowed one. A device that is no longer linked makes it an ordinary chat on the
            // server, rather than losing the message.
            agent?.let {
                put("kind", "agent")
                put("device", it.device)
                if (it.directory.isNotBlank()) put("directory", it.directory.trim())
            }
        },
    )

    /** A turn. [forceTool] makes the model call that tool (the server allows `image_generate` only). */
    suspend fun sendMessage(chatId: String, text: String, attachments: List<String> = emptyList(), forceTool: String? = null): Turn = send(
        "POST",
        "/chats/$chatId/messages",
        buildJsonObject {
            put("text", text)
            put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
            forceTool?.let { put("force_tool", it) }
            put("client", CAPABILITIES)
        },
    )

    suspend fun updateChat(chatId: String, changes: JsonObject): Chat = send<ChatEnvelope>("PATCH", "/chats/$chatId", changes).chat

    /** The team's size, both ends at once: the server checks the pair (422 outside its bounds). */
    suspend fun setAgents(chatId: String, agents: AgentsSetting): Chat = updateChat(chatId, buildJsonObject { put("agents", agentsJson(agents)) })

    suspend fun deleteChat(chatId: String) = empty(authed("$v2/chats/$chatId").delete().build())

    suspend fun stop(chatId: String, messageId: String) =
        empty(authed("$v2/chats/$chatId/messages/$messageId/stop").post(ByteArray(0).toRequestBody(null)).build())

    suspend fun edit(chatId: String, messageId: String, text: String): Turn =
        send("POST", "/chats/$chatId/messages/$messageId/edit", buildJsonObject { put("text", text) })

    suspend fun regenerate(chatId: String, messageId: String): Turn = send("POST", "/chats/$chatId/messages/$messageId/regenerate", null)

    suspend fun continueChat(chatId: String): Turn = send("POST", "/chats/$chatId/continue", null)

    suspend fun executePlan(chatId: String): Turn = send("POST", "/chats/$chatId/plan/execute", null)

    suspend fun answer(chatId: String, cardId: String, answer: CardAnswer) {
        val body = LembasJson.encodeToString(CardAnswer.serializer(), answer).toRequestBody(JSON)
        empty(authed("$v2/chats/$chatId/interactions/$cardId").post(body).build())
    }

    // --- The activity panel, background agents and the team -----------------------------------

    /** Everything the panel shows; `context = false` leaves out the costly context count. */
    suspend fun activity(chatId: String, context: Boolean = true): Activity = get<ActivityEnvelope>("/chats/$chatId/activity?context=$context").activity

    /** The events route: an `activity` frame whenever the panel's facts change. */
    fun activityEvents(chatId: String): Request = authed("$v2/chats/$chatId/activity/events").header("Accept", "text/event-stream").build()

    /** One background agent and its whole conversation, read-only. `kind` is `member` or `job`. */
    suspend fun agent(chatId: String, kind: String, id: String): AgentDetail = get("/chats/$chatId/agents/${segment(kind)}/${segment(id)}")

    /** The chat's subagent tree and room (spec 3.4.0); a 404 from a server without it. */
    suspend fun tree(chatId: String): TreeView = get("/chats/$chatId/tree")

    /** A subagent's conversation by its id (`s1`), read-only; 409 while an agent chat's device is away. */
    suspend fun subagentTranscript(chatId: String, id: String): SubagentTranscript = get("/chats/$chatId/subagents/${segment(id)}/transcript")

    /** Stop a background agent (a member leaves the team with those under it). */
    suspend fun stopAgent(chatId: String, kind: String, id: String): List<String> =
        send<Stopped>("POST", "/chats/$chatId/agents/${segment(kind)}/${segment(id)}/stop", null).stopped

    /**
     * Summarise the earlier turns now and stop sending them (the activity sheet's Compact). The
     * server refuses in its own words (409) while a reply is being written or with nothing to
     * summarise; the transcript is read again after, for the divider it leaves.
     */
    suspend fun compact(chatId: String) = empty(authed("$v2/chats/$chatId/compact").post(ByteArray(0).toRequestBody(null)).build())

    suspend fun team(chatId: String, since: Int = 0): TeamView = get<TeamEnvelope>("/chats/$chatId/team?since=$since").team

    suspend fun stopMember(chatId: String, name: String): List<String> = send<Stopped>("POST", "/chats/$chatId/team/${segment(name)}/stop", null).stopped

    // --- Scheduled tasks (`/api/v2/schedules`; shapes in api/Schedules.kt) ----------------------

    /** Every schedule of this person, newest first, each with its last run. */
    suspend fun schedules(): List<Schedule> = get<ScheduleList>("/schedules").schedules

    suspend fun schedule(id: String): Schedule = get<ScheduleEnvelope>("/schedules/${segment(id)}").schedule

    /** A page of a schedule's runs, newest first; [before] is the last run's id of the page before. */
    suspend fun scheduleRuns(id: String, before: String? = null, limit: Int = 20): ScheduleRuns =
        get("/schedules/${segment(id)}/runs?limit=$limit" + (before?.let { "&before=${segment(it)}" } ?: ""))

    /** One run and its result in full (Markdown a model wrote: untrusted). */
    suspend fun scheduleRun(id: String, runId: String): RunResult = get("/schedules/${segment(id)}/runs/${segment(runId)}")

    /** Words to a schedule: title, instruction, rule, the rule in words, the next runs. Saves nothing. */
    suspend fun compileSchedule(words: String, model: String? = null): ScheduleCompiled = send(
        "POST",
        "/schedules/compile",
        buildJsonObject {
            put("text", words)
            model?.let { put("model", it) }
        },
    )

    /** A rule read back: cleaned, in words, and its next runs. An empty rule cannot run. */
    suspend fun previewRule(rule: JsonObject): RulePreview = send("POST", "/schedules/preview", buildJsonObject { put("rule", rule) })

    /**
     * Save what the compile made of [words]: a new schedule (201), or with [id] the one being
     * edited. The words go along as the schedule's `request`, so editing starts from them.
     */
    suspend fun saveSchedule(compiled: ScheduleCompiled, words: String, id: String? = null, notify: String? = null, notifyWhen: String = ""): Schedule {
        val body = buildJsonObject {
            put("title", compiled.title)
            put("instruction", compiled.instruction)
            put("rule", compiled.rule)
            put("target", compiled.target)
            if (id == null) put("request", words)
            // Notify when (spec 3.8.0): left out where the form did not say, so an older server is sent nothing new.
            if (notify != null) {
                put("notify", notify)
                // The person's own words, or none: an empty condition is the server's default, in its own words.
                if (notify == "when") put("notify_when", notifyWhen.trim())
            }
        }
        return if (id == null) {
            send<ScheduleEnvelope>("POST", "/schedules", body).schedule
        } else {
            send<ScheduleEnvelope>("PATCH", "/schedules/${segment(id)}", body).schedule
        }
    }

    // --- Reports (`schedule.use`) ---------------------------------------------------------------

    /** The reports, newest first; [unread] only those not read yet, [schedule] only one schedule's. */
    suspend fun reports(unread: Boolean = false, schedule: String? = null): List<Report> {
        val asked = listOfNotNull(if (unread) "unread=1" else null, schedule?.let { "schedule=" + segment(it) }).joinToString("&")
        return get<ReportList>(if (asked.isEmpty()) "/reports" else "/reports?" + asked).reports
    }

    /** How many reports are unread: the drawer's badge. */
    suspend fun unreadReports(): Int = get<ReportList>("/reports?unread=1").unreadCount

    /** One report with its body. Read as the report itself, or as `{report: …}` around it. */
    suspend fun report(id: String): Report {
        val answer = get<JsonObject>("/reports/${segment(id)}")
        return LembasJson.decodeFromJsonElement(Report.serializer(), answer["report"] as? JsonObject ?: answer)
    }

    suspend fun readReport(id: String) = empty(authed("$v2/reports/${segment(id)}/read").post("{}".toRequestBody(JSON)).build())

    suspend fun deleteReport(id: String) = empty(authed("$v2/reports/${segment(id)}").delete().build())

    /** Continue the report as a conversation: the chat it is in, which stays under Reports. */
    suspend fun replyToReport(id: String): String? = send<ReportReply>("POST", "/reports/${segment(id)}/reply", null).chatId

    suspend fun pauseSchedule(id: String, paused: Boolean): Schedule =
        send<ScheduleEnvelope>("POST", "/schedules/${segment(id)}/" + if (paused) "pause" else "resume", null).schedule

    /** Run it now (202); the next scheduled run is not used up by this one. */
    suspend fun runScheduleNow(id: String): ScheduleRun = send<RunEnvelope>("POST", "/schedules/${segment(id)}/run", null).run

    /** Delete it; [keepChat] keeps the chat its results went to. */
    suspend fun deleteSchedule(id: String, keepChat: Boolean = true) = empty(authed("$v2/schedules/${segment(id)}?keep_chat=$keepChat").delete().build())

    /** The models scheduled work may run on, in the order they are tried, and which are free. */
    suspend fun schedulingModels(): SchedulingModels = get("/schedules/models")

    /** The person's narrowing of that list ([ids] empty: all of them). */
    suspend fun chooseSchedulingModels(ids: List<String>): SchedulingModels =
        send("PUT", "/schedules/models", buildJsonObject { put("models", JsonArray(ids.map { JsonPrimitive(it) })) })

    /** The person's events route: a `schedules` frame with the whole list whenever it changes. */
    fun events(): Request = authed("$v2/events").header("Accept", "text/event-stream").build()

    // --- Model pictures --------------------------------------------------------------------------

    /**
     * A model's picture, with the token: only a path under the instance's own
     * `/api/v2/models/images/` is fetched -- a list that named anything else, another host
     * included, gets the initial instead. At most [IMAGE_MAX_BYTES]; null when there is none.
     */
    suspend fun modelImage(path: String): ByteArray? = withContext(Dispatchers.IO) {
        val url = modelImageUrl(base, path) ?: return@withContext null
        http.newCall(authed(url).header("Accept", "image/*").get().build()).await().use {
            if (!it.isSuccessful) return@use null
            val body = it.body ?: return@use null
            if (body.contentLength() > IMAGE_MAX_BYTES) return@use null
            val bytes = body.source().use { source ->
                source.request(IMAGE_MAX_BYTES + 1)
                source.buffer.readByteArray(minOf(source.buffer.size, IMAGE_MAX_BYTES + 1))
            }
            bytes.takeIf { b -> b.size <= IMAGE_MAX_BYTES }
        }
    }

    // --- Attachments -------------------------------------------------------------------------
    suspend fun upload(name: String, mime: String, bytes: ByteArray, chatId: String? = null): Attachment {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType()))
            .build()
        val url = "$v2/attachments" + (chatId?.let { "?chat=$it" } ?: "")
        return call(authed(url).post(body).build(), AttachmentEnvelope.serializer()).attachment
    }

    suspend fun attachmentBytes(id: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(authed("$v2/attachments/$id").get().build()).await().use {
            if (!it.isSuccessful) throw ApiException(it.code, detail(it))
            it.body!!.bytes()
        }
    }

    // --- What a `show` card opens -------------------------------------------------------------

    /**
     * The text of a file a `show` card named, as the chat sees it now:
     * `GET /api/v2/chats/{chat}/show?node=<node_id>&version=<version_id>` for a file in the tree,
     * `?shown=<shown_id>` for the copy a linked device sent ([ShowCard.query]). The server rebuilds
     * the chat's view and checks the path again, so a grant taken away since takes the file away
     * too (404, in the server's words). `text/plain; charset=utf-8`, at most 1 MB: above it a 413,
     * and a 415 for a file that is not text. Always plain text -- an HTML file is its source, and
     * the app never asks for a page to render.
     */
    suspend fun shownText(chatId: String, show: ShowCard): String = withContext(Dispatchers.IO) {
        val url = "$v2/chats/$chatId/show".toHttpUrl().newBuilder()
            .apply { show.query.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        http.newCall(authed(url.toString()).header("Accept", "text/plain").get().build()).await().use {
            if (!it.isSuccessful) throw ApiException(it.code, detail(it))
            val body = it.body!!
            // The server shows at most 1 MB; anything much larger is not a file it meant.
            if (body.contentLength() > SHOWN_MAX_BYTES) throw ApiException(413, "")
            val bytes = body.source().use { source ->
                source.request(SHOWN_MAX_BYTES + 1)
                source.buffer.readByteArray(minOf(source.buffer.size, SHOWN_MAX_BYTES + 1))
            }
            if (bytes.size > SHOWN_MAX_BYTES) throw ApiException(413, "")
            bytes.toString(Charsets.UTF_8)
        }
    }

    // --- The person's files ------------------------------------------------------------------

    /** A directory, as the person may see it (no chat's grants): `GET /api/v2/files?path=`. */
    suspend fun files(path: String = "/"): FileListing {
        val url = "$v2/files".toHttpUrl().newBuilder().addQueryParameter("path", path).build()
        return call(authed(url.toString()).get().build(), FileListing.serializer())
    }

    /**
     * A file's text: `GET /api/v2/files/content?path=`. A file the server will not hand over as
     * text (not text, over 1 MB) is not an error: it comes back with `withheld` saying why.
     */
    suspend fun fileContent(path: String): FileContent {
        val url = "$v2/files/content".toHttpUrl().newBuilder().addQueryParameter("path", path).build()
        return call(authed(url.toString()).get().build(), FileContent.serializer())
    }

    /** A file of the tree in the web UI's own viewer (`/files/view`), for what the app does not draw. */
    fun webFile(path: String): String = "$base/files/view".toHttpUrl().newBuilder().addQueryParameter("path", path).build().toString()

    /** The same file in the web UI's viewer, which renders HTML in its sealed frame. */
    fun webViewer(chatId: String, show: ShowCard): String = "$base/chat/$chatId/show".toHttpUrl().newBuilder()
        .apply { show.query.forEach { (k, v) -> addQueryParameter(k, v) } }
        .apply { if (show.line > 0) addQueryParameter("line", show.line.toString()) }
        .build()
        .toString()

    // --- Push --------------------------------------------------------------------------------
    suspend fun pushKey(): PushKey = get("/push")

    suspend fun registerPush(endpoint: String, p256dh: String, auth: String) {
        val body = buildJsonObject {
            put("endpoint", endpoint)
            put("p256dh", p256dh)
            put("auth", auth)
        }
        empty(authed("$v2/push").post(body.toString().toRequestBody(JSON)).build())
    }

    suspend fun forgetPush() = empty(authed("$v2/push").delete().build())

    // --- Voice (/v1, the `models` scope) -----------------------------------------------------
    suspend fun transcribe(wav: ByteArray, language: String? = null): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "speech.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .apply { language?.let { addFormDataPart("language", it) } }
            .build()
        return call(authed("$base/v1/audio/transcriptions").post(body).build(), Transcript.serializer()).text
    }

    suspend fun speech(text: String): ByteArray = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("input", text.take(20_000))
            put("response_format", "mp3")
        }
        http.newCall(authed("$base/v1/audio/speech").post(body.toString().toRequestBody(JSON)).build()).await().use {
            if (!it.isSuccessful) throw ApiException(it.code, detail(it))
            it.body!!.bytes()
        }
    }

    fun streamRequest(chatId: String, messageId: String): Request =
        authed("$v2/chats/$chatId/messages/$messageId/stream").header("Accept", "text/event-stream").build()

    companion object {
        /**
         * What this app can do, sent with every message (spec 3.6.0): the phone carries out
         * `phone_action` calls the person confirms here, so the chat is offered the tool.
         */
        private val CAPABILITIES = JsonArray(listOf(JsonPrimitive("phone_actions")))

        /** 1 MB, the server's own limit on a shown file, with room for a little more. */
        const val SHOWN_MAX_BYTES = 1_100_000L

        fun emptyBody(): RequestBody = ByteArray(0).toRequestBody(null)

        /** A model's picture is small; anything larger is not one. */
        const val IMAGE_MAX_BYTES = 2_000_000L

        private const val IMAGE_PATH = "/api/v2/models/images/"

        /** The picture's URL on [base], or null for a path that is not one of the instance's pictures. */
        fun modelImageUrl(base: String, path: String?): String? {
            if (path.isNullOrBlank() || !path.startsWith(IMAGE_PATH)) return null
            val name = path.removePrefix(IMAGE_PATH)
            if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' } || name.startsWith(".")) return null
            return "$base$IMAGE_PATH$name"
        }

        /** One path segment, escaped: an agent's name or a job's id is a model's choice. */
        fun segment(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
