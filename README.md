# AI Hub

**North star:** one Android app where a planner AI writes, a fleet of other AIs
verify the work in a loop, and the answer is only released when independent
reviewers agree it passes. Every change to this repo should move the app closer
to a fully automatic, self-verifying multi-AI workspace — not further away.

Nine services, one live WebView, a review loop that hops between them.

---

## For AI agents continuing this project

Read this README **first** (you are probably looking at it via
`https://github.com/arfan1210/ai-hub-test/blob/main/README.md`). It is the
project's memory: it records not just *what* the app does but *why* it must be
built that way. Skipping it is how previous attempts drifted.

**Respect the non-negotiables.** They are a hard contract, not preferences:

| Constraint | Value |
| --- | --- |
| Package | `com.arfan.aihub` |
| Kotlin | 2.0.20 |
| AGP | 8.5.2 |
| Gradle | 8.7 |
| compileSdk / targetSdk | 34 |
| minSdk | 29 |
| Dependencies | Material + AppCompat + core-ktx **only** (no new deps) |
| Async | `android.os.Handler` only — **no coroutines**, no worker threads |
| WebViews | **exactly one alive**; destroyed on switch / home / finish |
| User agent | mobile UA with `; wv` stripped; desktop UA only for Coder + Arena |
| Cookies | flushed in `onPause` |
| Signing | never touch. Same four GitHub Secrets, same identity, forever |
| Workflows | `build.yml` and `ask-gemini.yml` are **do not edit** |
| Secrets | no key material, passwords or API keys in the repo or in logs |

**Workflow for agents:** use the *strong-model-writes / applier-applies* pattern.
Plan and write the change with the strongest model available, then hand the exact
patch to the applier step (the Ask Gemini workflow) to commit and build. Do not
improvise architecture mid-run, do not silently add libraries, and do not "clean
up" the invariants above because they look redundant — every one of them exists
because it broke in production.

**Do not drift.** If a requirement is ambiguous, prefer the behaviour that keeps
one WebView, keeps the log, and keeps the review verdict honest. A wrong PASS is
worse than a paused run.

---

## Architecture

```
MainActivity            Home grid, one live WebView, toolbar, review bar + log,
                        overlay host. Owns ReviewController and the wake flag.
AiServices              The single source of truth for the 9 service cards.
ServiceCardView         One Home grid card (built in code).
ServicePrefs            Per-service desktop/mobile choice.
WebViewFactory          Builds the one live WebView (UA, viewport, cookies).
ReviewSettings          Per-service eligible/enabled/role/dailyCap/usedToday,
                        loop knobs (maxRounds, autoMode, escalationAfter),
                        daily reset + Z.ai escalation budget.
ReviewSettingsView      Full-screen settings overlay (ON/OFF, role, cap, Close).
ReviewController        The loop: capture -> one reviewer at a time -> verdict
                        -> merge issues -> Qwen rework -> next round.
ReviewSession           Persisted in-flight session (resume, no duplicate sends).
ReviewLog               Append-only, persisted review log (history is kept).
SiteAdapters            Per-site JS: capture assistant-only replies, detect busy,
                        type + send through the native value setter.
JsonUtil                Tiny JSON read/escape helpers for WebView JS replies.
VerdictParser           Strict VERDICT/ISSUES parser. FAIL -> REVISE.
app/src/test/…          Robolectric behaviour tests + the review logic tests.
```

### The review protocol

Every reviewer must answer in exactly this shape:

```
VERDICT: PASS | REVISE | FAIL
ISSUES:
- ...
```

`ISSUES: NONE` is what a PASS looks like. The parser takes the **last** verdict in
the reply and folds `FAIL` into `REVISE` (a failure is still "needs another
round"). If no `VERDICT:` line exists, the loop never guesses: it stops with
**CALIBRATION NEEDED** in the status bar and the log.

### The loop

1. **Capture once.** Task (newest user message in Qwen) and answer (newest
   assistant reply) are frozen into a snapshot, once per round.
2. **One reviewer at a time.** The app opens that reviewer in the single WebView,
   sends the *same frozen snapshot* plus the protocol, and polls for a new,
   stable, assistant-only reply.
3. **Parse.** PASS is recorded; REVISE/FAIL issues are collected.
4. **Decide.** All PASS -> done. Otherwise the merged issues go back to Qwen, the
   app waits for Qwen to *actually finish* (polled, never a fixed delay), and the
   next round starts.
5. **Escalate.** After `escalationAfter` failed rounds, Z.ai is asked once
   (max 2 per calendar day, persisted), and the loop continues.
6. **Stop conditions.** All PASS, `maxRounds`, STOP, a login wall, a page that
   does not look like a chat, or a daily cap with nothing left to ask.

Sessions are persisted after every step. Pausing (backgrounding, process death,
a login wall) **saves**; only a real finish **clears**. Resume restores the
reviewer list verbatim and never re-sends an instruction that may already have
gone out.

---

## Build and release pipeline

- **Build:** push to `main` -> `Build Debug APK` (`build.yml`) -> Gradle 8.7 +
  JDK 17 + SDK 34 -> `app-debug.apk` attached to a GitHub Release tagged
  `v0.1.<run_number>`.
- **Ask Gemini:** `ask-gemini.yml` lets a cloud model write into the repo. Model
  is **`gemini-3.8-flash`**; it commits and pushes, then triggers the build.
- **Signing:** stable identity comes from four GitHub Secrets —
  `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Gradle
  decodes the keystore into the OS temp dir; no key material is ever in the repo
  (`*.jks` / `*.keystore` are ignored). The Ask Gemini workflow also needs
  `GEMINI_API_KEY`.
- **Never change the signing identity.** A new key means the user must uninstall
  the app and lose every login. This is permanent.

Local build: `gradle assembleDebug` (JDK 17, SDK 34, Gradle 8.7).

---

## Conventions

- **Verdict protocol** is parsed strictly, from assistant-only DOM nodes.
- **Calibration over guessing.** Unknown DOM -> `CALIBRATION NEEDED`, visible in
  the status bar and the log. Never fabricate a PASS.
- **One WebView.** Anything that needs a page must go through
  `ReviewController.onWebViewReady` so the view references stay valid.
- **Handler-only async.** Every step is a posted callback guarded by a `sequence`
  counter, so cancelling is one `removeCallbacksAndMessages(null)`.
- **No secrets.** Not in code, not in resources, not in logs.
- **Keep the log.** Entries are appended; the log survives tab switches.

---

## Lessons learned (do not relearn these)

1. **`gemini-2.5-flash` was retired.** The Ask Gemini workflow must use
   `gemini-3.8-flash`.
2. **Free quotas are small.** Rotate MiMo / Arena / Gemini instead of depending
   on one service; hence per-service daily caps and default-ON reviewers that
   are cheap to rotate.
3. **Google login needs the UA.** The mobile user agent must have `; wv`
   stripped or Google sign-in refuses the WebView.
4. **Selectors are heuristic.** All these sites ship hashed CSS class names.
   Adapters use semantic attributes first and structure last, and expect a
   calibration pass whenever a site redesigns.
5. **3 GB is not enough for eight sites.** Never keep more than one WebView; hop
   sequentially through services.
6. **Signing is permanent.** Changing the keystore forces a reinstall and wipes
   logins.
7. **Never parse the whole page.** The user's own prompt (which contains the
   verdict protocol) would be read back as a "PASS".
8. **Freeze the snapshot once per round.** Re-capturing per reviewer made rounds
   review each other's answers.
9. **Resume must restore the reviewer list** and can never report ALL PASS from
   an empty list.
10. **The Senior path is separate.** Escalating to Z.ai must not reset the
    configured reviewers.
11. **Overlays must be closable,** and the review bar must not live in a
    container that is cleared on a tab switch.
12. **Clear `FLAG_KEEP_SCREEN_ON`** on finish, stop and pause.
13. **`onDestroy` pauses and saves.** Stopping/clearing there destroys resumes.

---

## CHANGELOG rule

**Maintain [`CHANGELOG.md`](CHANGELOG.md) diligently for every release.** Every
change that lands on `main` gets an entry under `Unreleased` (or the version
being prepared): what changed, why, and which invariant it protects. A release
note is not a substitute for the changelog.

---

## Services

| Service | URL | Role | Default mode | Reviewer by default |
| --- | --- | --- | --- | --- |
| Qwen | https://chat.qwen.ai | Planner | Mobile | Never (owns the task) |
| Coder | https://coder.qwen.ai | Builder | **Desktop** | Never (builder) |
| Arena | https://arena.ai/agent | Builder/agent | **Desktop** | Off |
| Gemini | https://gemini.google.com | Design/Language | Mobile | **On** |
| DeepSeek | https://chat.deepseek.com | Code review | Mobile | **On** |
| Meta | https://www.meta.ai | Logic review | Mobile | **On** |
| Zai | https://chat.z.ai | Senior review | Mobile | Off (escalation only) |
| MiniMax | https://agent.minimax.io | Optional | Mobile | Off |
| MiMo | https://aistudio.xiaomimimo.com/#/c | General | Mobile | **On** |

Coder and Arena default to desktop (desktop UA + 1280 px viewport); every other
service defaults to mobile. Each service's choice is remembered separately.

Loop defaults: `maxRounds = 8`, `autoMode = false`, `escalationAfter = 3`,
Z.ai escalations max 2 per calendar day.

---

## Using the review loop

1. Talk to **Qwen** until it has produced the answer you want reviewed.
2. **Cfg** -> tick the reviewers you want, set their daily caps and roles. Qwen
   and Coder are locked OFF. **Close** dismisses the overlay.
3. **Review** runs one manual round (opens a reviewer, sends the frozen
   snapshot, parses the verdict, returns to Qwen).
4. **Auto** runs the full loop until ALL PASS or `maxRounds`.
5. **Senior** asks Z.ai directly (the same path auto-escalation uses).
6. **Stop** cancels everything; the wake flag is cleared.
7. Tap the status line to open the **log**; it keeps every entry.

---

## Roadmap

- [x] Home grid, one live WebView, per-service desktop/mobile mode, cookies.
- [x] Arena as the ninth service (desktop builder/agent).
- [x] Review settings: per-service eligibility, ON/OFF, role, daily caps.
- [x] Strict verdict protocol with calibration failures.
- [x] Manual one-reviewer round with frozen snapshots.
- [x] Auto loop: rotate reviewers, merge issues back to Qwen, rework, re-check.
- [x] Z.ai escalation (auto after N failed rounds + manual Senior button).
- [x] Session persistence with duplicate-free resume.
- [ ] Per-site calibration profiles (store confirmed selector sets).
- [ ] Diff-aware review (send only what changed between rounds).
- [ ] Cost-aware reviewer selection (spend the cheapest quota first).
- [ ] Export the log/session as a shareable transcript.
