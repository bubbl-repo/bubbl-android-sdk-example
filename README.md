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
3. Android dependency resolution uses Bubbl Maven (`https://maven.bubbl.tech/repository/releases/`) with no username/password required.

For local SDK override, Gradle checks `BUBBL_ANDROID_SDK_LOCAL_MAVEN`
and defaults to `../../bubbl-current/sdk/bubbl-android-sdk-standalone/sdk/build/localMaven`.
That path works after publishing the SDK locally:

```bash
cd /path/to/bubbl-android-sdk-standalone
./gradlew :sdk:publishReleasePublicationToLocalMavenRepository
```

## Test

```bash
./gradlew test
```
