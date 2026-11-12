# Changelog

What changed in each release of LLeMbas for Android. Versions follow SemVer; until 1.0.0 the app
is in alpha and releases come off `main`.

## [Unreleased] — for 1.0.0-beta.8

### Fixed

- **The wake word never started, on any phone.** The engine read its model out of the APK in a
  way that only works for files stored uncompressed, and they were not; the failure was swallowed,
  so the notification went on saying it listened. The model is now copied out by stream (once per
  model version, so an update that brings a new model replaces the old one) and stored
  uncompressed as well.
- **The wake word's notification told the default phrase, not yours**, each time the app was
  opened. It is now written from one place, from what the wake word is really doing.
- A change of phrase could release the engine while the old listening loop was still using it.
  The old loop is now stopped and waited for first.
- A microphone that failed to start, or stopped giving sound, was not noticed (and one kind of
  failure kept a processor core busy for nothing). Both are failures that are shown now.
- Opening the app with the wake word on but the microphone permission taken away could close the
  app. The service is no longer started without the permission; the state says what is missing.

### Changed

- **Wake phrases the engine can hear.** "Hey Lembas" is gone: Lembas is not a word the engine's
  model was ever trained on, so it had to be repeated until it happened to land. The phrase is now
  a greeting and a name, each chosen in Settings › Wake word: **Hey**, **OK** or **Hello**, then
  **Assistant**, **Computer**, **Google** (Settings says a Google device nearby will answer too),
  or an ordinary name -- Victoria, Samantha, Jessica, Amanda, Olivia, Oliver, Sebastian, Jonathan,
  Alexander, Christopher. The default is "Hey Assistant", and a phrase saved by an earlier beta
  becomes it. The phrases are English whatever language the app is in, and Settings says so: the
  model knows English speech only.
- **Sensitivity** (Low, Normal, High) in Settings › Wake word: how easily the phrase is taken as
  heard, against how often it starts by mistake. Each phrase now goes to the engine with a
  boosting score and a trigger threshold (1.0 / 0.35, 1.5 / 0.25, 2.0 / 0.15; a short phrase such
  as "OK Computer" is held 0.05 stricter). These are starting values from the engine's
  documentation, not yet tuned on phones.
- The phrases' spelling in the model's token pieces is generated from the model's own tokenizer
  (`scripts/gen-wake-phrases.py`) and checked against its token table in CI, instead of written by
  hand. Elizabeth and Benjamin are left out: the tokenizer can only spell them letter by letter.
- **The wake word says what it is doing**, in its notification and under its switch in Settings:
  *Starting*, *Listening for "…"*, paused and why (screen off, battery saver, assistant open, no
  microphone permission, or the microphone gives no sound because another app has it or it is
  switched off), or not working and why (its model could not be unpacked, the engine did not
  start, the microphone did not start) -- with the technical reason under it in Settings. A
  failure is tried again by itself, after 2 s, 5 s, 15 s, a minute, then every five minutes.
- A microphone permission given or taken in the system's settings is noticed when the app comes
  back to the screen.

## [1.0.0-beta.7] — 2026-11-10

For LLeMbas server 2.0 (2.0.0-beta.10 or later for phone actions).

### Added

- **Share to LLeMbas, with a say in it.** *Ask LLeMbas* in any app's share menu now opens a
  sheet over that app: what came in (the text or link, the files by name), a line for what to do
  with it (optional), and where it goes -- a **new chat** or the **last assistant chat**. Files
  are uploaded and go with the message; the chat then opens in the app.
- **A home-screen widget**: *Ask anything* opens the assistant, the microphone opens it
  listening, and the clock opens the last assistant chat. It shows nothing from your account.
- **Spoken conversations with the assistant.** Ask by voice and, once the answer has been read
  aloud, the assistant listens again -- until you say "stop" (or "that's all"), tap **End**, or
  say nothing for a few seconds. The sheet says *In conversation* while it lasts. With a
  Bluetooth headset connected, its microphone is used, so the conversation can stay in your ear.
  Settings › Assistant › *Keep a spoken conversation going* (on; it needs voice replies).
- **A wake word, if you want one** (off by default). Settings › Assistant › *Wake word*: say
  "Hey Lembas" (or one of three other English phrases) and the assistant opens, listening, over
  whatever is on screen. It listens on the phone with an open-source engine (sherpa-onnx) and a
  small on-device model; nothing leaves the phone before the word, and a notification is up the
  whole time it listens. It pauses in battery saver, while the assistant is open, and -- unless
  you change it -- with the screen off. Settings explains what it costs in battery and how to
  keep a phone's battery optimisation from stopping it. Not in the F-Droid build, which builds
  every library from source.
- **"Summarise my notifications"**, if you allow it (off by default). Settings › Assistant ›
  *Notification summaries*, plus Android's own notification-access switch, which Settings opens
  for you. Then ask the assistant about "my notifications", or tap its chip, and the
  notifications on the phone right now go with that one question -- never before you ask, never
  while the phone is locked, and the app keeps none of them.
- **Phone actions** (with LLeMbas server 2.0 and harness spec 3.6.0): a model may offer to do
  something on this phone -- set an alarm or a timer, add a calendar event, draft a text or an
  email, open the dialler with a number, start a route (with stops), add a stop, search nearby,
  end the route, open an app or a web page, play/pause/skip, switch the torch, open a settings
  panel, or run a Tasker task. A card says what will happen; nothing happens until you tap
  **Do it**, never while the phone is locked, and drafts and calls are left for you to send and
  place. The chat (and the assistant's sheet) tells the model whether it was done, declined or
  failed. Navigation goes to Google Maps or **OsmAnd** (Settings › Navigation app: whichever is
  installed, or ask); OsmAnd can also be asked where you are and where the running route goes,
  once you allow LLeMbas inside OsmAnd.
- **Code in colour.** The file viewer highlights a source file by its language (as the server
  names it), and a reply's code blocks by their fence's language: comments, strings, numbers,
  keywords, type names, and markup's tags and attributes, in the theme's own colours in Shire
  and Moria. Still read-only; a language it does not know is shown plain.
- **Launcher shortcuts** (long-press the icon): Ask LLeMbas, Speak, Last chat.
- **A headset's voice button** opens the assistant listening, when LLeMbas answers it; over the
  lock screen it asks to unlock first.

### Changed

- The Quick Settings tile always shows as ready, never greyed out.

## [1.0.0-beta.6] — 2026-11-10

For LLeMbas server 2.0 (2.0.0-beta.9 or later for schedules on a computer, the update check through the instance, pinning, folders, memory and search).

### Added

- **The app tells you when a newer version is out.** Settings › About › *Look for new versions*:
  at most once a day, when the app starts, it asks your instance which release goes with it
  (an instance older than that reads the public list of LLeMbas for Android releases on GitHub;
  plain HTTPS, no Google service, nothing about you sent) and, when there is a newer one, says so in a notification that opens the release's page with its APKs. On by
  default in a beta, off in a release. *Include betas* offers test versions too (on by default
  in a beta). **Check for an update** asks at once, in either build; the full build can then
  install a release itself.
- **Archived chats**, in the drawer under Scheduled: every archived chat, newest first. A tap
  opens one; **Restore chat** puts it back among your chats.
- **Read aloud on a reply** in a chat, beside Copy, when your instance has text-to-speech: the
  same voice the assistant answers in. Tap again (or another reply's) to stop; leaving the chat
  stops it too.
- **Pinned chats and folders** in the drawer, as on the web: pinned chats at the top wherever
  they are filed, then your folders as a tree (a tap folds or opens one, and the web folds it
  too), then the rest. A long press on a chat offers **Pin chat** and **Move to folder**; on a
  folder, **Rename folder** and **Delete folder** (its chats stay). **New folder** is beside New
  chat at the top of the drawer.
- **Search finds words inside your chats.** Typing in the drawer's search box still filters
  the titles at once; a moment later the chats whose messages hold the words follow under *In
  the messages*, with the words marked.
- **Generate images** in a chat, when your instance makes pictures: from **+** in the composer,
  the next message asks for one (a new chat can start with a picture too), and the picture shows in the reply at its own size. A tap opens
  it whole. Only pictures your instance made and keeps are drawn; nothing is loaded from an
  address a reply names.
- **Memory**, in the drawer: what models remember about you, as Settings › Memory on the web.
  A tap changes or forgets one; **Remember** adds one (in the data group you pick, when you have
  more than one). What a model wrote is marked, and shown as plain text.
- **A scheduled task that runs on one of your computers** says which, in which folder and mode,
  on its page and in each run; a run that found the computer away says *not linked now*.
- **A scheduled task that watches a web page** says so on its page (*Only when this page
  changes*, and the address), and a run that found the page unchanged is shown as *Unchanged*.
- **A scheduled task's notification shows its result.** Unlocked, it carries the task's name
  and the first lines of what it found (pull it open for more); on the lock screen only
  *LLeMbas* and that a scheduled task finished or failed, nothing from your account. **Run again** starts the task again from the
  notification, once the phone is unlocked. Needs an instance that sends these; an older one
  keeps the fixed sentence.
- **A message waiting behind a reply says how many are ahead of it** and for how long it has
  waited, where the instance tells.

### Changed

- **Allow once and Deny on an approval's notification ask for the phone to be unlocked first**
  (Android 12 and newer), so a locked phone cannot answer for you. This holds for the
  approvals a scheduled run on a computer will send too.
- **The microphone in a chat follows Settings › Voice input**, as the assistant does: with
  *LLeMbas* it records and your instance writes the words down when you stop; with *Phone* the
  phone's own recogniser writes them into the box as you speak and stops at the end of what you
  said. The setting's explanation now says it covers both.

## [1.0.0-beta.5] — 2026-11-10

For LLeMbas server 2.0 (2.0.0-beta.8 or later for the progress line, the subagent list and the new agent chat's Subagents setting).

### Changed

- **The app speaks your account's language**, the one chosen on your instance, rather than the
  phone's: a phone set to Slovak signed in to an English account shows the app in English.
  **Settings › Language** chooses otherwise -- *Same as my account* (the default), *Phone
  language*, or any language the app has, each named in itself. On Android 13 and newer the
  system's own per-app language (Settings › Apps › LLeMbas › Language) offers the same
  languages, and a language chosen there is kept as your choice.
- **Agent chats are easy to find.** A new chat's page starts with **Chat** or **Agent chat**,
  one line each, side by side. With no computer linked, Agent chat is still there and says how
  to link one -- install the LLeMbas CLI, `lembas login` to this instance, allow remote work and
  start the service, each command with Copy -- with **Check again**.
- **The assistant is a sheet over the app you are in**, not a full screen. Holding the power
  button raises it to half the height, with the app still in sight under it; drag its handle up
  for the full height, down (or Back, or a tap outside) to close it.
  - **The microphone first**: it opens listening, and the words appear as you say them. A pause
    sends them; tap the microphone to send at once, or the keyboard to type instead. (Settings ›
    Assistant can turn the listening off.)
  - **The model and the connection** at the top: the model's picture and name, the instance and
    a dot that says whether it answers; a tap picks another model, which the assistant keeps.
  - **Use screen**: when Android hands the assistant the screen (its text, a screenshot, or
    both), one tap sends it with your next question -- never without the tap. Quick actions do
    it in one go: **Summarise this**, **Translate this**, **Explain this**.
  - **Continue the last chat** the assistant made, from where it stopped; and every question in
    the sheet carries on the same chat, until **New chat**.
  - **Voice reply**: a spoken question is answered aloud through your instance's text-to-speech
    (switch it off at the top), and **Read aloud** reads any answer.
  - **Open in app** carries on the same chat in the app.
  - Why there is no wake word is said where you look for one: on stock Android only the
    assistant built into the phone may listen for one.
- **Settings › Voice input** says what each choice is: **LLeMbas** is your instance's
  speech-to-text, through the server -- the recording goes to your instance and nowhere else;
  **Phone (Google or the system's)** is the phone's own recogniser -- words appear as you speak,
  and the recording goes wherever that recogniser sends it. No Google library is in the app: it
  uses whichever recogniser the phone has, and says so when it has none.

- **What a reply is doing, in the words every LLeMbas app uses.** Under a reply being written
  the app draws the server's progress -- *Queued*, *Waiting for* a model *and why*, *Loading*,
  *Reading the prompt: 1999 / 3000 tokens (66%)*, *Thinking*, *Writing: 12.5 tok/s*,
  *Running bash: bun test*, *Waiting for you*, *Waiting for subagents*, *Compacting*,
  *Retrying in 7 s*, *Stopped*, *Failed* -- with how long it has been at it, ticking each
  second (`· 1m 05s`). The same in the assistant's sheet. A server that does not send it yet
  is shown as before. A scheduled run under way says it too (*Waiting for coder: all its
  slots are in use*). The words are your instance's, in your language.

- **Subagents, as a list**: while a reply runs with subagents, the activity sheet
  shows the main agent and each subagent -- its name, its position, what it is doing now and
  how full its context is -- with the ones that ended after them, dimmed, saying how each ended
  (*done*, *out of context*, *stopped*), and the open slots. A subagent's row opens its
  conversation, read-only. In an agent chat the Subagents setting is there too -- a new one's
  included -- and goes to the computer the chat runs on, which runs them; a computer whose CLI
  is too old to is said beside it.
- **An approval says which subagent asks** -- *Subagent parser (s1), reviews the parser,
  asks:* -- when a subagent needs something nobody in its tree may allow.

### Fixed

- **A corrected command on an approval runs as corrected.** A linked computer's approval card
  now shows its heading (*Run a command*), the command itself in the box you can change, and
  the server's reason; Allow sends your correction only when you changed it. The card names the
  subagent asking, when one does. (Pinned to the server's current `dev` contract.)

## [1.0.0-beta.4] — 2026-11-09

For LLeMbas server 2.0 (2.0.0-beta.7 or later for the Subagents setting and the panel's compaction).

For LLeMbas server 2.0.0-beta.7 (an older 2.0 server works; the app then guesses whether
subagents are offered and works out auto-compact itself).

### Changed

- **Subagents, by that name.** Everything the app says about the work a chat hands off now
  calls it a subagent -- the strip, the activity sheet and the chat's options. The team's size
  in Options is **Subagents**, Min and Max -- shown when the instance says subagents are
  offered for the chat (or, for a new chat, for the model chosen), and only up to the most the
  instance allows. When the chat's model serves one request at a time, the setting says that
  subagents need another model, on another server, named in the chat. Which model a subagent
  runs on is not a setting: it is the chat's own, unless you name another in the chat.
- **Subagents is one slider with two thumbs, Min and Max**, from 1 to the most the instance
  allows, in whole agents and with the values beside it ("Min 1 · Max 4"). The numbers count the
  chat's own agent: **1 is the agent alone, with no subagents**, and Max 4 is the agent and up to
  3 subagents. The options button says the same ("Subagents: Min 1 · Max 4").
- **The activity sheet, laid out as a side panel is on a computer**, and always one tap away:
  the button at the top of every chat (a new one too) opens it as a sheet. **Context**: how
  much of the window is used, in per cent and in tokens of the window, a bar split by what
  fills it, each part with its tokens and share, how far it is to compacting by itself -- and a
  **Compact** button that does it now. **Background tasks**: what is running -- subagents, a
  team's members and background commands -- each on a card with how long it has run and a
  stop button (which asks first); a card opens to what it was asked, its latest step, what it
  is writing and its whole conversation, and folds those working under it into "N under it".
  The finished ones are folded away under **Finished**. Then the team's **Room** and the
  **Plan**.
- **The line above the message box** says what runs in the background -- "2 running · 1 queued
  · 4 finished" -- only while anything does, and opens the sheet.

## [1.0.0-beta.3] — 2026-11-09

For LLeMbas server 2.0 (2.0.0-beta.6 or later for scheduled tasks).

### Changed

- **A new look, shaped like the AI apps people know.** The app opens on a **new chat** -- the
  model's picture, a greeting, a few suggestions and the message box -- instead of a list.
  **Chats are in the drawer** (the menu button, or a swipe from the edge): search, New chat, New
  agent chat, Files, Scheduled, then today's chats, the earlier ones and the agent chats, with
  Settings and the account at the foot. Long-press a chat there to archive it.
- **The model is the title**: its picture and name at the top of the chat; tap it to choose
  another in a sheet that shows each model's picture, what it can do (sees images, uses tools,
  its context window) and how it thinks (a Thinking switch, or its effort levels).
- **The conversation reads cleaner**: your messages in bubbles on the right, the model's answers
  full width; its thinking and each round of tools fold into small chips you can open; code
  comes in its own block with the language and a Copy button; attached pictures show as
  thumbnails; three quiet dots while an answer is being written. Under an answer: Copy and
  Regenerate. A long press on a message: Copy, Select text, Edit (yours) or Regenerate.
- **The message box** is one rounded field: **+** to attach (camera, photos, files), **Options**
  for what the model offers -- Thinking on or off or the effort level, the team's size, Plan
  first (or an agent chat's mode, and a new agent chat's device and folder) -- the microphone,
  and Send, which becomes Stop while an answer is being written.
- **Settings** are grouped lists, and link to Android's notification settings for the app.

### Added

- **Scheduled**: in the drawer. Each scheduled task with its rule in words, how its last run
  went (or that it is running now) and when it runs next, and a switch to pause it; the list
  keeps itself current while it is open. A task's page has Run now (which does not use up the
  next scheduled run), Pause, its next runs and its history: each run's status, when, the model
  that ran it -- and which one it stood in for when the first was busy -- the first lines of its
  result, or why it failed or what it is waiting for and when it tries again. **A run opens its
  result in full**, with Open its chat. A new task is said in words; the instance shows what it
  understood -- the title, the rule in words, the instruction and the next five runs -- before
  anything is saved, and says why when it could not make out all of it. Editing starts from
  the words the task was made from. **Models for scheduled tasks** (the button at the top of
  Scheduled): the models scheduled work runs on, in the order they are tried, which are free
  right now, and a tick to leave one out. Needs a LLeMbas server with scheduling (2.0.0-beta.6);
  an older one says so.
- **Notifications for scheduled tasks**, on two channels of their own (results, and failures).
  They carry no content -- the instance sends only that a task finished or failed, and which
  run -- and on the lock screen they show only the app's name and that sentence. A tap, or
  **Open**, goes to the run's result; a failure offers **Pause**, and after three failures in
  a row the notification asks whether to pause the task.
- **Screenshots of every screen on each change**: the CI run renders the screens with made-up
  data, light and dark, on a large and a small phone and with large type, and keeps the
  pictures with the run.

## [1.0.0-beta.2] — 2026-11-09

For LLeMbas server 2.0 (2.0.0-beta.5 or later for the activity panel, the Thinking switch and team
sizes).

### Added

- **New agent chat**, on the Agents tab: pick one of the devices linked right now (a computer
  running `lembas service`), optionally a folder on it, and the mode (Manual, Edit, Auto, Plan),
  and the first message starts the chat there. With no device linked, the screen says how to
  link one. An open agent chat's mode can be changed from the same chip.
- **Photos**: the attach button offers Take a photo (the camera app, no camera permission for
  LLeMbas itself), Choose photos (Android's photo picker) and Choose files.
- **Copy** a reply or one of your messages with the copy button under it.
- **Rename chat** in the chat's menu.
- **Try again** beside an error on the chat list.
- **Test builds for every change**: each push to `dev` leaves an installable APK on the CI run,
  signed with a public test key so a newer test build installs over an older one. The version
  in Settings › About carries the run's number.
- **The activity panel**: the lightning button in a chat, or the line above the message box
  ("3 agents working · 1 queued"), opens a full-screen view of what the chat is doing --
  **Context** (how much of the model's window is used, a bar and the breakdown by category),
  **Outputs** (the files the chat and its agents wrote, with lines added and removed; tap one to
  read it), **Background tasks** (every helper, command and team member as a tree, with its
  state, how long it has run, its model, its latest step and what it is writing; open its whole
  conversation read-only, or stop it after a confirmation; finished ones folded under "N
  finished"), the team's **Room** and the **Plan**. It keeps itself current while it is open.
- **Agents, fewest and most**, in the chat's chips where the model can run a team: how many
  agents the chat's team may have, its own included (1 to 4 to begin with). Set it before the
  first message or change it in an open chat.
- **Model pictures**: the model picker and the chat's title show each model's picture from the
  instance, or its initial where it has none.

### Changed

- A beta (`vX.Y.Z-beta.N`) is published on GitHub as a pre-release by itself.
- **The chat list is read again whenever you come back to it**, from a chat or another app, so a
  new chat, a new title or a finished reply shows without pulling to refresh.
- **A reply being written stays in view**: the chat follows it to the end, unless you have
  scrolled up to read, and picks up again once you scroll back down.
- **An instance that cannot be reached says so in one sentence** -- no network, an address that
  only resolves at home, a server that is down -- instead of a raw network error, everywhere:
  sign-in, the chats, a chat, Files, the viewer, the assistant and Share.
- The effort chosen for a **new** chat is now sent with its first message (it was lost until the
  chat existed).
- **The effort control is the model's own**: none for a model with one level, a **Think before
  answering** chip that switches thinking on and off for a model that offers exactly that, and
  the effort picker for more levels. "Off" is offered only by a model that can really switch
  thinking off. A chat started without a choice starts at the model's start level.
- Requires the beta.5 `/api/v2` of LLeMbas 2.0 for the panel, the team size, the Thinking switch
  and the pictures; against an older 2.0 beta the app keeps the effort picker and the jobs chips.
- The agent mode "Manual" is translated, and "Plan" no longer reads as a price plan in German,
  French and Hungarian.

### Fixed

- **A message that could not be sent is not lost**: its words and attachments go back into the
  composer, with the reason.
- Leaving a chat while a reply was streaming could show a "cancelled" error on the next one.
- When the server refused the stream of a reply (the chat deleted elsewhere, the sign-in
  revoked), the half-drawn reply stayed on screen; the chat is now read again.

- **Pinned to LLeMbas server 2.0.0-beta.4**: the contract, the theme and the strings come from that
  release. Nothing a person sees changes; the server's API is the same as in beta.1.

## [1.0.0-beta.1] — 2026-11-03

For LLeMbas server 2.0, and only for it.

### Added

- **The file viewer.** When the model shows a file (the `show` tool, in a chat or from a linked
  device), the reply carries a card with the file's name and path; Open shows it full screen,
  read-only, at the line it named. Markdown is rendered with the same limits as a reply (no
  images, no HTML, links open in the browser for http, https and mailto only); code and text are
  numbered lines; a long Markdown file is shown as text. **HTML is never rendered in the app**:
  it is shown as its source, and "Open in the browser" opens the web viewer, which draws it in
  its sealed frame. Copy takes the whole file.
- **Files**: the person's own files, read-only, from the folder button beside Settings. Browse
  the tree as the web UI's explorer shows it (folders first, back goes up one), and open a file
  in the same viewer as a shown one, under the same rules. A file the server does not hand over
  as text -- an image, a binary, anything over 1 MB -- says why, and offers the web UI's viewer.
- **A server older than 2.0 is refused** with a sentence, at sign-in and for an instance signed
  in to before: the device-link protocol must be 3 or newer, and the app API 2.

### Changed

- **The server's contract is pinned to LLeMbas 2.0** (`dev`): no canvas, chats without knowledge
  bases. Theme and strings are generated from the same commit; `gen-strings.py` takes a server
  ref, as the other two scripts do. Selected text uses the web UI's `--selection` tint.
- **The app reads app API 2**, the server 2.0's: a server that still says 1 (a 2.0 build from
  before the show route and the files) is refused as too old, one that says 3 as too new.
- **A shown file is fetched from the server's own route**, `GET /api/v2/chats/{chat}/show`
  (`?node=&version=` for a file in the tree, `?shown=` for a device's copy), and a file that is
  not text says so in the server's words. The contract now carries the server's `show` cards and
  the files routes, and the tests read those instead of a hand-written card.
- CI also runs on pushes to `dev`.

### Removed

- **The Reports notification channel**: LLeMbas 2.0 has no reports. The channel is deleted on
  first start.

## [0.1.0] — unreleased

The first version: a native client for a LLeMbas instance (server 1.7.0 or newer), and the
phone's digital assistant.

### Added

- **Sign in by device code**, as the LLeMbas CLI does: type the instance's address, approve the
  code in the browser. The token is sealed with a key in the Android Keystore. Several instances,
  one in use at a time.
- **Chats and agent chats**: the list (archive by swiping), a chat with its reply streaming in --
  thinking, tool calls with their output behind a tap, edits, steered messages -- the plan, the
  background jobs, stop, edit, regenerate, continue, carrying out a plan, the model, effort and
  Plan mode. Attachments from the phone, and dictation through the instance's speech-to-text.
- **Cards**: approvals (once, always, deny with a reason, a corrected command), questions and
  plans are answered in a sheet. First answer wins -- on the phone, the web page or the device.
- **Notifications through UnifiedPush**, with no Google services: a finished reply, a card
  waiting with nobody watching (an approval can be answered from the notification), a report.
- **The digital assistant**: choose LLeMbas under Default apps › Digital assistant app, and
  holding the power button (or the assist gesture) opens a sheet over whatever is on screen.
  Ask by typing or by voice; a spoken question is answered aloud. "Include screen" sends the
  screen's text, or a screenshot, with the question -- off until turned on. Over the lock screen
  it asks to unlock before sending anything. Also a Quick Settings tile and an "Ask" shortcut.
- **Share to LLeMbas**: text, links, pictures and files from any app start a chat.
- **Moria and Shire**, generated from the server's own design tokens; nine languages.
- **Updates itself** from llembas.eu (the `full` build); the `fdroid` build leaves that to F-Droid.
