package eu.llembas.android.api

import kotlinx.serialization.Serializable

// --- A linked device's directories (`GET /api/v2/devices/{id}/directories`) -------------------
//
// The JSON twin of the page's directory dialog (harness spec 3.9.0), as the contract pins it
// (`contract/device_directories*.json`). Every field has a default, so a server that says less
// still parses.

/** The model last used in a directory on the device, as one the person still has. */
@Serializable
data class DirectoryModel(val id: String = "", val name: String = "") {
    /** What to call it: its name, else its id. */
    val label: String get() = name.ifBlank { id }
}

/** A subdirectory: whether a session may be opened in it ([reason]: the device's words when not), and its [model]. */
@Serializable
data class DirectoryEntry(
    val path: String = "",
    val name: String = "",
    val accepted: Boolean = true,
    val reason: String = "",
    val model: DirectoryModel? = null,
)

/**
 * One directory on a device, or -- with an empty [path] -- the device's places. Where there is
 * no listing, [code] says why (`device_not_linked`, `cli_too_old`, `no_answer`, `refused`) and
 * [error] is the sentence for the person; a typed path still works then.
 */
@Serializable
data class DeviceDirectory(
    val device: String = "",
    val path: String = "",
    val name: String = "",
    val accepted: Boolean = true,
    val reason: String = "",
    val model: DirectoryModel? = null,
    val parent: String = "",
    val roots: List<String> = emptyList(),
    val entries: List<DirectoryEntry> = emptyList(),
    /** Whether the device says a directory's model at all. */
    val remembers: Boolean = false,
    val code: String = "",
    val error: String = "",
) {
    /** The device's places, not a directory. */
    val places: Boolean get() = path.isEmpty()

    /** Whether this answer is a listing; one with a [code] is the reason there is none. */
    val listed: Boolean get() = code.isEmpty()
}

/**
 * The model a new agent chat on a device and directory starts on when the app names none
 * (`GET /models?kind=agent&device=&directory=`): [from] `directory` (the model last used
 * there) or `default`. The device remembers it; nothing here is kept by the app.
 */
@Serializable
data class StartModel(val model: String = "", val from: String = "")
