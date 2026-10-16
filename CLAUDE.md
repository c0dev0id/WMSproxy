# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app that reads WMS, WMTS, ArcGIS REST and plain XYZ tile services, serves them
as XYZ tiles on loopback, and pushes the chosen layers into a DMD Hub account so they show
up in the DMD2 navigation app and the DMD route planner. A layer goes to DMD with its own
address when DMD can fill the template itself, and with the proxy's address otherwise.

## Building and testing

The project is not built locally. CI builds every push:

- `check.yml` (every branch except `main`): `./gradlew --continue lintDebug test assembleDebug assembleRelease`.
  `assembleRelease` is there because it is the only task that runs R8; it comes out unsigned.
  The debug APK is uploaded as the `app-debug` artifact.
- `build.yml` (`main` only): lint, tests, signed release, and the `dev` pre-release that the
  in-app updater installs from.
- `catalog.yml`: see "Library and catalogue" below.

Use `test`, not `testDebugUnitTest`. `:core` is a plain JVM module, and the Android variant
task does not reach it. Only `:core` and `:tool` have tests (JUnit 4, backtick names). A
single test class is `./gradlew :core:test --tests 'de.codevoid.wmsproxy.core.LibraryMatchTest'`.

The debug build has the applicationId suffix `.debug`, so it installs beside the release build.

## Modules

- `core/`: everything that is not Android. Capabilities parsing, tile templates and math, the
  list and detail models, the DMD wire format, the HTTP server, the request log. It must stay
  free of Android dependencies so that all of it can be tested with plain JUnit.
- `app/`: the Android app. The proxy service, the stores, and the Compose screens.
- `tool/`: `CatalogTool`, a JVM CLI that reads every library service with the app's own
  parser and writes `app/src/main/assets/catalog.json`.
- `tools/`: Python scripts. `check-library.py` measures library entries.
  `service-list.py` generates the library section of `docs/service-catalogue.md`.

## Architecture

**Request path.** `ProxyService` is a `specialUse` foreground service. It runs `ProxyServer`,
which has two `core.http.HttpServer` listeners on `127.0.0.1`: a plain one and a TLS one. The
TLS one exists because DMD refuses cleartext. Its certificate is for `local.codevoid.de`,
which resolves to loopback. `Tls.kt` fetches the certificate at runtime, caches it and renews
it, so HTTPS URLs must use that hostname, not the IP. The only route is
`/tileproxy/<source>[/<layer>]/{z}/{x}/{y}`. `TileLayer.urlFor` expands the upstream
template. `Upstream.client` fetches the tile under a per-host semaphore of 6, and the bytes
are relayed unchanged. The proxy never decodes or re-renders an image. A failure, or a body
that is not a raster image, is answered with a 502, never with a blank tile, because a blank
tile would be cached by the client as real data. `/probe` answers every request with the
blank tile and logs it. It is how DMD's placeholder substitution was worked out.

**Rewrites and direct vs. proxied.** `Rewrite` (`core/Sources.kt`) lists what the proxy does
that a plain XYZ client cannot: padded zoom, flipped rows, quadkey, WMS bbox, subdomains,
Referer, cleartext upstream. `DmdLayers.kt` decides what DMD can do itself. DMD substitutes
only `{X}/{Y}/{Z}` and `{BBOX}`, so any other rewrite, including an `http://` upstream, sends
the layer through the proxy (`directBlocker()`). A new `Rewrite` blocks direct use until DMD
is shown to handle it.

**Reading a service.** `ServiceReader` is the single entry point, shared by the app
(`CapabilitiesFetcher`, over OkHttp) and `CatalogTool` (over `java.net.http`). Each side
supplies its own HTTP GET, so the shipped catalogue and a read on the phone cannot disagree.
A tile template becomes one implicit layer without a request (`XyzTemplate`). Anything else
is tried at each address `CapabilitiesCandidates` lists, parsed by `CapabilitiesParser`
(`Capabilities.kt`, the largest file), and probed once by `PlainZoom` to see whether a padded
zoom also answers unpadded. The parser's acceptance rules are WebMercator offered, raster
format advertised, and tile levels addressable as numbers. `check-library.py` reimplements
these rules in Python. When the two disagree, check both.

**State.** All state lives in process-wide `object` singletons, initialised once in
`WmsProxyApp.onCreate`, and exposed as `StateFlow`. Persistence is JSON files in `filesDir`.
There is no database and there are no migrations. Codecs ignore unknown keys and turn
unreadable input into an empty default instead of throwing.

| Object | File | Holds |
|---|---|---|
| `proxy.Sources` | `sources.json` | Loaded `TileLayer`s, and `startOnBoot` (set by starting or stopping the proxy, not a user setting). The proxy reads it on every request. |
| `catalog.UserServices` | `services.json` | Addresses the user added, and favorites. |
| `catalog.CatalogStore` | `catalog-cache.json` | The user's own reads, overlaid on the shipped `catalog.json`. The newer `fetchedAt` wins. Loaded lazily by `warmUp()`. |
| `catalog.Catalog` | (none) | Combines library, user services, cache and sources into the one list (`ServiceCatalog.assemble`). |
| `dmd.DmdHub` / `SecureStore` | encrypted | DMD session. The password is encrypted with an AndroidKeyStore AES-GCM key, never written to plain JSON. |

**DMD Hub.** `DmdHub` talks to `app.advhub.net/api/ios`. That endpoint requires a specific
User-Agent. On a 401, `DmdHub` signs in again once, then signs out. It uses its own client
that validates TLS. `DmdSync.mergeForPush` replaces every account entry with the
`cl_wmsproxy_` id prefix and keeps foreign entries. The phone ignores `enabled`, so a layer
is turned off by leaving it out of the push.

**Upstream TLS validation is disabled** (`InsecureTls` in `Upstream.kt`). This is marked
temporary, and it must be narrowed before any source carries credentials. Never point a
client at a real authenticated host through `Upstream.client`.

**UI.** Jetpack Compose with one `NavHost` and type-safe `@Serializable` routes
(`ui/Navigation.kt`): the catalogue list, a service screen, the layer preview (osmdroid over
OSM) and settings. `DmdViewModel` and `UpdateViewModel` are scoped to the activity, so a sync
keeps running across screens.

## Library and catalogue

- `app/src/main/assets/library.json` is curated by hand: name, URL, region, category, note.
  The `usable`/`refused` counts are written by `tools/check-library.py --update "<entry name>"`,
  never typed in. `regions` sets the sort order of the wide regions.
- `app/src/main/assets/catalog.json` (about 2.6 MB) is generated. Never edit it by hand.
- On any push that changes `library.json`, the Catalogue workflow reads only the new entries,
  then commits `catalog.json` and `docs/service-catalogue.md` back to the same branch as
  "Refresh the bundled catalogue". Pull before pushing to that branch again. Starting the
  workflow by hand (`--all`) re-reads every server. Do that after a parser change.
- Locally: `./gradlew :tool:installDist && tool/build/install/tool/bin/tool [--all]`. Exit 1
  means a service could not be read and kept its previous document.
- In `docs/service-catalogue.md`, the text between `<!-- library:begin -->` and
  `<!-- library:end -->` is generated (`python3 tools/service-list.py`). Everything else is
  written by hand. A service that was checked and left out, or removed, gets a row there with
  the date and the reason.
- Adding a library entry usually touches `library.json`, `docs/service-catalogue.md` and an
  `Added` line in `CHANGELOG.md`.

## Conventions

- `CHANGELOG.md` follows Keep a Changelog. Record user-visible changes under `[Unreleased]`.
- The dependency versions in `app/build.gradle.kts` are pinned on purpose (the Compose BOM,
  and navigation 2.8.x because 2.9 needs Compose 1.8). Read the comment next to a version
  before bumping it.
- `minSdk` 26, `targetSdk` 34, `compileSdk` 35, JVM 17.
