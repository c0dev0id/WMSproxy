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

### Upstream certificates are not validated — temporary

The relay client trusts every upstream certificate and accepts every hostname
(`InsecureTls` in `ProxyServer.kt`). This is a deliberate, known-bad shortcut, recorded
here so it is not mistaken for an oversight.

Cause: the first end-to-end session showed DMD2 reaching the proxy successfully over
HTTPS across z5–z16, with every relay failing as
`SSLHandshakeException: CertPathValidatorException: Trust anchor for certification path
not found` against `tiles.autobahn.de`. The device is not at fault — the updater talks
to `api.github.com` from the same process, so the system trust store and the clock are
both sound. The remaining hypothesis, untested, is an upstream serving a leaf without
its intermediate: browsers fetch the missing certificate via the AIA extension, Android
and OkHttp do not.

Decision: bypass validation now, diagnose later. The direction of the risk matters. This
is the proxy validating *upstreams*, not the client validating the proxy — the TLS
listener and its certificate are untouched, so nothing about how DMD2 trusts us changes.

The deadline is auth (milestone 4). Today the worst case is wrong imagery from a
tampered connection. Once sources carry Basic credentials or API keys, those go out over
connections nobody verified, which is a different category of problem. Before that
lands, narrow this to what is actually needed:

1. Reproduce against whichever upstream actually fails. The original report came from
   `tiles.autobahn.de`, which has since stopped serving anyone, so that evidence is
   gone — and with it any certainty about what the cause was. Confirm a chain with
   `openssl s_client -showcerts -connect <host>:443` and count the certificates
   returned; one means the intermediate is missing.
2. If so, prefer supplying the intermediate to the client's trust manager over trusting
   everything, or pin that single host.
3. Only if the cause turns out to be something else should the bypass survive, and then
   as a per-source opt-in the user sets deliberately — never the default.

### `{bbox}` makes WMS a template, not a second code path

A WMS upstream looked like it needed its own branch on the relay: compute the tile's
extent, build a `GetMap`, handle the response separately. It does not. A tile has an
exact WebMercator extent, so a `GetMap` for that extent at 256×256 *is* the tile, and
adding a `{bbox}` placeholder to template expansion covers the whole case. The request
stays a string being filled in. No pixel is touched and no projection is changed — it is
arithmetic from the tile index, which `TileMath.tileBbox` already did.

The same trick reaches further than expected. WMTS KVP is an ordinary template once
`TileMatrix`/`TileRow`/`TileCol` are mapped to `{z}`/`{y}`/`{x}`, and a matrix identifier
like `EPSG:900913:12` is expressible as the literal `EPSG:900913:{z}` — the awkward part
is a constant. So two of the protocols this project exists to bridge need no new
machinery at all, only a generator that writes the right template.

`Bbox.asWmsParameter()` formats under `Locale.ROOT`. The default locale would render the
decimal mark as a comma on a German device, which is also the separator between the four
values — four fields becoming eight, and a request that fails or, worse, parses into
something else entirely.

### `:core` is JVM-tested but Android-run, and JAXP is where that bites

Every capabilities import failed on the device with

    Not valid XML: This parser does not support specification "Unknown" version "0.0"

before a byte of XML was examined. Android's abstract `DocumentBuilderFactory` throws
`UnsupportedOperationException` from `setXIncludeAware` unconditionally; the desktop JVM's
Xerces accepts it. One unguarded hardening call, green in CI, dead on the phone.

This is the standing cost of the architecture, and worth naming rather than treating as a
one-off. Keeping `:core` free of Android dependencies means its tests run in CI without an
emulator, which is what makes this project testable at all given no device is ever
available here. But Android-free is not Android-identical: `:core` still *executes* on
Android, against Android's implementations of the platform libraries. Wherever the JDK
offers something optional — JAXP being the obvious one, but also charsets, locale data and
date formatting — CI proves only that the desktop implementation accepts it.

So configuration of a platform facility is treated as best-effort: applied one step at a
time, each failure tolerated, and never able to refuse work that would otherwise succeed.
A setting that genuinely cannot be skipped — namespace awareness here, since local names
are how every element is matched — stays unguarded on purpose, so its absence fails loudly
rather than producing silent nulls.

Second lesson from the same bug: the failure was reported as "Not valid XML", which is a
statement about the server. It was a statement about us. A configuration error and a
malformed document now say different things, because the first message sent the reader to
investigate a service that had done nothing wrong.

### Spec-grounded checks yes, invented heuristics no

The importer briefly grew three pieces of cleverness about the URL the user pastes: it
appended `SERVICE=…&REQUEST=GetCapabilities` when none looked present, guessed the
service from whether the path contained `wmts`, and used the fetch URL as the endpoint
when a document published none. All three are the same mistake in different clothes —
a rule invented here, applied to a server that never agreed to it.

Each one fails in the worst available way: silently, against a server that was working,
with a message blaming the server. `/gwc/service/wmts` is a convention rather than a
rule. An endpoint may need `acceptVersions`, a MapServer `map=` file, or another vendor
parameter only the person pasting it knows about. And the address a service publishes
for GetMap is frequently *not* the one that served its capabilities, because proxies and
aliases exist.

The line is not "never check anything". It is **whose rule is being enforced**:

- A check the specification demands is fair game. `OnlineResource` is mandatory in both
  WMS schemas, so a document lacking one is broken and saying so is reading the spec
  aloud, not inventing policy. Likewise refusing a layer with no WebMercator, or matrix
  identifiers that are not levels — those are statements about what the document says.
- A rule this project made up is not, however convenient. Completing someone's URL is
  the clear case: nothing in WMS or WMTS says a capabilities request may be reconstructed
  from a fragment of one.

So `CapabilitiesParser.parse(xml)` takes no URL at all and is pure in the strict sense —
same document, same result, no ambient input. Every decision is made on returned data.
The URL is something to fetch.

**Deferred, deliberately: broken servers.** Real deployments violate the specification,
and accommodating them is wanted eventually. When that happens it must be per defect and
explicit — this server omits that element, so do this — never a general fallback that
quietly repairs anything unrecognised. The current strictness is what makes that possible
later: a failure that names exactly what the document lacked is the evidence a targeted
workaround needs, whereas a silent guess destroys it.

### Capabilities import, and what it refuses

`CapabilitiesParser` turns a WMS 1.1.1/1.3.0 or WMTS 1.0.0 document into layers with
templates ready to serve. Pure, in `:core`, driven from fixtures in CI.

**Only EPSG:3857 is ever requested.** That is not a limitation to lift later; it is the
no-reprojection rule applied at the point of import. It also disposes of the WMS 1.3.0
axis-order trap for free: latitude-first ordering applies to *geographic* CRSs, and
WebMercator is projected, so the bbox goes out x-first under both versions with nothing
to remember and nothing to get wrong.

What it refuses matters as much as what it accepts, and each refusal is reported to the
user with its reason rather than dropped:

- A layer with no WebMercator CRS. Fetching it in another projection and labelling it as
  this one is the error the project exists not to make.
- A tile cache serving only vector tiles — the normal state of a GeoServer GWC. Drawing
  those means rendering.
- Matrix identifiers with no template form. Zero-padded levels (`L00`, `L01`) are
  refused rather than guessed at: `L` + zoom would request `L0` for level zero, which
  does not exist, and every tile would 404.

Details worth not rediscovering. CRS elements are **inherited** down the WMS layer tree,
so the parser walks recursively and accumulates — a child may be serveable only through
what its parent declared. A published `OnlineResource` often already carries query
parameters the server needs (MapServer's `map=` is the classic), so the separator is
chosen rather than assumed. WMTS commonly publishes both KVP and RESTful endpoints at
different paths, and appending KVP parameters to the REST one 404s on every tile, so the
KVP constraint is read rather than taking the first `Get`. External XML entities are
disabled because the document comes from a URL the user pasted, but DOCTYPE stays
allowed, because WMS 1.1.1 documents legitimately carry one.

### The log is a flow, and the UI is three tabs

The request counter showed a number that never changed. The cause was not the counter:
`RequestLog` had no way to say it had changed, so the screen read a snapshot when it
opened and again only when Refresh was pressed, while entries arrive on the server's
worker threads. Anything that has to be asked for is stale by the time it is drawn.

`RequestLog` now publishes a `StateFlow`, republished on every record and clear, each
change a fresh immutable list so a reader never sees the deque mid-mutation. That is what
makes a live view possible at all; the counter was the visible symptom, not the fault. It
cost `:core` a dependency on kotlinx-coroutines-core — plain JVM, so the Android-free
rule holds — pinned to the version `:app` already resolves through
kotlinx-coroutines-android, because two coroutines versions on one classpath is nobody's
idea of a good afternoon.

The screen became three tabs: Sources, Log, App. The log earns its own tab because it is
the project's primary diagnostic and needs the full height; as a section at the foot of a
long scrolling page it was unreadable. Start/stop and the running state sit above the
tabs, visible from all of them.

**Tail-following stops when the user scrolls up.** Scrolling up is how an earlier failure
gets read, and a log that yanks you back to the bottom on every arriving tile is useless
exactly when it matters most. Following resumes on returning to the end. The bottom
detector allows one entry of slack, so the item that just arrived does not itself count
as having scrolled away.

### Sources are configured, and the model moved to :core

`TileLayer` lived in `:app`, which put template expansion — the decision about which
upstream tile a request becomes — beyond the reach of a plain `test` run. It is in
`:core` now, `@Serializable`, with validation and a JSON codec beside it and unit tests
over all three.

Validation is not politeness. Every rule it enforces describes a source that would fail
later and further away: a template without `{y}` fetches one tile forever, a name
containing a slash quietly answers on a different route, subdomains with no `{s}` to fill
are a silent no-op, a duplicate source/layer pair shadows an existing route. The user
finds out while typing.

`:app` keeps only what needs a `Context`: the starting set and a store over the app files
dir. Decoding is lenient — an unreadable or truncated file yields an empty config rather
than an exception, because there is nothing useful the app could do with the throw, and
unknown keys are ignored so a config written by a newer build still loads. An empty
stored list is a real state, meaning the user deleted everything, and is deliberately not
mistaken for a missing file and refilled with the defaults.

The server reads the source list per request rather than capturing it at construction, so
an edit takes effect on the next tile with no restart to remember.

### The relay draws one line: image or not

The relay originally refused `text/*` and anything containing `html`, which caught the
known case — an error page returned as 200 on failed auth — and nothing else. It fails
open.

Surveying `api.mobidata-bw.de/geoserver` showed the size of the gap. A GeoServer answers
the same endpoint in a dozen non-image formats beside PNG, and its GWC tile caches (WMTS
*and* TMS) serve vector tiles exclusively — there is no raster tile to be had there at
all. None of those types contains `html`, so every one would have been relayed to the
client as a tile.

That is the blank-tile rule in different clothing. The client caches what it is handed,
so bytes it can never draw persist exactly like a placeholder would.

The first version over-corrected: it listed the formats a client can decode — png, jpeg,
webp, gif, bmp — and refused the rest, `image/tiff` and `image/geotiff` among them. That
is a model of the client's decoder maintained here on guesswork. It refuses tiles that
might have rendered, and when one does not render it teaches us nothing, because we never
let it through to find out.

`TileMediaType` in `:core` now draws one line only: raster image, or not. Any raster type
is relayed byte for byte whatever it is. If a format proves unusable, *that* is when a
rule for it is added — on evidence from a real client, not on an assumption about one.
The set of what works gets learned rather than predicted.

Refused is what is not an image at all — features, documents, an error page returned as
200 — and a response that states no Content-Type, since guessing at an absent type is how
undrawable bytes reach the cache. `image/svg+xml` is refused with them despite being an
image media type: it is a vector document, so relaying it only defers the decision to
somewhere that cannot act on it.

Vector formats stay refused permanently, not pending support: making them usable means
rendering, and nothing here decodes or re-encodes.

**If conversion is ever added, it is per-format and on evidence, never blanket.** Two
reasons, both from the rider's side rather than the architecture's. A rider with ten
layers configured, panning and zooming, generates a lot of tiles — converting all of them
is work this device does not need to be doing. And it is unknown whether the client trusts
its cache blindly or revalidates; if it revalidates, a re-encoded image may not match what
it holds. Worth noting for that day: the proxy currently emits only `Content-Type` and
`Content-Length`, dropping upstream `ETag`, `Last-Modified` and `Cache-Control`, so the
client cannot revalidate through it at all today.

### A courtesy tile host is not infrastructure

`tiles.autobahn.de` was the first built-in layer and it stopped serving third parties —
403 on every tile, in a browser as much as from the proxy, so withdrawn access rather
than a hotlink or user-agent block. It had never been a public tile service, only a
convenient one that happened to answer.

This is the normal failure mode for anything borrowed. The built-in layers are therefore
labelled as placeholders, not defaults: `tile.openstreetmap.org` for the reference
path-style case, and CARTO's basemaps for a source carrying both `{s}` rotation and a
layer segment. Both are courtesy hosts under the same standing risk, and both are
replaced the moment sources become configurable (milestone 9) — that milestone is what
actually fixes this, not a better-chosen default.

Two consequences taken now:

- The proxy identifies itself: `WMSproxy/<version> (+<project URL>)`. The OSM
  Foundation's tile usage policy requires a User-Agent naming the application and treats
  a generic one as grounds for blocking. Carrying the build means an operator's abuse
  report and the on-device request log name the same version.
- Traffic stays light by construction — the client caches, the proxy deliberately does
  not, and nothing here pre-fetches. That is what keeps this within *light use*.

Distributing an app with a courtesy host compiled in is the grey area of that policy.
It is tolerable for one rider on a pre-release; it would not be for a general audience,
which is one more reason configurable sources cannot be deferred indefinitely.

## Reference sources

Known-good upstreams, useful as fixtures and for manual checks:

- `https://tile.openstreetmap.org/{z}/{x}/{y}.png` — XYZ, path style. The reference
  case. Requires an identifying User-Agent and light use.
- `https://{s}.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png` — XYZ with `{s}`
  subdomain rotation (`a`–`d`); also the fixture for a source with a layer segment.
- `https://tiles.autobahn.de/osm_tiles/{z}/{x}/{y}.png` — **dead for third parties.**
  Answers 403 to everything, browsers included. Kept here so it is not rediscovered as
  a candidate.
- `https://mt1.google.com/vt/lyrs=y&x={x}&y={y}&z={z}` — XYZ, query style.
- `https://geoservices.bayern.de/od/wms/dtk/v1/dtk25?REQUEST=GetCapabilities&SERVICE=WMS`
  — WMS; verified reachable, returns `application/vnd.ogc.wms_xml`.
- `https://api.mobidata-bw.de/geoserver/ows?service=WMS&version=1.3.0&request=GetCapabilities`
  — GeoServer, 13 thematic layers of Baden-Württemberg mobility data. **The WMS fixture.**
  Verified: a `GetMap` in `EPSG:3857` over an exact tile bbox at `WIDTH=HEIGHT=256`
  returns a valid 256×256 RGBA PNG, which is precisely the tile-over-WMS case. It has a
  layer concept, so it also exercises the two-segment `/tileproxy/<source>/<layer>/...`
  path with a real provider.

  Whether those layers read as a basemap or as an overlay is not this project's business:
  transparency is a property of the payload, and the payload is relayed untouched. The
  client stacks layers and decides what to do with them.

  Its tile caches are useless to us: every WMTS and TMS tileset is `@pbf`, vector only.
  Its `WebMercatorQuad` TileMatrix identifiers are the bare integers `0`–`24`, so it does
  not exercise the non-integer identifier case (`EPSG:900913:12`, `L12`) either. A WMTS
  raster fixture and a flipped-Y TMS fixture still have to come from somewhere else.

**IGN Géoportail is a migration trap.** URLs under `wxs.ign.fr` are retired: IGN moved
to the Géoplateforme and the redirect to `data.geopf.fr` was switched off on
30 September 2024. The old API-key path segment no longer applies. A saved
`wxs.ign.fr/.../geoportail/wmts?...` template fails because the host is gone, not
because the client mishandled it — no proxy can rescue that. The current form is
`https://data.geopf.fr/wmts?...`.
