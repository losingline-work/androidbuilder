# Offline Maven bundle

The on-device embedded Gradle build resolves dependencies over the network (Aliyun mirrors → Google Maven /
Maven Central → JitPack). When the device has no/unstable network — or for libraries with no domestic mirror
(JitPack, e.g. MPAndroidChart) — that download fails and a milestone that needs the lib can't build.

This bundle pre-seeds the common libraries into a **local offline Maven cache** so those builds resolve with
**no network**.

## Produce the bundle (once, on a networked machine)

Requires a JDK and `gradle` on PATH.

```bash
./tools/build-offline-maven.sh
# → app/src/main/assets/offline-maven.zip   (git-ignored; large; never committed)
```

The coordinate list lives in two synced places:
- `tools/build-offline-maven.sh` (`COORDS=(...)`)
- `app/src/main/java/com/androidbuilder/agent/DependencyCatalog.java` → `offlineBundleCoordinates()`

It is the curated capability libraries (charts, image loading, http, json, qrcode, datetime, …) plus the base
toolchain/UI (AGP 8.7.3, base AndroidX, Material, kotlin-stdlib 1.8.22). To add a lib, update BOTH.

## Install it on the device

Two paths:
1. **Manual (works today):** copy `offline-maven.zip` to the device, then in the app go to
   **Settings → Import offline-maven.zip**. It unzips into the app's `offline-maven` cache.
2. **As an app asset:** ship `app/src/main/assets/offline-maven.zip` in the APK (it is git-ignored, so build
   it locally before assembling the release).

Then set **Settings → Dependency mode → Local cache**. From then on:
- the build adds the offline-maven directory as the first Maven repository (no network for cached libs);
- the planner and the milestone generator are told these libraries ARE available and may use them
  (e.g. real `MPAndroidChart` charts instead of hand-drawn Canvas).

## Caveats

- The **Android Gradle Plugin** has a large transitive graph and a plugin-marker pom. If `plugins { id
  'com.android.application' version '8.7.3' }` still can't resolve fully offline from the bundle, run ONE
  online build first — Gradle warms the plugin into the runtime's own cache (`runtime/home/.gradle`), after
  which subsequent builds are network-free regardless. The bundle's primary value is the **app** libraries.
- The build does **not** pass `--offline`, so anything missing from the bundle falls through to the mirrors
  (graceful degradation) and warms the cache for next time.
