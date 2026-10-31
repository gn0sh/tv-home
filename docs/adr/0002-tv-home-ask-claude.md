# ADR 0002: tv-home M1b "Ask Claude" (voice to streaming app, and the playback overlay)

Status: proposed (architect), 2026-10-31. Needs Brian's go-ahead on cost and the API key (hard stops 3 and 5).
Architecture: `docs/architecture/tv-home.md` sections 7 and 7b.

## Context
Brian (11:23Z): talk to Claude on the home screen and have it open the streaming service that has what he asks for.
Brian (11:32Z): ask about what is on screen without pausing playback. The Anthropic API key is a secret like the
calendar URL. Prefer no new accounts.

## Decision
1. **Where the call runs: on the TV**, from a JVM module `:assistant-claude` built on the official anthropic-java SDK
   (3.2.0, OkHttp under the hood, same client as `:core`). The contract, tool schemas, system prompt and deep-link
   builders live in `:core` so they are tested in the sandbox; the SDK call is tested against MockWebServer with
   hand-written response fixtures. No test calls the real API.
2. **Which service has it: Claude's server-side web search** (`web_search_20260209`, `max_uses: 3`), not a catalog
   API. No new account; costs $0.01 per search.
3. **Claude picks, our code builds.** Claude calls one strict client tool, `open_title`, whose `service` is an enum
   of installed services. The intent URI is built locally from `services.json`, with a per-service id regex, then
   search link, then plain app launch as fallbacks. Web content cannot make the app open an arbitrary URI.
4. **Model `claude-opus-5-5`** (the latest, as Brian asked), effort `low`, adaptive thinking, server-side refusal
   fallback on. The model is a config string.
5. **Voice:** Android `SpeechRecognizer` behind an on-screen mic button (the remote's mic button belongs to the
   system assistant). Fallback: on-screen keyboard, or typing from the phone through M1c.
6. **Overlay:** an accessibility service that filters keys only (no window content) catches one long-press pattern
   while another app plays and shows a `TYPE_ACCESSIBILITY_OVERLAY` panel. Context comes from MediaSession metadata
   via a notification listener. No screen capture in M1b.
7. **Key handling:** same path as the calendar URL (ADR 0001), redaction of `sk-ant-` patterns. Brian creates a
   dedicated key in a dedicated workspace with a monthly spend limit; the app enforces a daily question cap
   (default 20).

## Alternatives considered
- **Don't build it: the built-in Google TV assistant (Gemini / Google Assistant on the remote's mic button)**
  already handles "play Stranger Things on Netflix" on many devices, at no cost, with no sideload. Strongest case
  against M1b. Brian should try it first (task B0). M1b then earns its place only for what the built-in assistant
  does badly (open-ended "something like X", questions about what is playing).
- **Run the Claude call on Brian's PC instead (via M1c)**: the key never touches the TV, and with Claude desktop /
  Claude Code it uses his Claude plan instead of API billing. Cost: needs the PC on, and the phone or laptop rather
  than the TV remote. Recommend building M1c before M1b; if typing on the phone is good enough, M1b may shrink to
  just the overlay or be dropped.
- **TMDB watch-providers API**: accurate, structured, cheap per call, but needs a free account and API key
  (Brian's hard stop) and per-country data licensing terms. Rejected; revisit if web search proves unreliable.
- **JustWatch**: no official public API. Rejected.
- **Raw HTTP to the Messages API instead of the SDK**: smaller APK, no reflection. Rejected as the default (SDK is
  the supported path); it is the documented fallback if anthropic-java fails under R8 on Android (task B6).
- **Cheaper model** (Claude Sonnet 5.5 at $2 / $10 per MTok roughly halves the token cost): Brian's call, not
  ours; one config value.
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
- Recommend: a 3-member panel (devil's advocate, Brian's seat, Android platform/security) on this ADR before B3,
  because of the cost and the accessibility service.
