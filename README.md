# NPAT for Android

Native Kotlin Android app ported from `su-gr/statsupdate/components/NPATGame.tsx`, `NPATBoard.tsx`, and `lib/npat.ts`. Android 8.0 (API 26) or newer. Open this directory in Android Studio and run the `app` configuration, or use `./gradlew assembleDebug`.

## Offline Bluetooth play

1. Install the app on each Android phone. Choose **Bluetooth · offline** and enter a name.
2. On one phone choose **Host a table**, select categories, and tap **Host Bluetooth table**. Allow Bluetooth permissions, enable Bluetooth, and accept the discoverability prompt.
3. Other players choose **Join nearby**, select the host phone, and accept any Android pairing prompts on both phones. Previously paired phones also appear. If discovery does not find the host, use **Make phone discoverable again** on the host, or pair in Android Bluetooth settings and scan again.
4. Ready up and play normally. Timers, draft collection, voting, scores and rematches use the same game rules. Wi-Fi, mobile data, Firebase, and internet access are not used in this mode.

This is a secure Bluetooth Classic/RFCOMM star network, with up to seven guests plus the host (actual connection capacity depends on the phones). The host stores the room in memory and serializes changes using revision checks. Guests sync their countdown with the host clock. The app keeps the screen awake at an active offline table; keep the host app open and all phones within Bluetooth range.

Temporary guest disconnections preserve the seat and retry the connection automatically. A player can also return to **Join nearby** and select the same host using the same installation/player identity. The host can remove a player who cannot reconnect to finish reviews. Rotation preserves the Bluetooth session. If the host leaves, closes the app, loses its process, or remains unavailable, the table ends: hosting does not transfer to another phone. Bluetooth rooms are separate from online rooms, and web players cannot join them.

Android 12+ requests Nearby devices permissions. Android 11 and earlier require Location permission for discovery and may require Location to be enabled. Bluetooth remains optional for online play. See Android's [Bluetooth permission requirements](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions).

## Features

- Saved player identity/name, room rejoin after restart, and locally saved answer drafts.
- Public room discovery (last 50 rooms, ten-minute visibility), private rooms, five-character codes, copy/share invitations, and capacity checks.
- All 14 original categories; choose one to six. Two to eight players, 60-second rounds, three rounds per game.
- Ready checks, host start, deterministic host transfer on departure, random letters, countdown, draft sync, and stop-the-bus validation.
- Three-second final-answer collection, with last synced drafts retained for offline players.
- Peer approval/disapproval, editable decisions, review progress, own-answer preview, and no review timeout.
- Automatic zero for empty, duplicate or wrong-letter answers; ten points for strict-majority approval. Ties score zero.
- Scoreboard, tied winners, final-round scores, rematches, explicit leave confirmation, reconnect status, and house rules.

## Firebase compatibility

Uses the **same existing database**, `npat-5d26e-default-rtdb.asia-southeast1.firebasedatabase.app`, under `games/{CODE}`. The JSON shape, round tokens, player ordering and scoring rules match the website. No Firebase project, database rules, authentication or website files were changed.

The native app uses Firebase's HTTPS REST API and ETag/If-Match conditional writes, with retries on HTTP 412. This is Firebase's [documented transaction equivalent](https://firebase.google.com/docs/database/rest/save-data#section-conditional-requests). It polls while foregrounded, waiting 750 ms between sync operations, rather than using the browser SDK's persistent listener. Updates therefore have modest latency and require an internet connection. HTTP Date headers provide an approximate server clock offset (second precision). The original browser client also accesses the database without authentication; this app follows that existing access model. No web API key or service-account credentials are needed.

The app does not remove disconnected players automatically. Leaving removes a player from the roster and review duties; leaving the last seat deletes that room. Backgrounding the app pauses polling without leaving. An `npat://join?code=ABCDE` link prefills the room code.

The existing database has no `createdAt` index on `/games`. When Firebase rejects the indexed REST query, the app reads rooms and performs the same sort/last-50 filtering locally. Lobby refreshes run every five seconds. For a large database, adding that index would reduce bandwidth; no rule change is required to use the app now.

## Validation

`./gradlew --no-problems-report testDebugUnitTest assembleDebug lintDebug`

On this machine Gradle's optional problems-report generation timed out after tasks completed; `--no-problems-report` avoids that reporting issue.

Rule tests cover final answer freezing, offline draft fallback, stale round rejection, duplicate/wrong-letter zero scores, disapproval completion, tied votes, majority scoring, rematch reset, host transfer and review duties after departure.

Local-state tests cover conflicting peer edits, isolated snapshots, simultaneous updates with retries, deletion, and host timestamps. Physical Bluetooth pairing and gameplay require real phones; JVM tests do not verify the radio connection.

Bluetooth device check: disable Wi-Fi/mobile data on two or more real phones, host/join and complete all three rounds, stop the bus, test a timeout, vote and rematch. Rotate both phones, move a guest out of range and back, remove an absent reviewer, deny permissions, cancel pairing, retry discovery, then end the table from the host. Confirm that returning to Online still loads Firebase rooms.

For a cross-client check, create a private room on Android, join from the website, ready both players, play three rounds, review every eligible answer, check scores, and start a rematch. Also check timeout collection, rotating the phone, background/rejoin, and explicit departure during review. End-to-end device checks require a running emulator or attached phone and database access.

The app now targets API 36, version 1.2 (versionCode 3), with Android 16 back-navigation support and an in-app privacy policy. The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Release signing, listing copy, and remaining Play Console steps are documented in [release/README.md](release/README.md).

The standalone Vercel-ready privacy page is [privacy-site/index.html](privacy-site/index.html). It has no build step; set Vercel's Root Directory to `privacy-site`. Support contact: `cutlerylabs@gmail.com`. The text is generated from the same policy bundled in the Android app. See [privacy-site/README.md](privacy-site/README.md).
