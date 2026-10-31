# Tasks: tv-home M1b (Ask Claude, and the playback overlay)

Architecture: `docs/architecture/tv-home.md` sections 7 and 7b. ADR 0002. Where: sandbox / CI / Brian as in
`docs/tasks/tv-home-m1.md`. Builds on M1's T1-T6.

## Proposed acceptance criteria (for the product-manager to fold into the spec)
- B-AC1 Given fixture model responses, a request resolves to a `Resolution` with an installed service and a
  `LaunchPlan` (deep link, then search link, then app launch); an id that fails the service regex falls back to search. JVM tests.
- B-AC2 `DeepLinkBuilder` passes the shared `deeplink-vectors.json` for Netflix, YouTube, Disney+, Prime Video, Max,
  Hulu, Apple TV, Paramount+, Peacock and Spotify.
- B-AC3 Claude cannot cause a URI we did not build: a fixture where the tool input carries a URL, an unknown service
  or an oversized field is rejected or falls back. JVM tests.
- B-AC4 Errors (no key, offline, 401, 429, 5xx, refusal, daily cap) each show one plain sentence; no key text in UI,
  logs or CI output (grep for `sk-ant-`).
- B-AC5 Voice works from the remote's mic through the on-screen mic button on Brian's device, or the keyboard
  fallback is shown when recognition is unavailable.
- B-AC6 Overlay: while a show plays, the configured long-press opens the panel within 1 s, the show keeps playing in
  the apps listed as "keeps playing" in B5's table, Back closes it, and every other key behaves as before.
- B-AC7 The overlay sends the now-playing title, subtitle and position when the app publishes them, and says "I
  can't see what's playing" when it does not.
- B-AC8 No API spending until Brian approves; the daily cap (default 20) is enforced.

| # | Task | Owner | Where | Depends | Files | ACs |
|---|---|---|---|---|---|---|
| B0 | Brian tries the built-in assistant first: hold the remote's mic button and say "play Stranger Things on Netflix", then "who plays Eleven?" while it plays. Record what works | manager asks Brian | Brian | none | `docs/status.md` | scope check |
| B1 | `services.json` + `DeepLinkBuilder` + `LaunchPlan` + `deeplink-vectors.json` (URL-encoding of queries, id regex per service) | backend-engineer | sandbox | M1 T1 | `core/src/main/resources/tvhome/`, `core/.../services/` | B-AC1, B-AC2 |
| B2 | Assistant contract in core: `Assistant`, `AskRequest` (incl. `NowPlaying`), `AskResult`, `open_title` JSON schema built from installed services (stable order), system prompt (treats web and now-playing text as data), local validation of tool input | backend-engineer | sandbox | B1 | `core/.../assistant/` | B-AC1, B-AC3 |
| B3 | `:assistant-claude` module: `ClaudeAssistant` on anthropic-java 3.2.0 (model string config, effort low, adaptive thinking, `web_search_20260209` max_uses 3, refusal fallback, `pause_turn` loop with a 3-round cap, stop_reason checks, typed exception mapping), daily cap counter, `Redactor` for `sk-ant-`. MockWebServer fixtures: open_title, text clarify, not found, pause_turn, refusal, max_tokens, 401, 429, 529, malformed tool input. No live calls | backend-engineer | sandbox | B2 | `assistant-claude/` | B-AC3, B-AC4, B-AC8 |
| B4 | `AskActivity` (own launcher tile): on-screen mic button with `SpeechRecognizer`, RECORD_AUDIO permission flow, keyboard fallback, Thinking / result / clarify / error states, 3 s countdown launch that Back cancels, `LaunchPlan` executor (catches `ActivityNotFoundException`), installed-service detection, `<queries>` for the service packages | frontend-engineer | written in sandbox, compiles in CI | B2 | `app/.../ask/`, manifest | B-AC4, B-AC5 |
| B5 | Device spike (emulator first, then Brian's TV with his OK): which key events the accessibility service receives per remote button; `SpeechRecognizer.isRecognitionAvailable()` and remote-mic audio; per app (his installed services): deep link / search link opens?, MediaSession metadata present?, keeps playing with overlay focus?, keeps playing during speech recognition?; whether `settings put secure enabled_accessibility_services`, `cmd notification allow_listener` and `appops ... SYSTEM_ALERT_WINDOW` work on his Google TV version. Output: per-app table and `verified` flags in `services.json` | qa-engineer with frontend-engineer | CI emulator, then Brian | B1, B4, M1 T11 | `docs/research/tv-home-device-spike.md` | B-AC2, B-AC5, B-AC6, B-AC7 |
| B6 | Build hardening: R8 keep rules for anthropic-java/Jackson/kotlin-reflect, APK size before/after, emulator smoke test of `ClaudeAssistant` against a local mock server in a release build; `provision.ps1` accepts `anthropic_key`; CI grep adds `sk-ant-` | devops-engineer | CI | B3 | `app/proguard-rules.pro`, `scripts/`, workflow | B-AC4 |
| B7 | Overlay: `TvHomeAccessibilityService` (key filter only, `canRetrieveWindowContent=false`, consumes only the configured long-press), `TYPE_ACCESSIBILITY_OVERLAY` panel (right third, text only, focus while open, Back closes), `MediaContextListener` (notification listener used only for `getActiveSessions`), `NowPlaying` mapping, fallback to typed input for apps B5 marks as pausing | frontend-engineer | written in sandbox, compiles in CI | B4, B5 (key choice and per-app table) | `app/.../overlay/`, manifest, `res/xml/accessibility_config.xml` | B-AC6, B-AC7 |
| B8 | Reviews: security-engineer on ADR 0002 and on B2, B3, B6, B7 (secret, external input from web and voice, accessibility service, notification listener, third-party SDK); code-reviewer on each task. Recommend a 3-member panel on ADR 0002 before B3 | security-engineer, code-reviewer | sandbox | each task | report text to manager | all |
| B9 | **Hard stops for Brian:** approve the per-question cost (about $0.10 typical, cap 20/day), create a dedicated Anthropic API key in a workspace with a monthly spend limit, run `provision.ps1 -Key anthropic_key` on his PC; approve the three adb setting changes for the overlay | manager asks Brian | Brian | B6, B7, QA pass | `docs/status.md` | B-AC8 |
| B10 | QA against B-AC1 to B-AC8 on the emulator and then his TV, including one real (approved) question per path | qa-engineer | CI, Brian | B9 | `docs/qa/tv-home-m1b.md` | all |

Order: B0 (any time, free) → B1 → B2 → (B3, B4 in parallel) → B6 → B5 → B7 → B8 throughout → B9 → B10.
B1-B3 are fully sandbox work and can start before CI minutes reset. Recommend running M1c (no API key, no
spending) before B3-B10; if Brian finds typing on the phone enough, cut M1b to the overlay or drop it.
