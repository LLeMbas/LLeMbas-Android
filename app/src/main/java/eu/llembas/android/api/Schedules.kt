package eu.llembas.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// --- Scheduled tasks (`/api/v2/schedules`) ------------------------------------------------
//
// Provisional: written against the server's scheduling plan before its routes and contract
// fixtures landed. Every field has a default, so a server that says less still parses; the
// contract re-pin is where these meet the server's real shapes (and `ContractTest` holds them).

/**
 * One run of a schedule: when it was due and how it went. [status] is `ok`, `silent` (nothing
 * worth saying), `failed`, `missed`, `skipped`, `waiting` (no model free yet) or `running`;
 * [timing] `on_time`, `late` or `catch_up`. [error] is the server's sentence, never a model's.
 */
@Serializable
data class ScheduleRun(
    val id: String = "",
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    val status: String = "",
    val timing: String = "",
    val model: String = "",
    /** `pinned`, or the reason another model ran it (`switched: <from> busy`). */
    @SerialName("model_reason") val modelReason: String = "",
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("report_path") val reportPath: String? = null,
    val error: String = "",
    /** How many runs were missed before this one (`missed ×N`). */
    val missed: Int = 0,
    val notified: Boolean = false,
)

/**
 * A scheduled task. [rule] is the recurrence in words, as the server says it ("Every weekday at
 * 08:00"); [instruction] what the model is asked each time. [notify] is `always`, `matters` or
 * `never`; [mode] `fresh` (a chat per run) or `continue` (one growing chat).
 */
@Serializable
data class Schedule(
    val id: String,
    val title: String = "",
    val instruction: String = "",
    val rule: String = "",
    val paused: Boolean = false,
    @SerialName("next_runs") val nextRuns: List<String> = emptyList(),
    @SerialName("last_run") val lastRun: ScheduleRun? = null,
    val model: String = "",
    val notify: String = "",
    val mode: String = "",
    @SerialName("failure_streak") val failureStreak: Int = 0,
    @SerialName("chat_id") val chatId: String? = null,
) {
    val nextRun: String? get() = nextRuns.firstOrNull()
}

@Serializable
data class ScheduleList(val schedules: List<Schedule> = emptyList())

@Serializable
data class ScheduleEnvelope(val schedule: Schedule)

@Serializable
data class ScheduleRuns(val runs: List<ScheduleRun> = emptyList(), val more: Boolean = false)

/**
 * What the server made of a person's words: a title, the rule in words, the instruction the
 * model will be given, and the next five runs -- shown before anything is saved. [rule] is the
 * server's own form of the rule, handed back unchanged when the preview is saved.
 */
@Serializable
data class SchedulePreview(
    val title: String = "",
    @SerialName("rule_text") val ruleText: String = "",
    val instruction: String = "",
    @SerialName("next_runs") val nextRuns: List<String> = emptyList(),
    val rule: JsonObject? = null,
)

@Serializable
data class SchedulePreviewEnvelope(val preview: SchedulePreview)
