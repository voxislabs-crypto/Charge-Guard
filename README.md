# Charge-Guard

A phone charging alarm if disturbed to prevent theft.

## Android CLI Setup (this workspace)

This repository is configured for Android builds in a dev container.

- Android SDK location: `.android-sdk/`
- Gradle wrapper: `./gradlew` (Gradle 8.7)
- JDK for builds: OpenJDK 21 (`/usr/lib/jvm/java-21-openjdk-amd64`)

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_ROOT="$PWD/.android-sdk"
./gradlew assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Signed Release APK

1. Create a release keystore (one-time):

```bash
keytool -genkeypair \
	-v \
	-keystore /workspaces/Charge-Guard/chargeguard-release.jks \
	-alias chargeguard \
	-keyalg RSA \
	-keysize 2048 \
	-validity 10000
```

2. Copy the signing template and fill your values:

```bash
cp keystore.properties.example keystore.properties
```

3. Build release APK:

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_ROOT="$PWD/.android-sdk"
./gradlew assembleRelease
```

4. Release APK output:

- Signed (when `keystore.properties` or env vars are set):
	`app/build/outputs/apk/release/app-release.apk`
- Unsigned fallback (if signing values are missing):
	`app/build/outputs/apk/release/app-release-unsigned.apk`

## Install On A Device (USB)

```bash
export ANDROID_SDK_ROOT="$PWD/.android-sdk"
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Notes

- Emulator support in containers is often limited; building in-container + deploying to a physical device is usually the smoothest workflow.

## Anti-Theft Guard Flow

1. Open the app once. Monitoring starts automatically.
2. Plug the phone into charge.
3. When the phone locks, guard waits until movement stops, then auto-arms after 3 seconds.
4. Optional: set grace countdown with slider (5-12 seconds).
5. Optional: adjust motion sensitivity with the slider, or tap Calibrate While Still to auto-tune threshold.
6. Choose arm mode:
	- Auto-arm on lock while charging (default)
	- Manual arming with Arm Guard button
7. Optional: enable Disarm PIN and save a 4+ digit code.
8. If someone moves or unplugs it while armed, the selected grace window starts.
9. If the phone remains locked when the countdown ends, a loud alarm tone and spoken warning loop starts.
10. Unlocking the phone stops the alarm and disarms until next lock event, or use Disarm (PIN required if enabled).

## On-Screen Mode Indicator

- Mode: Monitoring
- Mode: Pending Auto-Arm (set-down delay)
- Mode: Armed
- Mode: Alarm Active

## Important Disclaimer

- Protection only works while the phone is plugged in and charging.
- Set-down behavior: after lock, motion must settle, then a 3-second delay runs before arming.

## Monetization Skeleton (Implemented)

The app now includes:

- Google Play Billing one-time unlock (`premium_unlock`)
- AdMob banner integration
- UMP consent flow
- Premium state that hides ads when unlocked

Before production release, update these values:

1. Create an in-app product in Play Console with product id `premium_unlock`, or change `PREMIUM_PRODUCT_ID` in `app/build.gradle.kts`.
2. Replace test AdMob IDs in `app/src/main/res/values/strings.xml`:
	- `admob_app_id`
	- `admob_banner_unit_id`
3. Test purchases with Play internal testing track and test accounts.
4. Keep Play Billing for digital unlocks to stay policy-compliant.

### Current APK Locations

- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- Release APK: `app/build/outputs/apk/release/app-release.apk`

## Persistence

- Monitoring service auto-restarts on boot.
- Auto-arm sequence runs on each lock event while charging.
