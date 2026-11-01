# ADR 0002: tv-home M1b "Ask Claude" (voice to streaming app, and the playback overlay)

Status: proposed (architect), 2026-10-31; v2 applies `docs/reviews/security-tv-home-design.md`. Needs Brian's go-ahead on cost and the API key (hard stops 3 and 5).
Architecture: `docs/architecture/tv-home.md` sections 7 and 7b.

## Context
Brian (11:23Z): talk to Claude on the home screen and have it open the streaming service that has what he asks for.
Brian (11:32Z): ask about what is on screen without pausing playback. The Anthropic API key is a secret like the
calendar URL. Prefer no new accounts.

## Decision
1. **Where the call runs.** Build order is **M1 → M1c → M1b** (confirmed). Typed requests from the phone or laptop
   run on the PC through M1c (Claude on the PC finds the title, then calls `tv_open`), so that path needs no API key
   anywhere. The remote/voice and overlay paths call the API **from the TV**. Security prefers keeping the key on the
   PC; we do not do that for the TV paths because (a) Brian uses them while watching, when the PC may be off, and
   (b) a PC relay would add a LAN listener on the PC plus TV-to-PC pairing, the inbound surface M1c avoids. Spec M1b
   AC8 accepts the residual risk (anyone with adb access to the TV can extract the key), mitigated by a dedicated
   workspace key whose spend limit exists before the key is provisioned, and the daily cap. If Brian finds M1c's
   typing enough, M1b shrinks to the overlay or is dropped and no key goes on the TV.
   The TV call lives in a JVM module `:assistant-claude` built on the official anthropic-java SDK
   (3.2.0, OkHttp under the hood, same client as `:core`). The contract, tool schemas, system prompt and deep-link
   builders live in `:core` so they are tested in the sandbox; the SDK call is tested against MockWebServer with
   hand-written response fixtures. No test calls the real API.
2. **Which service has it: Claude's server-side web search** (`web_search_20260209`, `max_uses: 3`), not a catalog
   API. No new account; costs $0.01 per search.
3. **Claude picks, our code builds.** Claude calls one strict client tool, `open_title`, whose `service` is an enum
   of installed services. The intent URI is built locally from `services.json`, with a per-service id regex, then
   search link, then plain app launch as fallbacks. Web content cannot make the app open an arbitrary URI, and
   nothing launches until Brian presses Open on a screen showing title, service and the fallback used (`#2`).
4. **Model `claude-opus-5-5`** (the latest, as Brian asked), effort `low`, adaptive thinking, server-side refusal
   fallback on, streamed so the screen shows progress, 20 s overall timeout. Weighed against faster models for a TV
   voice assistant (architecture §7 latency table, estimates): Opus 5.5 ~8-14 s with search, ~$0.10; Sonnet 5.5
   ~6-10 s, ~$0.05; Haiku 5.5 ~4-7 s, ~$0.02 but only the older web search tool and weakest on the vague requests
   that are M1b's reason to exist. Web search dominates latency, so the first lever is skipping it when not needed.
   Opus stays the default; if B10 measures p95 over 10 s on his TV, Brian is offered Sonnet 5.5 (one config value).
5. **Voice:** Android `SpeechRecognizer` behind an on-screen mic button (the remote's mic button belongs to the
   system assistant). Fallback: on-screen keyboard, or typing from the phone through M1c.
6. **Overlay:** an accessibility service that filters keys only (no window content, no gestures, no global
   actions) catches one fixed button, the remote's Input key, while another app plays and shows a `TYPE_ACCESSIBILITY_OVERLAY` panel. Context comes from MediaSession metadata
   via a notification listener. No screen capture in M1b.
7. **Key handling:** same path as the calendar URL (ADR 0001), redaction of `sk-ant-` patterns. Brian creates a
   dedicated key in a dedicated workspace with a monthly spend limit; the app enforces a daily question cap
   (default 20).

## M1b lean (2026-11-01, built on branch `m1b-ask-claude`)
Brian chose "start now (lean)": a general assistant he can talk to from the dock, which can also open shows. What was
built, and where it differs from the decisions above:
- **Tile and view:** an Ask Claude tile in the dock after Calendar; OK opens a full-screen Ask view (BackHandler, one
  Back to leave, conversation dropped on leaving). Speech via the system `RecognizerIntent` dialog (no RECORD_AUDIO);
  if there is none, a text field. The transcript is shown with Send / Edit / Cancel before anything is sent.
- **Call:** raw HTTPS to `/v1/messages` with OkHttp in `:core` (`AnthropicClient`), the fallback named under
  Alternatives, instead of the SDK and a new `:assistant-claude` module: smaller APK, no R8 rules. Non-streaming,
  20 s call timeout, no redirects, host pinned to `api.anthropic.com`, no automatic retries. Model `claude-opus-5-5`,
  effort `low`, thinking left adaptive (Opus 5.5 cannot disable it), `max_tokens` 4096, `fallbacks: "default"` for
  server-side refusal fallback. **No web search** in this slice (decision 2 deferred): Claude answers from its own
  knowledge, so availability on a service can be wrong; the answer and Open screen make the pick visible first.
- **Reply:** structured output (`output_config.format` JSON schema) `{answer, open: {app_package, title, deep_link} |
  null}`, `app_package` an enum of installed packages. Parsed strictly in `:core` (`AskReply.parse`): no valid
  `answer` gives "Couldn't work that out"; an `open` naming an app that is not installed, or malformed, is dropped.
  Instead of decision 3's `services.json` id regexes, a deep link is used only if it is a plain https URL, launched as
  a VIEW intent restricted to the chosen package; else the app's search for the title; else its home. The screen says
  which one Open will use. Nothing launches without Open.
- **Limits:** one follow-up (spec AC6), then Start over. Daily cap 20 (decision 7), counted per API call including
  Retry, stored as a date and a count only (`no_backup/ask-count.txt`); a call refused with 400/401/403 is given
  back. Errors: no key "Ask Claude isn't set up yet"; 401/403 "Claude rejected the key"; 400 "Claude rejected the
  request"; 429/529 "Claude is busy" with Retry; offline / 5xx / timeout "Can't reach Claude" with Retry; refusal or
  bad reply "Couldn't work that out".
- **Data:** only the question (and the follow-up with the earlier answer) and installed app names + package ids.
  Never calendar data. Nothing logged; every result type's toString hides text; errors are enums.
- **Key:** unchanged path (ADR 0001): `provision.ps1 -Key anthropic_api_key` / `provision.sh -k anthropic_api_key`,
  stdin and content write only. Already supported; no key exists anywhere yet. Putting one on the TV is Brian's call.
- **Not in this slice:** the overlay (decision 6), web search, streaming progress, the B0 "try the built-in assistant
  first" check.

**Cost per question (spec AC9, estimate, not measured):** input about 700-1,000 tokens (instructions ~250, app list
~10 tokens per app, reply schema ~150, question ~20; a follow-up adds ~200) = $0.003-0.004 at $4/MTok. Output about
200-1,200 tokens (answer ~150, JSON ~40, low-effort thinking 0-1,000) = $0.004-0.024 at $20/MTok. **Typical ~$0.015,
range $0.01-0.03** per question; without web search this is well under the ~$0.10 above. `max_tokens` 4096 caps
one question at about $0.09 (4,096 x $20/MTok plus input), which only a runaway reply would reach. The daily cap
bounds a typical day at about $0.60 (~$18 a month if used to the cap every day) and a worst case at about $1.80 a
day; at 5 questions a day, about $2-3 a month. Sonnet 5.5 ($2/$10)
would halve it (one constant, `AskConfig.MODEL`).

## Alternatives considered
- **Don't build it: the built-in Google TV assistant (Gemini / Google Assistant on the remote's mic button)**
  already handles "play Stranger Things on Netflix" on many devices, at no cost, with no sideload. Strongest case
  against M1b. Brian should try it first (task B0). M1b then earns its place only for what the built-in assistant
  does badly (open-ended "something like X", questions about what is playing).
- **Run the Claude call on Brian's PC instead (via M1c)**: adopted for typed requests, rejected for the TV paths
  (decision 1). The key never touches the TV, and with Claude desktop /
  Claude Code it uses his Claude plan instead of API billing. Cost: needs the PC on, and the phone or laptop rather
  than the TV remote. Recommend building M1c before M1b; if typing on the phone is good enough, M1b may shrink to
  just the overlay or be dropped.
- **TMDB watch-providers API**: accurate, structured, cheap per call, but needs a free account and API key
  (Brian's hard stop) and per-country data licensing terms. Rejected; revisit if web search proves unreliable.
- **JustWatch**: no official public API. Rejected.
- **Raw HTTP to the Messages API instead of the SDK**: smaller APK, no reflection. Rejected as the default (SDK is
  the supported path); it is the documented fallback if anthropic-java fails under R8 on Android (task B6).
- **Faster or cheaper model** (Sonnet 5.5, Haiku 5.5): see decision 4. Switching is Brian's call; one config value.
- **Screen capture with MediaProjection for the overlay**: consent dialog every session, black frames for DRM
  apps, and video frames sent to Anthropic. Deferred.
- **Reading the player's UI through accessibility window content**: would get on-screen titles, but means reading
  screen content, which the spec rules out. Rejected.

## Consequences
- Cost: about $0.02-0.20 per question, typically ~$0.10 (architecture section 7); roughly $10-15 a month at 5 a
  day. Spending starts only after Brian approves.
- APK grows by the SDK's dependencies (Jackson, kotlin-reflect, jsonschema-generator); R8 keep rules needed.
- The overlay needs three device settings changed over adb (accessibility service, notification listener, maybe
  appops). These are part of the one install ask.
- "Without pausing" is not guaranteed for every app: a player may pause when speech recognition takes audio focus.
  The overlay falls back to typed input per app, from B5's results.
- Data sent to Anthropic: the question, installed service names, and (overlay) the now-playing title, subtitle and
  position. Never calendar data.
- The overlay's settings changes append to the accessibility list and keep a rollback file (`#4`); the notification
  listener needs Brian's explicit OK (`#11`).
- Recommend: a 3-member panel (devil's advocate, Brian's seat, Android platform/security) on this ADR before B3,
  because of the cost and the accessibility service.
