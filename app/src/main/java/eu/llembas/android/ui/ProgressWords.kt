package eu.llembas.android.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import eu.llembas.android.R
import eu.llembas.android.api.Progress
import eu.llembas.android.api.ProgressWords
import eu.llembas.android.api.elapsed
import eu.llembas.android.api.progressLine
import eu.llembas.android.api.progressText
import kotlinx.coroutines.delay

/** The progress texts' resources, by the spec's key (`progress.<key>` with `.` as `_`). */
private val PROGRESS_STRINGS = mapOf(
    "queued" to R.string.progress_queued,
    "queued_ahead" to R.string.progress_queued_ahead,
    "waiting_for_model" to R.string.progress_waiting_for_model,
    "loading_model" to R.string.progress_loading_model,
    "reading_prompt" to R.string.progress_reading_prompt,
    "reading_prompt_bare" to R.string.progress_reading_prompt_bare,
    "thinking" to R.string.progress_thinking,
    "writing" to R.string.progress_writing,
    "writing_bare" to R.string.progress_writing_bare,
    "running_tool" to R.string.progress_running_tool,
    "running_tool_bare" to R.string.progress_running_tool_bare,
    "waiting_for_person" to R.string.progress_waiting_for_person,
    "waiting_for_subagents" to R.string.progress_waiting_for_subagents,
    "compacting" to R.string.progress_compacting,
    "retrying" to R.string.progress_retrying,
    "stopped" to R.string.progress_stopped,
    "stopped_reason" to R.string.progress_stopped_reason,
    "failed" to R.string.progress_failed,
    "reason_busy" to R.string.progress_reason_busy,
    "reason_slots" to R.string.progress_reason_slots,
    "reason_group" to R.string.progress_reason_group,
    "what_approval" to R.string.progress_what_approval,
    "what_approval_bare" to R.string.progress_what_approval_bare,
    "what_question" to R.string.progress_what_question,
    "what_plan" to R.string.progress_what_plan,
    "stopped_reason_person" to R.string.progress_stopped_person,
    "stopped_reason_steps" to R.string.progress_stopped_steps,
    "stopped_reason_budget" to R.string.progress_stopped_budget,
    "line" to R.string.progress_line,
)

/** [ProgressWords] from the app's string resources, in the app's language. */
fun progressWords(resources: Resources): ProgressWords = ProgressWords { key, args ->
    PROGRESS_STRINGS[key]?.let { id -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args) }
}

/** The end of a progress line: the shared `progress.line` separator and [clock], without the text before it. */
fun progressClock(resources: Resources, clock: String): String = resources.getString(R.string.progress_line, "", clock)

/** The clock a progress line ticks by: now, again on every whole second. */
@Composable
fun rememberTick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L - now % 1000L)
        }
    }
    return now
}

/** In a preview or a screenshot the clock stands still, this long after the phase began. */
private const val STILL_ELAPSED = 65_000L

/**
 * The progress line to draw for [progress] (`{{text}} · {{elapsed}}`), ticking once a second;
 * or the text alone with [withElapsed] false, where a line has no room (a list's row). Null
 * when there is no progress, or it is a phase this app does not know: draw `status` then.
 */
@Composable
fun progressLineOf(progress: Progress?, withElapsed: Boolean = true): String? {
    progress ?: return null
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val words = remember(resources, locale) { progressWords(resources) }
    // Under inspection (previews, screenshots) nothing may animate forever, and a shot must be
    // the same every time.
    val now = if (LocalInspectionMode.current) progress.since + STILL_ELAPSED else rememberTick()
    return if (withElapsed) progressLine(progress, words, now, locale) else progressText(progress, words, now, locale)
}

/**
 * What a reply is doing, one line: the progress text, then `· elapsed` (`progress.line`) kept
 * whole at the end while the text gives way (two lines, then an ellipsis) -- on a phone a long
 * reason must not push the clock off. [status], the free text, when there is no progress this
 * app can draw. Nothing at all when neither says anything.
 */
@Composable
fun ProgressStatus(progress: Progress?, status: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val text = progressLineOf(progress, withElapsed = false)
    if (text == null) {
        if (status.isNotBlank()) Text(status, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
        return
    }
    val clock = progress?.takeUnless { it.phase in Progress.NO_ELAPSED }?.let { p ->
        val now = if (LocalInspectionMode.current) p.since + STILL_ELAPSED else rememberTick()
        elapsed(now - p.since)
    }
    Row(modifier, verticalAlignment = Alignment.Top) {
        Text(text, style = style, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        // The clock after the shared separator: `progress.line` (`{{text}} · {{elapsed}}`) with
        // the text left out, so the separator is the spec's and not a copy of it.
        if (clock != null) Text(progressClock(LocalContext.current.resources, clock), style = style, color = color, maxLines = 1, softWrap = false)
    }
}
