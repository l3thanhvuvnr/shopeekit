# Shopee checkout — authoritative UI labels

Ground-truth strings the CheckoutSniper matcher keys off. **Do not guess these** —
they are extracted from the app itself and should be re-extracted after a Shopee
update rather than hand-edited.

## Source

Shopee VN **3.77.25 (37725)**. The checkout screen is a React-Native module
(`@shopee-rn/checkout`, `@shopee-rn/checkout--opc-payment-selection`) downloaded
at runtime — its **code** is not in the APK, but its **i18n string catalog is**,
bundled (7z) inside the base APK:

```
base.apk → res/raw/bundle_v9.7z
  strings/@shopee-rn/checkout/i18n/collection-vi-live-*.json
  strings/@shopee-rn/voucher-pages/i18n/collection-vi-live-*.json
  strings/@shopee-rn/cart/i18n/…   order-payment/…   shopeepay/…
```

Re-extract (needs 7-Zip):

```bash
unzip -o base.apk 'res/raw/bundle_v9.7z' -d out
7z x -y -o out/bundle out/res/raw/bundle_v9.7z
# grep the vi json, e.g.:
grep -oE '"[a-z0-9_]*":"[^"]*"' out/bundle/strings/@shopee-rn/checkout/i18n/collection-vi-live-*.json
```

## Stable resource-ids (STRONGEST signal — verified on real device)

From a live `uiautomator dump` on a Poco F3 (Shopee VN 3.77.25), the checkout CTAs
render as **text-less `android.view.ViewGroup`s** — so text matching can't see them.
But they carry **semantic, stable resource-ids**. These are matched first (the
matcher's `resourceIdHints`), with text/position as fallback:

| Element | resource-id | node | notes |
|---|---|---|---|
| `PLACE_ORDER_BUTTON` | `buttonPlaceOrder` | clickable ViewGroup, text="" | bottom-right of checkout |
| `VOUCHER_PICKER_ROW` | `buttonCartPageUseVoucher` | clickable ViewGroup, text="" | "Shopee Voucher" label is a child TextView |
| `APPLY_VOUCHER_BUTTON` | `btnOkVoucherSelectionSubmitSection` | TextView "Đồng ý" (resolve to clickable parent) | bottom bar of the voucher drawer |

Other useful ids seen: `checkoutPaymentMethod`, `buttonCheckBoxNotChecked`,
`viewPlatformVoucherSelected` (selected-voucher summary bar with "Đã áp dụng…"
text), voucher cards `sectionVoucherCard_<n>` / `radioBtnVoucher_<n>_selected`,
shop-voucher row is a text-less clickable ViewGroup with **no** id. Disabled CTAs
gain an `_disabled` suffix (e.g. `buttonAction_disabled`) — deliberately NOT matched,
so a disabled button is never tapped.

> The two voucher-row *label* TextViews confusingly share id `labelPlatformVoucher`
> (both "Voucher của Shop" and "Shopee Voucher"). Match the clickable **row**
> (`buttonCartPageUseVoucher`), not the label id.

## Element → i18n key → rendered VN string

| Matcher element | i18n key | VN string | Notes |
|---|---|---|---|
| `PLACE_ORDER_BUTTON` | `label_opc_place_order` | **ĐẶT HÀNG** | primary OPC CTA (uppercase) |
| `PLACE_ORDER_BUTTON` | `label_place_order` | Đặt hàng | title-case variant |
| — (negative) | `label_opc_cancel_place_order` | **Mua sau** | *cancel* button next to CTA — never tap |
| — (negative) | `label_opc_continue_place_order` | Tiếp tục | secondary confirm (context-dependent) |
| `VOUCHER_PICKER_ROW` | `label_opc_platform_voucher` / `label_platform_voucher` | **Shopee Voucher** | the platform voucher row (target) |
| — (negative) | `label_opc_shop_voucher` | **Voucher của Shop** | the *shop* row — avoid |
| (row value) | `label_select_or_enter_code` | Chọn hoặc nhập mã | value cell on the platform row — do NOT veto "nhập mã" |
| `APPLY_VOUCHER_BUTTON` | `voucher_drawer_button` | **Đồng ý** | the voucher drawer's main confirm CTA (primary — put first) |
| `APPLY_VOUCHER_BUTTON` | `voucher_list_label_ok` | OK | alt drawer confirm |
| `APPLY_VOUCHER_BUTTON` | `voucher_wallet_title_button_apply` | Áp dụng | alt confirm |
| `APPLY_VOUCHER_BUTTON` | `voucher_tnc_label_use_now` | Dùng ngay | per-voucher use |
| (code input) | `label_voucher_input_apply` | Áp Dụng | apply-typed-code button (manual code) |
| cart CTA (not OPC) | `label_cart_checkout` | Mua hàng | cart screen, **not** the checkout button |

### Voucher auto-selection + "applied" indicators (used to verify success)

Shopee **auto-selects** the best platform voucher when the drawer opens, so
`AutoBest` must NOT tap an **already-selected** voucher item (that toggles it off).
BUT once the user has manually **de-selected** the best voucher, Shopee remembers
the removal and reopens the drawer with **nothing** selected — confirming then
applies nothing. So `AutoBest` first checks the selection state
(`radioBtnVoucher_<n>_selected` / `viewPlatformVoucherSelected` / "đã được tự động
chọn"); only if nothing is selected does it re-tick the top voucher card, then confirm.

> **Zero-bounds CTA nodes:** Shopee's RN wraps `buttonCartPageUseVoucher` (voucher
> row) and `btnOkVoucherSelectionSubmitSection` ("Đồng ý") in a semantic-id node
> that is frequently laid out at `[0,0][0,0]` (so `isVisibleToUser=false`), while
> the visible content sits in children. Discovery therefore trusts an **exact
> resource-id match regardless of bounds/visibility** and taps it via `ACTION_CLICK`;
> a position pin alone drifts onto the look-alike row at the same spot.

| i18n key | VN string | Use |
|---|---|---|
| `voucher_checkout_auto_selected_limit_one` | "1 voucher đã được tự động chọn cho bạn." | proof Shopee auto-picks |
| `voucher_checkout_applied_fsv` | "Đã áp dụng Ưu đãi phí vận chuyển" | applied signal (freeship) |
| `voucher_checkout_applied_discount_voucher` / `voucher_checkout_selected_discount_reward` | "giảm {amount}" | applied discount |
| `voucher_checkout_selected_coin_cashback_reward` | "{amount} Xu" | applied coin cashback |

`VoucherApplyParser` reads these off the checkout screen after the drawer closes,
to show the applied discount in the 2-step rehearsal. It asserts "applied" only on
an explicit phrase ("đã áp dụng"/"tự động chọn") — never on a bare "giảm" (product
discounts use it too); the engine's real success signal is *structural* (drawer
opened → confirm tapped → back on checkout).

## Run modes (SnipeMode) — 2-step vs 3-step

Placing an order on real Shopee is irreversible, so the snipe splits into:

| Mode | Steps | Ends on |
|---|---|---|
| `VOUCHER_ONLY` (default) | Shopee Voucher → chọn → **Đồng ý**, then STOP | `VoucherApplied` (no order) |
| `FULL_CHECKOUT` | above + **ĐẶT HÀNG** (only if voucher applied) | `Success` / `RequiresPin` / … |

Default is `VOUCHER_ONLY` so an accidental arm can never buy. `FULL_CHECKOUT`
places the order **only** when the voucher step returns `Applied`; a failed voucher
apply retries and never falls through to a voucher-less order.

## Result parsing (OrderResultParser)

| Result | i18n examples | Match |
|---|---|---|
| OutOfStock | `opc_item_oos` "Sản phẩm tạm hết hàng", `error_buy_out_of_stock` | "hết hàng", "sold out" |
| PaymentError | `label_pay_in_advance_error_toast` "Thanh toán chưa thành công", "…thất bại" | negated-"thành công", "thất bại" |
| Success | "Đặt hàng thành công", "đã đặt hàng", "mã đơn hàng" | guarded so negated forms don't leak |

> ⚠️ Many **failure** strings contain the word "thành công" (e.g. "chưa thành
> công", "không thành công"). The parser checks negated-success → PaymentError
> **before** the success branch. Keep that order.

## Voucher timing & claim — "săn voucher" (governs the snipe fire timing)

Flash/săn vouchers are **not instantly usable**: they must be **claimed ("Lưu")**
into the *Ví Voucher* wallet first, and claim + use can each be time-gated and
limited-quantity. This is why the sniper opens the drawer **after** the release
instant T (a voucher released at 00:00 is not in a checkout screen loaded at
23:59 — the drawer list is fetched on open).

| Mechanic | i18n key | VN string |
|---|---|---|
| Claim to wallet | `voucher_label_claim` / `vlp_get_voucher_button` | **Lưu** → then `voucher_claimed`="Đã lưu" |
| Claimed → wallet | `label_followed_and_voucher_has_claimed` | "Mã giảm giá đã được lưu vào Ví Voucher của bạn" |
| Claim time-gate (countdown) | `label_voucher_claim_in` / `label_voucher_claim_from` | "**Lưu mã sau: {time}**" / "Lưu mã từ: {date}" |
| Claim too early | `voucher_claim_error_not_start` | "Thời gian để lưu voucher này về ví vẫn chưa bắt đầu" |
| Use time-gate (post-claim) | `label_voucher_use_in` / `label_valid_from` | "Hiệu lực sau: {time}" / "Có hiệu lực từ {date}" |
| Use too early | `vlp_msg_voucher_invalid_valid_not_start` | "thời gian sử dụng của voucher này chưa bắt đầu" |
| Limited qty (progress) | `label_flash_voucher_n_claimed` | "**{n}% ĐÃ LƯU**" |
| Sold out | `label_flash_voucher_claimed` / `vlp_alert_voucher_invalid_fully_claimed` | "**Đã lưu hết**" / "đã hết lượt sử dụng" |
| First-come urgency | `label_first_come_first_served` | "Lượt sử dụng có hạn. Nhanh tay kẻo lỡ bạn nhé!" |
| Claim **and** apply in one go | `voucher_best_claimed_and_applied` | "Voucher tốt nhất đã được lưu và tự động áp dụng" |
| Code-entry path (drawer) | `label_select_or_enter_code` / `platform_voucher_claim_input_placeholder` | "Chọn hoặc nhập mã" / "Nhập mã shopee voucher" |
| Refresh the list | `label_cart_refresh` | "Tải lại trang" |

**Engine consequence:** fire just **after** T (server-gated), reopen the drawer
each attempt (so AutoBest re-selects the now-live voucher), never place the order
before T, and keep retrying past T to catch a **late** release. See
`CheckoutSniperEngine.executeFireLoop`.
