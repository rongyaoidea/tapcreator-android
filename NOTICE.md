# NOTICE

This file lists upstream projects and third-party components that tapcreator is derived
from or bundles, together with their licenses. tapcreator itself is licensed under the
GNU General Public License v3.0 only (see `LICENSE`).

## Derived from / inspired by upstream projects

- **OpenMinis** (https://github.com/OpenMinis) — GNU GPL-3.0.
  - The agent loop-detector in
    `app/src/main/java/com/tapcreator/app/backend/agent/ToolLoopDetector.kt`
    is derived from / adapted against OpenMinis, which is likewise GPL-3.0.
    Combining it under GPL-3.0 is compatible; both source and license are preserved here.
  - Earlier versions also adapted OpenMinis's Android PRoot + Alpine sandbox design. The
    sandbox has been removed from this codebase (its former binaries and Linux rootfs are no
    longer bundled or distributed); attribution is retained for the historical derivation.

## Third-party libraries (Gradle dependencies)

The app additionally links standard open-source libraries (AndroidX, Kotlin coroutines,
OkHttp, Room, Hilt, Coil, Media3/ExoPlayer, kotlinx.serialization, Firebase, etc.), each
under its own license (Apache-2.0 / BSD / MIT and similar). Their license texts ship within
the app's `THIRD PARTY NOTICES` generated at build time.

## Attribution notice (GPL-3.0 §5)

When redistributing this work or modified versions, keep this NOTICE and the `LICENSE`
(GPL-3.0), preserve copyright/attribution notices in the source files, and make the
Corresponding Source available under GPL-3.0.
