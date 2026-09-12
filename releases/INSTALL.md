
# Install Usage Widget for Cloudflare

If an earlier build of this app is installed under a different package id, uninstall it first; Android treats a changed package id as a separate app.

1. Transfer `UsageWidget.apk` to the Pixel and open it in Files. Allow installing from Files if prompted.
2. Open **Usage Widget for Cloudflare** and allow **Network** access on GrapheneOS.
3. Tap **Try the demo widget**, then **Add home-screen widget** to try it immediately. After pinning, long-press the widget to resize it, from a one-line strip up to a full card; on a Pixel or GrapheneOS home screen the default is a compact single row.
4. For real usage, use the app's **Open Cloudflare API tokens** button. Create a custom token with **Account > Billing > Read**, restricted to your account.
5. Paste that token into the app. Paste your own account ID, which appears in your Cloudflare dashboard URL. Confirm the Workers Paid checkbox and tap **Save and connect**.

The same Billing Read token already covers subscriptions; no new permission is required. Cloudflare describes Billing Read as read access to the billing profile, subscriptions, invoices and entitlements. On the author account on 2026-09-12 the profile returned name, billing email, postal address and account type, and no card fields. Give the token an expiry date and revoke it in Cloudflare if the phone is lost; scripts/check-billing-token.ps1 in the repository shows what your own token exposes without printing values.

The existing desktop Wrangler login was checked, but it does not have Billing Read permission. This is the only missing account-connection step. Do not use the global API key.

The widget reads Cloudflare directly. No server, Google Play services, or additional subscription. Billing data is daily; the widget checks approximately every three hours and has a manual refresh button.

The large total is usage charges for current subscription cycles, excluding fixed subscription fees, tax and credits. CPU/request meters appear when Cloudflare provides identifiable raw measurements. Tap the card to see all returned services.

The request count depends on the account's billing snapshot returning within-allowance Workers rows. If the count is absent, open the app to see which Workers rows were returned.

The app was built and checked locally, including Android 12/15 behavior tests and an Android-native widget render. Physical Pixel/GrapheneOS installation and comparison with your live billing dashboard remain to be done on your device.

Keep the project's `private/` folder safe so future release builds can update this installation without uninstalling it.

This APK is a personal build signed with the author's key and tested only on a Pixel 7 running GrapheneOS. It may not work on other devices or launchers. Compare its SHA-256 with SHA256SUMS.txt before installing, or build from source with scripts/build.ps1.

🌱
