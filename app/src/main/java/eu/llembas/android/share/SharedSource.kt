package eu.llembas.android.share

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.system.Os
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * What another app may hand this one to send on. A share names its files by address, and the
 * address is the other app's word: it could as well name something of this app's own -- a file
 * in its private data, a copy in its cache through its own provider -- and have the person send
 * it to a chat without knowing. So only a `content:` address of another app's provider is taken,
 * and what it opens must not be a file of this app's.
 */
object SharedSource {
    /**
     * Whether an address is refused by its looks: anything that is not `content:` (a `file:`
     * path is read with this app's own rights), and a provider that is this app's ([own]: its
     * authorities). An authority may carry a user before an `@`; the provider is what follows.
     */
    fun refused(scheme: String?, authority: String?, own: Set<String>): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return true
        val provider = authority?.substringAfterLast('@')?.lowercase().orEmpty()
        return provider.isBlank() || provider in own.map { it.lowercase() }
    }

    /**
     * Whether [resolved] -- where an opened descriptor really leads -- is inside one of [roots],
     * this app's private places. Compared by whole path segments: `/data/user/0/app` does not
     * hold `/data/user/0/app.other`.
     */
    fun inside(resolved: String, roots: List<String>): Boolean = roots.any { root ->
        val base = root.trimEnd('/')
        base.isNotEmpty() && (resolved == base || resolved.startsWith("$base/"))
    }

    /** This app's providers, by authority: its own FileProvider and whatever its libraries declare. */
    fun ownAuthorities(context: Context): Set<String> {
        val declared = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers
        }.getOrNull().orEmpty()
        return declared.flatMap { it.authority.orEmpty().split(';') }.filter { it.isNotBlank() }.toSet() + (context.packageName + ".files")
    }

    /** This app's private places, as the paths a descriptor can lead to (the data directory under each of its names). */
    fun privateRoots(context: Context): List<String> {
        val dirs = listOfNotNull(context.dataDir, context.filesDir?.parentFile, context.cacheDir, context.codeCacheDir, context.noBackupFilesDir) +
            context.externalCacheDirs.orEmpty().filterNotNull().mapNotNull { it.parentFile } +
            context.getExternalFilesDirs(null).orEmpty().filterNotNull().mapNotNull { it.parentFile }
        return dirs.flatMap { listOf(it.absolutePath, runCatching { it.canonicalPath }.getOrDefault(it.absolutePath)) }.distinct()
    }

    fun acceptable(context: Context, uri: Uri): Boolean = !refused(uri.scheme, uri.authority, ownAuthorities(context))

    /**
     * Opens what [uri] names for reading, or throws: an address refused by its looks is never
     * opened, and one that opens onto a file of this app's is closed again unread. The stream
     * is of the very descriptor that was looked at, so nothing can be swapped in between.
     */
    fun open(context: Context, uri: Uri): InputStream {
        if (!acceptable(context, uri)) throw SharedRefused()
        val opened = context.contentResolver.openAssetFileDescriptor(uri, "r") ?: throw IOException("The file could not be opened.")
        try {
            val leads = runCatching { Os.readlink("/proc/self/fd/" + opened.parcelFileDescriptor.fd) }.getOrDefault("")
            val resolved = if (leads.startsWith("/")) runCatching { File(leads).canonicalPath }.getOrDefault(leads) else leads
            if (inside(resolved, privateRoots(context)) || inside(leads, privateRoots(context))) throw SharedRefused()
            return opened.createInputStream()
        } catch (e: Throwable) {
            runCatching { opened.close() }
            throw e
        }
    }
}
