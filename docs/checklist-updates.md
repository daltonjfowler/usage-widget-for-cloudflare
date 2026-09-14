# In-app update, on-device checklist

No phone is attached at build time, so these steps are Dalton's. The file download,
the signer check on a real APK, the installer dialog and the notification are only
provable by hand.

## One-time host setup

1. Install the local Cloudflare CLI once:
   - `cd updates`
   - `npm install`
2. From the repo root, after `scripts/build.ps1` is green:
   - `scripts/publish.ps1 -Notes "In-app updates"`
   - wrangler must already be logged in; the script runs `wrangler deploy` inside `updates/`.
3. In a browser, open `https://usagewidget-updates.daltonjfowler.workers.dev/latest.json`
   and confirm it shows `"version_code": 3` and the same SHA-256 as `releases/SHA256SUMS.txt`.

## Install this build by hand one last time

4. Copy `releases/UsageWidget.apk` (versionCode 3) to the phone and install it, or
   `scripts/build.ps1 -Install` with USB debugging. Open the app once.

## Check for update, already current

5. Open the app, scroll to the Updates card. Confirm the line reads `You have 1.1 (3)`.
6. Tap `Check for update`. With the GrapheneOS Network permission on for this app,
   expect `You have the latest.`
7. Turn the Network permission off (App info, Permissions, Network). Tap
   `Check for update` and expect the no-connection line, not a crash.

## Update to a newer build

8. Bump `versionCode` to 4 in a later build, `scripts/build.ps1`, refresh
   `releases/SHA256SUMS.txt`, then `scripts/publish.ps1 -Notes "..."`. Confirm
   `/latest.json` shows `version_code` 4.
9. On the phone, Updates card, `Check for update`. Expect
   `1.x (4) is available, <size>, <notes>` and an `Update` button.
10. Tap `Update`. If GrapheneOS has not granted install-from-this-source, the app sends
    you to the unknown-sources screen with a one-line explanation; turn it on and tap
    `Update` again. Watch the download percentage, then confirm the Android install
    dialog appears.
11. Confirm the install, reopen the app, and check App info reads the new version.

## The daily notification path

12. The daily check job runs every 24 hours on any network. With a newer version
    published and Network on, wait for the job. Expect one Updates notification:
    `Usage Widget 1.x (4) is available. Tap to update.` Tapping opens the app at the
    Updates card.
13. Confirm the notification never appears twice for the same version code.

## Failure honesty (optional, needs a tampered host)

14. If you can serve a `latest.json` whose `sha256` does not match the APK, tap `Update`
    and confirm `The download did not match its checksum. Nothing was installed.`
15. If you can serve an APK signed with a different key, tap `Update` and confirm
    `The update was not signed with this app's key. Nothing was installed.`
