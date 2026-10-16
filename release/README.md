# Google Play release preparation

The app targets Android 16 / API 36. Version 1.2 uses versionCode 3. Every later upload must increment versionCode. The application ID for the first Play upload is `com.cutlerylabs.npat`; it identifies the app permanently. The Kotlin source namespace remains `com.statsupdate.npat`, which is separate from the published application ID.

## Signing

Release signing uses `.signing/upload.properties`, or environment variables `NPAT_UPLOAD_STORE_FILE`, `NPAT_UPLOAD_STORE_PASSWORD`, `NPAT_UPLOAD_KEY_ALIAS`, `NPAT_UPLOAD_KEY_PASSWORD`. See `upload.properties.example` for the file format. The real properties and keystore are ignored by Git. Release packaging fails with a clear message if signing is not configured. Debug builds do not need signing credentials.

If this workspace contains a generated `.signing/npat-upload.jks`, it is the upload key for this new app, with its passwords in `.signing/upload.properties`. Back up that entire folder securely outside the repository before uploading. Do not put it on GitHub, Vercel, or in the store listing. Keep the same upload key for updates. For an already-published app, use its existing upload key instead.

Build: `./gradlew --no-problems-report bundleRelease assembleDebug testDebugUnitTest lintRelease`

The signed upload bundle is `app/build/outputs/bundle/release/app-release.aab`. Choose Play App Signing in Play Console; this key signs uploads, while Play manages the signing key for distributed installs. Keep the debug APK for local testing.

## Listing draft

Name: **NPAT: Name Place Animal Thing**

Public support email: **cutlerylabs@gmail.com**

Short description: **Race the letter, review answers, and play with friends online or over Bluetooth.**

Full description:

Play the quick-thinking Name, Place, Animal, Thing word game with friends. Get one letter, fill every category before time runs out, and stop the bus when your sheet is complete.

Create an online room and invite friends with a room code, or play nearby over Bluetooth without internet. Choose up to six categories, ready up, and compete over three rounds. Review opponents' answers and award points for unique, correctly formed answers approved by a majority of the other players.

Features include timed rounds, public and private online rooms, nearby Bluetooth tables, peer voting, a live scoreboard, and rematches. Bluetooth tables require the host to remain nearby with the app open; supported simultaneous connections depend on your phones.

## Work still required in Play Console

- Provide your developer identity and verification details; register/pay through your own account. Use `cutlerylabs@gmail.com` as the public support email.
- Publish `privacy-site` on Vercel and enter its public URL.
- Upload the six numbered JPEGs in `release/store-assets/screenshots`, captured from the real native app on an Android emulator with sample data. See that folder's README for the upload order and capture details. Ready-made listing artwork is in `release/store-assets/icon-512.png` and `release/store-assets/feature-graphic-1024x500.png`, rendered from the app's existing visual style. Recreate them with `powershell -File scripts/build-store-assets.ps1` if needed.
- Complete the content-rating and target-audience questionnaires truthfully. The selectable "Cause of death" category and user-generated text should be considered; no rating has been assumed.
- Declare ads as absent for this build. There is no account-creation flow or login requirement. Explain room creation/joining and Bluetooth requirements in reviewer app-access instructions.
- Complete Data safety based on actual operation, including display names, random player identifiers, submitted game content and gameplay activity transmitted to Firebase, peer visibility, and service-provider handling. Do not claim that nothing is collected. Review the policy and Firebase configuration to determine collection/sharing categories and retention accurately.
- Run the production-access testing required for your account. Test online Android/web games, Bluetooth pairing and gameplay on multiple real phones, background/reconnect, permission denial, Android 16 back gestures, and keyboard insets. Unit tests do not establish real-device compatibility.
- Submit the signed AAB to a testing track first; review pre-launch results before production submission.

No Play Console submission or public website deployment has been performed.
