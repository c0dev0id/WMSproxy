# Development Journal

## Software Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.0.21 |
| Build | Gradle 8.9, AGP 8.7.3, Java 17 (temurin) |
| SDK levels | `compileSdk 35`, `targetSdk 34`, `minSdk 34` |
| Modules | `:core` (pure JVM), `:app` (Android) |
| HTTP server | hand-rolled, `:core` |
| HTTP client | OkHttp 4.12.0 |
| Config | kotlinx-serialization JSON |
| UI | Compose / Material 3 |
| Tests | JUnit4 |
| CI | GitHub Actions — `Check` on branches, `Build` on `main` |

Deliberately absent: proj4j or any coordinate library, any image/bitmap library, any
caching layer, any database. See *Key Decisions*.

## Core Features

Planned, in milestone order. Nothing below is implemented yet beyond the scaffolding.

0. In-app update check against the rolling `dev` pre-release. **Done.**
1. Foreground service hosting an HTTP server on loopback, with a request log. **Done.**
2. XYZ upstreams on `/tileproxy/<source>[/<layer>]/{z}/{x}/{y}` — template expansion, TMS y-flip,
   quadkey, `{s}` subdomains. **Done** for a hardcoded source; configuration pending.
3. Authentication — HTTP Basic and API key (query parameter or header), secrets in the
   Android Keystore.
4. WMS upstreams on `/wms` — generated GetCapabilities, `GetMap` rewrite, version and
   axis-order translation.
5. Alignment gate — tile-backed layers exposed through `/wms` when a `GetMap` bbox is
   exactly a tile.
6. WMTS upstreams — capabilities parsing, TileMatrix identifiers, KVP and RESTful
   templates.
7. Config UI — capabilities import wizard, editors, JSON import/export.

## Key Decisions

### Request rewriting only — no image processing

The proxy translates requests and streams the upstream body through byte-for-byte. It
never decodes or re-encodes an image.

This was not the first design. An earlier draft had a full pipeline: mosaic upstream
tiles, warp between projections, crop, rescale, composite and re-encode. It was
rejected because the requirement is protocol normalization, not rendering — and the
pipeline brought a bitmap dependency, OOM risk, generation loss on every hop, and the
bulk of the latency.

The consequence is a hard rule: **if a request cannot be answered by rewriting alone,
refuse it.** That rule is what keeps the codebase small.

### No caching

DMD2 already caches rendered tiles. A second cache on the same device duplicates
storage and makes staleness ambiguous — two caches can disagree and there is no way to
tell which one served a given tile.

An in-memory LRU was proposed for pan/zoom latency and rejected for that reason. It is
also less necessary than it appears: because DMD2 caches the result, the proxy is hit
roughly once per DMD2 tile, so re-fetch amplification is bounded by request alignment,
not by cache absence. A `TileCache` seam with a no-op implementation is acceptable if
it ever needs revisiting, but nothing caches today.

### No reprojection — a CRS mismatch is an error

Each layer advertises exactly the CRS list its upstream supports. A request in any
other CRS is refused, and the import wizard rejects a source offering no usable CRS.

Rewriting a bbox into a different CRS was considered and measured first. It returns an
image rendered in one projection but labelled as another, and the error is wildly
non-uniform:

- *4326-only upstream, 3857 requested* — Mercator's nonlinear latitude stretch,
  `≈ (1/8)·R·tan(φ)·Δφ²`. At 50°N: 3.6 mm at z16, 0.9 m at z12, 15 m at z10, 237 m at z8.
- *UTM-only upstream (e.g. 25832)* — both projections are conformal, so the residual is
  essentially meridian convergence, `γ ≈ (λ−λ₀)·sin φ`, which a bbox rewrite ignores. A
  feature at distance `r` from tile centre shifts by `r·γ`: at z16 about 1 m near the
  central meridian but 16 m at 4.4° off it, four times worse per zoom level out.

So a fallback would sometimes be centimetre-accurate and sometimes put the rider on the
wrong road, with no way to tell which. Erroring out is honest, and it removes proj4j
along with its EPSG-resource-vs-R8 failure mode (the dataset ships as a separate
artifact loaded as a classpath resource; R8 strips it, and only release builds break).

If a real source ever needs this, the numbers above are the basis for a gated version
that refuses above a configurable error threshold.

### Two façades, both natively supported by DMD2

DMD2 accepts XYZ templates *and* a WMS service URL — confirmed against sources already
in use (`tiles.autobahn.de/osm_tiles/{z}/{x}/{y}.png`, `mt1.google.com/vt/…x={x}&y={y}&z={z}`,
and the Bayern DTK25 WMS). So both proxy endpoints land on input DMD2 handles natively.

This removed a risk the design had been carrying. An earlier draft made the WMS façade
the only endpoint and depended on DMD2 issuing tile-aligned `GetMap` requests — an
unverified assumption. With `/tileproxy/...` available, every tile-backed source is reachable
exactly regardless, and the alignment gate on `/wms` is a convenience rather than a
dependency.

### Errors are never blank tiles

On upstream failure the service returns an HTTP error or a WMS
`ServiceExceptionReport`, never a transparent or placeholder image. DMD2 auto-caches
layers, so a placeholder returned for a transient error would be cached as real data
and persist as a permanent hole in the map. An upstream answering `200` with an HTML
error page (common when auth fails) must also become a service exception rather than a
relayed body.

### `:core` stays Android-free

All rewrite logic lives in a plain JVM module. AGP is firewall-blocked on the
development platform, so the project can never be built or run locally and CI is the
only correctness signal. Keeping `:core` free of Android dependencies means its tests
run under a plain `test` task with no emulator, which is the only fast feedback
available. An Android dependency there would push those tests into `:app` and out of
reach.

This is also why CI landed before any application code: a green run on an empty
scaffold makes every later failure attributable to the change that caused it.

### Toolchain pinned to motoLauncher's

AGP 8.7.3 / Kotlin 2.0.21 / Java 17 / `compileSdk 35` are exactly what already passes
CI in `c0dev0id/motoLauncher`, rather than the newest available. Since nothing can be
built locally, an unproven toolchain failing alongside unproven project code is hard to
untangle. Bumping is a separate, revertable commit.

`minSdk` is 34 rather than motoLauncher's 30: the target device is Android 14+, and
`FOREGROUND_SERVICE_SPECIAL_USE` with its `<property>` subtype declaration is an API 34
feature, so a lower floor would only add version-gating code for devices never targeted.

### `specialUse` foreground service, loopback-bound

`specialUse` rather than `dataSync` because Android 15 caps `dataSync` foreground
services at 6 cumulative hours, which would kill the service mid-ride.

The server binds to `127.0.0.1` only. The proxy holds credentials and serves them
unauthenticated, so unreachability from the network must be structural rather than a
policy choice.

### In-app updater, and why it comes first

The device is updated from the published `dev` pre-release rather than over a cable,
because the project cannot be built locally at all. Having it in place before the proxy
exists shortens every later test cycle to: push, wait for CI, tap the button.

Ported from motoLauncher, with four deliberate changes:

- **kotlinx-serialization, not `org.json`.** `org.json` is an Android platform class
  stubbed out on the JVM, so parsing with it forces Robolectric and pushes the tests
  into `:app`. Parsing with kotlinx-serialization keeps `parseRelease` in `:core` under
  plain JUnit, which matters because those tests are the only pre-CI signal there is.
- **The installed version is normalized before comparing.** The debug variant appends
  `-debug` to `versionName` while the published asset does not. motoLauncher has this
  latent: on a debug build every check reports the running build as an available update,
  and `deleteInstalledUpdate` looks for a filename that never exists, so the cached APK
  is never reaped. Stripping the suffix fixes both.
- **HTTP status is checked before parsing.** An anonymous GitHub API call is rate
  limited at 60/hour per IP; without a status check a 403 arrives as a JSON parse
  failure, which reads like a bug in the parser.
- **One hoisted UI state** instead of `setClickable`/`setSubtitle` callbacks driving a
  RecyclerView by hardcoded index. Button state and label derive from the same value, so
  they cannot disagree.

The comparison is `isDifferentBuild`, not `isNewer`. Builds are `dev-<short sha>` under
a single rolling tag, and a git SHA carries no ordering — there is nothing to compare
greater-than against. "Different" is also the wanted behaviour: whatever `main` last
published is what should be installed, including after a revert.

The load-bearing convention is a triple that must agree: `versionName` is
`dev-<short sha>`, the published asset is named `wmsproxy-<versionName>.apk`, and the
release is deleted and recreated under the constant tag `dev`. The version lives in the
filename because GitHub's release JSON carries no version for an asset. Break any one
of the three and every check reports a phantom update.

`buildConfig = true` is required in AGP 8 and is load-bearing here — the comparison
reads `BuildConfig.VERSION_NAME`.

### The request log is the point of this stage

DMD2's request shape is undocumented, and the design carried one unverified assumption:
whether its `GetMap` extents land on tile boundaries. That decides whether tile-backed
sources can appear in the single WMS layer list or must stay on `/tileproxy/...`.

Rather than guess, the service logs every inbound request verbatim and annotates each
`GetMap` with the tile it resolved to, or with the offending extent when it did not
resolve. The answer then comes from a shared log rather than from argument.

Two consequences shaped the build:

- **A log of nothing answers nothing.** A client will not issue map requests unless
  `GetCapabilities` returns a valid document listing a layer, and that layer renders. So
  one source is hardcoded, and the XYZ relay works, purely so requests happen at all.
  Configuration replaces the constant later.
- **The log is bounded and never persisted.** Query strings will carry credentials once
  authenticated sources exist, and nothing here is worth keeping across a restart.

### Failures are never quietly successful

Three cases are refused rather than papered over, all for the same reason — the client
caches what it is given, so a wrong answer persists as a hole in the map:

- An upstream error becomes a service exception, never a blank tile.
- An upstream `200` carrying `text/html` is a failure, not a tile. This is the usual
  shape of an auth failure, and relaying it would put markup in the tile cache.
- A `GetMap` in a CRS the layer does not serve, or spanning something that is not a
  tile, is refused with the reason rather than approximated.

### The server is hand-rolled, and TLS is why

Ktor CIO was the original choice and was removed. Its TLS support is client-side only;
CIO server HTTPS remains an unmerged prototype. That only became fatal once DMD turned
out to refuse cleartext to `127.0.0.1` under its own network security policy — a policy
evaluated inside that app, which nothing here can influence. Serving HTTPS became the
only remaining avenue that does not depend on another party changing their app, so the
engine had to support TLS.

Netty and Jetty do, but both are heavy on Android, and Netty's ServiceLoader and R8
behaviour had already cost one release build in this project. Against two GET routes
served to a single client over loopback, a ~200-line server is the smaller risk.

Two things make it defensible rather than reckless: the omissions are deliberate and
documented (no chunked transfer, bodies, pipelining or keep-alive — anything not
understood is a 400, never a guess), and both the line length and header count are
bounded. The payoff is that it is plain JVM in `:core`, so concurrency, binary bodies,
a throwing handler and recovery after a malformed request are all covered by tests that
run in CI. That coverage was unreachable while the server was an Android dependency.

### HTTPS on loopback, via a name the system already trusts

DMD refuses cleartext to `127.0.0.1` under its own network security policy, and a
self-signed certificate was rejected too — Android apps have ignored user-installed CAs
since API 24 unless they opt in. What did work: a certificate for a **real hostname
whose A record points at `127.0.0.1`**. The client connects by name, DNS returns
loopback, the proxy answers, and the chain validates against the system CA store. No
root, no CA install, no change to the other app.

Issuance must use **DNS-01**. HTTP-01 connects to the A record and refuses a reserved
address, which is the wall hit first.

Two hard rules learned the expensive way:

- **Never a wildcard.** A wildcard key covers every host in the domain, and this key is
  shipped in a public artifact. Only ever issue for the single name.
- **The key is public the moment it ships.** It lives in the APK, and the APK is a
  public release. Keeping it out of the repository only stops scanners reporting it and
  triggering a revocation — it does not make it secret. That is acceptable solely
  because the name resolves to loopback, so the certificate authenticates nothing an
  attacker could not already reach on their own device.

Renewal is the standing cost: 90 days, and a new build each time.

### The certificate ships in the APK

`app/src/main/assets/localhost.p12` holds a self-signed certificate and its key, both
committed. That is deliberate. The key only ever authenticates `127.0.0.1`, and any app
on the device can already reach the proxy, so publishing it grants nothing that local
access does not. Generating on device instead would require a certificate-building
library and protect nothing.

It carries `subjectAltName` with `IP:127.0.0.1`. Without that it fails validation even
when trusted, because modern TLS stacks ignore CN entirely — a common way this is got
wrong. PKCS12 rather than JKS, which Android does not support.

Expectations for the self-signed certificate are low: Android apps have ignored
user-installed CAs since API 24 unless their network security config opts in, so a
client will most likely reject it, and only a CA in the **system** store (root) or a
publicly trusted certificate for a name resolving to loopback would change that. The
TLS plumbing is identical in every one of those cases, so it had to be built regardless;
swapping the certificate later is a file change.

## Reference sources

Known-good upstreams, useful as fixtures and for manual checks:

- `https://tiles.autobahn.de/osm_tiles/{z}/{x}/{y}.png` — XYZ, path style.
- `https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}` — XYZ, query style.
- `https://geoservices.bayern.de/od/wms/dtk/v1/dtk25?REQUEST=GetCapabilities&SERVICE=WMS`
  — WMS; verified reachable, returns `application/vnd.ogc.wms_xml`.

**IGN Géoportail is a migration trap.** URLs under `wxs.ign.fr` are retired: IGN moved
to the Géoplateforme and the redirect to `data.geopf.fr` was switched off on
30 September 2024. The old API-key path segment no longer applies. A saved
`wxs.ign.fr/.../geoportail/wmts?...` template fails because the host is gone, not
because the client mishandled it — no proxy can rescue that. The current form is
`https://data.geopf.fr/wmts?...`.
