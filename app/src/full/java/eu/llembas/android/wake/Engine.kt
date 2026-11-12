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
 * The model's files are copied out of the APK's assets into the app's no-backup files
 * ([unpackWakeModel]: by stream, keyed by the model's version), so the engine reads them as files
 * beside a keywords file written for the chosen phrase.
 */
object WakeEngine {
    const val AVAILABLE = true

    private val FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    /**
     * The engine for [keywords] (the keywords file's text). Throws a [WakeError] that says which
     * half failed -- the model's files or the engine -- and never returns nothing.
     *
     * One at a time: two service instances may overlap for a moment (the wake word turned off and
     * on again), and the unpacking and the keywords file are shared. The keywords go into a file
     * of this call's own, which the engine reads as it is made and which is gone afterwards.
     */
    @Synchronized
    fun create(context: Context, keywords: String): WakeDetector {
        val dir = File(context.noBackupFilesDir, "wakeword")
        try {
            unpackWakeModel({ context.assets.open("wakeword/$it") }, dir, FILES)
        } catch (e: Exception) {
            throw WakeError(WakeFailure.Model, e)
        }
        checkKeywords(keywords, File(dir, "tokens.txt").readText())
        var file: File? = null
        try {
            val written = File.createTempFile("keywords", ".txt", dir)
            file = written
            written.writeText(keywords)
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
                keywordsFile = written.path,
                // The engine's own default (the Kotlin wrapper's is 2). The phrase counts once
                // MORE than this many blank frames follow it -- two frames, about 80 ms of
                // anything that is not the phrase going on. So: say it, then a short pause; a
                // question run straight into the name can be missed.
                numTrailingBlanks = 1,
            )
            return SherpaDetector(KeywordSpotter(null, config))
        } catch (e: Throwable) {
            // UnsatisfiedLinkError included: a phone whose processor the library was not built for.
            throw WakeError(WakeFailure.Engine, e)
        } finally {
            file?.delete()
        }
    }

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
