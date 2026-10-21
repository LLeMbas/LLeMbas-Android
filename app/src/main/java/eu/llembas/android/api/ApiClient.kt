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

    suspend fun models(chatId: String? = null): List<ModelInfo> = get<ModelList>(if (chatId != null) "/models?chat=$chatId" else "/models").models

    suspend fun folders(): List<Folder> = get<FolderList>("/folders").folders

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
    ): NewChat = send(
        "POST",
        "/chats",
        buildJsonObject {
            put("text", text)
            put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
            model?.let { put("model", it) }
            effort?.let { put("effort", it) }
            mode?.let { put("mode", it) }
            put("temporary", temporary)
        },
    )

    suspend fun sendMessage(chatId: String, text: String, attachments: List<String> = emptyList()): Turn = send(
        "POST",
        "/chats/$chatId/messages",
        buildJsonObject {
            put("text", text)
            put("attachments", JsonArray(attachments.map { JsonPrimitive(it) }))
        },
    )

    suspend fun updateChat(chatId: String, changes: JsonObject): Chat = send<ChatEnvelope>("PATCH", "/chats/$chatId", changes).chat

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
        fun emptyBody(): RequestBody = ByteArray(0).toRequestBody(null)
    }
}
