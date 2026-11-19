package eu.llembas.android.shots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.llembas.android.api.GeneratedMedia
import eu.llembas.android.api.MediaJob
import eu.llembas.android.api.Message
import eu.llembas.android.api.Step
import eu.llembas.android.api.ToolBlock
import eu.llembas.android.api.ToolCall
import eu.llembas.android.ui.chat.ChatActions
import eu.llembas.android.ui.chat.ChatContent
import eu.llembas.android.ui.chat.JobCard
import eu.llembas.android.ui.chat.JobSeen
import eu.llembas.android.ui.chat.MediaActions
import eu.llembas.android.ui.chat.MediaCard
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Generated media's cards, and a chat that holds them, each from made-up state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "xhdpi")
class MediaShots {
    private val actions = object : MediaActions {}
    private val made = "/home/frodo/Generated"

    private val video = GeneratedMedia(
        kind = "video",
        path = "$made/video-20260102-030405.mp4",
        nodeId = "node7",
        name = "video-20260102-030405.mp4",
        mime = "video/mp4",
        size = 2_400_000,
        width = 832,
        height = 480,
        seconds = 5.0,
    )
    private val music = GeneratedMedia(
        kind = "music",
        path = "$made/a-walking-song-for-the-long-road-east-in-the-evening-20260102-030405.mp3",
        nodeId = "node9",
        name = "a-walking-song-for-the-long-road-east-in-the-evening-20260102-030405.mp3",
        mime = "audio/mpeg",
        size = 4_100_000,
        seconds = 187.0,
    )
    private val model = GeneratedMedia(kind = "model3d", path = "$made/ring-20260102.glb", nodeId = "node10", name = "ring-20260102.glb", size = 880_000)
    private val picture =
        GeneratedMedia(kind = "image", path = "$made/kite.png", nodeId = "node8", name = "kite.png", mime = "image/png", width = 1024, height = 576)

    private val running = MediaJob(id = "mjob1", kind = "video", state = "running", active = true, source = "Studio · Text to video", seconds = 95.0)
    private val queued = MediaJob(id = "mjob4", kind = "music", state = "queued", active = true)
    private val failed = running.copy(
        id = "mjob3",
        state = "failed",
        active = false,
        error = "The workflow ran out of video memory while sampling. Nothing was made.",
    )
    private val cancelled = running.copy(id = "mjob5", kind = "model3d", state = "cancelled", active = false, error = "The far side was told to stop.")

    @Test
    fun cards() {
        val drawn: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MediaCard(video, null, actions)
                MediaCard(music, null, actions)
                MediaCard(model, null, actions)
                JobCard(JobSeen(running, at = 1), actions)
                JobCard(JobSeen(queued, at = 1), actions)
                JobCard(JobSeen(failed, at = 1), actions)
                JobCard(JobSeen(cancelled, at = 1), actions)
            }
        }
        shoot("60-media-cards", Phone.Scroll, content = drawn)
        shoot("60-media-cards", Phone.SmallScroll, fontScale = 1.5f, content = drawn)
    }

    @Test
    fun aChatWithGeneratedMedia() {
        fun call(id: String, media: GeneratedMedia? = null, job: MediaJob? = null) = ToolCall(
            id = id,
            name = "video_generate",
            kind = "image",
            status = "ok",
            media = media,
            mediaJob = job?.let { eu.llembas.android.api.MediaJobRef(it.id, it.kind, "running") },
            block = ToolBlock(kind = "tool", title = "a kite over the dunes", state = "done", label = "Video"),
        )
        val reply = Message(
            id = "m9",
            role = "assistant",
            steps = listOf(
                Step(index = 0, kind = "tools", calls = listOf(call("c1", media = picture))),
                Step(index = 1, kind = "tools", calls = listOf(call("c2", job = running))),
                Step(index = 2, kind = "text", markdown = "The picture is here. The video is being made; it takes a few minutes."),
            ),
        )
        val state = Fakes.transcript.copy(
            messages = listOf(Message(id = "m8", role = "user", text = "A kite over the dunes, as a picture and as a video."), reply),
            mediaJobs = mapOf("mjob1" to JobSeen(running, at = 1)),
            summary = null,
        )
        shoot("61-chat-media") { ChatContent(state, object : ChatActions {}, agent = false, media = actions) }
        shoot("61-chat-media", Phone.Small, fontScale = 1.5f) { ChatContent(state, object : ChatActions {}, agent = false, media = actions) }
    }
}
