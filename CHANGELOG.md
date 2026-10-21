# Changelog

What changed in each release of LLeMbas for Android. Versions follow SemVer; until 1.0.0 the app
is in alpha and releases come off `main`.

## [Unreleased]

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
