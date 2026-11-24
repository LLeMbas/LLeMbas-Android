package eu.llembas.android.ui.chat

import android.os.SystemClock
import android.text.format.Formatter
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.llembas.android.R
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.GeneratedImage
import eu.llembas.android.api.GeneratedMedia
import eu.llembas.android.api.MediaJob
import eu.llembas.android.api.MediaView
import eu.llembas.android.ui.theme.LocalPalette
import eu.llembas.android.ui.viewer.playTime
import kotlinx.coroutines.delay

// What a model made or an agent showed, in the reply: a card for a file (a picture drawn, a video
// or a track with Play, a PDF with Open, a 3D model or anything else as what it is) and a card for
// a generation still under way.
// The file is one of the person's Files, so each card leads there, and out of the app the ways
// a file leaves it: saved where the person chooses, shared, or opened with another app. The
// cards are drawn only where a chat provides [LocalChatMedia] -- the chat's own screen, which
// is never over the lock screen; the assistant's sheet provides none and draws none.

/** What the media cards of a chat can do; nothing by default, for the screenshot tests. */
interface MediaActions {
    /** Play a generated video or track, over the chat. */
    fun play(media: GeneratedMedia) {}

    /** Play sound or video the person attached. */
    fun play(attachment: Attachment) {}

    /** Lay a document's pages out over the chat (a PDF). */
    fun view(media: GeneratedMedia) {}

    /** The folder of the person's Files the file is in. */
    fun openInFiles(media: GeneratedMedia) {}

    /** Save it where the person chooses (the system's "Save to" page). */
    fun download(media: GeneratedMedia) {}

    fun share(media: GeneratedMedia) {}

    fun openWith(media: GeneratedMedia) {}

    /** Stop a generation that is under way. */
    fun cancel(job: MediaJob) {}
}

/** A chat's generated media as its cards need it: what is known of its jobs, and what a card can do. */
class ChatMedia(val jobs: Map<String, JobSeen>, val actions: MediaActions)

/** Provided by the chat's screen; null anywhere else, and then no media card is drawn. */
val LocalChatMedia = compositionLocalOf<ChatMedia?> { null }

private val CardShape = RoundedCornerShape(16.dp)

/** The string for a kind this app has a word for; null for any other. */
internal fun mediaKindWord(kind: String): Int? = when (kind) {
    "image", "image_edit" -> R.string.media_image
    "video" -> R.string.media_video
    "music" -> R.string.media_music
    "audio" -> R.string.media_audio
    "model3d" -> R.string.media_model
    "pdf" -> R.string.media_pdf
    "archive" -> R.string.media_archive
    "text" -> R.string.media_text
    "binary" -> R.string.media_binary
    else -> null
}

/**
 * The word for what a file is: a picture, a video, music, audio, a 3D model, a PDF, an archive,
 * a text file, a file. A kind this app does not know is the server's own word for a generated
 * file and "File" for one an agent showed ([shown]): a shown file always says what it is.
 */
@Composable
internal fun mediaKind(kind: String, shown: Boolean = false): String =
    mediaKindWord(kind)?.let { stringResource(it) } ?: if (shown) stringResource(R.string.media_binary) else kind

/** The mark of a kind; `binary` and a kind this app does not know are a file. */
internal fun mediaIcon(kind: String): ImageVector = when (kind) {
    "image", "image_edit" -> Icons.Filled.Image
    "video" -> Icons.Filled.Movie
    "music", "audio" -> Icons.Filled.MusicNote
    "model3d" -> Icons.Filled.ViewInAr
    "pdf" -> Icons.Filled.PictureAsPdf
    "archive" -> Icons.Filled.FolderZip
    "text" -> Icons.Filled.Description
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** What is known of a file, in one line: what it is, how long, how large, its size in pixels. */
@Composable
internal fun mediaFacts(media: GeneratedMedia): String {
    val context = LocalContext.current
    return listOfNotNull(
        mediaKind(media.kind, media.shown).ifBlank { null },
        media.seconds.takeIf { it > 0 }?.let { playTime((it * 1000).toInt()) },
        media.size.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) },
        if (media.width > 0 && media.height > 0) "${media.width} × ${media.height}" else null,
    ).joinToString(" · ")
}

/**
 * A file a tool call made, or one an agent showed. A picture is drawn -- from its attachment
 * where the call has one ([image]), else from the file itself; a video or a track is its name
 * with Play (48 dp), which opens the player over the chat; a PDF is its name with Open, which
 * lays its pages out over the chat; a 3D model, an archive and anything this app does not know
 * is its name and what it is. Under each, four buttons of one size: Open in Files, Download,
 * Share, Open with.
 *
 * A file that was shown is always named: its picture has its name and facts under it, so one
 * that cannot be drawn on this phone (too large, a format the phone does not read) is still a
 * name, a kind in words and the ways to the file.
 */
@Composable
fun MediaCard(media: GeneratedMedia, image: GeneratedImage?, actions: MediaActions, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (media.view) {
            MediaView.Picture -> {
                // A result that names no node has no picture to ask for: its row stands in, so the card is never empty.
                val drawable = (image != null && image.id.isNotBlank()) || media.nodeId.isNotBlank()
                if (image != null && image.id.isNotBlank()) GeneratedPicture(image) else GeneratedPicture(media)
                if (media.shown && drawable) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) { MediaWords(media) }
                } else if (!drawable) {
                    MediaRow(media, actions)
                }
            }

            MediaView.Video, MediaView.Sound, MediaView.Document, MediaView.Details -> MediaRow(media, actions)
        }
        // Four buttons of 48 dp: nothing here depends on how long a word is in any language.
        Row {
            CardButton(Icons.Filled.FolderOpen, R.string.media_open_in_files) { actions.openInFiles(media) }
            CardButton(Icons.Filled.Download, R.string.download) { actions.download(media) }
            CardButton(Icons.Filled.Share, R.string.report_share) { actions.share(media) }
            CardButton(Icons.AutoMirrored.Filled.OpenInNew, R.string.files_open_with) { actions.openWith(media) }
        }
    }
}

/** The file's name on one line (its middle gives) and what is known of it on the next. */
@Composable
private fun MediaWords(media: GeneratedMedia) {
    val palette = LocalPalette.current
    Text(media.title, style = MaterialTheme.typography.bodyLarge, color = palette.ink, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
    Text(mediaFacts(media), style = MaterialTheme.typography.bodySmall, color = palette.inkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * A file as a row: a mark of 48 dp -- Play for sound and video, Open for a PDF, else the kind's
 * icon -- and the file's words, which give.
 */
@Composable
private fun MediaRow(media: GeneratedMedia, actions: MediaActions) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().border(1.dp, palette.border, CardShape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val filled = IconButtonDefaults.filledIconButtonColors(containerColor = palette.accent, contentColor = palette.accentInk)
        when (media.view) {
            MediaView.Video, MediaView.Sound -> FilledIconButton(onClick = { actions.play(media) }, colors = filled, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.play))
            }

            MediaView.Document -> FilledIconButton(onClick = { actions.view(media) }, colors = filled, modifier = Modifier.size(48.dp)) {
                Icon(mediaIcon(media.kind), contentDescription = stringResource(R.string.viewer_open))
            }

            MediaView.Picture, MediaView.Details -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(mediaIcon(media.kind), contentDescription = null, tint = palette.inkMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { MediaWords(media) }
    }
}

@Composable
private fun CardButton(icon: ImageVector, label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(label), tint = LocalPalette.current.inkMuted) }
}

/** The word for where a job stands; a state this app does not know is said as the server says it. */
@Composable
internal fun jobState(state: String): String = when (state) {
    "queued" -> stringResource(R.string.job_queued)
    "running" -> stringResource(R.string.job_running)
    "done" -> stringResource(R.string.job_done)
    "failed" -> stringResource(R.string.job_failed)
    "cancelled" -> stringResource(R.string.job_cancelled)
    "expired" -> stringResource(R.string.job_expired)
    else -> state
}

/**
 * A generation that takes minutes, on the card of the call that started it: what is being made,
 * where it stands and for how long, and Cancel while it is under way. One that ended without a
 * file says why in the server's own sentence. The mark keeps its 48 dp and Cancel its own width;
 * the words between them give.
 */
@Composable
fun JobCard(seen: JobSeen, actions: MediaActions, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val still = LocalInspectionMode.current
    val job = seen.job
    var now by remember { mutableLongStateOf(if (still) seen.at else SystemClock.elapsedRealtime()) }
    // The clock on the card runs on the phone between two reads of the job.
    LaunchedEffect(job.id, job.underWay) {
        while (job.underWay && !still) {
            now = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    Row(
        modifier.widthIn(max = 480.dp).fillMaxWidth().border(1.dp, palette.border, CardShape).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                // A wheel that never ends never lets a screenshot be taken: a still mark there.
                job.underWay && !still -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))

                job.underWay -> Icon(Icons.Filled.HourglassEmpty, contentDescription = null, tint = palette.inkMuted)

                job.unsuccessful -> Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = if (job.state ==
                        "failed"
                    ) {
                        palette.danger
                    } else {
                        palette.inkMuted
                    },
                )

                else -> Icon(mediaIcon(job.kind), contentDescription = null, tint = palette.inkMuted)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOf(mediaKind(job.kind), jobState(job.state)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
                color = palette.ink,
            )
            val elapsed = seen.elapsed(now)
            val facts = listOfNotNull(
                if (elapsed >
                    0
                ) {
                    playTime((elapsed * 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                } else {
                    null
                },
                job.source.ifBlank { null },
            )
            if (facts.isNotEmpty()) {
                Text(
                    facts.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.inkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Why it was not made, in the instance's words: a sentence for the person, shown whole.
            if (job.unsuccessful && job.error.isNotBlank()) {
                Text(
                    job.error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (job.state == "failed") palette.danger else palette.inkMuted,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (job.underWay) {
            TextButton(onClick = { actions.cancel(job) }) { Text(stringResource(R.string.cancel)) }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/**
 * What a call that made media leaves in the reply, always in view: its file's card; or its
 * job's card, which becomes the file's once the job is done.
 */
@Composable
fun CallMedia(media: GeneratedMedia?, image: GeneratedImage?, job: JobSeen?, chat: ChatMedia) {
    if (media != null) MediaCard(media, image, chat.actions)
    if (job != null) {
        val result = job.job.result
        if (result != null && job.job.state == MediaJob.DONE) MediaCard(result, null, chat.actions) else JobCard(job, chat.actions)
    }
}
