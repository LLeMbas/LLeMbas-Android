package eu.llembas.android.wake

import android.content.Context

/**
 * The F-Droid build has no wake-word engine: F-Droid builds every library from source, and the
 * engine (sherpa-onnx, Apache-2.0) comes to the other build as its project's prebuilt Android
 * library. Settings says so; everything else about the wake word is the same code in both.
 */
object WakeEngine {
    const val AVAILABLE = false

    fun create(context: Context, phrase: WakePhrase): WakeDetector? = null
}
