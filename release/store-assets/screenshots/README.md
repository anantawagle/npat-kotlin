# NPAT Play Store screenshots

Upload these six **1080 × 1920 JPEGs** in this order:

1. `01-play.jpg` — One letter. Think fast. Timed answer sheet and Stop the bus.
2. `02-bluetooth.jpg` — No internet? Still game on. Offline Bluetooth table setup.
3. `03-room.jpg` — Your friends. Your table. Room code, players and readiness.
4. `04-categories.jpg` — Pick your perfect mix. Category selection.
5. `05-review.jpg` — Good answer? You decide. Peer approval and disapproval.
6. `06-results.jpg` — Play. Score. Rematch. Final scores and rematch action.

Open `index.html` for the full gallery, or `contact-sheet.jpg` for a single overview. `NPAT-store-screenshots.zip` contains the six upload files and this guide. Only the numbered JPEGs belong in Play Console's **Phone screenshots** field; the contact sheet is a review aid.

The app screens are actual Android 16 emulator captures of the native app's own views. Names, answers and scores are local sample fixtures. The surrounding headlines and frames are HTML/CSS artwork. No generated or redesigned UI replaces the app interface. The raw PNG captures are retained under `raw/`. Fixtures do not create rooms or write to Firebase and do not establish that real Bluetooth pairing has been tested.

## Recreate

1. Start an Android emulator and note its serial from `adb devices`.
2. Build the debug capture APK: `./gradlew --no-problems-report assembleDebug`.
3. Run `python scripts/capture-store-screenshots.py --serial emulator-5554`. For an alternate build output, pass `--apk app/build-play/outputs/apk/debug/app-debug.apk`. This helper intentionally accepts emulator serials only. It sets that emulator to 1080×1920 at 320 dpi, removes animations and notifications, and installs the debug APK.
4. Run `python scripts/build-store-screenshots.py`. It requires Python Playwright and its Chromium browser. Rendering is local, with no external fonts or assets.

Capture-only instrumentation lives in `app/src/debug` and is excluded from release builds. It disables the scheduled network poller before the Activity starts, then renders sample state through the existing screen methods. The signed Play bundle does not contain the screenshot harness.

The helpers do not change the previously generated release bundle. If the production UI changes, recapture the screens before rebuilding the store artwork.
