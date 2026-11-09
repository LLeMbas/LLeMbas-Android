package eu.llembas.android.shots

import eu.llembas.android.api.ActivitySummary
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.Chat
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Message
import eu.llembas.android.api.Metrics
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.Schedule
import eu.llembas.android.api.SchedulePreview
import eu.llembas.android.api.ScheduleRun
import eu.llembas.android.api.Step
import eu.llembas.android.api.Tail
import eu.llembas.android.api.ToolBlock
import eu.llembas.android.api.ToolCall
import eu.llembas.android.auth.Instance
import eu.llembas.android.auth.Prefs
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Draft
import eu.llembas.android.ui.home.ChatsState
import eu.llembas.android.ui.scheduled.EditState
import eu.llembas.android.ui.scheduled.ScheduleState
import eu.llembas.android.ui.scheduled.ScheduledState
import eu.llembas.android.ui.settings.SettingsState
import java.time.LocalDate

/** Made-up data for the screenshots: the server's own contract files where they fit. */
object Fakes {
    private fun text(name: String): String = requireNotNull(javaClass.classLoader!!.getResource("$name.json")).readText()

    /** The day the drawer is drawn on: chats updated on it are "Today". */
    val today: LocalDate = LocalDate.of(2026, 3, 10)

    val models = LembasJson.decodeFromString(ModelList.serializer(), text("models")).models
    val question = LembasJson.decodeFromString(Message.serializer(), text("message_user"))
    val answer = LembasJson.decodeFromString(Message.serializer(), text("message_assistant"))

    private val packing = Message(
        id = "m3",
        role = "user",
        text = "Plan a three-day walk to the hills. What should I pack?",
        attachments = listOf(Attachment(id = "a1", name = "map.jpg", image = true), Attachment(id = "a2", name = "route.gpx", mime = "application/gpx+xml")),
    )

    private val packingSteps = listOf(
        Step(
            index = 0,
            kind = "thinking",
            text = "Three days, hills, early spring: warm layers, water, food that keeps.",
            label = "for 4 seconds · 120 tokens",
        ),
        Step(
            index = 0,
            kind = "tools",
            calls = listOf(
                ToolCall(id = "c1", name = "web_search", status = "ok", block = ToolBlock(kind = "web", title = "weather in the hills next week")),
                ToolCall(
                    id = "c2",
                    name = "read",
                    status = "ok",
                    text = "Day 1: 18 km\nDay 2: 22 km\nDay 3: 15 km",
                    block = ToolBlock(kind = "read", title = "notes/route.md"),
                ),
            ),
        ),
        Step(
            index = 1,
            kind = "text",
            markdown = "Here is a list for **three days**, light enough to carry:\n\n" +
                "- Lembas, wrapped in leaves (one bite fills a grown man)\n" +
                "- A warm cloak and a spare pair of socks\n" +
                "- Two litres of water, and a way to refill it\n\n" +
                "```python\ndef pack(days):\n    return [\"lembas\"] * days + [\"cloak\", \"rope\"]\n```\n\n" +
                "The second day is the longest: start early.",
        ),
    )

    private val packingAnswer = Message(
        id = "m4",
        role = "assistant",
        model = "qwen",
        steps = packingSteps,
        metrics = Metrics(completionTokens = 412, elapsedMs = 9000, tokPerSecond = 45.8),
    )

    val chat = Chat(id = "c1", title = "Packing for the road", model = "qwen", updatedAt = "2026-03-10T09:00:00+00:00")

    val newChat = ChatState(models = models)

    val newAgentChat = ChatState(
        models = models,
        devices = listOf(LinkedDevice(id = "d1", name = "Workstation", host = "workstation"), LinkedDevice(id = "d2", name = "Laptop", host = "laptop")),
        draft = Draft(directory = "~/src/garden"),
    )

    val transcript = ChatState(
        chat = chat,
        messages = listOf(question, answer, packing, packingAnswer),
        models = models,
        teamOffered = true,
        summary = ActivitySummary(working = 2, active = 2, total = 2),
    )

    /** The last reply while it is being written: its tools done, its words coming in. */
    val writing = transcript.copy(
        messages = listOf(question, answer, packing, packingAnswer.copy(steps = emptyList(), complete = false)),
        live = LiveReply(messageId = "m4", steps = packingSteps.take(2), tail = Tail(text = "Here is a list for **three days**, light enough")),
        summary = null,
    )

    /** A chat on a model with effort levels, a team and a plan: the tune sheet with everything. */
    val tuned = transcript.copy(
        chat = chat.copy(model = "gpt-oss", effort = "high", mode = "plan", agents = AgentsSetting(1, 4)),
    )

    val chats = ChatsState(
        chats = listOf(
            chat,
            Chat(id = "c2", title = "Repairing the garden gate", kind = "agent", updatedAt = "2026-03-10T08:00:00+00:00", running = true),
            Chat(id = "c3", title = "A letter to the mayor about the bridge", updatedAt = "2026-03-10T07:00:00+00:00", unread = true),
            Chat(id = "c4", title = "Sourdough timings", updatedAt = "2026-03-09T18:00:00+00:00"),
            Chat(id = "c5", title = "Translate the recipe into Slovak", updatedAt = "2026-03-08T10:00:00+00:00"),
            Chat(id = "c6", title = "Why do leaves keep bread fresh, and does it work with any leaf or only some?", updatedAt = "2026-03-01T10:00:00+00:00"),
            Chat(id = "c7", title = "Clean up the build scripts", kind = "agent", updatedAt = "2026-03-02T10:00:00+00:00"),
        ),
    )

    private val home = Instance(id = "i1", base = "https://llm.example.org", name = "Home", email = "frodo@example.org", sealedToken = "")
    private val work = Instance(id = "i2", base = "https://ai.example.com", name = "Work", email = "f.baggins@example.com", sealedToken = "")

    val settings = SettingsState(
        instances = listOf(home, work),
        active = home,
        prefs = Prefs(theme = "", assistantTemporary = true),
        hasDistributor = true,
        version = "1.0.0-beta.3",
        selfUpdate = true,
    )

    private val ok =
        ScheduleRun(id = "r1", dueAt = "2026-03-10T06:30:00+00:00", startedAt = "2026-03-10T06:30:02+00:00", status = "ok", model = "qwen", chatId = "c9")
    private val nextRuns = (11..17).map { "2026-03-${it}T06:30:00+00:00" }

    val schedules = listOf(
        Schedule(
            id = "s1",
            title = "Morning news on renewables",
            instruction = "Sum up the news about renewable energy from the last day, in five points.",
            rule = "Every weekday at 07:30",
            nextRuns = nextRuns,
            lastRun = ok,
            model = "qwen",
            notify = "matters",
        ),
        Schedule(
            id = "s2",
            title = "Check the garden sensors",
            instruction = "Read the sensors and tell me if the soil is too dry.",
            rule = "Every 3 hours",
            nextRuns = listOf("2026-03-10T12:00:00+00:00"),
            lastRun = ScheduleRun(id = "r2", status = "silent"),
        ),
        Schedule(
            id = "s3",
            title = "Weekly backup report",
            instruction = "Summarise last week's backups and anything that failed.",
            rule = "Every Monday at 09:00",
            paused = true,
            lastRun = ScheduleRun(id = "r3", status = "failed", error = "The model could not be reached."),
        ),
        Schedule(
            id = "s4",
            title = "Dentist",
            instruction = "Remind me to call the dentist.",
            rule = "Once, on Thursday at 10:00",
            nextRuns = listOf("2026-03-12T09:00:00+00:00"),
            lastRun = ScheduleRun(id = "r4", status = "waiting"),
        ),
    )

    val scheduled = ScheduledState(schedules = schedules)

    val schedule = ScheduleState(
        schedule = schedules[0],
        runs = listOf(
            ok,
            ScheduleRun(
                id = "r0",
                startedAt = "2026-03-09T06:41:00+00:00",
                status = "ok",
                model = "gpt-oss",
                modelReason = "switched: qwen busy",
                chatId = "c8",
            ),
            ScheduleRun(id = "r-1", dueAt = "2026-03-06T06:30:00+00:00", status = "silent", model = "qwen"),
            ScheduleRun(id = "r-2", dueAt = "2026-03-05T06:30:00+00:00", status = "missed", missed = 2),
            ScheduleRun(
                id = "r-3",
                startedAt = "2026-03-04T06:30:00+00:00",
                status = "failed",
                model = "qwen",
                error = "The model stopped answering after 10 minutes.",
            ),
        ),
    )

    val editing = EditState(
        words = "Every weekday at 7:30, sum up the news about renewable energy in five points",
        preview = SchedulePreview(
            title = "Morning news on renewables",
            ruleText = "Every weekday at 07:30",
            instruction = "Sum up the news about renewable energy from the last day, in five points.",
            nextRuns = nextRuns,
        ),
    )
}
