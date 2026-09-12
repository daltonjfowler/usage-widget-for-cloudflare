# Usage Widget for Cloudflare

Standalone Android project. Do not modify sibling projects on this machine or deploy a Worker for this app.

- Java 17, Android platform Views/RemoteViews, minSdk 31, compile/target SDK 35. No runtime library dependencies or Google Play services.
- Keep credentials out of source, logs, screenshots, fixtures and APKs. Billing Read only; token is entered in the app and encrypted using Android Keystore.
- API: `GET https://api.cloudflare.com/client/v4/accounts/{id}/billable-usage` with no query parameters for the current billing cycle. Alpha schema: reject incompatible responses and preserve the last snapshot.
- Sum `ContractedCost` for period rows, never `CumulatedContractedCost`. Keep currencies separate. Do not turn missing consumption into zero or confuse billable quantity with total consumed quantity.
- Selected Workers Paid allowances and base fee are manual settings; never imply automatic plan detection. All fixed subscription fees are separate from usage charges.
- Tests: `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1`. Includes APK build, JUnit billing tests, Robolectric Android 12/15 behavior tests and lint. Add a regression for real bugs.
- Tests or docs may use labeled demo data. Never silently substitute demo data for a failed live fetch.
- No em dashes or left-border accent stripes in UI text/cards. Sign off with 🌱.
- Keep handoff status in PLAN.md current, including live-account and physical-device validation limits.
