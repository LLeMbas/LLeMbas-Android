package eu.llembas.android.assist

import android.Manifest
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    val recording: Boolean get() = record != null

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
        val body = pcm.toByteArray()
        pcm.reset()
        return if (body.isEmpty()) body else wav(body)
    }

    companion object {
        const val RATE = 16_000
        private const val MAX_BYTES = RATE * 2 * 120 // two minutes

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

/** Speech out: the instance's text-to-speech, played once. */
class Speaker(private val context: Context) {
    private var player: MediaPlayer? = null

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
            }
            prepare()
            start()
        }
    }

    fun stop() {
        player?.runCatching {
            stop()
            release()
        }
        player = null
    }
}
