# NDC Shelf development guide

## Goal

NDC Shelf is a privacy-first Android app for scanning ISBN barcodes, retrieving
book metadata, and managing a personal library by Nippon Decimal
Classification (NDC).

The current release candidate is `v0.6.0` (`versionCode = 8`) — the first build
distributed as a signed APK through GitHub Releases. Earlier tags exist but no
APK was ever published for them.

Read `docs/HANDOFF.md` first. It carries the live state: what is in flight, what
was measured on a physical device, and what the next task is.

## Stack and structure

- Kotlin, Java 17, Gradle Kotlin DSL
- Jetpack Compose and Material 3
- Room for device-local persistence
- CameraX and ML Kit Barcode Scanning
- NDL Search SRU API for bibliography and NDC metadata
- Single `app` module; package boundaries are documented in
  `docs/ARCHITECTURE.md`

## Build and validation

Use the repository Gradle wrapper. **The app has two product flavors**
(`standard` and `ai`), so task names carry the flavor:

```bash
./gradlew verifyV06ReleaseConfiguration verifyBackupPolicy verifyLicenseReport \
  :app:cyclonedxDirectBom verifyRoborazziStandardDebug lintStandardDebug \
  assembleStandardDebug assembleAiDebug lintAiDebug
```

That command mirrors the CI `verify` job. Also run the shell gates:

```bash
.github/scripts/verify-translations.sh
git diff --exit-code -- app/schemas
```

The project requires **JDK 17** (`$HOME/.local/share/mise/installs/java/temurin-17.0.20+8`
on the maintainer's machine — GraalVM 21 fails with a `JdkImageTransform` error)
and Android SDK 37. For scanner or camera changes, also verify the debug APK on
a physical Android device. Report clearly when a check could not run because the
SDK, emulator, or device was unavailable.

`standard` is the distributed flavor. `ai` bundles an on-device LLM runtime; it
is built and size-checked in CI but **not attached to releases** — see
`docs/ON_DEVICE_LLM_FINDINGS.md` for the measurements behind that decision.

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
- New dependencies require a clear need and a compatible license. Any dependency
  change must regenerate `gradle/verification-metadata.xml`
  (`./gradlew --write-verification-metadata sha256 <tasks>`), otherwise CI fails.
- CI gates must fail closed. Two gates silently passed with zero inputs in the
  past (`verify-action-pins.sh` and the OSV scan); when adding a check, make it
  fail when it has nothing to inspect.

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

