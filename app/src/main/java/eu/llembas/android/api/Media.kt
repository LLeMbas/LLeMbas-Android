package eu.llembas.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// What a model made, as the app is told of it (`contract/tool_media.json`, `media_job*.json`,
// `mcp_servers.json`): a tool call's typed result, a long generation's job, and -- because they
// arrived with the same server -- the instance's MCP servers and how each is signed in to.

/**
 * A file a tool call made: [kind] is what was asked for (`image`, `image_edit`, `video`, `music`,
 * `model3d`), and the file is in the person's Files at [path] -- a moment's truth, since they may
 * move it; [nodeId] names it wherever it is. Its bytes are `GET /files/raw?node=`. [width],
 * [height] and [seconds] are 0 where the server did not know them.
 */
@Serializable
data class GeneratedMedia(
    val kind: String = "",
    val path: String = "",
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("version_id") val versionId: String = "",
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val seconds: Double = 0.0,
) {
    /** How the card shows it, by what was asked for: an unknown kind is its details, never a player. */
    val view: MediaView
        get() = when (kind) {
            "image", "image_edit" -> MediaView.Picture
            "video" -> MediaView.Video
            "music" -> MediaView.Sound
            else -> MediaView.Details
        }

    /** What to call it: its name, else the last name of its path. */
    val title: String get() = name.ifBlank { FileKinds.name(path) }

    /** Where its bytes are asked for: by its id, which outlives a rename or a move; by its path from a result without one. */
    val raw: Raw get() = if (nodeId.isNotBlank()) Raw.Node(nodeId) else Raw.Path(path)

    /** The folder of the person's Files it was written to. */
    val folder: String get() = FileKinds.parent(path)
}

/** What a generated file's card is: a picture drawn, a player, or (a 3D model, anything unknown) the file's details. */
enum class MediaView { Picture, Video, Sound, Details }

/** The job a tool call started (`media_job`): [state] as it was when the call returned. */
@Serializable
data class MediaJobRef(val id: String = "", val kind: String = "", val state: String = "")

/** What a finished job was counted as; an empty [currency] is the instance's own. */
@Serializable
data class MediaCost(val amount: Double = 0.0, val currency: String = "")

/**
 * A long generation (`GET /media/jobs/{id}`): `queued` and `running` are under way, `done` has
 * its [result], `failed`, `cancelled` and `expired` say why in [error] -- the server's sentence,
 * for the person. [seconds] is how long it has taken so far, or took. [told]: the chat was woken
 * with a reply about it.
 */
@Serializable
data class MediaJob(
    val id: String = "",
    val kind: String = "",
    val state: String = "",
    val active: Boolean = false,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    val source: String = "",
    val prompt: String = "",
    val started: String? = null,
    val ended: String? = null,
    val deadline: String? = null,
    val seconds: Double = 0.0,
    val error: String = "",
    val result: GeneratedMedia? = null,
    val cost: MediaCost? = null,
    val told: Boolean = false,
) {
    /** Still to end: by the server's word, or by a state that is one of the two that are under way. */
    val underWay: Boolean get() = active || state in ACTIVE

    /** Ended some way other than with a file. */
    val unsuccessful: Boolean get() = !underWay && state != DONE

    companion object {
        val ACTIVE = setOf("queued", "running")
        const val DONE = "done"
        const val CANCELLED = "cancelled"

        /** A job known only by the call that started it. */
        fun of(ref: MediaJobRef): MediaJob = MediaJob(id = ref.id, kind = ref.kind, state = ref.state, active = ref.state in ACTIVE)
    }
}

@Serializable
data class MediaJobList(val jobs: List<MediaJob> = emptyList())

/**
 * A new file that was not made because something is already called that (409, `exists: true`):
 * the server's sentence. Nothing was written.
 */
class FileExists(message: String) : java.io.IOException(message)

/**
 * How an MCP server is signed in to: [kind] `none`, `secret` or `oauth`; [state] `ready`,
 * `signed_in`, `signed_out` or `needs_signin`. [account] is who the provider says signed in -- a
 * stranger's text, drawn as plain text. [signinUrl] is a page of the instance to open in a
 * browser, where the person signs in to the instance itself with its cookie: the app's token is
 * never part of it.
 */
@Serializable
data class McpAuth(
    val kind: String = "none",
    val state: String = "ready",
    val shared: Boolean = false,
    val account: String = "",
    @SerialName("expires_at") val expiresAt: String? = null,
    val renews: Boolean = false,
    @SerialName("signin_url") val signinUrl: String? = null,
) {
    /** The server's tools are not offered until somebody signs in. */
    val needsSignIn: Boolean get() = state == "signed_out" || state == "needs_signin"
}

@Serializable
data class McpServer(val id: String = "", val slug: String = "", val name: String = "", val tools: Int = 0, val auth: McpAuth = McpAuth()) {
    val label: String get() = name.ifBlank { slug.ifBlank { id } }
}

@Serializable
data class McpServerList(val servers: List<McpServer> = emptyList())

/**
 * The address the browser is sent to for a sign-in: always a page of the instance the app is
 * signed in to ([base]). An address on that origin is taken as it is; a path alone, or an
 * address the instance wrote with another name for itself, is that path on [base] -- so nothing
 * the list says can send the person to another host. Null for no address, or one that is no
 * http(s) page.
 */
fun signInAddress(base: String, url: String?): String? {
    val said = url?.trim().orEmpty()
    if (said.isEmpty()) return null
    val want = runCatching { java.net.URI(said) }.getOrNull() ?: return null
    val home = runCatching { java.net.URI(base) }.getOrNull() ?: return null
    if (want.scheme != null && want.scheme.lowercase() !in setOf("http", "https")) return null
    val path = want.rawPath.orEmpty()
    if (!path.startsWith("/") || path.startsWith("//")) return null

    fun port(u: java.net.URI) = when {
        u.port >= 0 -> u.port
        u.scheme.equals("https", true) -> 443
        else -> 80
    }
    val same = want.scheme != null && want.scheme.equals(home.scheme, true) && want.host != null && want.host.equals(home.host, true) &&
        port(want) == port(home) && want.userInfo == null
    return if (same) said else base.trimEnd('/') + path + want.rawQuery?.let { "?$it" }.orEmpty()
}

/** Which bytes of the instance are asked for: a file of the tree (or one version of it), a file by its id, or a chat's attachment. */
sealed interface Raw {
    data class Path(val path: String, val version: String? = null) : Raw

    /** A file by its node id, wherever it is now: what a generated file's result carries. */
    data class Node(val id: String) : Raw

    /** A chat's attachment. Answered whole: a player gets a copy, not a stream. */
    data class Attached(val id: String) : Raw
}
