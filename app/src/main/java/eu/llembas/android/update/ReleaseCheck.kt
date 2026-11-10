package eu.llembas.android.update

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import eu.llembas.android.BuildConfig
import eu.llembas.android.LembasApp
import eu.llembas.android.R
import eu.llembas.android.api.AppsInfo
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.SharedHttp
import eu.llembas.android.api.await
import eu.llembas.android.auth.Prefs
import eu.llembas.android.lembas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.Request

/** A version as the releases name it: `1.2.3`, or `1.2.3-beta.4`, which sorts below `1.2.3`. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val beta: Int?) : Comparable<AppVersion> {
    val isBeta: Boolean get() = beta != null

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this,
        other,
        { it.major },
        { it.minor },
        { it.patch },
        // A release sorts above every beta of itself.
        { it.beta ?: Int.MAX_VALUE },
    )

    override fun toString(): String = "$major.$minor.$patch" + (beta?.let { "-beta.$it" } ?: "")

    companion object {
        private val SHAPE = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-beta\.(\d+))?$""")

        /**
         * A tag or a version name; a test build's `-debug…` is left off first. Anything else
         * (`-rc.1`, a typo) is not a version this check knows, and is never offered.
         */
        fun parse(text: String): AppVersion? {
            val match = SHAPE.matchEntire(text.trim().substringBefore("-debug")) ?: return null
            val (a, b, c, beta) = match.destructured
            return AppVersion(a.toInt(), b.toInt(), c.toInt(), beta.takeIf { it.isNotEmpty() }?.toInt())
        }
    }
}

/** One of GitHub's releases, as far as the check needs it. */
@Serializable
data class PublicRelease(
    @SerialName("tag_name") val tag: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("html_url") val page: String = "",
)

/** A newer release to tell the person about: its version and the page its APKs are on. */
data class UpdateOffer(val version: AppVersion, val page: String)

/** What a check found. */
sealed interface UpdateResult {
    data class Newer(val offer: UpdateOffer) : UpdateResult

    data object Current : UpdateResult

    data object Unreachable : UpdateResult
}

/**
 * The update check: the release the instance recommends (`GET /api/v2/apps`), which knows what
 * speaks its API; from a server without that, the public releases of the app on GitHub, read
 * over plain HTTPS -- no
 * Google service, no account, nothing sent but the request itself. It runs when the app starts,
 * at most once a day and only while Settings › About › *Look for new versions* is on (on by
 * default in a beta, off in a release), and when the person taps *Check for an update*. A newer
 * release becomes a notification that opens its page, where the APKs are; installing stays the
 * person's step (or F-Droid's, or Obtainium's).
 *
 * Betas are offered to a beta, or to anyone who turned *Include betas* on.
 */
class ReleaseCheck(private val context: Context) {
    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        val prefs = context.lembas.instances.prefs.first()
        val installed = AppVersion.parse(BuildConfig.VERSION_NAME) ?: return@withContext UpdateResult.Current
        // The instance first: it knows which release speaks its /api/v2. Only a server without
        // the route (or none signed in, or none reachable) leaves it to GitHub's list.
        val server = runCatching { context.lembas.instances.client()?.apps() }.getOrNull()
        if (server != null) {
            return@withContext recommended(server, installed, prefs.offersBetas(installed.isBeta))
                ?.let { UpdateResult.Newer(it) } ?: UpdateResult.Current
        }
        try {
            val releases = SharedHttp.newCall(
                Request.Builder().url(RELEASES).header("Accept", "application/vnd.github+json").build(),
            ).await().use {
                if (!it.isSuccessful) return@withContext UpdateResult.Unreachable
                LembasJson.decodeFromString(ListSerializer(PublicRelease.serializer()), it.body!!.string())
            }
            newest(releases, installed, prefs.offersBetas(installed.isBeta))
                ?.let { UpdateResult.Newer(it) } ?: UpdateResult.Current
        } catch (e: Exception) {
            UpdateResult.Unreachable
        }
    }

    /** On start: once a day at most, when the setting is on; a release is announced once. */
    suspend fun daily(now: Long = System.currentTimeMillis()) {
        val store = context.lembas.instances
        val prefs = store.prefs.first()
        val installed = AppVersion.parse(BuildConfig.VERSION_NAME) ?: return
        if (!prefs.checksUpdates(installed.isBeta) || !due(prefs.updateCheckedAt, now)) return
        val found = check()
        if (found == UpdateResult.Unreachable) return
        store.savePrefs { it.copy(updateCheckedAt = now) }
        val offer = (found as? UpdateResult.Newer)?.offer ?: return
        if (offer.version.toString() == prefs.updateNotified) return
        notify(offer)
        store.savePrefs { it.copy(updateNotified = offer.version.toString()) }
    }

    fun notify(offer: UpdateOffer) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val page = PendingIntent.getActivity(
            context,
            NOTIFICATION,
            open(offer.page),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = context.getString(R.string.update_notice_text)
        val notice = NotificationCompat.Builder(context, LembasApp.CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_notice_title, offer.version.toString()))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(page)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            // Nothing from the account: the app's version is fine on a locked screen.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION, notice)
    }

    companion object {
        const val REPOSITORY = "https://github.com/LLeMbas/LLeMbas-Android"
        const val RELEASES = "https://api.github.com/repos/LLeMbas/LLeMbas-Android/releases?per_page=30"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val NOTIFICATION = 0x11e4

        /** The newest release later than [installed] that may be offered; drafts never are. */
        fun newest(releases: List<PublicRelease>, installed: AppVersion, betas: Boolean): UpdateOffer? = releases
            .asSequence()
            .filter { !it.draft }
            .mapNotNull { r -> AppVersion.parse(r.tag)?.let { it to r } }
            // A pre-release is a beta whatever its tag says, and a beta tag is one whatever GitHub says.
            .filter { (v, r) -> betas || (!v.isBeta && !r.prerelease) }
            .filter { (v, _) -> v > installed }
            .maxByOrNull { (v, _) -> v }
            ?.let { (v, r) -> UpdateOffer(v, r.page.ifBlank { "$REPOSITORY/releases/tag/${r.tag}" }) }

        /**
         * The server's recommendation, when it is newer than [installed] and may be offered: a
         * beta only with [betas]. None recommended (null) is no offer -- the server knows no
         * Android release that speaks its API, so none is pushed.
         */
        fun recommended(info: AppsInfo, installed: AppVersion, betas: Boolean): UpdateOffer? {
            val app = info.android ?: return null
            val version = AppVersion.parse(app.version) ?: return null
            if (version.isBeta && !betas) return null
            if (version <= installed) return null
            val tag = app.tag.ifBlank { "v$version" }
            return UpdateOffer(version, app.page.takeIf { it.startsWith("https://") } ?: "$REPOSITORY/releases/tag/$tag")
        }

        fun due(last: Long, now: Long): Boolean = last <= 0 || now - last >= DAY_MS || now < last

        fun open(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** *Look for new versions*: as chosen, else on in a beta and off in a release. */
fun Prefs.checksUpdates(installedIsBeta: Boolean): Boolean = updateCheck ?: installedIsBeta

/** *Include betas*: as chosen, else on in a beta and off in a release. */
fun Prefs.offersBetas(installedIsBeta: Boolean): Boolean = updateBetas ?: installedIsBeta
