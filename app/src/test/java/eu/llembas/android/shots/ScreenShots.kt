package eu.llembas.android.shots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.llembas.android.ui.chat.Steps
import eu.llembas.android.ui.chat.UserBubble
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app's screens with made-up data ([Fakes]), as a phone shows them. See [shoot]. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xhdpi")
class ScreenShots {
    @Test
    fun transcript() = shoot("transcript") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            UserBubble(Fakes.question)
            Steps(Fakes.answer.steps)
        }
    }
}
