# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in
this repository.

## Project scope

WMSproxy is an Android foreground service that runs an embedded HTTP server on
`127.0.0.1:PORT` and re-exposes configured upstream map services as plain,
unauthenticated **XYZ tile templates** that DMD2 (`com.thorkracing.dmd2launcher`) can
consume.

DMD2 substitutes `{z}`/`{x}`/`{y}` into a tile URL, and separately supports a WMS
GetMap mode. Either way it handles only simple, unauthenticated sources — which locks
out authenticated services, WMTS with non-integer TileMatrix identifiers, flipped-Y
(TMS) schemes, `{s}` subdomain rotation and quadkeys. WMSproxy bridges exactly that gap.

## The one rule that shapes everything

**It is a request rewriter, not an image processor.**

It translates protocol, version, parameter naming, axis order, tile indexing and
authentication, then streams the upstream response body through byte-for-byte. It
never decodes, resamples, re-encodes, mosaics or composites an image. There is no
`Bitmap` on the data path, and there is no coordinate transformation anywhere.

If a request cannot be answered by rewriting alone, **refuse it** rather than
fabricate pixels. Two consequences worth stating explicitly:

- A CRS the upstream does not serve is an error, not a conversion. Rewriting a bbox
  between projections returns an image rendered in one projection but labelled as
  another — an error ranging from millimetres to hundreds of metres depending on the
  CRS pair, zoom and position, with no way for the rider to tell which they got.
- On upstream failure, return an HTTP error or a WMS `ServiceExceptionReport`, **never
  a blank or transparent tile**. DMD2 auto-caches layers, so a placeholder returned
  for a transient error is cached as real data and becomes a permanent hole in the map.

## Commands

These run in CI, not here — AGP is unavailable on this platform and the firewall
blocks it, so none can be executed locally (see *Build & CI*). They are listed because
they are what a change is judged by:

```sh
# what Check runs on a branch push, in one invocation
./gradlew --continue lintDebug test assembleDebug assembleRelease

./gradlew lintDebug        # Android lint + AAPT resource linking, debug variant
./gradlew test             # JUnit4; :core is plain JVM, so use `test`, not testDebugUnitTest
./gradlew assembleDebug    # unminified, debug-signed
./gradlew assembleRelease  # the only task that runs R8 — unsigned without the SIGNING_* env

# what Build adds on main
./gradlew lint
./gradlew assembleRelease -PappVersionName=dev-<sha> -PappVersionCode=<run>

# one test class, or one test
./gradlew :core:test --tests "de.codevoid.wmsproxy.core.TileMathTest"
./gradlew :core:test --tests "de.codevoid.wmsproxy.core.TileMathTest.quadKey*"
```

## Architecture

```
:core   pure Kotlin/JVM, no Android deps  ← all logic, unit-tested in CI
:app    Android: Compose UI, foreground service, Ktor wiring
```

Because nothing on the data path touches `android.graphics`, `:core` holds
essentially the whole program: capabilities parsing and generation, the rewriters, CRS
support checks, tile arithmetic and auth. **Keep it Android-free** — an Android
dependency there pushes its tests into `:app` and out of reach of a plain `test` run,
which matters because no device is ever available to check behaviour.

### One façade: XYZ tiles

`/t/<source>/<layer>/{z}/{x}/{y}` is the only thing the client sees. Source and layer
are separate segments because one provider commonly hosts many layers sharing
connection settings and credentials.

**Do not add a northbound WMS service.** It was considered and dropped: a tile request
carries an integer `z/x/y`, so there is no extent to interpret, no axis order to get
wrong, and no arbitrary bbox that might not correspond to a tile. Accepting GetMap
reintroduces all three for no gain, because the client can express a tile template
directly.

WMS and WMTS remain **upstream** protocols. When an upstream is a WMS server, a tile
becomes a GetMap of exactly `TileMath.DEFAULT_TILE_SIZE` pixels square over that tile's
extent — and that is where the version traps live: `SRS` in 1.1.1 versus `CRS` in
1.3.0, and 1.3.0 ordering geographic coordinates latitude-first. Getting those wrong
produces a map that renders perfectly in the wrong place.

## Hard constraints

- **No image processing.** No decode, resample, warp, mosaic, composite or re-encode.
- **No reprojection.** No proj4j or any coordinate library. The only geometry is
  WebMercator tile arithmetic in `TileMath`.
- **No northbound WMS.** The client interface is XYZ tiles only; see *One façade*.
- **No caching.** DMD2 already caches; a second cache duplicates on-device storage and
  makes staleness ambiguous.
- **No database or schema migrations** while the version is below 1.0. Config is
  kotlinx-serialization JSON in the app files dir.
- **Bind to loopback only.** The proxy holds credentials and serves them
  unauthenticated, so non-reachability from the network must be structural.
- Secrets live in the Android Keystore, outside the config JSON, so exported config is
  safe to share.

## Build & CI

**Do not attempt to build locally.** Android Studio / AGP are unavailable on this
platform and the firewall blocks AGP — do not work around this. All builds run in CI.
Correctness depends on careful API use and reading before writing.

Two workflows, ported from `c0dev0id/motoLauncher`:

- **`Check`** (`.github/workflows/check.yml`) — every push to a branch other than
  `main`, plus `workflow_dispatch`. One Gradle invocation,
  `./gradlew --continue lintDebug test assembleDebug assembleRelease`, because the tasks
  share `compileDebugKotlin` and one daemon start-up, and `--continue` reports lint, test
  and compile failures together. `assembleRelease` is included because it is the only
  task that runs R8: a missing-class or shrinking failure cannot appear in a debug build
  and would otherwise only surface on `main`, blocking the release. Uploads the debug APK
  (`.debug` applicationId, so it installs beside a release build) and reports on failure.
  Deliberately no `pull_request` trigger: the push run's result already shows on a PR.
- **`Build`** (`.github/workflows/build.yml`) — only on push to `main`. Lint, test,
  signed `assembleRelease`, then republishes the `dev` pre-release. The `SIGNING_*`
  repo secrets are set; the Gradle signing config degrades gracefully without them.

Use the `ci-verifier` agent to read run results — it keeps log volume out of the main
conversation and hands back a punch list.

## Conventions

- Commits are authored `c0dev0id <sh+git@codevoid.de>` with **no trailers** and no
  attribution lines.
- Each change that affects behaviour or a load-bearing decision updates `CHANGELOG.md`
  (Keep a Changelog, under `[Unreleased]`) and `.github/development-journal.md` in the
  same task. The changelog is written for the user, not the developer — no class names
  or CI detail; that material belongs in the journal.
- Read the journal's *Key Decisions* before proposing structural changes.
- Commit every logical step separately rather than batching unrelated changes.
