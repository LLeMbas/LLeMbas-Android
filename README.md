# LLeMbas for Android

A native Android app for a [LLeMbas](https://github.com/LLeMbas/LLeMbas) instance: your chats
and agent chats, notifications that arrive when the app is closed, and LLeMbas as the phone's
digital assistant -- hold the power button and ask.

- **Native, not a web view.** Kotlin and Jetpack Compose on the server's JSON API (`/api/v2`).
  The installable web app keeps working; this is for what a web page on a phone cannot do.
- **No Google services.** Notifications come through [UnifiedPush](https://unifiedpush.org)
  (ntfy, or any distributor you like), so the app runs on de-Googled phones and builds for F-Droid.
- **Your instance, nobody else's.** Nothing is predefined: the app talks to the address you give
  it, and to nothing else.

> **Alpha.** 0.x versions may change how things work between releases.

## Install

Download the APK from the [latest release](https://github.com/LLeMbas/LLeMbas-Android/releases/latest)
and check it against `SHA256SUMS`. The `full` APK updates itself from llembas.eu; the `-fdroid`
one does not. Android 10 or newer; the instance must run LLeMbas server 2.0 or newer (an older
one is refused at sign-in, with a sentence saying so).

**Test builds.** Every push to `dev` builds a debug APK: open the newest green run under
[Actions](https://github.com/LLeMbas/LLeMbas-Android/actions/workflows/ci.yml), download the
`llembas-android-debug-N` artifact (GitHub asks you to be signed in), unzip it and install the APK that does
not end in `-fdroid`. It is a separate app ("eu.llembas.android.debug") beside a released one,
signed with a public test key kept in this repository, so each test build installs over the one
before it -- and anyone could sign one, so take test builds only from this repository's runs.

If your instance uses a certificate from your own certificate authority, install that CA on the
phone first (Settings › Security › Encryption & credentials › Install a certificate › CA
certificate). The app trusts CAs you installed, as the browser does.

## Use

- **Sign in**: type the instance's address and approve the code shown, in the browser.
- **Notifications**: install a UnifiedPush distributor (for example ntfy), then Settings ›
  Notifications › Turn notifications on.
- **Assistant**: Settings › Assistant › Open the assistant settings, and choose LLeMbas as the
  digital assistant app. On Samsung phones also set Settings › Advanced features › Side button ›
  Press and hold › Digital assistant.

A wake word is there if you want one, off by default: Settings › Assistant › Wake word, a
greeting and a name in English ("Hey Assistant"). Android reserves its low-power, always-on
hotword detection for the system's own preinstalled assistant, so LLeMbas listens with an
on-device engine in a microphone service, with a notification up the whole time; nothing leaves
the phone before the word. Not in the F-Droid build.

The wake word and the hands-free parts of the voice assistant (listening by itself, the spoken
conversation) are **experimental**: new, and tried on few phones. Settings marks them so.

## Build

Builds run on GitHub Actions (`.github/workflows/ci.yml`); locally it is
`scripts/fetch-wakeword.py` (the wake-word engine and model, hash-pinned, never committed) and
then the usual `./gradlew assembleFullStandardDebug` with JDK 17 and the Android SDK.

A release is three APKs of one app, signed with one key:

| File | Build | What it is |
|---|---|---|
| `llembas-android-<version>.apk` | `fullStandard` | The app: updates itself, has the wake word. The one to install. |
| `llembas-android-<version>-notifications.apk` | `fullNotifications` | The same, plus a notification listener for *notification summaries*. Separate because Google Play Protect blocks installing an app from outside the Play Store that can read notifications; it installs over the standard one, and back. |
| `llembas-android-<version>-fdroid.apk` | `fdroidStandard` | No updater and no wake-word engine: only what F-Droid can build from source. |

`scripts/check-apk.sh` checks each built file for what it must and must not declare.

Three things are generated from the server at one ref; the first two are pinned with a lock
file CI checks, and CI checks the strings against the code:

| What | Script | From the server |
|---|---|---|
| `ui/theme/Tokens.kt` | `scripts/fetch-tokens.py <server-ref>` | `tokens.css` |
| `contract/*.json` | `scripts/fetch-contract.py <server-ref>` | `tests/fixtures/api_v2/` |
| `res/values*/strings.xml` | `scripts/gen-strings.py <server-ref>` | the translation catalogues |

The icons come from the server's pixel leaf (`scripts/gen-icons.py`).

## Licence

MIT -- see [LICENSE](LICENSE).
