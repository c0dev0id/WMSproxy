# Development Journal

## Software Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.0.21 |
| Build | Gradle 8.9, AGP 8.7.3, Java 17 (temurin) |
| SDK levels | `compileSdk 35`, `targetSdk 34`, `minSdk 34` |
| Modules | `:core` (pure JVM), `:app` (Android) |
| HTTP server | Ktor 3.x, CIO engine *(planned)* |
| HTTP client | OkHttp *(planned)* |
| Config | kotlinx-serialization JSON *(planned)* |
| UI | Compose / Material 3 |
| Tests | JUnit4, OkHttp MockWebServer *(planned)* |
| CI | GitHub Actions — `Check` on branches, `Build` on `main` |

Deliberately absent: proj4j or any coordinate library, any image/bitmap library, any
caching layer, any database. See *Key Decisions*.

## Core Features

Planned, in milestone order. Nothing below is implemented yet beyond the scaffolding.

1. Foreground service hosting an HTTP server on loopback, with a request log.
2. XYZ upstreams on `/t/{layer}/{z}/{x}/{y}` — template expansion, TMS y-flip,
   quadkey, `{s}` subdomains.
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
unverified assumption. With `/t/...` available, every tile-backed source is reachable
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
