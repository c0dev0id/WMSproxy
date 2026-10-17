# Decisions

Each entry gives the decision, its context and its rationale. Most of them are also
explained in the KDoc of the code they concern.

## Scope

### WebMercator only, no reprojection

Only EPSG:3857 is ever requested. Tiles are cut on the WebMercator grid, and nothing in
the project reprojects. A layer an upstream cannot serve in WebMercator is skipped with
a reason, not fetched in another CRS and labelled wrongly. As a side effect, the WMS
1.3.0 axis-order rule does not apply: it concerns geographic CRSs, and WebMercator is
projected, so the bounding box is x-first in 1.1.1 and 1.3.0 alike.

### Requests are rewritten, never re-rendered

The proxy fills in a request string and relays the bytes it gets back. No image is
decoded on the data path. A WMS server becomes an upstream through `{bbox}`, which is
arithmetic from the tile index, not a reprojection.

### Loopback only

The proxy binds to `127.0.0.1`. It forwards to upstreams without asking the client for
any credentials, so it must not be reachable from the network.

## Proxy

### A failure is a 502, never a blank tile

Clients cache what they receive. A blank tile returned for a failed request would be
cached as real data and remain as a hole in the map.

### Only raster images are relayed

A 200 response is not proof of an image. HTML error pages, exception reports, vector
tiles and SVG all arrive as 200 with a body, and the client would cache them. Any
raster type passes byte for byte, including formats the client may fail to decode.
Judging what the client can draw would mean modelling its decoder. A rule for a single
format is added only on evidence. A missing content type is refused.

### Two listeners, plain and TLS

DMD refuses cleartext to loopback, so it gets the HTTPS listener. The plain listener
serves clients that refuse the certificate instead.

### The certificate is fetched at runtime

The HTTPS listener presents a publicly issued certificate for `local.codevoid.de`, a
name that resolves to `127.0.0.1`, so a validating client accepts it with no manual
trust step. The URL has to name the host, because a certificate for a hostname does not
validate against a bare IP. The certificate is short-lived, and a copy in the APK would
expire between releases. It is fetched from `dns.codevoid.de`, cached in `filesDir`,
and replaced only when the fetched copy expires later than the cached one. Without that
check, a server that has not yet renewed would trigger a refetch on every start. The key
is effectively public. It only authenticates a loopback listener that any local app can
already use, so publishing it grants nothing more. PKCS12, because Android does not
support JKS.

### A hand-written HTTP server

Ktor's CIO engine has no server-side TLS, and Netty or Jetty are heavy on Android for
two GET routes. The client is one app on loopback issuing bodiless GETs. That makes a
minimal HTTP/1.1 server viable, and as plain JVM code it can be tested against a real
socket in CI. The path is split before it is decoded and never rejoined, so a `%2F`
cannot reach another route. Anything the parser does not understand is a 400.

### 32 worker threads

Each worker blocks for one whole upstream request. Eight workers were enough while
every upstream was a fast tile server. A WMS server that renders on demand took 31
seconds per tile, eight such requests held every worker, and every other source
starved. Blocked threads cost only stack space. The pool stays fixed, so a stuck
upstream cannot spawn threads without bound.

### Six requests per upstream host

A server that renders on demand slows down under concurrency. One measured server
doubled its response time between 8 and 16 concurrent requests, until every request
missed the budget at once. OkHttp's `maxRequestsPerHost` applies only to asynchronous
calls, and the relay uses `execute()`. A per-host semaphore enforces the limit instead,
with a bounded wait, so one slow host cannot stall the others.

### Short tile timeout, long document timeout

A tile gets 5 seconds. One that arrives later has already been scrolled past, and the
worker was held for nothing. A capabilities document gets a 60-second read timeout:
NASA GIBS publishes 5.8 MB, and the user is waiting on purpose. It is a read timeout
rather than a call timeout, so a slow but steady download over a weak signal completes.

### Upstream certificate validation is disabled, for now

The first real upstream failed every request with an untrusted certificate path,
likely an incomplete chain that browsers complete through AIA and Android does not.
Validation is switched off for upstream requests. This risks only wrong map imagery
while no source carries credentials. It must be narrowed to the hosts that need it
before authentication is added. The update check and the certificate fetch use clients
that validate.

### Subdomains are chosen deterministically

`{s}` is filled by `(x + y) mod n`, not at random, so the same tile always comes from
the same host and the client's cache stays useful.

### The User-Agent names the app

Requests carry `WMSproxy/<version> (+https://github.com/c0dev0id/WMSproxy)`. The
OpenStreetMap tile usage policy requires a User-Agent that identifies the application,
and other courtesy hosts apply the same rule. The version lets a server operator's logs
and the app's request log agree on which build misbehaved.

### The probe route

DMD2 does not document which placeholders it substitutes. `/probe` takes an address with
every placeholder a client has been seen to use, one per query parameter. It answers
every request with a blank 200, so the client keeps the source, and logs it verbatim.
The log then shows what DMD filled in.

### The request log is in memory and bounded

The log is the primary diagnostic for how DMD2 behaves. It holds 300 entries and is
never persisted, because queries may carry credentials and nothing in it is worth
keeping across a restart.

## Android

### A `specialUse` foreground service

`dataSync` is capped at six cumulative hours on Android 15, which would stop the proxy
mid-journey. `specialUse` is not among the types Android 14 and 15 forbid starting from
`BOOT_COMPLETED`, so it can also come back after a reboot.

### Start on boot is recorded state, not a setting

The flag is set when the user starts the proxy and cleared only when they stop it,
including through the notification's Stop action. Being killed by the system does not
clear it. A phone that reboots during a ride comes back serving tiles without anyone
touching it, and a proxy the user stopped stays stopped.

### Cleartext traffic is allowed

Plain-HTTP tile servers are one of the cases the proxy exists for, and the preview
draws layers directly. The platform refuses both connections without
`usesCleartextTraffic`. Everything the app contacts on its own behalf is HTTPS.

### A separate debug applicationId

The debug build has the suffix `.debug`, so a branch build installs beside the release
build. The proxy is a navigation dependency, and testing a branch must not disturb the
instance in daily use.

## DMD

### Direct where DMD can, proxied otherwise

DMD substitutes only `{X}/{Y}/{Z}` and `{BBOX}`. Any other rewrite sends a layer through
the proxy. So does an `http://` upstream, because DMD refuses cleartext. `directBlocker()`
encodes this in `DmdLayers.kt`, not in `TileLayer`, because it is knowledge about DMD
rather than a property of a source. A new `Rewrite` blocks direct use until DMD is shown
to handle it.

### A service's reach is computed, not stored

The DMD pill (`DmdReach`) runs `directBlocker()` over every layer a cached document
offers, once per document. It is never written to the cache, so a change to what DMD
can fill corrects every row on the next start. Measured on the library at the time:
124 of 130 services green, 5 red, all five for a plain-HTTP address.

### Padded zoom is checked once per document

A zoom padded to `00`, `01` … is the one template feature DMD cannot fill. Many such
servers also answer the plain form. When a document is read, `PlainZoom` requests one
tile in the plain form. On an image, every layer of the document is stored in the plain
form and goes direct. One answer stands for all layers, because they share a server and
a matrix set.

## Data

### JSON files, no database, no migrations

The files are small. Before 1.0, a schema change is cheaper to absorb by letting an
unreadable field fall back to its default. Every codec ignores unknown keys and turns
unreadable input into an empty value rather than throwing. The cost of a bad file is
that file's content, never a crash.

### Stores are process-wide singletons

The proxy runs on its own threads inside a service while the screens run in the
activity. Both need the same state, and neither owns the other. Each store is an
`object` initialised once in `WmsProxyApp`. There is no dependency injection.

### Settings and caches are separate files

Favorites and own services (`services.json`) are kept apart from read documents
(`catalog-cache.json`), so reading a service again never rewrites a file that holds a
setting. Losing the cache costs a re-read and never a setting.

### The library is bundled, not fetched

The list works before anything else does and depends on no host staying up. The cost is
that a broken entry needs a new build to remove.

### A library entry claims only that usable layers exist

The library records services, not layers. Curating layers would mean thousands of them,
going stale whenever a provider changes theirs. The `usable` and `refused` counts are
measured by `check-library.py` and never typed. Hand-written hedges such as "very large
layer list" appeared only where someone remembered them and went stale silently.

### Layer documents ship with the app

`catalog.json` holds the document of every library service, so a library service opens
with no request and a layer loads with none. It is generated by `CatalogTool` with the
same `ServiceReader` the app uses, so the shipped document and a read on the phone
cannot disagree. A run reads only entries without a document, so adding an entry costs
one request. A document whose layers changed only in order keeps its previous read, so
the asset changes only where a server changed. Reading every server again is a manual
run.

### The user's reads overlay the shipped catalogue

`CatalogStore` shows the shipped documents with the user's reads on top wherever a read
is at least as new. Only the user's part is written. Both parts are loaded on first use,
off the main thread, so a process that only serves tiles never parses the 2.6 MB asset.

### The Python checker answers only yes or no

`check-library.py` reimplements the parser's acceptance rules in Python. It reports only
whether a service has usable layers and how many, never which format or template the
Kotlin would choose, to keep as little as possible in step between the two. A
disagreement between them is a reason to check both.

### Address matching errs towards keeping services apart

`LibraryMatch` reduces an address to a key that ignores the scheme, the case of host,
path and parameter names, parameter order, the standard capabilities parameters, and
the REST, WMS and WMTS forms of an ArcGIS service. Anything that may select a different map, such as another path or a `layers=`
parameter, still distinguishes two addresses.

### A stored layer's route is derived from its service

The source path segment comes from the service's name, so the same service always loads
to the same route. Names are restricted to `[A-Za-z0-9._-]`, because they are typed by
hand, read off a screen and pasted into another app.

## Parsing

### ArcGIS tile caches must prove their grid

A cache is accepted only when its description shows a fused cache, 256-pixel tiles,
spatial reference 3857, the origin at the WebMercator corner, and resolutions halving
from the zoom-0 value. Anything missing is a refusal with the reason, because a cache on
another grid served under a tile index is displaced imagery. The cache's own
`tile/{z}/{y}/{x}` endpoint is used rather than its WMTS wrapper, which answers slower and
less evenly. A service without a cache gets one `export` template per leaf layer.

### WMTS levels are checked by scale denominator

A matrix set may start partway down the pyramid and still number its levels from zero.
basemap.de calls its first level `00` while its scale says zoom 5. The scale denominator
decides whether level N is zoom N. Matrix width does not, because ArcGIS pads matrices to
`2^z + 1` while numbering levels correctly.

### XML hardening tolerates what the platform lacks

Entity references are disabled because the XML comes from an address the user pasted.
The DOCTYPE stays allowed because WMS 1.1.1 documents carry one. Each hardening step is
applied on its own and skipped where unsupported, because Android's
`DocumentBuilderFactory` throws on `setXIncludeAware` where the desktop JVM does not. That
difference once failed every import on the device while every test passed.

### Documents are parsed from the stream

A 5.8 MB document read into a String first is held about three times over. That is 17 MB
of churn before the tree is built.

## Build and release

### No local builds

Every build runs in CI, and a new build reaches the device through the in-app updater.
`check.yml` runs lint, the unit tests and a debug build on every branch push. It also
runs an unsigned `assembleRelease`, because that is the only task that runs R8, and a
shrinking failure would otherwise first show on `main`. It has no `pull_request`
trigger, because the push run already attaches to the PR's head commit.

### `:core` stays free of Android

Every geometry, document and URL decision in `:core` is testable with plain JUnit in CI.
An Android dependency would push its tests into `:app`. For the same reason, release
parsing uses kotlinx-serialization and not `org.json`, which is a stub on the JVM.

### One rolling `dev` pre-release

Every push to `main` replaces the `dev` pre-release with `wmsproxy-dev-<sha>.apk`. The
updater installs a build that is different, not newer, because a SHA has no order. It
also means a revert on `main` reaches the device. The version is read from the asset's
filename, the only place both sides agree on it. The check uses `releases/tags/dev`,
because `releases/latest` skips pre-releases. The `dev` tag is deleted together with the
release, so each build creates it again at the commit it built. Without that, the tag
stays at the commit it was first created on, and keeps that commit reachable.

### The catalogue workflow commits its own output

The Catalogue workflow commits `catalog.json` and `docs/service-catalogue.md` to the
branch it ran on. A push made with the workflow token starts no other workflow, so the
next real push runs Check with the asset in place. The commit touches only generated
files, so it rebases onto anything that landed while the services were being read.

### Pinned dependency lines

The Compose BOM is pinned, and navigation stays on 2.8.x because 2.9 moves to Compose 1.8
and lifecycle 2.9, a toolchain step of its own. `buildConfig` is on because the updater
compares `BuildConfig.VERSION_NAME`.
