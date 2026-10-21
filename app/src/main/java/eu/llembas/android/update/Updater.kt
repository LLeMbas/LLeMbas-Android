package eu.llembas.android.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import eu.llembas.android.BuildConfig
import eu.llembas.android.R
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.SharedHttp
import eu.llembas.android.api.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/** What llembas.eu says the newest release is (`site/android/latest.json`, written at release). */
@Serializable
data class Latest(val version: String, val url: String, val sha256: String, val notes: String = "")

/**
 * The `full` build updates itself, as LLeMbas Desktop does: the manifest on the website names
 * the newest APK and its SHA-256. The download is checked against that hash and its signer
 * against this app's own before Android's installer is asked -- which refuses a different
 * signer on its own as well. The `fdroid` build never runs this: F-Droid updates it.
 */
class Updater(private val context: Context) {
    suspend fun checkAndOffer(): String = withContext(Dispatchers.IO) {
        try {
            val latest = SharedHttp.newCall(Request.Builder().url(MANIFEST).build()).await().use {
                if (!it.isSuccessful) return@withContext context.getString(R.string.update_unreachable)
                LembasJson.decodeFromString(Latest.serializer(), it.body!!.string())
            }
            if (!newer(latest.version, BuildConfig.VERSION_NAME.removeSuffix("-debug"))) {
                return@withContext context.getString(R.string.update_none)
            }
            if (!context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return@withContext context.getString(R.string.update_allow_installs)
            }
            val apk = File(context.cacheDir, "update.apk")
            SharedHttp.newCall(Request.Builder().url(latest.url).build()).await().use { response ->
                if (!response.isSuccessful) return@withContext context.getString(R.string.update_unreachable)
                apk.outputStream().use { out -> response.body!!.byteStream().copyTo(out) }
            }
            if (sha256(apk) != latest.sha256.lowercase()) {
                apk.delete()
                return@withContext context.getString(R.string.update_bad_file)
            }
            if (!sameSigner(apk)) {
                apk.delete()
                return@withContext context.getString(R.string.update_bad_file)
            }
            install(apk)
            context.getString(R.string.update_installing, latest.version)
        } catch (e: Exception) {
            e.message ?: context.getString(R.string.update_unreachable)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val theirs = pm.getPackageArchiveInfo(apk.path, flags)?.signingInfo?.apkContentsSigners ?: return false
        val ours = pm.getPackageInfo(context.packageName, flags).signingInfo?.apkContentsSigners ?: return false
        return theirs.map { it.toCharsString() }.toSet() == ours.map { it.toCharsString() }.toSet()
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context,
                id,
                Intent(context, InstallStatusReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(status.intentSender)
        }
    }

    companion object {
        const val MANIFEST = "https://llembas.eu/android/latest.json"

        /** Whether `a` is a later x.y.z than `b`. */
        fun newer(a: String, b: String): Boolean {
            val left = a.split(".").map { it.toIntOrNull() ?: 0 }
            val right = b.split(".").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = left.getOrElse(i) { 0 }
                val r = right.getOrElse(i) { 0 }
                if (l != r) return l > r
            }
            return false
        }
    }
}

/** The installer's answer. Asking the person to confirm is the usual one, and it is shown. */
class InstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
