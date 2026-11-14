# Code review: game-day scoreboard (PR #47, commit 0cb58d0)

Reviewer: code-reviewer (opus/high), 2026-11-14. Verdict: REQUEST CHANGES (1 must-fix).

| Key | Severity | Where | Finding |
|---|---|---|---|
| design-canvas/dialog-density | must-fix | GameBoard.kt TicketDialog; DesignCanvasBox.kt:21 | A Dialog re-provides LocalDensity from the window, so the ticket (and PickDialog, HiddenAppsDialog) lays out at the real density, not the 1280x720 canvas: at density 2.0 it overflows the safe area and the 900-character fit fails. Fix: capture the canvas density and provide it inside the dialog. |
| snow-redraw-scope/board-draw-allocs | should-fix | GameBoard.kt:264, 319, 440, 615 | Path, PathEffect and float arrays built per draw. Build once in drawWithCache. |
| safe-area/board-shadow | nit | GameBoard.kt:301-309 | Shadow ring reaches ~13 dp past the board zone. |
| stale-doc/pick-short | nit | Picks.kt | `short` doc says "not shown"; the ticket shows it. |

Held up: no compile errors found against Compose 1.7 API files; home-back-twice (stub is the only, leaf, focus target; dialog is its own window); OK never a no-op; DIM and drift; board == null behaves as main; PickQaHook is a no-op in release and debug and validates through Picks.parse; opponentAbbr validated on parse and on cache read; workflow change is the upload glob only; cold start; core tests 791 pass.

## Delta re-review at 15aaed0 (2026-11-14): APPROVE

All four findings fixed (dialog density provided in TicketDialog, PickDialog, TileMenu and HiddenAppsDialog; draw allocations moved to drawWithCache; shadow capped; doc corrected). Polish commit b08d11d (cones fade in the hero band, no unknown-TV text on the board, room for the focused stub): no findings. Merge of main (PR #46, Watch pill timing): rules intact; the board's `watch == null` means no pill is showing. Core tests 796 pass. Note: at density 1.5 a full-height ticket sits exactly on the safe-area edge (y 54), passing with no margin.
