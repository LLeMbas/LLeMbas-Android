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
one does not. Android 10 or newer; the instance must run LLeMbas server 1.7.0 or newer.

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

There is no wake word. Android reserves always-on hotword detection for the system's own
preinstalled assistant; no app from outside can use it.

## Build

Builds run on GitHub Actions (`.github/workflows/ci.yml`); locally it is the usual
`./gradlew assembleFullDebug` with JDK 17 and the Android SDK.

Three things are generated from the server and pinned, each with a lock file CI checks:

| What | Script | From the server |
|---|---|---|
| `ui/theme/Tokens.kt` | `scripts/fetch-tokens.py <server-ref>` | `tokens.css` |
| `contract/*.json` | `scripts/fetch-contract.py <server-ref>` | `tests/fixtures/api_v2/` |
| `res/values*/strings.xml` | `scripts/gen-strings.py` | the translation catalogues |

The icons come from the server's pixel leaf (`scripts/gen-icons.py`).

## Licence

MIT -- see [LICENSE](LICENSE).
