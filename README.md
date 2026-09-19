# WMSproxy

An Android foreground service that re-exposes map services as plain XYZ tile
templates on `127.0.0.1`, for clients that only handle the simple case.

DMD2 substitutes `{z}`/`{x}`/`{y}` into a tile URL and nothing else. That rules out
authenticated services, WMS and WMTS servers, flipped-Y (TMS) row order, `{s}`
subdomain rotation and quadkeys. WMSproxy translates those on the way out.

**It rewrites requests; it does not process images.** The upstream response body is
relayed byte for byte. Nothing decodes, resamples, re-encodes or reprojects. A request
that cannot be answered by rewriting alone returns an error.

## Interface

```
http://127.0.0.1:8088/tileproxy/<source>[/<layer>]/{z}/{x}/{y}.png
https://<tls-host>:8443/tileproxy/<source>[/<layer>]/{z}/{x}/{y}.png
```

Both listeners bind loopback only. The layer segment is absent for sources with no
layer concept. HTTPS exists because a client may refuse cleartext to loopback under
its own network security policy; the certificate must be one the system already
trusts, for a hostname resolving to `127.0.0.1`.

## Supported upstreams

| | |
|---|---|
| XYZ templates | `{z}` `{x}` `{y}` anywhere in path or query |
| TMS row order | `flipY`, converted per request |
| Quadkey | `{q}` |
| Subdomain rotation | `{s}`, chosen deterministically so the client cache stays useful |
| WMS 1.1.1 / 1.3.0 | as `{bbox}` in a `GetMap` template, 256×256 per tile |
| WMTS 1.0.0 | KVP `GetTile`; `TileMatrix` prefixes such as `EPSG:900913:{z}` |
| Referer header | per source, for servers that require one |

Sources are added by hand or imported from a `GetCapabilities` URL. Import lists
layers it will not serve, with the reason.

**Only EPSG:3857 is ever requested.** Tiles are cut on the WebMercator grid and there
is no reprojection, so a layer not available in WebMercator is reported rather than
fetched in something else and labelled wrongly.

## Behaviour

- **Measured zoom range.** Adding a source fetches a tile at several zoom levels and
  keeps the span that answers within the tile budget, aimed at the middle of the
  layer's declared extent. Outside that span a transparent tile is returned without
  contacting the server.
- **Concurrency.** 32 request workers, at most 6 concurrent requests per upstream
  host. A server that renders on demand degrades under load, and past a point the
  extra parallelism slows every request.
- **Timeouts.** 5s per tile, 20s while measuring.
- **Errors are errors.** A failure returns HTTP 502, never a placeholder image. The
  client caches what it is given, so a blank square for a transient fault would become
  permanent. Non-image responses — an HTML error page returned as 200, a vector tile,
  a `ServiceExceptionReport` — are refused for the same reason.
- **No caching.** The client already caches; a second cache duplicates storage and
  makes staleness ambiguous.
- **Request log** of everything the client asks for, with the upstream result. Shared
  as text.
- **In-app updater** against the `dev` pre-release.

## Not supported

- **Authentication.** No Basic, API key, Bearer or OAuth2. Currently the largest gap.
- **Upstream certificate validation — disabled.** Every upstream certificate is
  accepted. Temporary, and must be fixed before authentication lands, or credentials
  would go over unverified connections.
- **WMTS RESTful `ResourceURL`** templates. KVP only.
- **Non-WebMercator tile matrix sets**, and WMTS `TileMatrix` identifiers that are not
  the zoom level (named scales, zero-padded levels).
- **Vector tiles.** Drawing them would mean rendering.
- **A northbound WMS service.** Dropped deliberately: a tile request carries an
  integer `z/x/y`, so there is no extent to interpret and no axis order to get wrong.
- **Multi-layer compositing**, `GetFeatureInfo`, `GetLegendGraphic`.
- **Config import/export.** Sources live in JSON in the app files dir; there is no way
  to move them off the device.

## Known issues

- The measured zoom range does not distinguish "no data here" from "too slow to
  draw". Both return a transparent tile, so a layer that is merely slow when zoomed
  out looks empty.
- The TLS certificate and its key ship inside the APK, so renewal means a rebuild and
  reinstall. The key is therefore effectively public and must never be a wildcard.

## Layout

```
:core   pure Kotlin/JVM — tile arithmetic, capabilities parsing, URL building,
        the HTTP server, config. All unit-tested.
:app    Android — Compose UI, foreground service, TLS, upstream client.
```

`:core` is kept free of Android dependencies so its tests run in CI without a device.
Android-free is not Android-identical: it still runs on Android's platform libraries,
which differ from the JDK's in places (JAXP notably), so configuration of a platform
facility is best-effort.

## Building

Builds run in GitHub Actions, not locally. A branch push produces the `app-debug`
artifact; a push to `main` publishes a signed APK to the `dev` pre-release.

```sh
./gradlew --continue lintDebug test assembleDebug assembleRelease   # what CI runs
./gradlew :core:test --tests "de.codevoid.wmsproxy.core.TileMathTest"
```
