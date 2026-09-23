# Development Journal

## Software Stack

| Concern | Choice |
|---|---|
| Language | Kotlin 2.0.21 |
| Build | Gradle 8.9, AGP 8.7.3, Java 17 (temurin) |
| SDK levels | `compileSdk 35`, `targetSdk 34`, `minSdk 26` |
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
  served from a public URL. Only ever issue for the single name.
- **The key is public the moment it is fetchable.** The keystore is served from a fixed
  URL behind trivial credentials, so treat the key as public. That is acceptable solely
  because the name resolves to loopback, so the certificate authenticates nothing an
  attacker could not already reach on their own device.

### The certificate is fetched at runtime, not bundled

The certificate is short-lived — Let's Encrypt, 90 days — so a copy baked into the APK
expires between releases and takes the HTTPS listener down with it. That renewal cost used
to be a new build every 90 days, with CI decoding a base64 secret into `localhost.p12` at
build time. Instead the app now fetches the current keystore from a fixed URL
(`Tls.CERT_URL`) with basic auth over a validating client, caches it in the files dir, and
refreshes in the background as it nears expiry. Renewal is entirely server-side; the APK
carries no certificate, and the CI injection step and `TLS_HOST` build field are gone.

The refresh replaces the cache **only when the fetched certificate's `notAfter` is later**
than the cached one. The trigger is the cached certificate nearing expiry, and if the
server has not renewed yet the fetch returns the same bytes — writing them back would leave
the trigger armed and refetch on every start. The newer-than gate breaks that loop.

Offline is not a concern: with no connectivity there are no tiles to serve anyway, and a
previously cached certificate keeps the listener up. First run needs connectivity, which a
user setting up sources already has; until the first fetch succeeds only the plain listener
runs, and `ProxyService` brings the secure listener up once the cache exists.

Standardised on the one hostname (`Tls.HOST`): the certificate's SAN is that name only, so
a URL naming `127.0.0.1` would fail hostname validation. The old self-signed loopback
certificate and its on-device "install this certificate" export are both gone — a publicly
trusted certificate needs no manual trust step. PKCS12 rather than JKS, which Android does
not support.

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

### A WMS upstream has no latency ceiling, and the server was built assuming one

Measured against a real service, one layer answers a tile in **31 seconds** at z5–z7 and
under a second at z12; a line layer on the same server answers in 0.6s at every zoom. The
difference is what a `GetMap` actually is — the server rasterises every feature in the
requested extent on demand, and at low zoom that extent is an entire federal state.

Two things followed from that, and only one of them was ours.

**The proxy was starving itself.** `HttpServer` ran eight workers, each blocked for the
whole of one upstream request. Eight slow tiles held every worker, and a request log
showed twelve seconds of total silence while a second source that was answering in under
a second got nothing. Eight was chosen when every upstream was a tile server replying in
milliseconds; a client asking for a screenful at a time exceeds it trivially. The pool is
32 now and still fixed, so a stuck upstream still cannot spawn threads without bound —
these threads are blocked on a socket rather than working, so the cost is stack space.

**The timeout is a judgement, not a bug.** A 20-second read timeout cuts off a server that
would have answered in 31. Raising it would make those tiles arrive; it would also mean
holding a connection for half a minute for one tile of a map someone is riding across.
Left as is deliberately: a tile that takes 31 seconds has no use on a moving map, and the
log says plainly what happened rather than leaving a blank square.

What is *not* available here is the obvious mitigation. Scale hints would let a layer be
refused instantly outside the range where it means anything — WMS has
`MinScaleDenominator`/`MaxScaleDenominator` for exactly this — but the document measured
carries none at all, for any layer. Nothing to read, so nothing to enforce; inventing a
zoom range would be precisely the invented rule ruled out above.

The general point for later: tile-server upstreams have a latency ceiling and WMS
upstreams do not. Anything sized for the first will be wrong for the second.

There are now three patiences, and the third was learned the hard way. Serving a tile
gets five seconds, measuring a zoom range gets twenty, and reading a capabilities document
gets sixty. The import path had been using the tile client, on the assumption — written
into its own comment — that a capabilities document runs to a few hundred kilobytes. NASA
GIBS publishes 5.8 MB describing over thirteen hundred layers, and the tile budget cut it
off every time.

The distinction is who is waiting and for what. A tile that takes five seconds has already
been scrolled past; a document the user asked for, with a progress line on screen, is one
they will sit through. All three are read timeouts rather than call timeouts, so a slow
link that is still delivering is not mistaken for a dead one.

The same document exposed a second cost: reading it into a String first held it three
times over — the bytes, a UTF-16 copy about twice their size, then the bytes again on the
way into the parser. The parser now takes an `InputStream` and the response goes straight
in. The DOM it then builds is still the dominant cost and still proportional to the
document, so it remains the next thing to hurt — but not yet, and not soon: both were
confirmed importing on the device afterwards, GIBS included, so 5.8 MB and thirteen
hundred layers build a tree a phone can hold. A streaming parser is the answer when
something exceeds that, and nothing yet does.

Worth separating the two failures, because they looked identical and were not. GIBS is
5.8 MB from a fast server and ran out of budget mid-download. DWD is 750 KB from a slow
one — seven seconds before the first byte, then a 55 KB/s trickle — and ran out of budget
before any data arrived at all. A size limit would have fixed one and a latency limit the
other; patience fixed both.


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

### A private companion takes `serializer()` private with it

`@Serializable` generates its `serializer()` accessor on the class's companion object.
Declaring a companion `private` — an ordinary thing to do when it only holds a constant
— makes that generated accessor private too, and the class becomes serializable in name
only:

    Cannot access 'companion object Companion': it is private in 'SourceLibrary'

The error points at the *call site*, not the declaration, so it reads as a problem with
the code doing the decoding rather than with the class being decoded. Constants for a
serializable class go at file level instead.

Worth noting how it was found, because the guess was wrong. The failing line was a dense
`compareBy` with vararg selectors, which is a real inference hazard, and that was the
first suspicion — plausible, wrong, and it would have been committed as a fix had the
compiler output not said something else entirely. The log named the cause exactly; the
reasoning from the shape of the code did not.

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

The same trap, a second time, and worse: the app began crashing before it could draw a
screen. Nothing in `Application.onCreate` had changed, and the whole app-side diff since
the last working build sat inside the import dialog — so the only new code that could run
that early was in `:core`.

It was a regex — confirmed on the device, not merely suspected: escaping the brace made
the app start again. `TileLayer` gained a `companion object` to hold `PADDED_ZOOM`, and

    Regex("""\{z:(\d{1,2})}""")

ends with an unescaped `}`. The JVM treats a dangling `}` as a literal and compiles it
happily, which is why every unit test passed. A companion is constructed in the outer
class's `<clinit>`, so the first `TileLayer` built — in `BuiltInSources.all`, inside
`Sources`' own initialiser, called unwrapped from `Application.onCreate` — turns any
rejection into an `ExceptionInInitializerError` before there is a UI to report it on.

Two rules follow, and they are about placement as much as syntax:

- **Escape both braces in every pattern.** A construct the JVM tolerates is not evidence
  about the device, and a unit test cannot tell the two apart because it *is* the JVM.
  `Capabilities.kt`'s `PLACEHOLDER` had the same dangling brace and was fixed with it.
- **A class initialiser is the worst place to learn this.** `Regex` compilation in a
  `companion object` runs at class-load, so it cannot be caught where it is used and its
  stack trace names the initialiser rather than the pattern. Anything that can reject
  input belongs where a failure has somewhere to go.

CI cannot cover this: it compiles and it runs JVM tests, and neither executes the app.
That is the standing cost of having no device in the loop, and it is why `:core` being
"pure Kotlin" is about *dependencies*, not about the runtime it will actually meet.


### Blank outside the range, and the debt that leaves

An out-of-range zoom returns a transparent tile rather than 404. Two reasons, and the
second is the binding one: WMS prescribes a blank map outside a layer's declared scale
range, so there is a specification covering this case; and the client refuses a source
whose test tile is not a 200, so conforming is the job of the side facing it.

The tile is an asset, 334 bytes, a fully transparent 256×256 RGBA PNG. Building one on
demand would put an encoder on the data path for a value that never varies, and the one
rule this project has is that nothing on that path decodes or draws. When the asset
cannot be read the error comes back instead — a zero-byte body labelled `image/png` is a
corrupt tile the client would cache, which is worse than the error it replaced.

**The debt.** The measured range excludes a level for being slow as much as for being
empty, and a blank tile asserts emptiness. Where a layer has data at z5 and merely takes
half a minute to draw it, the client is now told there is nothing there — and it caches
that. This is the blank-tile rule bending, and it is recorded rather than argued away:
the earlier version of that rule was about transient failures, where a placeholder is
always wrong, and this is a permanent property of the source as configured, where it is
mostly right. "Mostly" is the debt.

Paying it means storing *why* each bound sits where it does, not only where: absent →
blank, too slow → an error the client can retry. The range already knows, since the probe
records a reason per level; only the bounds are kept. Worth doing when a layer turns out
to be wrongly blank, and not before — the per-host concurrency limit may widen the ranges
enough that the case stops arising.

### Overzoom is exact, and still not ours to do

Asked whether the proxy could upscale a lower-zoom tile instead of serving a blank
outside a source's range: blurry lines would be acceptable, displacement would not.

**Geometrically it is exact.** WebMercatorQuad is a strict quadtree, so tile `(z,x,y)`
*is* sub-cell `(x mod 2ⁿ, y mod 2ⁿ)` of its ancestor `(z-n, x>>n, y>>n)`. Checked rather
than assumed: across several tiles and depths the worst corner disagreement between a
child's own bbox and that sub-rectangle of its ancestor's was 1.9e-9 m, which is float64
noise. The crop offset is integer arithmetic and no coordinate transform occurs, so there
is nothing for displacement to enter through. One level over scales 128×128 px to 256,
two levels 64×64 — readable; three is mush.

It holds **only for a dyadic grid**: each level exactly twice the previous, same
`TopLeftCorner`, same tile size. WMTS permits arbitrary scale denominators, and a
non-power-of-two progression or a shifted origin makes the child something other than a
clean sub-rectangle — and then it *is* displacement. That is checkable from the
capabilities, so it would be a verified precondition, never an assumption.

**It is still refused, for three reasons that outrank the geometry:**

- It cannot be done by rewriting. Pointing the request at the ancestor's URL makes the
  client paint that whole image into the child's slot, which is both the wrong scale and
  offset by quadrant. Doing it properly means decode, crop, resample, re-encode — a
  `Bitmap` on the data path, per tile, about twenty tiles to a screen. That is the rule
  the project is built on, and breaking it for blur is a bad trade.
- Overzoom belongs to the client. Scaling an already-decoded tile on the GPU costs no
  request and no re-encode and looks better than anything re-encoded here would. DMD
  does not offer it — confirmed on the device — but a gap in one client does not make the
  job ours, any more than caching did.
- For a WMS upstream it would be actively wrong. A WMS renders any bbox at any scale and
  never runs out of resolution; where the measured ceiling reflects slowness rather than
  absent data, overzoom would serve blur in place of a correct tile the server was
  willing to draw. That is the conflation recorded above, made visible.

The honest fix for the blur case is the one already named as debt: record *why* a bound
sits where it does, so "no data here" and "too slow here" stop being the same answer.


### The northbound side conforms; compensation belongs upstream

A question about what to return for a zoom the source does not serve produced the wrong
instinct first: that XYZ has no standard, so the answer was ours to pick. Both halves of
that are wrong, and the second half more seriously than the first.

On the facts: the grid *is* standardised — OGC 17-083r4 defines `WebMercatorQuad`, which
is the XYZ scheme exactly, and OGC API–Tiles standardises access to it. The
`{z}/{x}/{y}` template is a documented convention with settled semantics, and **404 is
its conventional answer for a tile that is not there**. OSM returns 404 above z19, and
tile clients treat it as "nothing here" and carry on.

On the framing: "nothing specifies this, so we choose" is the invented-rule trap again.
The side facing the client is not this project's to design. It has to look like what an
XYZ client already knows how to consume, and the client's own quirks are the measure of
success — DMD's add-time probe treats a non-200 as failure, which is stricter than the
convention, but it handles 404 perfectly well while running.

**Compensation belongs on the upstream side.** Flipped rows, quadkeys, subdomain
rotation, credentials, a WMS made to answer like a tile server: absorbing those is the
entire reason this exists. None of it licenses inventing behaviour on the side facing the
client, where conforming is the job.

That also settles what an out-of-range tile returns without needing to separate "the
source has nothing here" from "the source cannot answer in time". The measured range
*is* the contract this proxy serves for that source, and 404 states it accurately under
either reason. A transparent 200 would assert there is no data where there is — the
blank-tile rule, and this time the fabrication would be permanent rather than transient.

Worth recording for the day scale ranges come up again: WMS 1.3.0 does prescribe a blank
map outside a layer's declared `Min`/`MaxScaleDenominator`, and WMTS prescribes an
`ows:ExceptionReport` with `InvalidParameterValue`. Both are about their own interfaces.
Neither governs what this proxy says to an XYZ client.

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

### The shipped list carries its own ordering, and the checker only votes

Three things about the bundled service list settled during a cleanup pass, each because
the first version put knowledge on the wrong side of a boundary.

**Region order is data.** `byRegion()` originally ranked regions against a `listOf(
"Global", "Europe")` in `:core`, while the regions being ranked are written in `:app`'s
`library.json`. Nothing connected the two but exact string equality, so a typo or a new
wide region drifted silently — `:core` has no way to see the asset and no test reads it.
The order is now a `regions` field in the same file as the entries, and `:core` knows no
region name at all. Anything unnamed sorts after the named ones, alphabetically, which is
what countries want.

**The list is read when the Library tab is first shown, not at startup.** It was a third
`init(Context)`/`get()` singleton called from `Application.onCreate`, alongside two that
earn that position: `Sources` fills the `StateFlow` the service reads immediately, and
`BlankTile` is on the tile data path with no `Context` available where it is used. The
library is neither — a few kilobytes one screen looks at — so every cold start paid an
asset read and a JSON parse before the first frame for a screen most launches never open,
and the foreground service held the parsed entries for its whole life.

The first replacement was a `remember` in the dialog, which was the whole mechanism the
singleton had provided — until the list moved to a tab. A tab leaves the composition on
every switch and takes its `remember` with it, so each return to the Library re-read and
re-parsed the file on the main thread, inside the frame that draws the switch. The
mechanism now is `BundledLibrary`, a holder that reads on first use and keeps the result:
lazy like the `remember`, held like the singleton, and still nothing at startup. It also
gives the asset name a home beside `BlankTile`'s, where a rename will find it.

**`tools/check-library.py` answers yes or no and nothing else.** It reimplements
`CapabilitiesParser`'s acceptance rule in Python, which is a real cost — commit `1546971`
widened what the importer accepts and the copy had to be widened with it. Porting it to
Kotlin was considered and rejected: Gradle cannot run on this platform, so a Kotlin
version would be unrunnable here, `:core` has no HTTP client and should not grow one, and
49 fetches against third-party servers do not belong in the CI run that gates every push.
Its value is precisely that it runs outside the build. What it can do is restate as little
as possible, so it no longer reproduces the format preference order or the `{z}` template
text — neither could change its verdict — and keeps only the accept/reject rule that has
to track the Kotlin. Equivalence after that change was checked by running both versions
over one set of fetched documents: identical verdicts and counts on all 49.

Still open: nothing runs the checker on a schedule, and a malformed `library.json` ships
green — `LibraryCodec` swallows the parse failure by design and the dialog simply shows no
list. The offline half of the check (the file parses, every entry has a name and a URL)
belongs in the `test` run; only reachability needs a scheduled job.

### What earns a place in the shipped list

Five German and Baden-Württemberg catalogues were worked through — LGL-BW's service
pages, BKG's open-data and INSPIRE catalogues, MobiData-BW's CKAN and LUBW's RIPS
metadata. Together they publish several hundred services, and nearly all of them pass
the acceptance check: WebMercator and a raster format are the *technical* bar, and it
turns out to be a low one.

The useful bar is different, and it is editorial: **does this help someone reading a
screen while riding?** In the project owner's words, what qualifies is topographic maps,
shadings, overlays with construction, detours and dangers, current weather and forecasts,
swamps, and POI-type things — shelters, bunkers, historic sites. What does not is "all
the statistics about whatever".

So the list admits a legible basemap, imagery and terrain shading; the things that change
a route; and the things worth stopping for. It excludes most of what these catalogues
actually hold: cadastral parcels, sheet-index grids, geodetic control points, historical
orthophotos by decade, Sentinel-2 scenes by month, soil and energy infrastructure,
administrative and statistical boundaries. Those are real services that work perfectly
through the proxy; they are simply not navigation.

Two rules for the entries themselves, learned from writing bad ones. **A note says what
you would see and what it is for** — not which protocol carries it, not how this entry
relates to another, and not a word a rider would have to look up. "The same layers over
WMS" failed all three: it described a sibling entry, named a protocol the user never
chooses, and said nothing about the service. "Orthophotos" failed the third. **And where
a provider offers the same data twice, only one entry ships** — the tiled one, because
pre-rendered tiles cost the upstream no per-request rendering and reach the rider sooner.
Two providers were offering both; NASA GIBS loses some rarely-wanted science layers that
way, out of well over a thousand, which is a price worth paying for a list that does not
ask the user to choose between two spellings of the same map.

Twelve entries came out of the five catalogues. The ratio is the point — a bundled list
is worth having only because someone already threw the rest away, and the checker cannot
do that part. Two traps the filtering itself turned up:

- A keyword search finds the wrong things. Searching the catalogue for "Limes" returned
  108 records, almost all municipal zoning plans for streets named *Limesstraße*. The
  four that mattered were the Denkmalpflege services, and they are named after the
  agency, not the monument.
- Density is a disqualifier of its own. The built- and art-monument service covers every
  listed building in the state; it passes every technical check and would be unreadable
  as an overlay at riding speed. Archaeological sites and world heritage went in, that
  one did not.

Two things worth recording for next time:

- **LGL-BW publishes no endpoint URLs on its own pages.** The product pages name services
  ("WMS LGL-BW ATKIS Digitale Topographische Karte 1:25 000") and link to `/Produkte/
  Open-Data/`, which links to per-record pages in the GDI-BW GeoNetwork. The URLs live in
  those records, at `owsproxy.lgl-bw.de/owsproxy/ows/<SERVICE_NAME>`; reading the 204
  records the Open Data page points at yields the full catalogue of 164 services. An
  earlier attempt at this guessed URLs from memory and produced plausible-looking ones
  that did not exist — the catalogue is the only honest route.
- **`rips-rasterdaten.lubw.bwl.de` is unreachable from CI's network** while
  `rips-gdi.lubw.baden-wuerttemberg.de` answers normally. That host carries the BW
  topographic raster archive. Unverifiable is not the same as broken, so nothing from it
  was added.

The full survey is checked in at `docs/service-catalogue.md`: all 328 WMS/WMTS endpoints
these catalogues publish, each fetched and run through the acceptance rule, with what is
already shipped marked. It exists so the editorial pass — the slow part — can be made
from a list rather than from another crawl, and so the routes above do not have to be
rediscovered. Regenerating it means redoing the four catalogue queries in *Where it came
from* and re-running the check; the file records its survey date for that reason.

### The library is a tab, not a section of the import dialog

It first shipped inside the import dialog, shown while that dialog was Idle or Failed. A
screenshot from the device settled it: in landscape, four entries were visible out of
sixty, inside a modal that scrolls within a page that scrolls, above the layer list the
dialog exists to show. A review pass had flagged the same thing earlier and it was judged
out of scope at the time; it was not.

The split is by job. Choosing *a service* is browsing — long list, filtering, reading
notes. Choosing *layers out of one service* is a decision about a document that has
already been fetched. They belong on different surfaces, so the library is a tab with a
region filter and the dialog went back to doing one thing.

Adding from the library hands the URL to that same dialog, prefilled, and fetches
immediately. Nothing is short-circuited: the server is still asked and its capabilities
still decide which layers appear, so a library entry cannot assert that a layer works.
The entry only claims the service is worth asking.

Two consequences fell out of the move:

- **The dialog's state is held by `MainScreen`, not by either tab.** Both the Sources tab
  and the Library tab open it, and it has to survive a tab switch made while it is up. A
  nullable URL doubles as the open/closed flag and as the prefill.
- **The probing indicator moved up with it.** A source added from the library is measured
  after the dialog closes, and the tab it was added from is not where the user
  necessarily is by then. It now sits above the tabs, where it is visible from any of
  them.

Per-layer settings — flipped Y, referer, subdomains — deliberately did *not* move into
this flow. They are properties of a layer, not of a service, and a library entry names a
service.

### A count, not a status light

Proposed: a coloured dot per library entry — green for all layers working, orange for
most, red for few. Measuring first killed it. The library only ships services that pass
the acceptance rule, so roughly nine entries in ten refuse nothing at all and the dot
would have been green almost everywhere: a control that costs a row of pixels and varies
four times in fifty-nine.

What does vary is the size of the catalogue behind each entry, from one layer to 1196,
and that is what actually decides whether choosing a layer is a glance or a hunt. A
thousand-layer service is *green* under the ratio rule and is precisely the one that
wastes the user's time.

So each row carries `12 layers`, or `22/47 usable` where some are refused. The second
number appears only in the four cases that have one, which keeps the "say nothing when
there is nothing to say" instinct behind the dot while dropping the part that did not
work. The first wording was "22 of 47 layers", which states a ratio without saying what
it is a ratio *of* — the reader has to guess that the missing 25 are ones this proxy
cannot serve. "Usable" says it, and says it more honestly than "working" would: what was
measured is that the capabilities describe a layer this proxy can serve, not that a tile
was ever fetched from it. Text rather than hue also survives sunlight on a handlebar and does not depend on
colour vision — on this screen a digit is the stronger signal, not the weaker one.

Three things fell out of it:

- **The number is measured, never typed.** `check-library.py --update` writes it. The
  notes previously carried hand-written hedges — "very large layer list" — on exactly the
  two services someone had remembered to hedge. Those are gone; the count says it for all
  fifty-nine and stays true as the list grows.
- **Refused layer *titles* are not shipped.** Costed at a few kilobytes, and rejected:
  knowing which layers are missing does not help before you know which one you wanted,
  and the import dialog already lists them with reasons at the moment it does.
- **Storing every usable title would cost 231 KB** against a 12 KB asset, for a list
  nobody scrolls on a phone. Capped at 25 layers it would have been 8.5 KB covering 47 of
  58 services — a genuinely tempting shape, and still not worth a tap target the dialog
  already provides.

The count inherits the caveat the list already carries: it records what was true when the
service was last checked, and a provider can change theirs the next day.

### Coming back after a reboot, and the service type that allows it

The proxy is a navigation dependency, so a phone that reboots on a ride has to come back
serving tiles without anyone taking a glove off. What is stored is not a preference the
user sets but the fact that they started the proxy and have not stopped it since: a flag
on the stored config, set when the service starts and cleared only on a deliberate stop.

It is cleared in `onStartCommand`'s `ACTION_STOP` branch rather than in the companion's
`stop()`, so the notification's own Stop action counts as a stop too. Being killed by the
system deliberately does *not* clear it — that is not the user changing their mind.

**The service type turned out to decide whether this was possible at all.** Starting a
foreground service from the background is refused by default, and `BOOT_COMPLETED` is a
documented exemption — but Android 14 narrowed that exemption by type, and Android 15
narrowed it further. Checked against the behaviour-change docs rather than assumed, the
blocked list is `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` and
`microphone`. `specialUse` is not on it.

That type was picked at the start for an unrelated reason — Android 15 caps `dataSync`
foreground services at six cumulative hours, which would stop the proxy mid-journey. It
now pays twice: `dataSync` would have been refused at boot as well. Confirmed on the
device in both directions — a reboot with the proxy running brings it back, and a reboot
after stopping it leaves it stopped — which retires the one part of this that was read
out of documentation rather than observed. Worth recording
because the reasoning does not generalise, it just happens to line up, and a future change
of service type would silently break the reboot behaviour.

The receiver listens for `BOOT_COMPLETED` only, not `LOCKED_BOOT_COMPLETED`: the config
lives in credential-encrypted storage and is not readable before first unlock, and a
proxy that came up without its sources would be worse than one that waits.

### DMD Hub sign-in follows the app's request shape, renews by re-login

The DMD Hub tab authenticates against `app.advhub.net/api/ios/auth/login` for the sake
of a later step that pushes configured sources into DMD as custom map layers. Two
decisions shaped it.

**The request shape is copied from the DMD Android app, down to the User-Agent.** The
endpoint refuses a request that does not send `DMD-HUB-Android/1.0`, so that header is
not a nicety — it is a gate. The rest of the shape (JSON `{email,password}` body, the
`{success,error,message,data:{token,refresh_token,user}}` envelope) matches too, verified
against the working `dmdcli` client rather than guessed. The reverse-engineered flow lives
in `/home/sdk/dmdcli`; a wrong field name here fails silently, so the pure envelope parse
sits in `:core` under a JUnit test. The parse is `coerceInputValues`, not just
`ignoreUnknownKeys`: on a *successful* login the live server sends `"message":null`, and a
JSON null against a non-nullable field with a default is a decode failure unless coerced —
a trap no hand-written fixture reproduced, so it only surfaced against the real endpoint.

**The session is renewed by signing in again, not by exchanging the refresh token.** The
envelope carries a refresh token and the app uses it, but supporting both a refresh path
and a re-login path is two recovery mechanisms for one problem. So the account's password
is stored and, when an authenticated call returns 401, the credentials are replayed **once**;
if that still 401s the credentials are stale and the account is signed out rather than
retried into the ground. One path, one retry, then stop.

**The password is stored, so it must not be in the config JSON.** The credentials blob is
encrypted with an AES-GCM key held in the `AndroidKeyStore` (`SecureStore`) and written to
`dmd-credentials.bin`, separate from `sources.json` — which the user is invited to export
and share. This is the "secrets live in the Keystore" rule finally cashed out. GCM's
authentication tag means a truncated or tampered file fails to decrypt rather than
yielding a half-valid credential.

What ships now is sign-in, sign-out and a confirmed-live session indicator; the layer
sync that all of this exists for is the next step and reuses `DmdHub.request`, which
already carries the renew-once-then-sign-out logic.

### The layer sync: what the API can express, and what it only pretends to

The DMD tab now pushes the configured sources to the account as custom map layers. The
endpoint (`/api/ios/custom-layers`) has no partial update — a POST is a full replacement
of the account's custom-layer set. So the sync is GET → merge → POST: keep every foreign
layer verbatim (as raw JSON, in `DmdSync.mergeForPush`, so a field DMD adds later survives
the round-trip) and substitute only ours. "Ours" is matched by name *or* by our own
`cl_wmsproxy_<path>` id prefix — name because that is the handle the user reads and asked
to overwrite, id because it also clears a layer orphaned when a source was renamed. The
name is the source's **title**, not its path: the title is the speaking label in DMD's
list, and the user accepted that titles are not guaranteed unique.

**The `enabled` field is a write-only ghost, confirmed from the decompiled app.** DMD's
`CustomLayerSyncManager.parseServerLayer` reconstructs each layer from `id, name, url,
tilePath, keyName, apiKey, isWms, wmsLayer, wmsVersion` and never reads `enabled` or
`maxZoom`; on/off lives in a device-local pref (`enabled_custom_rasters`) that is never
synced. So an API "disable" is inert. The **Sync** switch therefore means *inclusion*, not
a flag: off leaves the source out of the pushed set, which is the only way the wire can
turn a layer off. We still write `enabled:true`/`maxZoom:19` on the layers we do push, only
so the form is byte-identical to what DMD's own `pushNow` produces.

**Direct mode is gated on genuine compatibility.** The **Direct** switch sends the
upstream URL instead of the proxy's, so the source works in DMD and the web planner without
this device. `TileLayer.directBlocker()` names what stops it — a flipped TMS row, quadkey,
`{s}` rotation, padded zoom or Referer, none of which DMD can express — and an incompatible
source keeps its Direct switch disabled rather than silently pushing a URL that would not
render. WMS was excluded at first and is allowed now; the section *Direct sync revisited*
below records why.

**`splitTemplate` is a faithful port of DMD's own `parseCustomUrl`**, so a layer we push is
indistinguishable from one DMD created: it uppercases the placeholders DMD recognises, maps
the WMTS KVP and alias spellings, and splits `url` from `tilePath` at the last `/` before
`{Z}` (or at `?` for a `{BBOX}` WMS). It is unit-tested in `:core` against the live Google
and proxy forms — owning a small copy of DMD's logic is cheaper than depending on its
lenient empty-`tilePath` fallback and hoping it stays.

**The per-source choices live outside `sources.json`.** They are stored in a separate
`DmdSyncPrefs` (SharedPreferences, keyed by source path), not on `TileLayer`, so a proxy
source stays a pure proxy concept and editing a source does not disturb its sync choices. An
absent entry is the default (Sync on, Direct off), so a newly added source syncs through the
proxy with no interaction.

### Empty on first run, and the Log folded into Settings

With the Library tab in place as the way in, the two placeholder sources shipped on a
fresh install stopped earning their keep — they pointed at courtesy hosts that could stop
answering, exactly the failure mode a built-in should not carry. `SourceConfig()` now
starts empty, `object BuiltInSources` and `restoreDefaults()` are gone, and the "Restore
starting sources" button with them. An empty stored list was already a real state the init
path distinguishes from a missing file, so nothing had to change there.

The tab bar lost a tab in the same pass: **App** became **Settings**, and the request log
moved onto it rather than standing alone. The version and update controls are occasional
business, the log is something you glance at — one tab holds both. The layout constraint
that shaped `SettingsTab` is that a `weight(1f)` LazyColumn cannot sit inside a
`verticalScroll` Column, so the tab is a plain Column: a fixed top block (version, divider,
`UpdateSection`) above `LogSection`, which keeps its weighted list and follow-tail logic.
Landscape is the primary orientation and has room for both; portrait is tighter, which is
accepted. The log's per-tab count went away with the standalone tab.

### A WebMercator tile matrix set is not necessarily indexed by zoom

`parseWmts` took `linked.firstOrNull { it.isWebMercator }` and substituted the requested
zoom into that set's identifier template. Both halves are assumptions, and basemap.de
breaks both: its layers link four matrix sets, and the first WebMercator one,
`DE_EPSG_3857_ADV`, has fourteen levels named `00`–`13` whose scale denominators say they
are zooms **5–18**. Asking it for level `05` returns zoom 10 — the wrong scale over the
wrong ground, which is precisely the failure the no-reprojection rule exists to prevent.
The same document publishes `GLOBAL_WEBMERCATOR`, twenty levels, correctly numbered; the
parser simply never looked at it.

So the selection now filters before it picks: a set qualifies only if level `N` really is
zoom `N`, and among those the deepest wins. A layer whose only WebMercator set is offset
is skipped with a reason rather than served wrongly.

**The scale denominator is what proves it, and nothing else does.** The first attempt at
detection compared `MatrixWidth` against `2^N` and flagged four shipped USGS services as
misaligned. They are not: ArcGIS pads its matrices to `2^z + 1` — 2, 3, 5, 9, 17, 33 —
while numbering levels correctly. Checking the geometry the set itself declares put the
displacement at 0 m. A heuristic that condemns four working services on its first run is
not a detector, and the episode is worth keeping: the authoritative field was there all
along.

A set that declares no denominators is taken at its word. The field is required by the
spec, so its absence is a loose server rather than evidence of an offset — and every
existing fixture omits it, which is exactly the population that must keep working.

The Python checker mirrors the same rule. It had passed basemap.de's offset grid without
noticing, which would have put a silently-wrong source in the shipped list with a
measured layer count beside it.

### The proxy decides which address it hands out

The choice between the plain and the HTTPS template was made at each caller: the source
list picked by the switch, the DMD sync picked by the switch, and the root page never
looked — it always printed the plain address. Three sites, one of them wrong, for a
decision that belongs to the thing serving the addresses.

`ProxyServer.templateFor` now reads the switch itself, from the same config lookup that
already answers tile requests, and `secureTemplateFor` is gone. A caller that wants a
source's URL asks for it and gets the right one; there is no second function to pick
wrongly. The listeners themselves are unchanged — both still run — only the address the
proxy advertises follows the setting.

The Sources tab takes the whole `SourceConfig` rather than its layer list for the same
reason: the URLs it shows depend on the switch, so the switch has to be something the
tab was given, or a flip would not redraw the list.

### DMD Hub's native WMS mode, and what the proxy sync overwrites

Observed 2026-09-20 by fetching `/api/ios/custom-layers` before and after a proxy sync.

**The field schema for a WMS custom layer:**

```json
{
  "id":         "cl_mt5hvcfw_l1y5slwg",
  "name":       "Baustellen WMS Verlauf",
  "url":        "https://maps.mobilitaetsatlas.de/geoserver/ows",
  "tilePath":   "?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS=mwvlw:verlauf&STYLES=&CRS=EPSG:3857&FORMAT=image/png&TRANSPARENT=true&WIDTH=256&HEIGHT=256&BBOX={BBOX}",
  "keyName":    "",
  "apiKey":     "",
  "isWms":      true,
  "wmsLayer":   "mwvlw:verlauf",
  "wmsVersion": "1.3.0",
  "enabled":    true,
  "maxZoom":    19
}
```

DMD concatenates `url + tilePath` and substitutes `{BBOX}` with the tile's bounding box.
The WMS version, CRS, format, and tile size are all hardcoded into `tilePath`; DMD does
only the string replacement. `wmsLayer` and `wmsVersion` are metadata stored alongside
`tilePath` but redundant with it — `parseServerLayer` in the decompiled app reads the
full set, but the data that actually drives the GetMap request is in `tilePath`.

**DMD can consume public WMS services natively, without the proxy.** The three Baustellen
layers (`mwvlw:verlauf`, `mwvlw:umleitung`, `mwvlw:baustelle`) from
`maps.mobilitaetsatlas.de` were configured this way in the account before the proxy
existed. They rendered correctly because the service is public, speaks EPSG:3857, and
DMD's fixed `CRS=EPSG:3857` in `tilePath` happens to be what the server wants.

**A wrong inference, kept as a caution.** When the hand-added layers were not found in
the account after a proxy sync, the first reading was that the sync had replaced them.
It had not: the user had removed them earlier and restored them later the same day.
`mergeForPush` keeps every layer that is not ours — matched by name or by our own id
prefix — and no loss to a sync has been observed. What remains true is the mechanism
that made the inference plausible: the POST replaces the account's full set, so the
merge is the only thing standing between a sync and the user's other layers.

**What the proxy does that DMD's WMS mode does not:** version negotiation (`SRS` vs
`CRS`, axis-order flip in 1.3.0), TileMatrix selection for WMTS, TMS y-flip, subdomain
rotation, padded zoom, Referer spoofing, and eventually authentication. For a public
WMS that already speaks EPSG:3857 with correct axis order, DMD's native mode is
sufficient and the proxy adds nothing.

**Direct WMS follows from this.** The observed tilePath format (`CRS=EPSG:3857`, no axis
swap) confirms DMD 1.3.0 does *not* flip axes — it sends `BBOX=minx,miny,maxx,maxy`
regardless. That is correct for EPSG:3857 (which is not geographic) and wrong for
EPSG:4326 (which is, under 1.3.0). Since the proxy only serves WebMercator, a direct WMS
push is correct for every source it handles; the next section records enabling it.
### Direct sync revisited: WMS passes, padding is measured, the caption says why

Three sources the user had run in DMD by hand — MobiData-BW, the Rhineland-Palatinate
Mobilitätsatlas and TopPlusOpen — showed a disabled Direct switch. Two rules were responsible, and each was
stricter than its reason.

**WMS.** The exclusion said DMD's WMS mode does no version or axis-order handling. True,
and beside the point: the axis-order trap exists only for geographic coordinates under
1.3.0, and DMD draws WebMercator and nothing else, so the bbox it substitutes is
EPSG:3857, which is x-first under both versions. The version, the spelling of the CRS
parameter and the layer name are fixed in the template we already store and travel with
it; DMD's own URL parser accepts a `{BBOX}` template and marks it WMS, which
`splitTemplate` already ported. So a WMS source passes. The form DMD writes for a WMS layer
of its own, observed in the section above, is the same split — `url` the endpoint,
`tilePath` the query ending in `BBOX={BBOX}`, and only the bbox substituted — so the
pushed entry is indistinguishable from one DMD made. It carries `wmsLayer` and
`wmsVersion` too, read back out of the template's query, because DMD stores them beside
the path even though the path is what drives the request.

**Padded zoom.** TopPlusOpen names its levels `00`–`18`, so the import stores `{z:02}`,
which DMD cannot substitute. Fetched both ways, the live host answers `5` and `05` with
the same bytes. The rule is right in general — the journal above records servers that
return nothing for an unpadded level — and wrong for this server, and the only way to
tell is to ask. So the import asks: for a padded template, one extra fetch of the
plain-zoom form at the shallowest usable level, and the plain form is stored when it
answers. It is a measurement, like the zoom range, not a guess; the existing rule then
allows Direct with no special case. The two spellings differ only where the level has
fewer digits than the padding, so when the shallowest usable level already reads the same
both ways there is nothing to ask and the plain form is taken as is.

Confirmed on the device the same day: TopPlusOpen re-added from the Library came back
with plain `{z}` and an enabled Direct switch, and all three sources render in DMD
without the proxy.

**The caption.** "Needs the proxy" answered nothing when the user asked why. The card now
says what the source needs — flipped rows, a Referer, subdomain rotation, quadkeys or
padded zoom levels — from the same `directBlocker()` the switch is gated on, so the two
cannot disagree.

**The log carries the account's own layers.** The connection check already fetches the
account's custom layers to prove the token works; the ones that are not ours now go into
the request log verbatim, one line each. That is what the next sync's merge will see, so
a layer that seems to go missing can be checked against what the account actually held —
the section above records how easily that is misread — and it is where a form this app
does not yet reproduce shows up first.

### The sync, tidied: one failure channel, one direct decision, three states

A cleanup pass over the DMD sync, recorded because three of its choices are the kind a
later change would otherwise undo.

**`DmdHub` throws, and the exception type says what is left.** It had three failure
channels — `Result` from sign-in, a thrown exception from requests, a status code the
caller compared to 2xx — and the view model handled all three in each of three places.
Now every call throws: a `DmdAuthException` means there is no session any more (never
signed in, refused, or signed out because renewal failed), and anything else means the
network or the server, with the session left standing. That distinction is the whole of
what the tab needs to decide between showing the form and showing an error, so the view
model no longer inspects the session after the fact. `fetchLayers` and `pushLayers` own
the path and the success rule; the view model knows no HTTP.

**`sendsDirect` is the one place the Direct decision is made.** The switch on the card and
the push both computed "asked for direct, and nothing blocks it" on their own. One
extension on `TileLayer` in `:core` now answers it, `layersFor` builds the pushed set
from it, and both are under test — which `buildLayers` in the view model never was.
`directBlocker` is a plain extension too, kept in `DmdLayers.kt` rather than on
`TileLayer` because it encodes what DMD can substitute, not what a source is.

**Three status states, not five.** `Connected` and `Idle` rendered identically, and
`SigningIn` and `Checking` were told apart only on a screen where one of them cannot
occur. What the tab distinguishes is idle, busy and failed, so that is the enum.

**Smaller things worth knowing about.** The template splitter maps only the placeholders
a saved template can carry; DMD's aliases for hand-pasted URLs (`{zoom}`, `{TileMatrix}`
and the like) cannot reach it, because `SourceValidator` refuses them and the WMTS import
spells its own in `{z}`/`{x}`/`{y}`. The request log has a proper note entry for lines the
app writes about itself, used by the listener's start failure and by the account-layer
line, which is now one entry per check rather than one per layer. The OkHttp client for
the hub is built on first use, since building one loads the system trust store and most
launches never sign in. Sign-in persists its credentials on IO, where the Keystore work
belongs.

**Left alone, on purpose.** The sync choices stay in SharedPreferences: moving them to a
JSON file would lose every choice made so far for the sake of one fewer storage idiom.
A renamed source still reverts to the default choice, as the class doc says. The card's
two switches keep their own compact label-first rows rather than the standalone
`LabelledSwitch`; the reason is now written on the card.

### WCS 2.0 calls its root element `Capabilities`, exactly as WMTS does

A LANDFIRE coverage service pasted into the import reported "No layers in that
document". It had answered correctly: the document was WCS 2.0, whose root element is
`Capabilities` in the `wcs/2.0` namespace, and the parser dispatched on the local name
alone, read it as WMTS, and found no `Layer` elements. A WFS fell through to the generic
"not a WMS or WMTS document", which is true and unhelpful in the same way.

The dispatch now looks at the namespace where the local name is ambiguous, and both
coverage and feature services are refused by name with a pointer to the WMS the same
server usually offers — on GeoServer the URL differs only in `wcs` versus `wms`. The
checker mirrors the refusal, per the drift rule. Refusing is the right answer rather than
reading coverages as layers: a WCS returns data arrays, not images, and the proxy does
not draw.

### ArcGIS REST is a third upstream kind, proved from the service's own description

PAD-US loaded unreliably. Its library entry was ArcGIS Online's WMTS document, and
fetching the same tiles both ways showed why: byte-identical tiles, 0.3–1.9 s through the
WMTS wrapper against a steady 0.3 s from the cache's own `tile/{z}/{y}/{x}` endpoint. The
wrapper is an extra layer in front of the same cache, and it is the layer that wobbles —
past the five-second tile budget, a wobble is a refused tile.

Two ways to reach the direct endpoint were on the table. Rewriting the WMTS template's
path (`/WMTS/tile/1.0.0/…` to `/tile/`) would have worked for the five ArcGIS services in
the library and silently mis-served the first server whose WMTS matrix set is not its
native cache. So instead the description ArcGIS publishes at `MapServer?f=json` is a
capabilities document in its own right, and it states everything needed to prove the
grid before accepting it: `singleFusedMapCache`, 256-pixel tiles, spatial reference
102100/3857, the origin at the WebMercator corner, and per-level resolutions halving from
156543 m/px. The last is the same proof the WMTS path applies to scale denominators, in
the unit ArcGIS uses. Any of the five missing is a refusal with the reason.

Two consequences for the parser: it now sniffs the first byte and takes a JSON body to
the ArcGIS branch, and it takes the source URL, because the description names no
address of its own — the template is the fetched URL minus its query plus `/tile/…`.
`parse(stream)` callers that have no URL still work; only the ArcGIS branch needs it.

The checker mirrors the rule, per the drift rule, and measured the new entries through
the same JSON branch. The library keeps PAD-US and the four USGS basemaps both ways for
now, at the user's request, so the two paths can be compared on the device. The USFS
Motor Vehicle Use Map was asked for as well and refused by the import as it should be:
`EDW_MVUM_02` is a dynamic map service with no tile cache, so there is no tile endpoint
to reach, and its WMS is already the equivalent of ArcGIS's own render-on-demand
`export` request.

### Three addresses per source, chosen when copying

The *Serve over HTTPS* switch was a global answer to a question asked per paste. It was
introduced because showing both proxy addresses on every row was two lines and two
buttons repeating themselves; with Direct sync in play there is now a third address, the
source's own, and it is the one the user pastes most. A setting cannot pick per paste,
and a row cannot show three addresses. This reverses *The proxy decides which address it
hands out* above: the switch that section centralised is gone, and `SourcesTab` is back
to taking the layer list.

So the row shows the source's own address, and Copy opens a menu of three: direct, proxy
over HTTPS, proxy over HTTP. The switch and the `useHttps` field are gone; the field is
an unknown key in an older `sources.json` and is ignored by the codec, per the
no-migrations rule. Both listeners always ran, so nothing changes on the wire.

`ProxyServer.templateFor` now means the HTTPS address without qualification, because that
is the one that counts: DMD refuses cleartext to loopback, so it is what the sync pushes
and what the root page lists. `plainTemplateFor` is the plain listener's, kept for a
client that refuses the certificate.

A cleanup pass had the card withhold the direct address for a flipped or padded source,
by the rule the DMD tab's switch is gated on. The user reversed that: the direct address
is always offered, and the row instead says what the proxy does for the source — `Proxy:
inv Y → Y` — so a reader can see what a direct paste would lose and decide. That turned
the one "blocker" into the full list, `TileLayer.rewrites()`, with WMS bbox among them
as the one rewrite DMD can do itself; `directBlocker()` is now the first rewrite that
DMD cannot. The DMD tab keeps the gate, where a wrong choice would push a dead layer.

A second cleanup pass moved `rewrites()` out of `DmdLayers.kt` and next to `urlFor`,
where every rewrite but the Referer is performed: a list of what the proxy does belongs
with the proxy, and only the exception — the one rewrite DMD substitutes itself — is
DMD's to state. The same pass made the import dialog's layer list lazy. It composed every
discovered layer at once inside a scrolling column, which was fine for a dozen layers and
stalled for seconds on a national service publishing a thousand; the dialog is now two
shapes, URL-and-fetch or name-and-list, instead of a five-way switch on the import state.
The three copies of a button-that-opens-a-menu — the copy menu and the two library
filters — became one `PickerMenu`, and `Sources` updates its flow atomically and
serialises its writes, so the import batch adds what it measured from the IO thread
instead of hopping back to the main one per layer.

### Roadworks by state: what the national catalogue turned up

Asked for roadworks and diversions beyond Baden-Württemberg, Rhineland-Palatinate and
Karlsruhe, the national catalogue (`gdk.gdi-de.org`, CSW `GetRecords` over the German
terms) yielded state-level WMS for Schleswig-Holstein, Hamburg, Saxony, Brandenburg and
Mecklenburg-Vorpommern, all measured and shipped; `docs/service-catalogue.md` has the
table, the misses included. Two things worth keeping: Schleswig-Holstein's service is a
northern-Germany aggregate — it carries Hamburg's, Lower Saxony's and
Mecklenburg-Vorpommern's roadworks beside its own — and Hamburg publishes four separate
services (roadworks, motorway diversion routes, live traffic, police reports), each
shipped on its own because each is a different answer to a different question.

### The account dump: the wire form, read rather than assumed

A PAD-US tile service synced Direct showed an error in DMD, while the same address
pasted into DMD by hand worked. `splitTemplate` is a port of DMD's parser, but a port is
a claim, and this is the first source whose template puts `{y}` before `{x}` and ends
without an extension — `…/MapServer/tile/{z}/{y}/{x}`, split by us into
`url = …/MapServer/tile` and `tilePath = /{Z}/{Y}/{X}`. Rather than guess what DMD
makes of the pasted form, the Settings log gained **Log DMD layers**: it reads the
account's custom layers and writes each one into the log verbatim, ours included, so the
layer DMD wrote sits beside the one this app pushed and the difference can be read off.
`DmdSync.accountEntries` is the whole set; `foreignEntries` remains the filtered one the
sign-in check logs. Also measured while looking: the ArcGIS tile endpoint answers a
tile address with `.png` appended with HTTP 200 and an HTML body, so any client that
adds an extension gets a "tile" that is not one.

**What the dump showed, and what changed.** DMD's own entry for the pasted address was
`"url": "…/MapServer/tile/{z}/{y}/{x}"` — the whole template, placeholders as typed —
with **no `tilePath` key at all**, `isWms: false`, `wmsVersion: "1.1.1"`. Ours had the
same address split into `url = …/tile` and `tilePath = /{Z}/{Y}/{X}`. So the earlier
"faithful port of `parseCustomUrl`" split something DMD does not split: DMD has two
forms, by `isWms`. A WMS layer is the endpoint plus a `{BBOX}` query in `tilePath` — the
form observed on 2026-09-20 and the one our WMS layers already used. A tile layer is
one string. `addressFor` now produces exactly those two; `DmdLayer.tilePath` is
nullable and omitted when null (`explicitNulls = false`), because an explicit null would
be a third form neither side has seen. The split form is not gone from DMD — TopPlusOpen
rendered under it, with the same row-before-column order — so the trigger was the
missing extension rather than the order, consistent with the `.png` probe above. The
form DMD writes is the one to send; which of DMD's parsers tolerates what is not ours
to depend on.

**DMD's dialog writes WMS the same way: no `tilePath`.** A second dump, after the user
added the same Arbeitsstellen layer in DMD's own dialog as WMS, showed
`"url": "https://api.mobidata-bw.de/geoserver/ows?service=WMS&version=1.3.0&request=GetCapabilities"`
— the capabilities address as pasted — with `isWms: true`, `wmsLayer` and `wmsVersion`
set and no `tilePath` key. So the phone app never writes `tilePath`; the WMS entries
observed on 2026-09-20 with a GetMap query in `tilePath` were written by something else
(the account was restored from outside the app that day), and DMD's reader takes both.
The first instinct was to keep the `tilePath` form for WMS, because it carries the
exact request the import measured — the raster format the server offers, `SRS` versus
`CRS`, the `900913` spelling where that is all a server knows — where DMD's own form
hands all of that to DMD's fixed `image/png` and `EPSG:3857`. The user asked for proper
DMD entries instead, and that is the better call: a form DMD only tolerates is one DMD
can stop tolerating, and the tile-layer failure above was exactly that. So
`toDmdLayer` now writes what a user would have pasted — the endpoint, any parameter
that belongs to the service rather than the request (MapServer's `map=`), then
`SERVICE`, `VERSION` and `REQUEST=GetCapabilities` — with `wmsLayer` and `wmsVersion`
beside it and no `tilePath`. `DmdLayer` lost the field altogether, so its fields come in
DMD's own order. What the measured request protected is protected by the gate instead:
`Rewrite.WMS_FORMAT` and `Rewrite.WMS_CRS` are listed for a template whose format is not
`image/png` or whose CRS is not spelled `EPSG:3857` — the import prefers both whenever a
server offers them, so either in a template means the server did not — and both block
Direct, with the row and the DMD caption saying so. A template that names neither is
left alone: unspecified is not different.

The same dump showed every pushed WMS layer carrying `SERVICE=WMS&SERVICE=WMS`:
GeoServer publishes its GetMap endpoint as `…/ows?SERVICE=WMS&`, and `wmsTemplate`
appended its own. Harmless to the servers, but not what DMD writes. The endpoint's
SERVICE, VERSION and REQUEST parameters are now dropped before the template sets them,
for WMTS KVP as well; anything else it carries, such as MapServer's `map=`, stays.

### The probe: DMD's substitutions read off the log, not guessed

Whether DMD substitutes `{BBOX}` into a plain tile layer decides whether a WMS source
could be pushed as a tile layer carrying the measured GetMap — the server's own format
and CRS spelling inside the address, no gates needed — and nobody can read DMD's
renderer to find out. So the proxy gained `/probe`: any path, any query, answered with
the blank tile (200, so DMD keeps the source) and recorded in the log verbatim.
`ProxyServer.probeTemplate` is an address naming every placeholder a tile client has
been seen to substitute, one per query parameter; *Copy probe URL* on the Settings tab
hands it over. Pasted into DMD as a tile layer, each request shows which names DMD
filled and with what, the rest left as braces. With the WMS box ticked, it shows the
exact GetMap DMD composes — the 1.1.1 form included, which the account dump could not
show. The endpoint never touches the network and returns nothing a client would cache
as a map, so it costs the one rule nothing.

### The dialog's WMS form did not render; the measured one does

The `dev` build carrying DMD's dialog form for WMS — capabilities address in `url`,
`wmsLayer` and `wmsVersion`, no `tilePath` — was tried on the device: the Baustellen
sources that had rendered Direct since the sync began stopped, while PAD-US, pushed as a
whole address, rendered for the first time. So the two halves of the dump led to
different places. For a tile layer the dialog's form is the one that works. For WMS the
form that works is the one DMD's dialog does *not* write: the endpoint in `url` and the
whole measured GetMap query in `tilePath`. What DMD does with a capabilities address in
`url` and no `tilePath` — whether it composes a GetMap at all from a hub entry, and
whether the parameter case of `request=GetCapabilities` matters — is unknown, and the
probe with the WMS box ticked is how to find out before trying that form again. The WMS
push is back to the `tilePath` form, and the two gates that only existed for DMD
composing its own request went with it: with the measured request travelling whole,
the server's format and CRS spelling are already in the address.

### The probe answered: `{bbox}` is a placeholder DMD fills in any address

Pasted into DMD as a tile layer, the probe address came back with both `bbox` and
`BBOX` filled — `0.00,5009377.09,1252344.27,6261721.36` and so on, EPSG:3857 metres to
two decimals, `minx,miny,maxx,maxy`, tile-aligned at every zoom panned through. The
rest of what DMD did to the address: `{z}/{x}/{y}` uppercased to `{Z}/{X}/{Y}` and
`{zoom}`, `{TileMatrix}`, `{TileRow}`, `{TileCol}` mapped onto them, none of them
filled, because an address with a bbox takes the bbox path; `{r}` removed; `{-y}`,
`{q}`, `{quadkey}`, `{s}`, `{ratio}`, `{scale}`, `{width}`, `{height}`, `{proj}`,
`{crs}`, `{TileMatrixSet}`, `{Style}` and every key spelling left as braces. The
requests arrive as `DMDPlayGround/1.0`.

So on the phone a WMS source is a tile source whose address has a bbox in it, and the
sync briefly sent every source as the whole template in `url` and nothing else.

**The second reader.** The account is read by two programs, not one: the DMD app on the
phone and the route planner on the web, and the planner fills `{bbox}` only for a layer
with `isWms` true. A WMS GetMap as one address rendered on the phone and went out from
the planner with a literal `{bbox}`. So the WMS form went back, for good, to the one the
planner itself writes — endpoint in `url`, GetMap query in `tilePath`, `isWms` true,
`wmsLayer` and `wmsVersion` beside it — which the phone has rendered since the sync
began. Four forms in a day: the split `url`/`tilePath` for everything (phone: WMS and
extension-bearing tiles yes, ArcGIS no), the phone dialog's WMS form (neither reader,
from the hub), one address for everything (phone yes, planner no for WMS), and the
one that stands: one address for tiles, the planner's split for WMS. The lesson is not
which form won but the test that decides: a form is proven when both readers render
it, and matching what one of them writes proves nothing. The account dump and the probe
made this a day rather than a month, and both stay in the app.

**The probe in the planner** (its requests are visible in the browser's network tab
even when loopback refuses them) settled how the planner reads a WMS layer. Its parser
normalised the pasted address as the phone's does — `{z}` to `{Z}`, `{zoom}` and the
WMTS names onto `Z`/`Y`/`X`, `{r}` dropped, `{bbox}` to `{BBOX}` — split it at `?` into
`url` and `tilePath`, and set `isWms` on seeing `{BBOX}`. Then it sent
`…/probe?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=&STYLES=&SRS=EPSG:3857&FORMAT=image/png&TRANSPARENT=true&WIDTH=256&HEIGHT=256&BBOX=958826.08…`:
not one placeholder from `tilePath`, but a GetMap of its own from `url`, `wmsLayer`
(empty here) and `wmsVersion`. So the planner never uses `tilePath`; it needs a bare
endpoint, a layer name and a version, and the form the sync writes carries all three
with the `tilePath` beside them for the phone. In the planner the server is asked for
`image/png` in `EPSG:3857` whatever the import measured, so the rare server that only
speaks an alias or an 8-bit PNG renders on the phone and not there — noted, not gated,
since the planner is the reader that is skipped when it comes to it.

**And a wrong inference corrected the same hour.** The phone's probe had filled the
bbox, and that was read as "the phone fills `{bbox}` in any address", so for a moment
only a WMS GetMap was marked `isWms` and an ArcGIS export went as a plain address. The
MVUM requests then left the phone with `{bbox}` still in them. Re-read, the phone's
probe request says something else: every `tilePath` parameter was in it, `{Z}`/`{X}`/
`{Y}` unfilled, bbox filled — concatenation of `url` and `tilePath` plus replacement,
in WMS mode, which the phone's dialog had set by itself on seeing `{BBOX}` (never
dumped, inferred from the planner doing exactly that). So `isWms` means "replace the
bbox", the user's reading, and every template with a bbox carries it. The export renders
on the phone and not in the planner, whose composed request is a WMS one; accepted.

### A "no layers" report that was the server's doing, not the parser's

Reported 2026-09-21: the USFS Motor Vehicle Use Map imports as "No layers in that
document". Checked in this order, because the parser had changed four times that week:
the Python checker, an independent implementation of the acceptance rule, also finds
zero layers now and measured ten on the 19th; the document itself is 5 KB, names no
layer under 1.3.0 or 1.1.1 and declares no CRS; the service's REST description shows
ArcGIS Server 11.5 with all twelve map layers, so the service was republished and its
WMS capabilities came back without layer names. A `GetMap` for `LAYERS=1,2` still
returns a PNG, so a source imported before the change keeps working; only a fresh import
fails. The library entry now carries the measured zero rather than the stale ten, and
goes back up when the checker next sees names. The order of checks is the point of this
note: when the app reports what a server sent, run the checker before reading the
parser.

The same look showed the way back in: the service's REST `export` endpoint answers a
`bbox=…&bboxSR=3857&imageSR=3857&size=256,256&f=image` request with a PNG, a `{bbox}`
template like any WMS GetMap. So `parseArcGis` no longer refuses a service without a
tile cache; `parseArcGisDynamic` offers one export template per leaf layer, verified
against the National Map's transportation service (a 156 km tile of its 1M-scale roads
came back with content) since MVUM's own data source was down that hour: every export
and GetMap from it was a blank 256-pixel PNG and its feature queries failed. Blank is
the one thing the proxy must never relay as a map, so the library entry moved to the
REST description — ten layers measured, the count the WMS had — and renders as soon as
USFS reconnects their data. Two design points: the layer's numeric id is the
identifier, because that is what `layers=show:` takes and it survives a rename where a
name does not; and the parent group's name prefixes the title, because MVUM has "Roads"
twice, under two symbologies, and a list of identical titles helps nobody.

### The planner's source is public, and it ends the guessing on its side

`https://hub.dmdnavigation.com/planner/app.js` is served without a login. Its
`_buildCustomTileUrl` is the whole story for the web reader: `url` goes verbatim into a
MapLibre raster source (`tiles: [url]`, `tileSize: 256`, `maxzoom: cl.maxZoom || 19`),
so the placeholders it fills are MapLibre's — `{z}`/`{x}`/`{y}` lowercase, `{quadkey}`,
`{bbox-epsg-3857}` — and nothing else; `keyName=apiKey` is appended when both are set;
for `isWms` it appends a GetMap of its own from `wmsVersion` (`SRS` under 1.1.1, `CRS`
under 1.3.0) and `wmsLayer`, in `image/png`, 256 px, `BBOX={bbox-epsg-3857}`, joined with
`&` when the address already has a query. `tilePath` does not occur in the file. The
dialog stores `{id, name, url, keyName, apiKey, isWms, wmsLayer, wmsVersion, enabled,
maxZoom}` with a `cl_<base36 time>_<random>` id, insists on `{z}` in a non-WMS address,
and its *Detect* button fetches capabilities and lists `Layer > Layer` names. Custom
layers travel with the rest of the map preferences (`custom_layers` in
`_collectAllMapPreferences`), server as source of truth on load. `enabled` is the
planner's own on/off switch, read and written; `maxZoom` is honoured.

Two things followed, and both were undone within the hour, which is worth keeping.
`maxZoom` briefly carried the source's measured maximum, so the planner would scale the
deepest real tile instead of collecting errors past it; the next dump showed `8` on the
three USGS basemaps, whose caches go to 16. The probe aims at the centre of a service's
declared extent, and for a nationwide service that is open water, where deep tiles do
not exist — the debt CLAUDE.md records, now with a second cost. Back to DMD's 19 until
the probe can be trusted. And a probe entry that came back split at `?` with uppercased
placeholders was read as the phone rewriting every account entry on sync; the same dump
showed every entry this app pushed coming back byte for byte after both readers had
synced, so the split was the work of whichever dialog the probe was pasted into. The
"rewrite hazard" is withdrawn.

**An export source goes twice.** With no rewrite, a planner-only form survives in the
account, and the user's proposal stands: since the phone fills a bbox only in an `isWms`
layer and the planner fills one in a plain layer only through MapLibre's
`{bbox-epsg-3857}`, an ArcGIS export cannot be one entry for both. `layersFor` now
pushes such a source as *name (DMD App)* in the phone's form and *name (Hub Planner)* as
a plain address with `{bbox-epsg-3857}`, under the id suffix `_planner`. Each reader has
its own on/off — the phone's local, the planner's in the entry — so the rider switches
the foreign one off in each place once. WMS and tile sources stay one entry: both
readers render them.

### Both readers render the form, measured

2026-09-21, after the build with the planner's form and the measured maximum zoom, on
the phone and in the planner alike: PAD-US and the four USGS basemaps (tile services,
one address each), the LANDFIRE CONUS layers (WMS, endpoint plus `tilePath`) and the
Baustellen sources render in both. Two things do not, for reasons outside this code.
Some WMS servers answer the planner's tile requests without an
`Access-Control-Allow-Origin` header, and MapLibre needs one to draw a raster tile it
fetched, so those layers render on the phone and not in the browser; the server's
policy, not a request form. And the MVUM export layers cannot render in the planner at
all, because for an `isWms` layer it composes a WMS GetMap and the export endpoint is
not a WMS; on the phone their form is right and they wait on USFS, whose roads layer
answers its description again but still fails every query and draws every tile blank.

### A feature service is not a map service, and the description says which

The PAD-US 4.1 fee-manager service was asked for by its ArcGIS Online address, and it is a
`FeatureServer`: `capabilities` is `Query`, the export formats are CSV, shapefile and
GeoJSON, and `…/FeatureServer/export` answers 400. It serves shapes and attributes for a
client to draw, which the proxy does not do. The same organisation publishes the 3.0
edition as hosted tile layers (`MapServer`, `Map,TilesOnly,Tilemap`) and the 4.x edition
only as hosted feature layers, which is ArcGIS Online's default form for new data; USGS's
own server answered 502 while this was checked. So the library gets the 3.0 fee-manager
tile service, and 4.x appears when a map service or WMS of it does.

The finding underneath: both the parser and the checker accepted the feature service. The
drift rule held — they agreed — and both were wrong in the same way, because a description
without `singleFusedMapCache` takes the drawn-on-request branch, and a feature service
lists layers just as a dynamic map service does. The result was an `export` template the
server cannot answer: a green import for a layer that never draws, the failure mode the
acceptance rule exists to prevent.

Both now refuse on the `capabilities` field: a map service lists `Map` (cached ones
`Map,TilesOnly,…`, drawn ones `Map,Query,Data`), a feature service `Query`, an image
service `Image,…`, and only the first draws. The field is judged rather than the URL's
`MapServer` segment because the description is what the rest of the branch trusts. Its
absence passes, since old servers omit it and turning every one of them away would trade
one false acceptance for many false refusals. The refusal names what the service offers
and points at a `MapServer` of the same data or, for an image service, the WMS it usually
carries at `…/ImageServer/WMSServer` — which is how the 3DEP elevation entry already ships.

The current edition turned up a day later, on USGS's own server rather than ArcGIS Online:
`edits.nationalmap.gov/arcgis/rest/services/PAD-US` holds four map services drawn on
request, none cached and none with a WMS (the `WMSServer` path answers the REST
directory's HTML). `PAD_US_4_1` is the 4.1 fee layer coloured by manager name, 31 classes,
transparent where nothing is protected; it answered the zoom-13 export over Schnebly Hill
Road in 0.7 s with the Forest Service green over Coconino National Forest, and zooms 5 to 8
in 0.6–1.9 s, inside the tile budget. `PAD_US` is the same renderer over 4.0. The other
two, `PAD_US_gaz_combined` and `PAD_US_Landforms`, paint an opaque background under the
polygons and took 2–15 s per tile; they would cover DMD's map and miss the five-second
limit, so they stay out. The library ships both fee-manager editions: the cached 3.0 tile
service, because it answers in 0.3 s and is one address both DMD readers fill, and the
drawn 4.1 service, because it is current — it takes the dynamic ArcGIS path and so syncs as
the two-entry pair. The notes say which is which in a rider's terms, edition and speed.

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
