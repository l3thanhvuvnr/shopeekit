# Pre-open (prewarm) voucher drawer before T — design

## Context / problem

On-device 2-step measurement showed the voucher-apply hot path is ~3.4s, of which
the race-relevant part (fire → "Đồng ý" tap) is ~2.6s. That 2.6s is dominated by
two Shopee-side costs on a **cold** drawer open at/after T:
- ~0.6s RN drawer slide-in animation
- ~1.6s voucher-list network fetch (Shopee backend, under peak release load)

The voucher is claimed/applied at the "Đồng ý" tap (~2.6s); the ~0.76s drawer-close
that follows is downstream of the claim and does not affect grabbing the voucher.

Goal: shave the cold-open cost by **prewarming** the drawer before T, so the real
open at T reuses a warm RN component + warm TLS connection + loaded JS bundle.

## Decision

**Prewarm-then-reopen** (not "open & hold"). At ~T-2s (start of the existing
warm-up window) open the voucher drawer once and dismiss it, then let the fire
loop open it fresh at T exactly as today.

Rationale: the fire path stays byte-for-byte the verified path — prewarm only adds
a step *before* it, so it cannot break the working flow. "Open & hold + refresh"
could cut the ~0.6s animation too, but depends on unobserved Shopee behavior
(does an already-open drawer refresh at T?) and risks a stale pre-T auto-select.
The prewarm's logs will reveal that behavior for a possible future upgrade — no
blind code.

**#2 (skip drawer-close wait) is dropped.** The close-wait is downstream of the
claim (doesn't speed up grabbing the voucher) and is entangled with the 3-step
applied-verify (the checkout row is only readable once the drawer closes), so
skipping it risks a voucher-less order for no voucher-race benefit.

## Design

New capability threaded through the existing layers (no new architecture):

- `CheckoutActuator.back()` → `service.performGlobalAction(GLOBAL_ACTION_BACK)` —
  dismiss the drawer without confirming.
- `VoucherApplyFlow.prewarm()` (suspend): if on checkout and the "Shopee Voucher"
  row is present (and is NOT the place-order node), tap it, wait briefly for the
  drawer's "Đồng ý" button to render (warms RN + connection + triggers the pre-T
  fetch), then dismiss via `back()` and verify the drawer closed. Never taps
  "Đồng ý", never confirms, never applies a voucher. Best-effort: any failure is a
  no-op. Logs `prewarm: opened=… closed=… in Xms`.
- `CheckoutUiDriver.prewarmDrawer()` (suspend) + `AccessibilityCheckoutUiDriver`
  delegate → `ShopeeAccessibilityService.prewarmDrawer()` → `voucherFlow.prewarm()`.
- `CheckoutSniperEngine.runWarmUp`: after reaching the warm-up window (T-2s), call
  `driver.prewarmDrawer()` **once**, guarded by `clock.nowMs() < fireAtLocal -
  PREWARM_MIN_LEAD_MS` (1500ms) so an arm made too close to T skips prewarm and
  falls back to current behavior. Then continue the existing scroll nudges.

### Safety invariants
- Prewarm runs ONLY before T, in the warm-up window.
- Prewarm never taps place-order or the confirm button; dismiss is BACK only.
- Prewarm must leave checkout with the drawer closed; if it can't confirm closed,
  it logs a warning (the fire loop's retry still recovers — it just re-opens).
- No effect on drawer-skip: pre-T the row stays on the "Chọn hoặc nhập mã"
  placeholder → `platformVoucherRowValue` null → no skip, unchanged.

## Verification
- JVM: existing 13 engine tests still pass (add a no-op `prewarmDrawer` to the fake
  driver). Prewarm timing itself isn't unit-testable with the fixed fake clock
  (same as `warmUpNudge` today) — the device measurement is its test.
- On device: 2-step run, compare `fire → find apply_voucher_button conf=1.00`
  elapsed with prewarm vs the ~2.36s baseline, and read the `prewarm: …` line to
  confirm it opened+closed cleanly before fire.
