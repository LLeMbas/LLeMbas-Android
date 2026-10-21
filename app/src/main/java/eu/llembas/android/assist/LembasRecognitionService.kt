package eu.llembas.android.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import eu.llembas.android.lembas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Speech recognition through the instance (`/v1/audio/transcriptions`). Android requires a
 * digital assistant to name a recognition service; this is it, and any app that asks the
 * system's recognizer while LLeMbas is the assistant is answered by the instance's
 * speech-to-text. Listening ends when the caller says stop, or after [LIMIT_MS].
 */
class LembasRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = Recorder(scope)
    private var callback: RecognitionService.Callback? = null
    private var limit: Job? = null

    override fun onStartListening(intent: Intent?, listener: RecognitionService.Callback) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        callback = listener
        recorder.start()
        listener.readyForSpeech(Bundle())
        listener.beginningOfSpeech()
        limit = scope.launch {
            delay(LIMIT_MS)
            finish()
        }
    }

    override fun onStopListening(listener: RecognitionService.Callback) {
        finish()
    }

    override fun onCancel(listener: RecognitionService.Callback) {
        limit?.cancel()
        callback = null
        scope.launch { recorder.stop() }
    }

    private fun finish() {
        val listener = callback ?: return
        callback = null
        limit?.cancel()
        scope.launch {
            val wav = recorder.stop()
            listener.endOfSpeech()
            try {
                val text = lembas.instances.client()?.transcribe(wav).orEmpty()
                if (text.isBlank()) {
                    listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    listener.results(Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) })
                }
            } catch (e: Exception) {
                listener.error(SpeechRecognizer.ERROR_NETWORK)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val LIMIT_MS = 15_000L
    }
}
