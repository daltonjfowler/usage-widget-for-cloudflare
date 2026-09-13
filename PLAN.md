# Build and handoff

## Scope

Free Android home-screen widget for account-wide Cloudflare usage charges and Workers compute. User authorized the full local build and creation of this standalone Desktop folder. Device: Pixel 7 running GrapheneOS. User is comfortable installing an APK.

## Implemented

- Direct HTTPS billing API, no server or Cloudflare deployment.
- Account setup and connection verification, encrypted token, disconnect, account ID entered by the user (empty by default with a hint).
- Current billing-cycle cost totals and every returned service, separate currencies.
- Workers Standard raw request/CPU recognition, conditional paid-plan allowance bars in the app and widget.
- Compact resizable widget (SizeF variants STRIP/WIDE/MEDIUM/LARGE/TALL/XL, default 4x1) and a Workers request/CPU counter with pace projection, USD overage estimate, and unidentified-row diagnostics. The "+N" family token was removed in favor of currency-separated family overflow lines. The XL variant (key 180x330) is what a full-height three-row home-screen cell receives; it shows the full breakdown plus an allowance-left line, pace line, and next-bill line.
- Workers identification fixes from the first on-device screenshot: the exclusion token check runs on ServiceName and ChargeDescription only, so rows Cloudflare groups under the "Workers & Pages" family are recognized while Pages Functions is still excluded; request and CPU units now also accept the "million ..." variants (multiplier 1,000,000); a "Copy row names" button and Billing.rowLabels expose every returned row for developer diagnosis; when no Workers rows are identified the MEDIUM footer, LARGE metrics view, and TALL/XL extra line all say so instead of hiding the meters silently.
- Upcoming charges: best-effort /subscriptions fetch, listed subscription fees with renewal dates, a current-cycle usage projection, and an estimated next-bill figure. The DEFAULT_ACCOUNT constant was removed; the account field starts empty with a hint.
- Home-screen widget, demo, details, manual and three-hour background refresh.
- Cached data and clear offline/stale/permission-denied states.
- Build script and billing/Android regression tests.

## Validation

- Signed release APK compiles with debugging disabled. Personal signing key stays under ignored `private/`; preserve it for future updates.
- Final run: 101 tests passed (BillingTest 49, SubscriptionsTest 14, AndroidBehaviorTest 28, WidgetPreviewTest 10). Native Android rendering exported a real widget preview and a 192x192 launcher-icon preview, visually inspected.
- Android lint: 0 errors, warnings only. Remaining warnings concern English-only strings and deliberate synchronous preference commits on the background executor.
- Release signature verified with Android apksigner (APK signature v2, RSA 3072). Package `app.usagewidget`, version 1.1 (2), min SDK 31, target SDK 35, no debuggable flag. APK: 91196 bytes. SHA-256: 50fbcf2cd4c09b3fdf3deb242e51916f5613b70646091b2cc8f4dd370ad5bfb2 (recorded in `releases/SHA256SUMS.txt`).
- No physical Android device attached to ADB at build time.
- Workers request/CPU identification was verified against the live row names from the device on 2026-09-12. The user should still compare the widget's request count against the Workers overview counter (Requests / CPU time) in the Cloudflare dashboard.
- The first on-device screenshot (Pixel 7, GrapheneOS, tall widget) showed no identified Workers rows at all: the request and CPU meters and the pace line were absent while the dashboard reported six product families and 171.63k Workers requests. The app's diagnostics list then showed the actual cause: Cloudflare sends an empty ConsumedUnit for these rows (the app shows "units (unspecified)") and names them "Workers Standard Requests (first 10M are included)" and "Workers CPU ms (first 30M are included)", with a ChargeDescription that repeats the name plus "usage measured in Count". Identification now falls back to the service name when the unit is empty or "Count", KV and build-minute rows stay excluded, the exclusion check runs on ServiceName and ChargeDescription only, the "million ..." unit variants were added, and unidentified snapshots surface a "not identified" line plus a "Copy row names" button. Regression tests mirror the exact live row shapes. A fresh on-device check of the meters is still pending.
- Existing Wrangler OAuth login successfully identifies the account, but a read-only GET to the billing endpoint returns HTTP 403 (code 10000). The user must create a Billing Read API token and enter it in the app. No deployment token was copied into this project.
- On-device check passed on 2026-09-12 (Pixel 7, GrapheneOS, three-row widget): 166.95K requests (2%), 87.84K CPU ms (<1%), six families listed, "Left: 9.83M requests · 29.91M CPU ms", pace line, and "Next bill about $5.45 · Sep 14". The dashboard counter read 171.63k at a later data time than the billing snapshot (latest data 2026-09-11 UTC), which explains the gap. Missing metric amounts intentionally remain unavailable rather than zero.
- The /subscriptions endpoint and response shape are unverified against a live account; field names come from the docs only, and parsing is lenient with a reject-and-preserve policy.
- Subscriptions failures never fail the usage refresh; a failed subscriptions fetch keeps the last saved list and records a separate error.
- The estimated next bill excludes tax, credits, zone plans, and proration; zone plans need zone permissions and are not covered.
- Sub-monthly renewals are counted once for a single period.

## Before open-sourcing

- Choose a license. Done: MIT (see `LICENSE`).
- Rename the app and icon for trademark compliance. Done: display name "Usage Widget for Cloudflare", package `app.usagewidget`, and a new gas-pump-and-raincloud launcher icon replacing the former cloud icon.
- Confirm no personal identifiers or secrets are in tracked files. Done: the hardcoded account id and the sibling project name were removed, and the pre-publication history was squashed so neither appears in git history. `private/`, `.tools/` and the source zip stay ignored.
- Decide whether to publish release APKs. Done: `releases/UsageWidget.apk` is committed with a note that it is a personal build tested only on a Pixel 7 running GrapheneOS.
- Add a short contributing note. Done: see README.

## Next device check

Install APK, allow Network access, try demo and widget pinning, enter Billing Read token, compare totals with Cloudflare's Billable Usage page, check resize and refresh. Account setup does not require a new backend, paid widget app, or Google account.

## Artifacts

- `releases/UsageWidget.apk`: signed installable release.
- `releases/widget-demo.png`: Android-native MEDIUM-variant rendering with labeled example data.
- `releases/INSTALL.md`: short installation and connection guide.
- `releases/SHA256SUMS.txt`: APK integrity hash.
- `README.md`: build instructions and API limitations.

🌱
