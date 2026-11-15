# Changelog

All notable changes to AI Hub. This file is maintained for **every** release
(see the CHANGELOG rule in the README). Newest first.

## Unreleased

### Added — multi-AI review loop, automation and session resume

**Commit 1 — foundation + Arena**

- **Arena** is the ninth service (`id "arena"`, `https://arena.ai/agent`, role
  "Builder/agent", desktop mode, accent `#38BDF8`, initial `A`), placed directly
  after Coder. Coder and Arena are the two desktop-by-default services.
- `ReviewSettings`: per-service eligible / enabled / role / dailyCap / usedToday,
  persisted, with counters that reset on a calendar-day change. Qwen and Coder
  are structurally **ineligible** as reviewers; DeepSeek, Gemini, Meta and MiMo
  default ON; Arena, MiniMax and Z.ai default OFF. Loop knobs:
  `maxRounds = 8`, `autoMode = false`, `escalationAfter = 3`. Z.ai escalations
  are capped at 2 per day.
- `VerdictParser`: strict `VERDICT: PASS|REVISE|FAIL` + `ISSUES:` parsing that
  takes the last verdict, folds `FAIL` into `REVISE`, rejects our own prompt
  text, and returns *no verdict* rather than inventing a PASS.
- `SiteAdapters`: injected JavaScript that captures the newest **assistant-only**
  message node (semantic attributes first, markdown structure as a fallback),
  detects "busy", and types + sends through the native value setter so React
  inputs accept the text. All payloads are escaped; selectors are heuristic.
- `ReviewController`: manual single-reviewer round — require the Qwen tab,
  freeze task + answer, open ONE reviewer in the single WebView, send the frozen
  snapshot, poll for a stable verdict, log it, return to Qwen. Tab guard before
  every send, `NO_INPUT` retried up to 5 times at 2 s, STOP cancels, pause and
  service changes cancel, unknown DOM surfaces **CALIBRATION NEEDED**.
- `ReviewSettingsView`: full-screen overlay with ON/OFF, role and cap steppers;
  non-reviewer rows render locked OFF; rows re-sync from storage every time the
  overlay opens; **Close** really dismisses it.
- Review / Auto / Senior / Stop / Cfg buttons plus the status line and log panel
  live in the chat screen as **siblings** of the WebView container, so they
  survive every tab switch. The log is append-only and persisted.

**Commit 2 — automation**

- `ReviewController` auto mode (`Auto` button): reviewers rotate sequentially through the single
  WebView, all against one frozen snapshot per round. All PASS ends the run;
  otherwise the merged ISSUES text goes back to Qwen, the app polls until Qwen
  has genuinely finished and produced a new answer, then the next round starts,
  until ALL PASS or `maxRounds`.
- Z.ai escalation after `escalationAfter` failed rounds (max 2 per calendar day,
  persisted and counted before the send) and a manual **Senior** button that
  really drives Z.ai. The escalation is appended to the round and never rewrites
  the configured reviewer list.
- `ReviewSession`: the in-flight session (frozen snapshot, pending/completed
  reviewers, issues, round, mode) is persisted after every step. Pause and
  `onDestroy` **save** instead of clearing; resume restores the reviewer list
  verbatim, polls for the reply of the step that was already sent (no duplicate
  sends) and refuses to resume an empty list.
- Daily caps are counted **before** each send and the remaining count is logged;
  STOP cancels every pending callback; login expiry / unavailable page pauses the
  run with the service and reason visible; `FLAG_KEEP_SCREEN_ON` is held during a
  run and cleared on finish, stop and pause.

### Changed

- `JsonUtil` (new, internal): the JSON read/escape helpers moved out of
  `SiteAdapters` so they are unit-testable and testable from plain Kotlin. No
  behaviour change and no new dependency.
- Home subtitle reflects nine services.
- `CHANGELOG.md` added; the README is now the project's memory (north star, AI
  agent instructions, architecture, pipeline, conventions, lessons, roadmap).
- Tests extended for nine services, the verdict parser, settings/caps, session
  persistence, JS escaping and the review-bar/overlay behaviour.

### Fixed (regressions from earlier attempts, now guarded by code and tests)

- Verdicts are read only from assistant nodes, so the user's prompt can never be
  parsed as a PASS.
- Resume can no longer report ALL PASS from an empty reviewer list.
- The Senior path can no longer reset the normal reviewers.
- The settings overlay is closable again.
- Review controls stay visible after switching tabs.
- `FLAG_KEEP_SCREEN_ON` is always cleared.
- `onDestroy` pauses and saves instead of stopping and clearing.
- Log entries append; history is kept.

## v0.1 — initial app

- Home grid of eight services (Qwen, Coder, Gemini, DeepSeek, Meta, Zai,
  MiniMax, MiMo), one live WebView destroyed on switch, per-service
  desktop/mobile mode with Coder defaulting to desktop, `; wv`-stripped mobile UA,
  cookies flushed on pause, stable signing from four GitHub Secrets, and the
  Build Debug APK workflow publishing to GitHub Releases.
