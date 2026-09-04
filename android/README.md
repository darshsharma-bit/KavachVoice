# Layer 1 — VoiceArmor (Android)
# Layer 2 — CallGuard (Android)
# Kotlin, min SDK API 29 (Android 10+)
# Accessibility Service + TFLite + AI4Bharat IndicASR

## Setup
1. Open `android/` in Android Studio
2. Sync Gradle
3. Run on device (API 29+)

## Key files (to be created)
- `VoiceArmorService.kt` — Accessibility Service, audio hook, UAP application (Layer 1)
- `CallGuardService.kt` — Keyword spotter, UPI package detection, overlay UI (Layer 2)
- `UpiPackages.kt` — Allowlist of UPI app package names
- `UapProfile.kt` — UAP tensor loader from assets
