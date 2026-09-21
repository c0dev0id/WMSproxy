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
- On upstream **failure**, return an HTTP error, **never a blank or transparent tile**.
  DMD auto-caches layers, so a placeholder returned for a transient error is cached as
  real data and becomes a permanent hole in the map.

  The one deliberate exception is a zoom **outside a source's measured range**, which is
  a known absence rather than a failure: `BlankTile` serves a shipped transparent PNG
  without touching the network. WMS prescribes a blank map outside a layer's scale range,
  and DMD refuses a source whose tiles are not 200. Note the debt — the measured range
  cannot yet tell "no data here" from "too slow here", so blank asserts an emptiness it
  has not proved.

## Commands

Gradle runs in CI, not here — AGP is unavailable on this platform and the firewall
blocks it, so none of the tasks below can be executed locally (see *Build & CI*). They
are listed because they are what a change is judged by. The library checker after them is
plain Python and curl, and **does** run here.

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

```sh
# runs here: fetches every shipped service and re-applies the acceptance rule
python3 tools/check-library.py

# the same, writing measured layer counts back into the asset and stamping the check
# date. The counts the app shows are produced this way and are never typed by hand.
python3 tools/check-library.py --update
```

## Architecture

```
:core   pure Kotlin/JVM, no Android deps  ← all logic, unit-tested in CI
:app    Android: Compose UI, foreground service, TLS, upstream relay, DMD Hub sync
```

The HTTPS listener's certificate is **fetched at runtime and cached**, not bundled: a
copy baked into the APK expires between releases and takes the listener down with it.
`Tls` serves a publicly-issued certificate for **`local.codevoid.de`** — a real hostname
with an A record pointing to 127.0.0.1 — so a client validating TLS needs no manual
trust step. This name is the SAN on the issued certificate; renaming `Tls.HOST` without
re-issuing the certificate breaks HTTPS silently (clients reject a name mismatch).

Because nothing on the data path touches `android.graphics`, `:core` holds
essentially the whole program: capabilities parsing and generation, the rewriters, CRS
support checks, tile arithmetic and auth. **Keep it Android-free** — an Android
dependency there pushes its tests into `:app` and out of reach of a plain `test` run,
which matters because no device is ever available to check behaviour.

Dependencies in `:core` that cross the module boundary into `:app` must be declared
`api`, not `implementation`. `kotlinx-serialization-json` and `kotlinx-coroutines-core`
are already `api` because `TileLayer` (serializable) and `StateFlow` (from `RequestLog`)
are part of `:core`'s public surface. A new dep that only `:core` uses internally is
`implementation`; one whose types appear in `:core`'s public API is `api`.

### `:core` is Android-free, but it is not JVM-run

Android-free means *no Android dependencies*. It does not mean the JVM the tests run on
behaves like the runtime the code meets. This has bitten three times and it always looks
the same: green CI, broken device.

- `DocumentBuilderFactory.setXIncludeAware` throws unconditionally on Android and is
  accepted by the desktop JDK. Every hardening step is now wrapped in `runCatching`.
- A regex ending in an unescaped `}` compiles on the JVM, which reads a dangling brace as
  a literal. It crashed the app before it could draw a screen, because the pattern sat in
  a `companion object` initialiser and threw during class-load. **Escape both braces**,
  and keep anything that can reject input out of a class initialiser, where the failure
  surfaces with no UI to report it on.
- `String.format` without a `Locale` substitutes non-ASCII digits under some device
  locales. Everything formatting a URL or an identifier passes `Locale.ROOT`.

A unit test cannot catch any of these, because it *is* the JVM. Prefer the construction
that is unambiguous in both engines over the one a test proves works.

**`Locale.ROOT` is a rule, not just a cautionary tale.** Any new code formatting a URL,
identifier, or coordinate must pass `Locale.ROOT`. The incident above is why; the rule
applies forward to every call site, including ones the test suite never exercises.

### One façade: XYZ tiles

`/tileproxy/<source>[/<layer>]/{z}/{x}/{y}` is the only thing the client sees. Source
and layer are separate segments because one provider commonly hosts many layers sharing
connection settings and credentials. The layer segment is **absent** when the provider
has no layer concept, which is the normal case for a plain XYZ template — an invented
placeholder segment would be noise in a URL the user pastes by hand.

The `tileproxy` prefix namespaces tile routes so a user-chosen source name can never
collide with another endpoint.

`ProxyServer.templateFor` builds a source's proxy address on the TLS listener, and that
is the one that counts: DMD refuses cleartext to loopback, so it is what the DMD sync
pushes and what the root page lists. `plainTemplateFor` is the same route on the plain
listener, for a client that refuses the certificate instead. There is no scheme setting;
the Sources tab shows each source's *own* address and its Copy menu offers all three.

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

The WMTS trap is subtler: **a WebMercator tile matrix set is not necessarily indexed by
zoom.** A set may start partway down the pyramid and still number its levels from zero —
basemap.de's `DE_EPSG_3857_ADV` names its levels `00`–`13` while its scale denominators
say zooms 5–18, and the same document links a correct twenty-level set after it.
`parseWmts` therefore does not take the first WebMercator set: a set qualifies only if
level `N` really is zoom `N`, proven by `ScaleDenominator` against `WEB_MERCATOR_SCALE_0
/ 2^N`, and the deepest qualifying set wins. Do not "simplify" that back to
`firstOrNull`. And do not judge a set by `MatrixWidth == 2^N`: ArcGIS pads its matrices
to `2^z + 1` while numbering levels correctly, and that heuristic condemns four working
USGS services.

Some servers — BKG's TopPlusOpen being the real-world case — publish no
`OperationsMetadata` at all and express each layer's tile URL via a `ResourceURL`
element. `parseWmts` falls back to per-layer REST templates when no KVP endpoint is
found: `{Style}` and `{TileMatrixSet}` are substituted with the actual values, and
`{TileMatrix}`/`{TileRow}`/`{TileCol}` map to `{z}`/`{y}`/`{x}`. The padded-zoom
logic (`{z:02}`) applies identically to both KVP and REST paths.

WCS 2.x also names its root element `Capabilities`; `parse` tells it from WMTS by
namespace and refuses it by name, as it does a WFS. Dispatching on the local name alone
turns a coverage service into "no layers", which blames a server that answered well.

**ArcGIS REST is the third upstream kind.** A JSON body (`…/MapServer?f=json`) takes the
`parseArcGis` branch, which accepts a service only when its description proves the
WebMercator grid: fused cache, 256-pixel tiles, 3857, the origin at the corner, and
per-level resolutions halving from 156543 m/px. This branch is the only one that needs
the source URL, because the description names no address; the template is that URL minus
its query plus `/tile/{z}/{y}/{x}`. Do not derive the REST endpoint from a WMTS template
by path rewriting — it holds for the library's ArcGIS entries and fails silently for a
server whose WMTS matrix set is not its native cache. The checker mirrors the rule.

## The bundled service library

`app/src/main/assets/library.json` ships a curated list of map services, browsable on the
Library tab. An entry is a **service URL only** — never a layer. Picking one prefills the
import dialog and the usual fetch-and-choose flow takes over, so the server's own
capabilities always decide which layers exist. The library cannot assert that a layer
works; it only claims the service is worth asking.

`BundledLibrary` (in `:app/proxy/`) reads the asset the first time the Library tab is
shown and holds it for the process: not at startup, because most launches never open the
tab, and not per visit, because a tab leaves the composition on every switch and a
`remember` inside it re-read the file each time. Keep it that way.

Two rules govern what goes in, and both were learned by breaking them:

- **Passing the acceptance check is necessary, not sufficient.** Nearly every service in
  the public catalogues passes. The bar is editorial: does it help someone reading a
  screen while riding? Topographic maps, imagery, hillshade, roadworks, hazards, weather,
  and places worth stopping at. Not cadastral parcels, sheet indexes, historical
  orthophoto runs, or statistical boundaries.
- **A note says what you would see and what it is for.** Not which protocol carries it,
  not how the entry relates to another entry, and not a word a rider would have to look
  up. Where a provider offers the same data twice, only the tiled entry ships.

`usable`/`refused` counts on each entry are **measured, never typed** — written by
`tools/check-library.py --update`. They replaced hand-written hedges like "very large
layer list", which only appeared where someone remembered them and went stale silently.

**The checker is a second implementation of the acceptance rule, in Python, and it
drifts by default.** Any change to what `CapabilitiesParser` accepts — a format, a
matrix-set rule, a fallback — must be mirrored there in the same change, or the two
disagree about what ships: it passed basemap.de's offset grid without noticing, and
passed TopPlusOpen and EMODnet (both REST-only WMTS) while the app refused them — caught
and fixed together when REST WMTS support was added. A disagreement puts a "measured"
layer count beside a source that cannot be imported.

It also talks to every shipped server. Run it when something changed, not to see whether
anything did — the counts almost never move, and the hosts are other people's.

`docs/service-catalogue.md` is the survey behind the list: every WMS/WMTS endpoint found
in the public German and Baden-Württemberg catalogues, each fetched and run through the
acceptance rule, with what already ships marked. It exists so the editorial pass can be
made from a list instead of another crawl, and it records how each catalogue was reached
— none of them publish endpoint URLs where you would expect to find them.

## DMD Hub sync

The proxy signs in to DMD Hub (`app.advhub.net`) and pushes its own tile URLs into the
account's custom-layer list, so a source added here appears in DMD without anyone pasting
a URL. Pure parts in `:core` (`Dmd.kt`, `DmdLayers.kt`, `DmdSyncChoice`); everything needing a
device in `:app/dmd/`.

This is a **northbound push to a cloud API, not a northbound server** — it does not
conflict with *One façade*. The tile interface the client reads is still XYZ on loopback;
sync only tells DMD where to find it.

What a future change must not break:

- **The wire form mirrors DMD's own `CustomRasterEntry` exactly**, so a pushed layer is
  indistinguishable from one DMD made itself. `enabled` and `maxZoom` are written to match
  but DMD **ignores both on read** — a layer is turned off by *leaving it out of the
  pushed set*, never by flipping the flag.
- **The `User-Agent` is load-bearing.** The endpoint refuses a request without the one
  the DMD app sends. It is not ours to rename.
- **One recovery path.** A 401 means the token lapsed: sign in again with the stored
  password once, and if that fails sign out rather than hammer the endpoint. The refresh
  token is parsed and deliberately unused, so there are not two ways to recover.
- **`DmdHub` throws; callers catch one thing.** A `DmdAuthException` means there is no
  session (never signed in, refused, or signed out after failed renewal) — the form
  comes back. Anything else is the network or the server, and the session stands.
  `fetchLayers()` doubles as the live-session check; `pushLayers(body)` does the POST.
  The view model never inspects HTTP codes.
- **The DMD client validates TLS.** It must not borrow the upstream relay's
  certificate-blind client — this is a real host with real credentials on it.
- **Credentials never touch `sources.json`.** The password lives in `SecureStore`,
  AES-GCM under an `AndroidKeyStore` key, because the config file is something the user
  is invited to export. `DmdSyncChoice` (Sync / Direct per source) lives in
  `DmdSyncPrefs` (SharedPreferences, `:app`), keyed by source path, so editing a source
  does not disturb its sync choices.
- **Direct mode is gated by `directBlocker(): Rewrite?`.** `TileLayer.rewrites()` lists
  everything the proxy does for a source — flipped rows, `{s}`, quadkey, padded zoom,
  WMS bbox, Referer — and the blocker is the first of them DMD cannot do itself (WMS
  bbox it can). `rewrites()` lives in `Sources.kt` beside `urlFor`, because it describes
  the proxy; `directBlocker()` lives in `DmdLayers.kt`, because the exception is DMD's.
  The Sources row prints the whole list; the DMD tab captions the blocker in the same
  words.
  `TileLayer.sendsDirect(choice)` is the single decision
  function shared by the card switch and the push — do not duplicate that logic. WMS
  passes: DMD draws WebMercator only, so the bbox it substitutes is EPSG:3857, x-first
  under both WMS versions; version, CRS spelling and layer name are fixed in the stored
  template. `DmdSync.layersFor()` in `:core` is the one place sources become layers.
- **A padded zoom is measured at import.** `TileLayer.withPlainZoom()` produces the
  plain-zoom form; when the server answers it the plain form is stored, which then passes
  `directBlocker()`. Do not treat `{z:02}` in a stored template as merely cosmetic — if
  it is there, the server required it and the plain form was not tried (or failed).

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
  unauthenticated, so non-reachability from the network must be structural. Both the
  plain and TLS listeners bind `127.0.0.1`.
- **No third-party HTTP server.** `HttpServer` in `:core` is hand-rolled and must stay
  small enough to justify that; see *Why the server is hand-rolled*.
- **Secrets live in the Android Keystore**, outside the config JSON, so exported config
  is safe to share. `SecureStore` implements this for the DMD Hub password; anything
  secret added later belongs there and not in `sources.json`.
- **The service comes back after a reboot** when the user left it running, and stays
  stopped when they stopped it. `specialUse` is not on Android 14/15's list of foreground
  service types a `BOOT_COMPLETED` receiver may not start (`dataSync` is) — so changing
  the service type would silently break this as well as reintroducing the six-hour cap.
- **`minSdk 26` (Android 8.0)** is the natural floor: `startForegroundService()` and
  `NotificationChannel` both require API 26. `startForeground()` in `ProxyService` is
  version-gated — the 3-arg form with `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+,
  the deprecated 2-arg form on API 26–33.
- **Source and layer names are restricted to `[A-Za-z0-9._-]+`**, enforced by
  `SourceValidator`. They are also URL path segments — characters outside this set produce
  tile URLs that are structurally broken in ways tests won't catch.

**Standing exception, to be revisited:** certificates are not validated **on the upstream
relay only** (`InsecureTls` in `Upstream`) — a deliberate unblock. Every other client
validates normally, and must keep doing so: DMD Hub carries real credentials, and the
certificate fetch is what the HTTPS listener's own identity rests on.

`Upstream` holds **three OkHttp clients with different read timeouts**, chosen to match
what each wait type is worth: `client` (5 s, tile serving — a tile that arrives in 30 s
is useless to a rider), `probeClient` (20 s, zoom range measurement — probing can wait
longer), `capabilitiesClient` (60 s, document import — the user is watching a spinner).
Do not unify them; the timeouts are different for the same reason the operations are.

It is why **upstream source authentication** is still unimplemented: sending a map
server's credentials over an unvalidated connection would be worse than not supporting it
at all. DMD Hub sign-in is not a counter-example — it has its own validating client.

### Why the server is hand-rolled

Ktor's CIO engine has no server-side TLS — its implementation is client-only, and CIO
server HTTPS is an unmerged prototype. Netty and Jetty do support it but are heavy and
awkward on Android. Since a client may refuse cleartext to loopback under its own
network security policy, TLS is not optional, so the engine had to go.

Hand rolling is only defensible because the surface is tiny: GET only, two routes, one
client, on loopback. No chunked transfer, request bodies, pipelining or keep-alive. If
that stops being true, revisit the choice rather than growing the server.

The compensating benefit is that `HttpServer` is plain JVM and lives in `:core`, so it
is driven against a real socket in unit tests that run in CI. Under Ktor the same
coverage needed a device.

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
- **`Build`** (`.github/workflows/build.yml`) — only on push to `main`. Four jobs: lint,
  test, signed `assembleRelease`, and `draft-release`, which republishes the `dev`
  pre-release. The `SIGNING_*` repo secrets are set; the Gradle signing config degrades
  gracefully without them.

  **`draft-release` needs only `build`.** Lint and test do not gate it, so a red test
  still publishes an APK to the `dev` tag — observed, not theoretical. Useful when the
  failure is confined to test sources, and a hole in the net otherwise: a green `dev`
  release is not evidence that `main` is green.

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
- `FlowRow` is the only experimental Compose API in use, opted in once inside
  `WrappingRow`, the card every wrapping list row is built from. Reach for another
  `@OptIn` only when the stable alternative is genuinely worse, not because one already
  exists.
- The tab bar is **Sources · Library · DMD · Settings**. Settings holds the request log
  and the update check; there is no standalone Log tab. `DmdStatus` has three states:
  `Idle`, `Busy`, `Error` — `Connected`/`SigningIn`/`Checking` no longer exist.
- Shared composables live in the `// ---- shared` section at the end of `MainActivity.kt`:
  `WrappingRow`, `LabelledSwitch`, `ErrorText`, and `Field` beside the Sources tab. Use
  them before writing a list row, a labelled switch, an error line or a text field; the
  Library rows and the DMD card's switch pair are the deliberate exceptions, each with
  its reason in a comment.
- A line the app writes about itself goes into the request log through
  `RequestLog.note(origin, text)`, never by hand-building a `LoggedRequest` with
  placeholder fields. `TileLayer.displayName` is the title-or-path fallback; do not spell
  it out again.
