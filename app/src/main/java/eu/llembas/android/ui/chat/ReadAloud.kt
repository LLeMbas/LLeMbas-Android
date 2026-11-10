package eu.llembas.android.ui.chat

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import eu.llembas.android.assist.AssistController
import eu.llembas.android.assist.Speaker
import eu.llembas.android.lembas
import eu.llembas.android.ui.explain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A reply read aloud by the instance's text-to-speech (`/v1/audio/speech`), the same voice the
 * assistant answers in. One at a time: another reply's Read aloud stops the first. [available]
 * only when the instance says it has text-to-speech (`/api/v2/me` services), so a phone never
 * shows a button that can only fail.
 */
@Stable
class ReadAloud(private val context: Context, private val scope: CoroutineScope, private val onError: (String) -> Unit) {
    private val speaker = Speaker(context)
    private var job: Job? = null

    var available by mutableStateOf(false)
        private set

    /** The message being read (or fetched to be read), else null. */
    var reading by mutableStateOf<String?>(null)
        private set

    suspend fun load() {
        available = runCatching { context.lembas.instances.client()?.me()?.hasTextToSpeech == true }.getOrDefault(false)
    }

    fun toggle(messageId: String, markdown: String) {
        if (reading == messageId) return stop()
        stop()
        reading = messageId
        job = scope.launch {
            try {
                val api = context.lembas.instances.client() ?: return@launch
                speaker.play(api.speech(AssistController.plain(markdown)))
                speaker.playing.first { !it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(context.explain(e))
            } finally {
                if (reading == messageId) reading = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        speaker.stop()
        reading = null
    }
}

/** Provided by the chat; null where nothing can read aloud (the screenshots, a run's page). */
val LocalReadAloud = compositionLocalOf<ReadAloud?> { null }
