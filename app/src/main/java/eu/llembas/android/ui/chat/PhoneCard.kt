package eu.llembas.android.ui.chat

import android.app.KeyguardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.actions.ActionRunner
import eu.llembas.android.actions.Args
import eu.llembas.android.actions.NavApp
import eu.llembas.android.api.Card
import eu.llembas.android.api.CardAnswer
import eu.llembas.android.api.PhoneResult
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import eu.llembas.android.ui.theme.LocalPalette
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The actions a map app carries out. */
val NAV_ACTIONS = setOf("navigate", "add_stop", "search_nearby", "stop_navigation")

/** What the card shows of a phone card while it is being carried out. */
data class PhoneCardState(val running: Boolean = false, val locked: Boolean = false, val error: String? = null)

/**
 * A `phone_action` card (spec 3.6.0) with the phone behind it: Do it carries the action out
 * ([ActionRunner]) and answers what happened -- done, or failed and why; Cancel answers declined.
 * Never while the phone is locked: Do it says to unlock first. Used by the chat's card sheet and
 * by the assistant's sheet alike.
 */
@Composable
fun PhoneCard(card: Card, onAnswer: (CardAnswer) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs by context.lembas.instances.prefs.collectAsState(initial = Prefs())
    val installed = remember { NavApp.installedApps(context) }
    var state by remember(card.id) { mutableStateOf(PhoneCardState()) }
    val item = card.items.firstOrNull() ?: return
    val phone = item.phone ?: JsonObject(emptyMap())
    PhoneCardContent(
        phone = phone,
        navApps = installed,
        chosen = NavApp.chosen(prefs.navApp, installed),
        state = state,
        onDo = { app ->
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                state = state.copy(locked = true)
                return@PhoneCardContent
            }
            state = PhoneCardState(running = true)
            scope.launch {
                val action = (phone["action"] as? JsonPrimitive)?.content.orEmpty()
                val outcome = ActionRunner.run(context, action, Args(phone), app, askOsmAnd = prefs.osmandInfo)
                state = PhoneCardState(error = outcome.message.takeIf { outcome.status.wire == "failed" })
                onAnswer(CardAnswer(result = PhoneResult(outcome.status.wire, outcome.message)))
            }
        },
        onDecline = { onAnswer(CardAnswer(result = PhoneResult("declined"))) },
    )
}

/**
 * The card from its state alone (what the screenshot tests draw): the phone's icon and "Do this
 * on your phone?", the model's one line ([phone]'s `summary`), how it will be done and its facts,
 * the map app to use when Settings leaves it open ([chosen] null, more than one installed), and
 * Cancel / Do it -- each at least 48 dp, the line above them free to wrap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneCardContent(
    phone: JsonObject,
    navApps: List<NavApp>,
    chosen: NavApp?,
    state: PhoneCardState,
    onDo: (NavApp?) -> Unit = {},
    onDecline: () -> Unit = {},
) {
    val palette = LocalPalette.current
    val action = (phone["action"] as? JsonPrimitive)?.content.orEmpty()
    val summary = (phone["summary"] as? JsonPrimitive)?.content.orEmpty().ifBlank { action }
    val nav = action in NAV_ACTIONS
    var picked by remember(chosen) { mutableStateOf(chosen ?: navApps.singleOrNull()) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.surfaceRaised,
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = palette.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.action_card_title), style = MaterialTheme.typography.labelLarge, color = palette.accent)
            }
            Text(summary, style = MaterialTheme.typography.titleMedium)
            Text(howLine(action, picked), style = MaterialTheme.typography.bodyMedium, color = palette.inkMuted)
            phoneFacts(phone).takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
            }
            if (nav && chosen == null && navApps.size > 1) {
                Text(stringResource(R.string.nav_app_pick), style = MaterialTheme.typography.labelMedium, color = palette.inkMuted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    navApps.forEach { app ->
                        FilterChip(
                            selected = picked == app,
                            onClick = { picked = app },
                            label = { Text(stringResource(if (app == NavApp.OsmAnd) R.string.nav_app_osmand else R.string.nav_app_google)) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = palette.accentSoft),
                            modifier = Modifier.heightIn(min = 40.dp),
                        )
                    }
                }
            }
            if (nav && picked == NavApp.GoogleMaps && action in setOf("add_stop", "stop_navigation")) {
                Text(
                    stringResource(if (action == "add_stop") R.string.action_google_add_stop else R.string.action_google_no_stop),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.warning,
                )
            }
            when {
                state.locked -> Text(stringResource(R.string.action_unlock), style = MaterialTheme.typography.bodyMedium, color = palette.warning)

                state.error != null -> Text(
                    stringResource(R.string.action_failed, state.error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.danger,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDecline, enabled = !state.running, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onDo(picked) },
                    enabled = !state.running && (!nav || picked != null),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.accent, contentColor = palette.accentInk),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(if (state.running) R.string.action_running else R.string.action_do)) }
            }
        }
    }
}

/** How the action will be done, in a sentence: which kind of app, and what is left to the person. */
@Composable
private fun howLine(action: String, app: NavApp?): String = when (action) {
    "alarm", "timer" -> stringResource(R.string.action_line_clock)

    "calendar_event" -> stringResource(R.string.action_line_calendar)

    "sms_draft", "email_draft" -> stringResource(R.string.action_line_draft)

    "dial" -> stringResource(R.string.action_line_dial)

    "navigate", "add_stop", "search_nearby", "stop_navigation" ->
        stringResource(
            R.string.action_line_map,
            stringResource(
                if (app ==
                    NavApp.OsmAnd
                ) {
                    R.string.nav_app_osmand
                } else if (app == NavApp.GoogleMaps) {
                    R.string.nav_app_google
                } else {
                    R.string.nav_app
                },
            ),
        )

    "media", "torch" -> stringResource(R.string.action_line_now)

    "settings_panel" -> stringResource(R.string.action_line_settings)

    "automation_intent" -> stringResource(R.string.action_line_automation)

    else -> stringResource(R.string.action_line_open)
}

/**
 * The action's facts as one line, values only, in the order the spec lists the fields: what the
 * person checks before Do it ("07:00 · mon tue wed thu fri · Work"). A place is its name and,
 * when it has one, what the map app will search, or its coordinates.
 */
fun phoneFacts(phone: JsonObject): String {
    fun place(e: JsonElement?): String? {
        val o = e as? JsonObject ?: return (e as? JsonPrimitive)?.content
        val name = (o["name"] as? JsonPrimitive)?.content.orEmpty()
        val query = (o["query"] as? JsonPrimitive)?.content.orEmpty()
        val lat = (o["lat"] as? JsonPrimitive)?.content
        val lon = (o["lon"] as? JsonPrimitive)?.content
        val where = query.ifBlank { if (lat != null && lon != null) "$lat, $lon" else "" }
        return listOf(name, where).filter { it.isNotBlank() }.distinct().let {
            if (it.size == 2) "${it[0]} (${it[1]})" else it.firstOrNull()
        }
    }

    fun value(key: String, e: JsonElement?): String? = when {
        e == null -> null
        key in setOf("destination", "stop") -> place(e)
        key == "stops" -> (e as? JsonArray)?.mapNotNull { place(it) }?.joinToString(" → ")?.ifBlank { null }
        e is JsonArray -> e.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(" ").ifBlank { null }
        e is JsonObject -> e.entries.joinToString(", ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.content.orEmpty()}" }.ifBlank { null }
        e is JsonPrimitive -> e.content.takeIf { it.isNotBlank() && it != "false" && it != "true" }
        else -> null
    }
    return FACT_ORDER.mapNotNull { key -> value(key, phone[key]) }.joinToString(" · ")
}

private val FACT_ORDER = listOf(
    "time", "days", "seconds", "label", "title", "start", "end", "location", "to", "cc", "subject", "number",
    "stops", "stop", "destination", "mode", "query", "near", "app", "url", "command", "panel", "task", "values",
)
