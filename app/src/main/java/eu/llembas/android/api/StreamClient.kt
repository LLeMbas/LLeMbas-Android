package eu.llembas.android.api

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/** One server-sent event: its name and its data. */
data class Frame(val event: String, val data: String)

/**
 * Follow a reply: the stream's frames, and when the connection drops before `close`, the same
 * stream opened again after a pause -- 1, 2, 4, 8 … up to 30 seconds. Nothing is replayed or
 * deduplicated: the server's first pass after a reconnect is the whole state, which is exactly
 * what [LiveReply.apply] wants.
 *
 * A refusal (a 4xx: the chat was deleted, the token signed out) ends the flow with the error;
 * only a broken connection is retried.
 */
class StreamClient(private val api: ApiClient) {
    private val http = api.http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    fun follow(chatId: String, messageId: String): Flow<Frame> = flow {
        var wait = 1_000L
        while (true) {
            var closed = false
            var refused: ApiException? = null
            once(chatId, messageId).collect { result ->
                when (result) {
                    is Outcome.Event -> {
                        wait = 1_000L
                        emit(result.frame)
                        if (result.frame.event == "close") closed = true
                    }

                    is Outcome.Refused -> refused = result.error

                    is Outcome.Dropped -> Unit
                }
            }
            refused?.let { throw it }
            if (closed) return@flow
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000L)
        }
    }

    private sealed interface Outcome {
        data class Event(val frame: Frame) : Outcome
        data class Refused(val error: ApiException) : Outcome
        data object Dropped : Outcome
    }

    private fun once(chatId: String, messageId: String): Flow<Outcome> = callbackFlow {
        val source = EventSources.createFactory(http).newEventSource(
            api.streamRequest(chatId, messageId),
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    trySend(Outcome.Event(Frame(type ?: "message", data)))
                    if (type == "close") close()
                }

                override fun onClosed(eventSource: EventSource) {
                    close()
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val code = response?.code ?: 0
                    if (code in 400..499) {
                        trySend(Outcome.Refused(ApiException(code, response?.let { detail(it) } ?: "Refused ($code).")))
                    } else {
                        trySend(Outcome.Dropped)
                    }
                    close()
                }
            },
        )
        awaitClose { source.cancel() }
    }
}
