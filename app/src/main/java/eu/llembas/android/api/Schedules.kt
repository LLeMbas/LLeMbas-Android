package eu.llembas.android.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// --- Scheduled tasks (`/api/v2/schedules`) ------------------------------------------------
//
// The server's shapes, as its contract fixtures pin them (`contract/schedule*.json`,
// `event_schedules.json`; `ContractTest` reads every one). Every field has a default, so a
// server that says less still parses.

/** The model a run used: [reason] `switched` when it stood in for [switchedFrom], which was busy. */
@Serializable
data class RunModel(
    val id: String? = null,
    val name: String? = null,
    val reason: String? = null,
    @SerialName("switched_from") val switchedFrom: String? = null,
) {
    /** What to call it: its name, else its id. */
    val label: String? get() = name ?: id
}

@Serializable
data class RunTokens(val prompt: Long = 0, val completion: Long = 0)

@Serializable
data class RunCost(val micro: Long = 0, val currency: String = "")

/** Why a run failed or waits: [kind] a class the app may read, [text] the server's own sentence. */
@Serializable
data class RunError(@SerialName("class") val kind: String = "", val text: String = "")

/**
 * One run of a schedule: when it was due and how it went. [status] is `running`, `waiting` (no
 * model free yet, or a retry: [waitingFor], [retryAt]), `ok`, `silent` (nothing worth saying),
 * `failed`, `skipped` or `missed`; [timing] `on_time`, `late`, `catch_up` or `manual`.
 * [preview] is the result's first lines -- model output, shown as plain text and never as markup.
 */
@Serializable
data class ScheduleRun(
    val id: String = "",
    @SerialName("schedule_id") val scheduleId: String = "",
    val status: String = "",
    val timing: String = "",
    /** How many runs were missed before this one (`missed ×N`). */
    val missed: Int = 0,
    @SerialName("due_at") val dueAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null,
    @SerialName("waiting_for") val waitingFor: String? = null,
    @SerialName("retry_at") val retryAt: String? = null,
    /** What the run is doing now (harness spec 3.4.0, `progress.md`): waiting for which model and why, its reply's phase. */
    val progress: Progress? = null,
    val attempts: Int = 0,
    val model: RunModel = RunModel(),
    val tokens: RunTokens = RunTokens(),
    val cost: RunCost? = null,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("message_id") val messageId: String? = null,
    @SerialName("report_path") val reportPath: String? = null,
    val preview: String = "",
    val error: RunError? = null,
    val notified: Boolean = false,
)

/**
 * A scheduled task. [description] is the rule in words, as the server says it ("On the second
 * Monday of each month at 07:30"), in [timezone]; [rule] the server's own form of it; [request]
 * the person's words it was made from; [instruction] what the model is asked each time.
 * [notify] is `always`, `matters` or `never`; [mode] `fresh` (a chat per run) or `continue`.
 */
@Serializable
data class Schedule(
    val id: String,
    val title: String = "",
    val instruction: String = "",
    val request: String = "",
    val rule: JsonObject = JsonObject(emptyMap()),
    val description: String? = null,
    val timezone: String = "",
    val enabled: Boolean = true,
    @SerialName("next_run") val nextRun: String? = null,
    @SerialName("next_runs") val nextRuns: List<String> = emptyList(),
    val target: String = "chat",
    val mode: String = "",
    val notify: String = "",
    @SerialName("catch_up") val catchUp: String = "",
    @SerialName("grace_minutes") val graceMinutes: Int? = null,
    val model: String? = null,
    val effort: String? = null,
    val agents: Int? = null,
    val tools: List<String> = emptyList(),
    val origin: String = "",
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("fired_count") val firedCount: Int = 0,
    @SerialName("failure_streak") val failureStreak: Int = 0,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_run") val lastRun: ScheduleRun? = null,
    val running: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
) {
    val paused: Boolean get() = !enabled
}

@Serializable
data class ScheduleList(val schedules: List<Schedule> = emptyList())

@Serializable
data class ScheduleEnvelope(val schedule: Schedule)

@Serializable
data class RunEnvelope(val run: ScheduleRun)

@Serializable
data class ScheduleRuns(val runs: List<ScheduleRun> = emptyList(), val more: Boolean = false)

/** One run and its result in full: Markdown a model wrote, rendered as untrusted. Null: none. */
@Serializable
data class RunResult(val run: ScheduleRun, val text: String? = null)

/** `GET /events`, frame `schedules`: the whole list whenever [version] moves. */
@Serializable
data class SchedulesFrame(val version: String = "", val schedules: List<Schedule> = emptyList())

/**
 * What the server made of a person's words (`POST /schedules/compile`): a title, the
 * instruction, the rule and the rule in words, and the next runs -- shown before anything is
 * saved. [ok] false: the rest is filled as far as it went, and [reason] says why. An empty
 * [rule] cannot run, so it cannot be saved.
 */
@Serializable
data class ScheduleCompiled(
    val ok: Boolean = false,
    val reason: String? = null,
    val title: String = "",
    val instruction: String = "",
    val target: String = "chat",
    val rule: JsonObject = JsonObject(emptyMap()),
    val description: String? = null,
    @SerialName("next_runs") val nextRuns: List<String> = emptyList(),
    val timezone: String = "",
) {
    val savable: Boolean get() = rule.isNotEmpty() && instruction.isNotBlank()
}

/** A rule read back (`POST /schedules/preview`): cleaned, in words, and its next runs. */
@Serializable
data class RulePreview(
    val rule: JsonObject = JsonObject(emptyMap()),
    val description: String? = null,
    @SerialName("next_runs") val nextRuns: List<String> = emptyList(),
    val timezone: String = "",
)

/**
 * A model scheduled work may run on, in the order they are tried: [free] now or [busy] (the
 * server's words), and [chosen] when it is in the person's narrowing (all are, without one).
 */
@Serializable
data class SchedulingModel(
    val id: String,
    val name: String = "",
    val image: String? = null,
    val free: Boolean = false,
    val busy: String? = null,
    val chosen: Boolean = true,
)

/** `GET`/`PUT /schedules/models`: the models, the instance's order, and the person's narrowing. */
@Serializable
data class SchedulingModels(val models: List<SchedulingModel> = emptyList(), val instance: List<String> = emptyList(), val mine: List<String> = emptyList())
