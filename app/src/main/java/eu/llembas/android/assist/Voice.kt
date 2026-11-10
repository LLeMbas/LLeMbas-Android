package eu.llembas.android.assist

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Speech in: 16 kHz mono PCM, handed over as a WAV file. WAV because every speech-to-text
 * endpoint reads it (the server passes the file through to whatever its administrator set up),
 * and a minute of it is under 2 MB -- well inside `/v1/audio/transcriptions`' 25 MB.
 *
 * Nothing is recorded until [start], nothing is kept after [stop], and nothing is sent anywhere
 * but the instance the person is signed in to.
 */
class Recorder(private val scope: CoroutineScope) {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val pcm = ByteArrayOutputStream()
    private val _level = MutableStateFlow(0f)

    /** How loud the last moment was, 0..1 (RMS of the samples): the sheet's meter and its ear for silence. */
    val level: StateFlow<Float> = _level.asStateFlow()

    val recording: Boolean get() = record != null

    /** What has been said so far, as a WAV file, without stopping: for words shown as they come. */
    fun snapshot(): ByteArray = pcm.toByteArray().let { if (it.isEmpty()) it else wav(it) }

    /** How many seconds have been recorded. */
    val seconds: Float get() = pcm.size() / (RATE * 2f)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (record != null) return
        pcm.reset()
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, RATE),
        )
        record = audio
        audio.startRecording()
        job = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(maxOf(min, 4096))
            while (isActive && pcm.size() < MAX_BYTES) {
                val read = audio.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcm.write(buffer, 0, read)
                    _level.value = rms(buffer, read)
                } else if (read < 0) {
                    break
                }
            }
        }
    }

    /** Stop, and the recording as a WAV file's bytes (empty if nothing was heard). */
    suspend fun stop(): ByteArray {
        val audio = record ?: return ByteArray(0)
        record = null
        // Stop first: that returns the blocked `read`, so the loop sees it is cancelled and
        // ends, and only then is the buffer read -- never while it is still being written.
        withContext(Dispatchers.IO) { runCatching { audio.stop() } }
        job?.cancel()
        job?.join()
        audio.release()
        _level.value = 0f
        val body = pcm.toByteArray()
        pcm.reset()
        return if (body.isEmpty()) body else wav(body)
    }

    companion object {
        const val RATE = 16_000
        private const val MAX_BYTES = RATE * 2 * 120 // two minutes

        /** The loudness of [length] bytes of 16-bit little-endian samples, 0..1. */
        fun rms(buffer: ByteArray, length: Int): Float {
            val samples = length / 2
            if (samples == 0) return 0f
            var sum = 0.0
            for (i in 0 until samples) {
                val sample = ((buffer[i * 2 + 1].toInt() shl 8) or (buffer[i * 2].toInt() and 0xFF)).toShort() / 32768.0
                sum += sample * sample
            }
            return kotlin.math.sqrt(sum / samples).toFloat().coerceIn(0f, 1f)
        }

        fun wav(pcm: ByteArray): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(36 + pcm.size)
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(RATE)
                putInt(RATE * 2)
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(pcm.size)
            }
            return header.array() + pcm
        }
    }
}

/** Speech out: the instance's text-to-speech, played once. [playing] is true while it speaks. */
class Speaker(private val context: Context) {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    suspend fun play(mp3: ByteArray) {
        stop()
        val file = withContext(Dispatchers.IO) {
            File.createTempFile("reply", ".mp3", context.cacheDir).apply { writeBytes(mp3) }
        }
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                it.release()
                file.delete()
                player = null
                _playing.value = false
            }
            prepare()
            start()
        }
        _playing.value = true
    }

    fun stop() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
        _playing.value = false
    }
}

/**
 * Speech in through the phone's own recogniser, for Settings › Voice input › Phone: words as
 * they are said ([start]'s `onPartial`), and the end of speech found by the recogniser.
 *
 * While LLeMbas is the digital assistant the *system's* default recogniser is LLeMbas's own
 * ([LembasRecognitionService]), so this never asks for the default: it takes the on-device
 * recogniser (Android 12+), else another installed app's. No Google library is linked; a
 * phone without any recogniser simply has none to offer ([available]).
 */
class PhoneRecognizer(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun start(onPartial: (String) -> Unit, onLevel: (Float) -> Unit, onResult: (String) -> Unit, onFail: (Int) -> Unit) {
        stop()
        startWith(onDevice(context), onPartial, onLevel, onResult, onFail)
    }

    // `onFail`, not `onError`: inside the listener below that name is the listener's own method.
    private fun startWith(onDevice: Boolean, onPartial: (String) -> Unit, onLevel: (Float) -> Unit, onResult: (String) -> Unit, onFail: (Int) -> Unit) {
        val other = component(context)
        val made = when {
            onDevice && Build.VERSION.SDK_INT >= 31 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            other != null -> SpeechRecognizer.createSpeechRecognizer(context, other)
            else -> return onFail(SpeechRecognizer.ERROR_CLIENT)
        }
        recognizer = made
        made.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}

                override fun onBeginningOfSpeech() {}

                // About -2 dB in silence to 10 dB speaking: the meter's 0..1.
                override fun onRmsChanged(rmsdB: Float) = onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() = onLevel(0f)

                override fun onError(error: Int) {
                    // The on-device recogniser lacks this language: another app's may have it.
                    if (onDevice && other != null && (error == LANGUAGE_NOT_SUPPORTED || error == LANGUAGE_UNAVAILABLE)) {
                        stop()
                        startWith(false, onPartial, onLevel, onResult, onFail)
                        return
                    }
                    stop()
                    onFail(error)
                }

                override fun onResults(results: Bundle?) {
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    stop()
                    onResult(text)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }?.let(onPartial)
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            },
        )
        made.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName),
        )
    }

    /** Stop listening now; what was heard comes to `onResult`. */
    fun finish() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.runCatching {
            cancel()
            destroy()
        }
        recognizer = null
    }

    companion object {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED and ERROR_LANGUAGE_UNAVAILABLE (API 31).
        private const val LANGUAGE_NOT_SUPPORTED = 12
        private const val LANGUAGE_UNAVAILABLE = 13

        private fun onDevice(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

        /** Another app's recogniser -- never LLeMbas's own, which would send it back to the instance. */
        fun component(context: Context): ComponentName? = context.packageManager
            .queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .firstOrNull { it.packageName != context.packageName }

        /** Whether this phone has a recogniser of its own to offer. */
        fun available(context: Context): Boolean = onDevice(context) || component(context) != null
    }
}
