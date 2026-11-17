package eu.llembas.android.assist

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import eu.llembas.android.lembas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Who writes down what an app asked the system's recogniser to hear. */
enum class Recognise {
    /** The instance's speech-to-text: the recording is sent to it. */
    Instance,

    /** The phone's on-device recogniser: nothing leaves the phone through LLeMbas. */
    OnDevice,

    /** Nobody: the calling app is told there is no recogniser for it now. */
    Refuse,
}

/**
 * The lock-screen rule for the system recogniser: nothing goes to the instance while the phone
 * is [locked], whoever asks. Locked, the on-device recogniser hears it where the phone has one
 * ([onDevice]); a phone without one answers the app that it cannot.
 */
fun recogniseWith(locked: Boolean, onDevice: Boolean): Recognise = when {
    !locked -> Recognise.Instance
    onDevice -> Recognise.OnDevice
    else -> Recognise.Refuse
}

/**
 * Speech recognition through the instance (`/v1/audio/transcriptions`). Android requires a
 * digital assistant to name a recognition service; this is it, and any app that asks the
 * system's recognizer while LLeMbas is the assistant is answered by the instance's
 * speech-to-text. Listening ends when the caller says stop, at a pause after speech or when
 * nothing is said ([EndOfSpeech], as in the assistant's sheet), or after [LIMIT_MS].
 *
 * Not while the phone is locked ([recogniseWith]): an app over the lock screen gets the phone's
 * on-device recogniser, or [UNAVAILABLE]. A phone that locks while a recording is being made
 * sends nothing either.
 */
class LembasRecognitionService : RecognitionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val recorder = Recorder(scope)
    private var callback: RecognitionService.Callback? = null
    private var limit: Job? = null

    /** The on-device recogniser hearing a locked phone's request, while it does. */
    private var phone: SpeechRecognizer? = null

    private fun locked(): Boolean = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    private fun onDevice(): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    override fun onStartListening(intent: Intent?, listener: RecognitionService.Callback) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        dropPhone()
        when (recogniseWith(locked(), onDevice())) {
            Recognise.Instance -> Unit

            Recognise.OnDevice -> {
                if (Build.VERSION.SDK_INT >= 31) listenOnDevice(intent, listener)
                return
            }

            Recognise.Refuse -> {
                listener.error(UNAVAILABLE)
                return
            }
        }
        callback = listener
        recorder.start()
        listener.readyForSpeech(Bundle())
        listener.beginningOfSpeech()
        limit = scope.launch {
            val ear = EndOfSpeech()
            var waited = 0L
            while (waited < LIMIT_MS) {
                delay(EndOfSpeech.TICK_MS)
                waited += EndOfSpeech.TICK_MS
                when (ear.tick(recorder.level.value)) {
                    EndOfSpeech.Verdict.Said -> break

                    // Nothing said is nothing sent: silence written down is an invented sentence.
                    EndOfSpeech.Verdict.Nothing -> {
                        silent()
                        return@launch
                    }

                    EndOfSpeech.Verdict.Listening -> Unit
                }
            }
            finish()
        }
    }

    /**
     * The phone's on-device recogniser in LLeMbas's place: what it hears and says goes to the
     * calling app as it is. Never the system's default recogniser, which is this service.
     */
    @RequiresApi(31)
    private fun listenOnDevice(intent: Intent?, listener: RecognitionService.Callback) {
        val made = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        phone = made
        // The calling app may be gone by the time a word comes: nobody is left to tell.
        fun tell(say: () -> Unit) {
            runCatching(say)
        }
        made.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = tell { listener.readyForSpeech(params ?: Bundle()) }

                override fun onBeginningOfSpeech() = tell { listener.beginningOfSpeech() }

                override fun onRmsChanged(rmsdB: Float) = tell { listener.rmsChanged(rmsdB) }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() = tell { listener.endOfSpeech() }

                override fun onError(error: Int) {
                    dropPhone()
                    tell { listener.error(error) }
                }

                override fun onResults(results: Bundle?) {
                    dropPhone()
                    tell { listener.results(results ?: Bundle()) }
                }

                override fun onPartialResults(partialResults: Bundle?) = tell { listener.partialResults(partialResults ?: Bundle()) }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            },
        )
        made.startListening(intent?.let { Intent(it) } ?: Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
    }

    private fun dropPhone() {
        phone?.runCatching {
            cancel()
            destroy()
        }
        phone = null
    }

    override fun onStopListening(listener: RecognitionService.Callback) {
        phone?.let {
            it.stopListening()
            return
        }
        finish()
    }

    override fun onCancel(listener: RecognitionService.Callback) {
        dropPhone()
        limit?.cancel()
        callback = null
        scope.launch { recorder.stop() }
    }

    /** Nobody spoke: the caller is told so, and nothing is written down. */
    private fun silent() {
        val listener = callback ?: return
        callback = null
        scope.launch {
            recorder.stop()
            listener.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        }
    }

    private fun finish() {
        val listener = callback ?: return
        callback = null
        limit?.cancel()
        scope.launch {
            val wav = recorder.stop()
            listener.endOfSpeech()
            // The phone locked while it listened: the recording goes nowhere.
            if (locked()) {
                listener.error(UNAVAILABLE)
                return@launch
            }
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
        dropPhone()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val LIMIT_MS = 15_000L

        /**
         * What a calling app is told when nothing may hear it now: the recogniser is busy, which
         * is the error an app takes as "not now, try again" rather than as its own mistake or a
         * missing permission.
         */
        const val UNAVAILABLE = SpeechRecognizer.ERROR_RECOGNIZER_BUSY
    }
}
