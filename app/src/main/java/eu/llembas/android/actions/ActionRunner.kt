package eu.llembas.android.actions

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Carries out a phone action the person confirmed: [run] picks the way -- one intent, the torch, a
 * media key, or a navigation app -- and answers with done, failed (and why), never silence.
 */
object ActionRunner {
    // The reasons go back to the model as the tool's result, so they are written for it, in English.
    private const val NOT_POSSIBLE = "the phone cannot do this action with these arguments"
    private const val NO_NAV_APP = "no navigation app chosen or installed (Google Maps or OsmAnd)"
    private const val NO_DESTINATION =
        "a stop can only be added with the route's destination: name it (destination), or the person lets LLeMbas ask OsmAnd for it"
    private const val GOOGLE_CANNOT_STOP = "Google Maps cannot be told to stop navigating; the person stops it there"
    private const val OSMAND_NEEDS_COORDINATES = "OsmAnd takes a route through stops only with every point's lat and lon"

    /**
     * [askOsmAnd]: Settings › Phone actions says the person allowed LLeMbas inside OsmAnd, so its
     * position and the running route's destination may be asked for (`get_info`).
     */
    suspend fun run(context: Context, action: String, args: Args, nav: NavApp?, askOsmAnd: Boolean = false): ActionOutcome {
        when (action) {
            "torch" -> return PhoneActions.torch(context, args.bool("on"))
            "media" -> return PhoneActions.media(context, args.str("command").ifBlank { "toggle" })
            "navigate", "add_stop", "search_nearby", "stop_navigation" -> return navigate(context, action, args, nav, askOsmAnd)
        }
        val intent = PhoneActions.intentFor(context, action, args)
            ?: return ActionOutcome(ActionStatus.Failed, NOT_POSSIBLE)
        return PhoneActions.launch(context, intent, broadcast = action == "automation_intent")
    }

    private suspend fun navigate(context: Context, action: String, args: Args, nav: NavApp?, askOsmAnd: Boolean): ActionOutcome {
        val app = nav ?: return ActionOutcome(ActionStatus.Failed, NO_NAV_APP)
        val pkg = app.installed(context) ?: return ActionOutcome(ActionStatus.Failed, NO_NAV_APP)
        val travel = Travel.of(args.str("mode").ifBlank { null })
        // OsmAnd's own position and destination, if the person allowed LLeMbas to ask it.
        val info = if (app == NavApp.OsmAnd && askOsmAnd && action != "stop_navigation") osmandInfo(context, pkg) else null
        val step = when (action) {
            "navigate" -> args.place("destination")?.let {
                NavIntents.navigate(app, pkg, it, args.places("stops"), travel, info, replace = args.bool("replace_route") == true)
            }

            "add_stop" -> args.place("stop")?.let {
                NavIntents.addStop(app, pkg, it, args.place("destination"), travel, info, NO_DESTINATION)
            }

            "search_nearby" -> args.str("query").takeIf { it.isNotBlank() }?.let {
                NavIntents.searchNearby(
                    app,
                    pkg,
                    it,
                    info,
                    alongRoute =
                    args.str("near") == "route",
                )
            }

            else -> NavIntents.stop(app, pkg, GOOGLE_CANNOT_STOP)
        } ?: return ActionOutcome(ActionStatus.Failed, NOT_POSSIBLE)
        return when (step) {
            is NavStep.Unsupported -> ActionOutcome(ActionStatus.Failed, step.reason.ifBlank { OSMAND_NEEDS_COORDINATES })

            // The map app comes to the front and stays: it answers nothing more until the person
            // leaves it, so its having taken the intent is "done".
            is NavStep.Launch -> PhoneActions.launch(context, step.intent)
        }
    }

    /** OsmAnd's `get_info`, when the person allowed it inside OsmAnd; null otherwise, quietly. */
    private suspend fun osmandInfo(context: Context, pkg: String): OsmAndInfo? {
        val (code, extras) = forResult(context, NavIntents.osmandInfo(pkg), timeoutMs = 4_000) ?: return null
        return if (code == Activity.RESULT_OK) OsmAndInfo.of(extras) else null
    }

    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, Bundle?>>>()
    private val ids = AtomicInteger()

    /**
     * Start [intent] for its result through [ResultActivity] -- a see-through activity of the
     * app's own, so this works from the assistant's sheet (a voice-interaction session) as well.
     */
    suspend fun forResult(context: Context, intent: Intent, timeoutMs: Long = 60_000): Pair<Int, Bundle?>? {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<Pair<Int, Bundle?>>()
        waiting[id] = answer
        return try {
            context.startActivity(
                Intent(context, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_ID, id)
                    .putExtra(ResultActivity.EXTRA_INTENT, intent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            withTimeoutOrNull(timeoutMs) { answer.await() }
        } finally {
            waiting.remove(id)
        }
    }

    internal fun answered(id: Int, code: Int, extras: Bundle?) {
        waiting[id]?.complete(code to extras)
    }

    /** Asks another app for a result on [ActionRunner]'s behalf, then goes. Shows nothing of its own. */
    class ResultActivity : ComponentActivity() {
        private var id = 0
        private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            answered(id, result.resultCode, result.data?.extras)
            finish()
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            id = intent.getIntExtra(EXTRA_ID, 0)
            if (savedInstanceState != null) return
            @Suppress("DEPRECATION")
            val target = intent.getParcelableExtra<Intent>(EXTRA_INTENT)
            if (target == null) {
                answered(id, Activity.RESULT_CANCELED, null)
                finish()
                return
            }
            try {
                launcher.launch(target)
            } catch (e: ActivityNotFoundException) {
                answered(id, Activity.RESULT_FIRST_USER, null)
                finish()
            }
        }

        companion object {
            const val EXTRA_ID = "eu.llembas.android.RESULT_ID"
            const val EXTRA_INTENT = "eu.llembas.android.RESULT_INTENT"
        }
    }
}
