# AI Hub

A lightweight native Android app that puts **seven AI chat services** behind one
bottom tab bar — no accounts juggling, no browser tabs, and logins persist
across restarts.

| Tab | URL |
| --- | --- |
| Qwen | https://chat.qwen.ai |
| Gemini | https://gemini.google.com |
| DeepSeek | https://chat.deepseek.com |
| Zai | https://chat.z.ai |
| Meta | https://www.meta.ai |
| MiniMax | https://agent.minimax.io |
| MiMo | https://aistudio.xiaomimimo.com/#/c |

## Features

- **One WebView at a time.** Only the active tab has a live WebView; switching
  tabs destroys the old one and creates a fresh one. This keeps RAM usage low
  even on 3 GB devices.
- **Persistent logins.** First-party and third-party cookies are accepted and
  flushed to disk in `onPause()`, plus DOM storage is enabled — sign in once
  and stay signed in.
- **Real browser user-agent.** The `"; wv"` marker is stripped from the default
  WebView UA so Google sign-in (Gemini) works properly.
- **Smart back button.** Back navigates the WebView history if possible,
  otherwise it switches to the previous tab.
- **Dark theme** with a scrollable bottom tab bar so all seven labels fit on
  small screens.

## Download & install the APK

Every push to `main` (and every manual run of the **Build Debug APK** workflow)
publishes a GitHub Release with the debug APK attached.

1. Go to the repository's **[Releases](../../releases)** page.
2. Open the latest release (e.g. `v0.1.42`) and download
   **`app-debug.apk`** under *Assets*.
3. Copy the APK to your phone (USB, Drive, etc.) and tap it to install.
4. On first install, Android will ask you to allow installing apps from this
   source (Settings → Apps → Special access → Install unknown apps). Tap
   **Allow**, then **Install**.
5. Open **AI Hub**, pick a tab, and log in to your service — the session will
   be remembered.

> The APK is built as a *debug* build and is signed with the standard debug
> key, which is fine for personal installs but not for Play Store distribution.

## Building locally

Requirements: JDK 17, Android SDK (API 34), or just Gradle 8.7:

```bash
gradle assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

The repository intentionally ships **without** `gradle-wrapper.jar`; CI installs
Gradle itself via `gradle/actions/setup-gradle@v4`.

## Project layout

```
app/src/main/java/com/arfan/aihub/
├── AiServices.kt     # single config list of tabs (label + URL)
└── MainActivity.kt   # tab bar + single-WebView lifecycle management
```

To add or remove a service, edit `AiServices.TABS` only.

## Tech stack

- Kotlin 2.0.20, AGP 8.5.2, compileSdk 34, minSdk 29
- Material Components (`TabLayout`), plain `WebView` — no other runtime deps
