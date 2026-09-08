# NOTICE

This file lists upstream projects and third-party components that tapcreator is derived
from or bundles, together with their licenses. tapcreator itself is licensed under the
GNU General Public License v3.0 only (see `LICENSE`).

## Derived from / inspired by upstream projects

- **OpenMinis** (https://github.com/OpenMinis) — GNU GPL-3.0.
  - The Android PRoot + Alpine sandbox design in
    `app/src/main/java/com/tapcreator/app/backend/sandbox/PRootSandbox.kt` and the
    agent loop-detector in
    `app/src/main/java/com/tapcreator/app/backend/agent/ToolLoopDetector.kt`
    are derived from / adapted against OpenMinis, which is likewise GPL-3.0.
    Combining them under GPL-3.0 is compatible; both source and license are preserved here.

## Bundled native / binary components

- **proot** (https://github.com/termux/proot, and the OpenMinis fork) — **GPL-2.0**.
  Shipped as `app/src/main/jniLibs/arm64-v8a/libproot.so` and `libproot-loader.so`.
  Used as a separate executable via `exec()` (process isolation); its GPL-2.0 terms
  apply to the proot binaries. Source is available from the upstream project.
- **talloc** (Samba, https://talloc.samba.org) — **LGPL-3.0-or-later**.
  Shipped as `app/src/main/assets/libtalloc.so.2` (a dynamic dependency of proot).
  LGPL is satisfied by keeping the shared library under its own terms and providing relinking/
  replacement rights per LGPL.
- **Alpine Linux minirootfs** — an aggregate of its packages' licenses (musl **MIT**, BusyBox
  **GPL-2.0**, etc.). Provided at runtime via the app's asset rootfs; each package retains its
  own license. Not modified beyond configuration.

## Third-party libraries (Gradle dependencies)

The app additionally links standard open-source libraries (AndroidX, Kotlin coroutines,
OkHttp, Room, Hilt, Coil, Media3/ExoPlayer, kotlinx.serialization, Firebase, etc.), each
under its own license (Apache-2.0 / BSD / MIT and similar). Their license texts ship within
the app's `THIRD PARTY NOTICES` generated at build time.

## Attribution notice (GPL-3.0 §5)

When redistributing this work or modified versions, keep this NOTICE and the `LICENSE`
(GPL-3.0), preserve copyright/attribution notices in the source files, and make the
Corresponding Source available under GPL-3.0.
