# Changelog

What changed in each release of LLeMbas for Android. Versions follow SemVer; until 1.0.0 the app
is in alpha and releases come off `main`.

## [Unreleased]

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
