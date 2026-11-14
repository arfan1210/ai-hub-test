# AI Hub

A lightweight Android app for using eight AI services from one Home screen. It
uses one live WebView at a time, and keeps service logins across restarts.

| Service | URL | Role | Default |
| --- | --- | --- | --- |
| Qwen | https://chat.qwen.ai | Planner | Mobile |
| Coder | https://coder.qwen.ai | Builder | Desktop |
| Gemini | https://gemini.google.com | Design/Language | Mobile |
| DeepSeek | https://chat.deepseek.com | Code review | Mobile |
| Meta | https://www.meta.ai | Logic review | Mobile |
| Zai | https://chat.z.ai | Senior review | Mobile |
| MiniMax | https://agent.minimax.io | Optional | Mobile |
| MiMo | https://aistudio.xiaomimimo.com/#/c | General | Mobile |

## Features

- Dark, two-column Home grid; tap a card to open its service.
- Toolbar service switcher, Home, reload and desktop/mobile toggle.
- Coder starts in desktop mode (Windows Chrome UA, wide viewport, 1280px
  viewport meta); each service's mode choice is saved separately.
- Mobile mode uses the default WebView UA without the `; wv` marker.
- One WebView is destroyed when leaving or switching services. JavaScript,
  DOM storage, cookies and in-app links are enabled; cookies are flushed on pause.

## Install

Download `app-debug.apk` from the latest [GitHub Release](../../releases) and
install it on an Android device (minSdk 29).

## Build and signing

Use JDK 17, Android SDK 34 and Gradle 8.7:

```bash
gradle assembleDebug
```

The `Build Debug APK` workflow publishes the APK. Stable signing is read only
from GitHub Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, and
`KEY_PASSWORD`. Gradle decodes the keystore to a temp file; without all signing
environment variables, it uses the default debug key. No key material is stored
in the repository; `*.jks` and `*.keystore` are ignored.

## Project

`AiServices.kt` is the single service list; the Home grid and toolbar dropdown
are generated from it. The app uses Kotlin 2.0.20, AGP 8.5.2, compileSdk 34,
minSdk 29, and only Material Components/AppCompat alongside AndroidX core.
