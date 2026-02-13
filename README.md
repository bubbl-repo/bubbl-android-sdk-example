# Bubbl Android SDK Example App

Public barebones host app for the Bubbl Android SDK.

This example is aligned with:
- `guides/android-sdk/quickstart.md`
- `guides/android-sdk/method-reference.md`
- `guides/android-sdk/usage-examples.md`
from `bubbl-docs-redocly`.

## What this app demonstrates

- SDK initialization in `Application`.
- Runtime permissions via `PermissionManager`.
- Location tracking and geofence refresh flows.
- Campaign/cache APIs.
- Segment and correlation ID APIs.
- Configuration/privacy APIs.
- Event/CTA/survey APIs.
- FCM token sync and utility methods.

`MainActivity.kt` includes explicit method references for every API in `guides/android-sdk/method-reference.md`.

## Setup

1. Update `app/google-services.json` placeholders.
2. Add Maps key in `app/src/main/AndroidManifest.xml` (replace `REPLACE_WITH_GOOGLE_MAPS_API_KEY`).
3. If using GitHub Packages for private Android artifacts, set:
   - `BUBBL_MAVEN_USER`
   - `BUBBL_MAVEN_TOKEN`

## Test

```bash
./gradlew test
```

