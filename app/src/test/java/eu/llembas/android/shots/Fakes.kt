package eu.llembas.android.shots

import eu.llembas.android.api.ActivityAgent
import eu.llembas.android.api.ActivityEnvelope
import eu.llembas.android.api.ActivitySummary
import eu.llembas.android.api.AgentStep
import eu.llembas.android.api.AgentsSetting
import eu.llembas.android.api.Attachment
import eu.llembas.android.api.Chat
import eu.llembas.android.api.ContextPart
import eu.llembas.android.api.DataGroup
import eu.llembas.android.api.Folder
import eu.llembas.android.api.GeneratedImage
import eu.llembas.android.api.LembasJson
import eu.llembas.android.api.LinkedDevice
import eu.llembas.android.api.LiveReply
import eu.llembas.android.api.Memory
import eu.llembas.android.api.Message
import eu.llembas.android.api.Metrics
import eu.llembas.android.api.ModelList
import eu.llembas.android.api.Progress
import eu.llembas.android.api.RunError
import eu.llembas.android.api.RunModel
import eu.llembas.android.api.RunResult
import eu.llembas.android.api.RunTokens
import eu.llembas.android.api.Schedule
import eu.llembas.android.api.ScheduleCompiled
import eu.llembas.android.api.ScheduleRun
import eu.llembas.android.api.SchedulingModel
import eu.llembas.android.api.SchedulingModels
import eu.llembas.android.api.SearchHit
import eu.llembas.android.api.Step
import eu.llembas.android.api.SubagentTree
import eu.llembas.android.api.SubagentsOffer
import eu.llembas.android.api.Tail
import eu.llembas.android.api.ToolBlock
import eu.llembas.android.api.ToolCall
import eu.llembas.android.api.TreeAgent
import eu.llembas.android.api.TreeContext
import eu.llembas.android.assist.AssistTurn
import eu.llembas.android.assist.AssistUiState
import eu.llembas.android.assist.Connection
import eu.llembas.android.assist.LastAssist
import eu.llembas.android.auth.Instance
import eu.llembas.android.auth.Prefs
import eu.llembas.android.ui.chat.ChatState
import eu.llembas.android.ui.chat.Draft
import eu.llembas.android.ui.home.ChatsState
import eu.llembas.android.ui.memory.MemoryState
import eu.llembas.android.ui.scheduled.EditState
import eu.llembas.android.ui.scheduled.ModelsState
import eu.llembas.android.ui.scheduled.RunState
import eu.llembas.android.ui.scheduled.ScheduleState
import eu.llembas.android.ui.scheduled.ScheduledState
import eu.llembas.android.ui.settings.SettingsState
import kotlinx.serialization.json.jsonObject
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

    // A picture the instance made, and the composer asking for the next one.
    val picture = ChatState(
        chat = chat,
        models = models,
        imagesOffered = true,
        draft = Draft(image = true),
        messages = listOf(
            Message(id = "p1", role = "user", text = "Draw lembas wrapped in a mallorn leaf, on a wooden table."),
            Message(
                id = "p2",
                role = "assistant",
                model = "qwen",
                steps = listOf(
                    Step(
                        index = 0,
                        kind = "tools",
                        calls = listOf(
                            ToolCall(
                                id = "g1",
                                name = "image_generate",
                                status = "ok",
                                image = GeneratedImage(id = "att-picture", width = 1216, height = 832),
                                block = ToolBlock(kind = "image", title = "lembas in a mallorn leaf"),
                            ),
                        ),
                    ),
                    Step(index = 1, kind = "text", markdown = "Here it is: the leaf folded twice, as the elves wrap it."),
                ),
            ),
        ),
    )

    val newChat = ChatState(models = models)

    val newAgentChat = ChatState(
        models = models,
        devices = listOf(LinkedDevice(id = "d1", name = "Workstation", host = "workstation"), LinkedDevice(id = "d2", name = "Laptop", host = "laptop")),
        draft = Draft(directory = "~/src/garden"),
    )

    /** A new agent chat with no computer linked: the steps to link one. */
    val newAgentChatUnlinked = ChatState(models = models, instanceBase = "https://llm.example.org")

    val transcript = ChatState(
        chat = chat,
        messages = listOf(question, answer, packing, packingAnswer),
        models = models,
        teamOffered = true,
        summary = ActivitySummary(working = 2, queued = 1, done = 4, finished = 4, active = 3, total = 7),
    )

    private val fixture = LembasJson.decodeFromString(ActivityEnvelope.serializer(), text("activity")).activity

    private fun job(id: String, type: String, role: String, brief: String, state: String, seconds: Int, stoppable: Boolean = true) = ActivityAgent(
        kind = "job",
        id = id,
        type = type,
        name = id,
        role = role,
        brief = brief,
        state = state,
        seconds = seconds,
        model = if (type == "command") "" else "qwen",
        agent = if (type == "command") "" else "explore",
        manager = "lead",
        stoppable = stoppable,
        transcript = type != "command",
    )

    /**
     * The activity sheet with everything in it, as the reference has it: a context two thirds
     * free with auto-compact ahead, a team with a member under the lead, subagents and commands
     * running, four finished, the room and no plan.
     */
    val activity = fixture.copy(
        summary = ActivitySummary(queued = 1, working = 4, done = 4, finished = 4, active = 5, total = 9),
        agents = listOf(
            fixture.agents[0].copy(seconds = 1_135, step = AgentStep(tool = "edit", label = "Edit", title = "recipe.md", running = true)),
            job("job2", "subagent", "Find where the waybread recipe is used", "Search the notes for every use of the recipe", "working", 97),
            job("job3", "command", "Run related test files", "pytest -q tests/test_bake.py", "working", 40),
            fixture.agents[1].copy(role = "Relax status, rerun targeted tests", seconds = 39),
        ) + listOf(
            job("job4", "subagent", "Read the old packing list", "", "done", 64, stoppable = false),
            job("job5", "command", "Lint the notes", "ruff check", "done", 12, stoppable = false),
            job("job6", "subagent", "Check the weather on the pass", "", "error", 8, stoppable = false),
            job("job7", "command", "Count the provisions", "wc -l list.md", "stopped", 3, stoppable = false),
        ),
        context = fixture.context!!.copy(
            used = 41_200,
            limit = 128_000,
            percent = 32.2,
            categories = listOf(
                ContextPart("messages", 26_300, 20.5),
                ContextPart("memory", 2_400, 1.9),
                ContextPart("mcp_tools", 2_300, 1.8),
                ContextPart("tools", 2_000, 1.6),
                ContextPart("instructions", 1_300, 1.0),
                ContextPart("system", 500, 0.4),
                ContextPart("since", 200, 0.1),
                ContextPart("buffer", 6_400, 5.0),
                ContextPart("free", 80_400, 62.8),
            ),
        ),
    )

    /** The last reply while it is being written: its tools done, its words coming in. */
    val writing = transcript.copy(
        messages = listOf(question, answer, packing, packingAnswer.copy(steps = emptyList(), complete = false)),
        live = LiveReply(
            messageId = "m4",
            steps = packingSteps.take(2),
            tail = Tail(text = "Here is a list for **three days**, light enough"),
            status = "Writing",
            progress = Progress(phase = "writing", since = 1762790400000, rate = 12.46),
        ),
        summary = null,
    )

    /** Waiting for a model, the long reason: the progress line at its widest. */
    val waitingForModel = writing.copy(
        live = writing.live!!.copy(progress = Progress(phase = "waiting_for_model", since = 1762790400000, model = "Qwen 27B", reason = "busy")),
    )

    /**
     * The subagent tree beside the activity sheet (spec 3.4.0's tree): the main agent, a
     * sub-manager with one under it paused, one waiting for a slot, an open slot and one ended.
     */
    val tree = SubagentTree(
        min = 2,
        max = 5,
        size = 4,
        openSlots = 1,
        agents = listOf(
            TreeAgent(
                id = "lead", name = "lead", position = "the main agent", state = "working", model = "qwen", mode = "edit", manager = true,
                context = TreeContext(41_200, 131_072), progress = Progress(phase = "waiting_for_subagents", since = 1762790400000, running = 2),
            ),
            TreeAgent(
                id = "s1", name = "parser", position = "reviews the parser and everything that reads its output", parent = "lead",
                state = "working", model = "qwen", mode = "edit", manager = true, context = TreeContext(9_100, 131_072),
                progress = Progress(phase = "running_tool", since = 1762790401000, tool = "read", what = "src/parse.ts"),
            ),
            TreeAgent(
                id = "s3", name = "lexer", position = "the lexer's tests", parent = "parser", state = "paused",
                model = "qwen", mode = "manual", context = TreeContext(112_000, 131_072), progress = Progress(phase = "thinking", since = 1762790402000),
            ),
            TreeAgent(id = "s4", name = "docs", position = "writes the changelog", parent = "lead", state = "waiting", model = "qwen", mode = "edit"),
        ),
        ended = listOf(
            TreeAgent(
                id = "s2",
                name = "scout",
                position = "finds the old parser",
                parent = "lead",
                state = "out_of_context",
                ended = 1762790300000,
                findings = 2,
            ),
        ),
    )

    /** A chat on a model with effort levels, a team and a plan: the tune sheet with everything. */
    val tuned = transcript.copy(
        chat = chat.copy(model = "gpt-oss", effort = "high", mode = "plan", agents = AgentsSetting(1, 4)),
    )

    /** A chat whose model serves one request at a time, on an instance that caps subagents at 6. */
    val tunedOneAtATime = tuned.copy(
        chat = tuned.chat!!.copy(agents = AgentsSetting(2, 3), subagents = SubagentsOffer(offered = true, namedOnly = true, cap = 6)),
    )

    /** The agent alone: Min 1 · Max 1, no subagents, on an instance that caps a chat at 8. */
    val tunedAlone = tuned.copy(
        chat = tuned.chat!!.copy(agents = AgentsSetting(1, 1), subagents = SubagentsOffer(offered = true, cap = 8)),
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
            Chat(id = "c8", title = "The cram recipe, for the long road", folderId = "f2", pinned = true, updatedAt = "2026-02-20T10:00:00+00:00"),
            Chat(id = "c9", title = "Honey cakes for the party", folderId = "f2", updatedAt = "2026-02-21T10:00:00+00:00"),
            Chat(id = "c10", title = "Which leaves to wrap it in", folderId = "f1", updatedAt = "2026-02-22T10:00:00+00:00"),
        ),
        folders = listOf(
            Folder(id = "f1", name = "Baking", position = 0),
            Folder(id = "f2", name = "Recipes from the Shire, the long list", parentId = "f1", position = 0),
            Folder(id = "f3", name = "Letters", position = 1, collapsed = true),
        ),
    )

    // Memory: one the person wrote, two a model did, in two data groups.
    val memory = MemoryState(
        memories = listOf(
            Memory("m1", "Prefers lembas warm, with a little honey.", author = "user", dataGroup = DataGroup("default", "Default")),
            Memory(
                "m2",
                "Is walking to the mountain with three friends and asks for recipes that keep for weeks without spoiling on the road.",
                author = "model",
                dataGroup = DataGroup("default", "Default"),
            ),
            Memory("m3", "Writes the garden's diary in Slovak.", author = "model", dataGroup = DataGroup("garden", "The garden")),
        ),
        groups = listOf(DataGroup("default", "Default"), DataGroup("garden", "The garden")),
    )

    val chatsFound = chats.copy(
        foundFor = "bread",
        found = listOf(
            SearchHit(
                kind = "chat",
                id = "c4",
                title = "Sourdough timings",
                snippet = "…leave the bread to rise overnight, then bake it at dawn…",
                marks = listOf(listOf(11, 16)),
            ),
            SearchHit(
                kind = "chat",
                id = "c6",
                title = "Why do leaves keep bread fresh, and does it work with any leaf or only some?",
                snippet = "Mallorn leaves keep waybread fresh for weeks; other leaves dry the bread out within days.",
                marks = listOf(listOf(20, 28), listOf(67, 72)),
            ),
        ),
    )

    private val home = Instance(id = "i1", base = "https://llm.example.org", name = "Home", email = "frodo@example.org", sealedToken = "")
    private val work = Instance(id = "i2", base = "https://ai.example.com", name = "Work", email = "f.baggins@example.com", sealedToken = "")

    val settings = SettingsState(
        instances = listOf(home, work),
        active = home,
        prefs = Prefs(theme = "", assistantTemporary = true, accountLanguage = "en"),
        hasDistributor = true,
        version = "1.0.0-beta.3",
        selfUpdate = true,
        phoneRecognizer = true,
    )

    // The assistant's sheet, as it opens and as it answers.
    private val assistBase = AssistUiState(
        instanceName = "Home",
        connection = Connection.Online,
        models = models,
        modelId = models.firstOrNull { it.default }?.id ?: models.firstOrNull()?.id,
        canSpeak = true,
    )

    /** Just opened over an app that handed over its screen: listening, the words coming in. */
    val assistListening = assistBase.copy(
        listening = true,
        heard = "How long does waybread keep if it is wrapped in",
        level = 0.4f,
        screenText = true,
        last = LastAssist("c1", "Packing for the road", 0),
    )

    /** Opened from the tile, nothing asked yet: the last chat, the help, the microphone. */
    val assistOpened = assistBase.copy(last = LastAssist("c1", "Packing for the road", 0), screenText = true, includeScreen = true)

    /** A spoken question answered, the answer read aloud. */
    val assistAnswered = assistBase.copy(
        chatId = "c9",
        turns = listOf(AssistTurn(question.text.orEmpty(), earlier = answer.steps)),
        speaking = true,
    )

    /** An answer being written. */
    val assistWriting = assistBase.copy(
        chatId = "c9",
        busy = true,
        turns = listOf(
            AssistTurn(
                "What is on my screen?",
                reply = LiveReply("m9", tail = Tail(text = "It is a recipe for waybread: flour, honey and a leaf to wrap it in. The"), status = "Writing…"),
            ),
        ),
    )

    /** The instance cannot be reached. */
    val assistOffline = assistBase.copy(
        connection = Connection.Offline,
        models = emptyList(),
        modelId = null,
        canSpeak = false,
        error = "Home cannot be reached.",
    )

    // Scheduled: shaped as the server's contract fixtures are (contract/schedule*.json).
    private fun rule(text: String) = LembasJson.parseToJsonElement(text).jsonObject

    private val ok = ScheduleRun(
        id = "r1",
        scheduleId = "s1",
        status = "ok",
        timing = "on_time",
        dueAt = "2026-03-10T07:30:00+00:00",
        startedAt = "2026-03-10T07:30:02+00:00",
        finishedAt = "2026-03-10T07:31:40+00:00",
        attempts = 1,
        model = RunModel(id = "qwen", name = "Qwen", reason = "pinned"),
        tokens = RunTokens(prompt = 1200, completion = 340),
        chatId = "c9",
        messageId = "m9",
        preview = "1. Two new offshore wind farms approved in the North Sea. 2. Solar output hit a March record in Spain.",
        notified = true,
    )
    private val nextRuns = listOf(11, 12, 13, 16, 17).map { "2026-03-${it}T07:30:00+00:00" }

    val schedules = listOf(
        Schedule(
            id = "s1",
            title = "Morning news on renewables",
            instruction = "Sum up the news about renewable energy from the last day, in five points.",
            request = "Every weekday at 7:30, sum up the news about renewable energy in five points",
            rule = rule("""{"at":{"weekdays":[0,1,2,3,4],"times":["07:30"]}}"""),
            description = "Every weekday at 07:30",
            timezone = "Europe/Bratislava",
            nextRun = nextRuns.first(),
            nextRuns = nextRuns,
            mode = "fresh",
            notify = "matters",
            model = "qwen",
            chatId = "c9",
            firedCount = 41,
            lastRun = ok,
        ),
        Schedule(
            id = "s2",
            title = "Check the garden sensors",
            instruction = "Read the sensors and tell me if the soil is too dry.",
            rule = rule("""{"every":{"hours":3}}"""),
            description = "Every 3 hours",
            nextRun = "2026-03-10T12:00:00+00:00",
            nextRuns = listOf("2026-03-10T12:00:00+00:00"),
            mode = "continue",
            notify = "matters",
            running = true,
            lastRun = ScheduleRun(id = "r2", scheduleId = "s2", status = "silent", timing = "on_time"),
        ),
        Schedule(
            id = "s3",
            title = "Weekly backup report",
            instruction = "Summarise last week's backups and anything that failed.",
            description = "Every Monday at 09:00",
            enabled = false,
            failureStreak = 3,
            lastRun = ScheduleRun(
                id = "r3",
                scheduleId = "s3",
                status = "failed",
                error = RunError(kind = "unreachable", text = "The model could not be reached."),
            ),
        ),
        Schedule(
            id = "s4",
            title = "Dentist",
            instruction = "Remind me to call the dentist.",
            description = "Once, on Thursday 12 March at 10:00",
            nextRun = "2026-03-12T09:00:00+00:00",
            nextRuns = listOf("2026-03-12T09:00:00+00:00"),
            notify = "always",
            lastRun = ScheduleRun(
                id = "r4",
                scheduleId = "s4",
                status = "waiting",
                waitingFor = "model",
                retryAt = "2026-03-10T07:35:00+00:00",
                attempts = 2,
                error = RunError(kind = "no_model", text = "Waiting for a free model: Qwen: it is answering someone."),
            ),
        ),
    )

    val scheduled = ScheduledState(schedules = schedules)

    private val switched = ScheduleRun(
        id = "r0",
        scheduleId = "s1",
        status = "ok",
        timing = "late",
        dueAt = "2026-03-09T07:30:00+00:00",
        startedAt = "2026-03-09T07:41:00+00:00",
        model = RunModel(id = "gpt-oss", name = "GPT-OSS", reason = "switched", switchedFrom = "Qwen"),
        chatId = "c8",
        preview = "Little happened over the weekend: one grid-storage tender in Austria.",
    )

    val schedule = ScheduleState(
        schedule = schedules[0],
        runs = listOf(
            ok,
            switched,
            ScheduleRun(id = "r-1", scheduleId = "s1", dueAt = "2026-03-06T07:30:00+00:00", status = "silent", model = RunModel(id = "qwen", name = "Qwen")),
            ScheduleRun(id = "r-2", scheduleId = "s1", dueAt = "2026-03-05T07:30:00+00:00", status = "missed", missed = 2),
            ScheduleRun(
                id = "r-3",
                scheduleId = "s1",
                startedAt = "2026-03-04T07:30:00+00:00",
                status = "failed",
                model = RunModel(id = "qwen", name = "Qwen"),
                error = RunError(kind = "timeout", text = "The model stopped answering after 10 minutes."),
            ),
        ),
    )

    val run = RunState(
        title = "Morning news on renewables",
        result = RunResult(
            run = switched,
            text = """
                |Little happened over the weekend. **One thing** worth knowing:
                |
                |1. Austria opened a tender for **200 MW** of grid storage, bids due in May.
                |2. A [report](https://example.org/report) puts Europe's March solar output 18 % above last year.
                |
                |```text
                |storage tender: 200 MW, deadline 2026-05-15
                |```
            """.trimMargin(),
        ),
    )

    val schedulingModels = ModelsState(
        models = SchedulingModels(
            models = listOf(
                SchedulingModel(id = "gpt-oss", name = "GPT-OSS", free = false, busy = "it is answering someone", chosen = true),
                SchedulingModel(id = "qwen", name = "Qwen", free = true, chosen = true),
                SchedulingModel(id = "gemma", name = "Gemma 3 27B", free = true, chosen = false),
            ),
            instance = listOf("gpt-oss", "qwen", "gemma"),
            mine = listOf("gpt-oss", "qwen"),
        ),
    )

    val editing = EditState(
        words = "Every weekday at 7:30, sum up the news about renewable energy in five points",
        compiled = ScheduleCompiled(
            ok = true,
            title = "Morning news on renewables",
            instruction = "Sum up the news about renewable energy from the last day, in five points.",
            rule = rule("""{"at":{"weekdays":[0,1,2,3,4],"times":["07:30"]}}"""),
            description = "Every weekday at 07:30",
            nextRuns = nextRuns,
            timezone = "Europe/Bratislava",
        ),
    )
}
