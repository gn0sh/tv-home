# TV home screen: what to pack onto it (Brain Trust + Focus Group, 2026-11-02)

Asked by Brian 21:00Z: "pack that screen with useful, relevant information... more than just a place where I go to find something to watch."

## Panels run
- **Brain Trust (4 members, independent):**
  - Skeptic (assigned devil's advocate): **Rethink**
  - The User (Brian's seat): **Fix First**
  - Operator (platform, data, OLED): **Fix First**
  - 10-foot information designer: **Fix First**
  - No member said Go, so no pre-mortem round was needed.
- **Focus Group (4 simulated personas; hypotheses, not real user data):**
  - Dave (power user like Brian): **Probably**
  - Marcus (Echo Show user): **Definitely**
  - Pat (shares the room, devil's advocate): **No**
  - Ruth (74, low vision): **Probably not**

## Where everyone agrees
1. **The apps row keeps first focus.** Home to Netflix stays 2 presses, and info never sits between Brian and a show. All 8 voices said this, even the ones who want more info.
2. **Answer the daily questions without a click.** What's next on my calendar, what will the weather do to my day, and are my teams playing. Information you have to open is "dead weight".
3. **Weather is wanted by everyone, the skeptic included.** That means current temperature, the rain window, and **severe-weather alerts**, because tornado season in Middle Tennessee makes alerts a real safety item. The source is NWS api.weather.gov: free, no key, public domain.
4. **OLED burn-in is a real risk on the LG.** Use slow pixel drift, dim after about 10 minutes idle, and go near-black with a drifting clock after about 30 minutes. No fixed bright borders.
5. **Stale data kills trust.** Every panel needs to show "updated X min ago", or hide itself when stale.
6. **No news tickers, social feeds, ads, auto-scrolling text or tiny text.**

## Disagreements
- **How much to show:**
  - The Skeptic and Pat say cap it at 3 one-line items; the designer says 6 is the ceiling.
  - Dave, Marcus and the User seat want a game card and a Claude brief as well.
  - The stronger argument is the designer's: **about 6 glance items, enforced in code**, where a 7th has to replace one rather than stack. That is 3 lines plus the context card plus the brief, which still leaves room.
- **Calendar privacy with guests:** Pat raised it. A one-press Quiet/guest mode that hides event titles settles it.
- **Continue watching:** the User seat thinks he'd use it most. The Operator says Android TV only exposes it for apps that publish to Watch Next, so it's uncertain. Deferred until a spike proves it works.

## The strongest alternative (steelmanned)
Leave Home sparse, and add an **ambient mode** that fades in after about 60 seconds idle, dimmed and drifting, and disappears on any key. It protects the OLED and keeps the launcher fast. Verdict: it doesn't beat the main proposal, because Brian asked for information on the Home screen itself. But it is a good **second step**, as the idle and burn-in behaviour of the same screen.

## Recommended layout (1920x1080)
- **Top strip:** clock (left); date and weather chip with the rain time (right). The strip drifts slowly.
- **Hero, left 60%: "Now / Next".**
  - Shows the next event in large type with "in 42 min", plus the one after it.
  - Within 60 minutes it becomes a countdown ("Leave by 5:40").
  - The backdrop stays, dimmed to about 35%.
- **Hero, right 40%: one context card, which changes by situation.**
  - Morning: today's weather arc.
  - Evening: tomorrow's first event and the overnight low.
  - **Game day: the Preds/Ohio State card.** It shows the opponent, the start time, and which app carries the game. During the game it adds the live score, and a **Watch** button opens that app.
  - A severe-weather alert overrides everything else.
- **Apps row:** unchanged, with first focus.
- **Bottom strip:** Ask Claude, the full Calendar, and **one line of Claude daily brief** ("Rain after 4, Preds at 7 on ESPN+, dentist tomorrow 9am"). The brief is generated once or twice a day from the panels above and cached, so it costs pennies.
- **Text sizes:** 32 px minimum for anything meant to be read, 56-72 px for hero text and clock, contrast at least 7:1.

## Ranked items (build order)
1. Shared data layer: a cached last-good value, an "updated" stamp, stale and error states, and a per-panel off switch. Every panel depends on it.
2. Weather plus severe alerts (NWS). Size S-M.
3. Now/Next calendar in the hero, with the countdown. Size S; the calendar is already connected.
4. Game-day card for the Preds and Ohio State (NHL api-web and the ESPN scoreboard). Size M. These sources are unofficial and can break, so they sit behind an adapter, with a cached season schedule as the fallback.
5. OLED protection: drift, idle dim, and a separate LG and Vizio profile. Size S.
6. Claude one-line daily brief. Size M, and pennies a day.
7. Quiet/guest mode, which hides event titles. Size S.
- Later or maybe: ambient idle mode with photos, the Large-text mode Ruth needs, Continue watching (needs a spike first), and Google Tasks reminders.
- Skip: commute/traffic (needs a paid key), smart home (no Home Assistant), and news headlines.

## Decisions this changed
| Finding | Decision changed? | Scope | Owner |
|---|---|---|---|
| Apps row must keep first focus | Yes: a hard rule for the redesign | neutral | ux-designer |
| Cap of about 6 glance items, enforced in code | Yes: replaces "pack the screen" | cuts | ux-designer / frontend |
| Hero becomes the info surface, not decoration | Yes | adds | ux-designer |
| Severe-weather alerts (NWS) | Yes: added to the weather panel | adds | backend |
| Shared data layer with staleness first | Yes: becomes task 1 | adds | software-architect |
| OLED drift and idle dim | Yes: ships with the first new panel | adds | frontend |
| Quiet/guest mode | Yes: added (small) | adds | frontend |
| News, commute, smart home | Yes: cut | cuts | PM |
| Continue watching | Deferred: needs a spike first | neutral | software-architect |
| Ambient idle mode | Deferred: phase 2 | neutral | PM |
