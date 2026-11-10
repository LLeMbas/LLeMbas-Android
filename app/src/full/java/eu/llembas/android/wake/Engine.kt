package eu.llembas.android.wake

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * The wake-word engine: sherpa-onnx's keyword spotter (Apache-2.0) with its small English model
 * (a 3.3M-parameter zipformer, int8, Apache-2.0), both fetched hash-pinned at build time by
 * `scripts/fetch-wakeword.py`. It runs on the phone's CPU, one thread, on the 16 kHz samples the
 * service hands it; nothing leaves the phone.
 *
 * The model's files are copied out of the APK's assets once, into the app's no-backup files, so
 * the engine reads them as files beside a keywords file written for the chosen phrase.
 */
object WakeEngine {
    const val AVAILABLE = true

    private val FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    fun create(context: Context, phrase: WakePhrase): WakeDetector? = runCatching {
        val dir = File(context.noBackupFilesDir, "wakeword").apply { mkdirs() }
        for (name in FILES) {
            val target = File(dir, name)
            val size = context.assets.openFd("wakeword/$name").use { it.length }
            if (!target.exists() || (size >= 0 && target.length() != size)) {
                context.assets.open("wakeword/$name").use { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        val keywords = File(dir, "keywords.txt").apply { writeText("${phrase.tokens} @${phrase.id}\n") }
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(dir, "encoder.int8.onnx").path,
                    decoder = File(dir, "decoder.int8.onnx").path,
                    joiner = File(dir, "joiner.int8.onnx").path,
                ),
                tokens = File(dir, "tokens.txt").path,
                modelType = "zipformer2",
                numThreads = 1,
            ),
            keywordsFile = keywords.path,
        )
        SherpaDetector(KeywordSpotter(null, config))
    }.getOrNull()

    private const val RATE = 16_000

    private class SherpaDetector(private val spotter: KeywordSpotter) : WakeDetector {
        private val stream: OnlineStream = spotter.createStream()

        override fun accept(samples: FloatArray): Boolean {
            stream.acceptWaveform(samples, RATE)
            while (spotter.isReady(stream)) {
                spotter.decode(stream)
                if (spotter.getResult(stream).keyword.isNotBlank()) {
                    spotter.reset(stream)
                    return true
                }
            }
            return false
        }

        override fun release() {
            stream.release()
            spotter.release()
        }
    }
}
