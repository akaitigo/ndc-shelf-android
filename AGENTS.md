# NDC Shelf development guide

## Goal

NDC Shelf is a privacy-first Android app for scanning ISBN barcodes, retrieving
book metadata, and managing a personal library by Nippon Decimal
Classification (NDC).

The current stable baseline is `v0.1.2` (`versionCode = 3`). It was verified on
a Pixel 7 running Android 16: the app launches and the ISBN scan → NDL metadata
lookup → local shelf registration flow works.

## Stack and structure

- Kotlin, Java 17, Gradle Kotlin DSL
- Jetpack Compose and Material 3
- Room for device-local persistence
- CameraX and ML Kit Barcode Scanning
- NDL Search SRU API for bibliography and NDC metadata
- Single `app` module; package boundaries are documented in
  `docs/ARCHITECTURE.md`

## Build and validation

Use the repository Gradle wrapper:

```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleDebug
```

The project requires JDK 17 and Android SDK 37. For scanner or camera changes,
also verify the debug APK on a physical Android device. Report clearly when a
check could not run because the SDK, emulator, or device was unavailable.

## Implementation rules

- Keep the library usable offline; network failures must not block viewing
  locally stored books.
- Keep barcode recognition and library data on-device. Only the ISBN needed
  for metadata lookup may be sent to NDL Search.
- Preserve the NDL SRU request contract: use `recordPacking=xml` and parse the
  returned DC-NDL XML. Add or update parser tests when this flow changes.
- Validate and normalize ISBN values before repository operations.
- Do not silently alter or delete existing Room data. Schema changes require a
  migration, an updated exported schema, and tests.
- Keep UI state in the ViewModel/repository flow; Composables should not access
  Room or remote services directly.
- New user-facing text belongs in Android string resources.
- New dependencies require a clear need and a compatible license.

## Change hygiene

- Keep changes focused and add tests for behavior changes and bug fixes.
- Update `README.md`, architecture docs, or the roadmap when behavior or scope
  changes.
- Do not commit `local.properties`, build outputs, APK/AAB files, keystores,
  signing properties, credentials, or personal library data.
- Do not change `applicationId`, signing configuration, `versionCode`, or
  `versionName` unless the task explicitly requires a release change.
- Use Japanese for user-facing app copy and project documentation. Code symbols
  may remain English.

