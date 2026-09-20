# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Minimum Android version lowered to Android 8.0 (API 26).** The app previously
  required Android 14. The only constraint above API 26 was the foreground service type
  constant, which is now version-gated in code.

### Added

- A **proxy service** you start from the main screen. While it runs, the app serves map
  tiles on this device only, and shows a notification you can stop it from. Once started
  it also comes back by itself after the phone restarts, until you stop it — a reboot
  mid-journey does not leave you without a map.
- **A list of map services that work**, on its own **Library** tab, grouped by country
  with a filter for picking one out. Choose a service instead of hunting for a URL and
  the import opens with it filled in; the app then fetches its layers and you choose from
  those. Each service says how many layers it
  offers, so you know before you tap whether that is a glance or a hunt. 60 services to begin with, chosen for what helps while travelling — topographic
  maps, aerial imagery and hillshade; roadworks, detours and charging points; weather
  radar, flood and rockfall hazards; waterways and nautical charts; and places worth
  stopping at, such as world heritage sites, archaeological monuments, caves and
  waterfalls. Germany is covered down to the individual states. Anything not listed can
  still be added by hand.
- **Library filters**: filter the library list by region, category, and service name.
  Region and category choices are remembered across sessions; the name search is cleared
  on close. A **Clear** button appears when any filter is active. In landscape the filters
  sit on one line; in portrait they wrap to two rows to leave room for the list. Eight
  categories cover the library: Aerial, Basemap, Hazards, POI, Terrain, Traffic,
  Waterways, Weather.
- **USFS Motor Vehicle Use Map** added to the library. Shows which roads and trails in
  US National Forests are open to motor vehicles.
- **USGS PAD-US 3.0** added to the library. National parks, wilderness areas and other
  publicly accessible protected lands across the United States.
- **Import layers from a map server.** Paste the server's GetCapabilities URL and the app reads
  what the server offers, then you tick the layers you want. Layers it cannot serve are
  listed with the reason — no WebMercator, vector tiles only, a tile grid that cannot be
  addressed — rather than quietly left out. The URL is used exactly as you type it, so a
  server needing its own parameters still works. WMS servers work as sources now: a tile is
  requested as a map image of exactly that tile's extent, so nothing is redrawn or
  reprojected on the way through.
- **Your own map sources.** Add, edit and delete tile sources in the app: a name, the
  tile URL template, and the awkward bits DMD cannot handle on its own — `{s}` subdomain
  rotation, TMS row order, quadkeys, a required Referer. Each source shows its tile URL
  with a copy button — plain or HTTPS, whichever the **Serve over HTTPS** switch on the
  Settings tab selects; paste that into the Tile URL field of DMD's Add Custom Layer
  dialog, WMS checkbox unticked. Entries are checked as you save them, so a
  template missing `{y}` or a name that would change the URL is caught there rather than
  halfway up a mountain. Sources are kept on the device and survive restarts, and edits
  apply to the next tile without restarting the proxy. A fresh install starts empty — pick
  a service from the Library tab, or add your own.
- The proxy now also serves **HTTPS**, alongside the plain address, for clients that
  refuse unencrypted connections to this device. The main screen shows both addresses.
  The HTTPS address uses a real hostname that resolves back to this device, so the
  certificate is one the system already trusts and nothing has to be installed.
- A **live request log** on the **Settings** tab, showing everything DMD asks for as it
  happens. It follows new entries as they arrive, and stops following the moment you
  scroll up to read something — then picks up again when you return to the bottom. Share
  and copy hand over a whole session verbatim.
- A **Check for updates** button. It reports the build you are running, offers the
  latest published build when it differs, then downloads it and hands it to the system
  installer. This is how a new build gets onto the device.
- **Sign in to DMD Hub**, on a new **DMD** tab. This is the first step towards pushing
  your sources into DMD automatically, so you no longer copy each tile URL across by
  hand. Your password is kept encrypted on the device, never in the configuration you can
  export, and the connection renews itself quietly when it lapses — you sign in once. The
  tab confirms the connection is live rather than just remembering it, and a Sign out
  button removes the stored account.
- **Sync your sources to DMD** from the DMD tab, so you no longer paste each tile URL by
  hand. One button uploads your sources as DMD custom layers, under their titles; layers
  DMD already has that are not yours are left untouched. Each source has two switches:
  **Sync**, to leave a source out of the upload, and **Direct**, which — for sources DMD
  can read on its own — sends the source's own address instead of the proxy's, so it keeps
  working in DMD and the online route planner even when this device's proxy is not
  running. Sources that need the proxy (flipped tiles, quadkeys, subdomain rotation)
  keep the Direct switch disabled, and the card says which of those it is.

### Changed

- **Direct sync to DMD now covers more sources.** WMS sources can be sent to DMD as their
  own address: DMD only ever draws Web Mercator, where the map server's two protocol
  versions agree on coordinate order, so the trap the proxy guards against cannot arise.
  Tile servers that number their zoom levels with a leading zero are tested once when
  added; where the server also accepts the plain number, as TopPlusOpen does, the source
  becomes eligible for Direct as well.
- The request log now also lists the custom layers your DMD account holds that did not
  come from this app, each time the DMD tab confirms the connection.
- The **Add source** and **Edit** dialog is now as wide as the import dialog, so a long
  tile URL template can be read while it is typed.
- The certificate that lets clients trust the app's HTTPS address now **keeps itself up
  to date**. It is fetched when the proxy runs and renewed on its own before it expires,
  so HTTPS no longer stops working every few months until you install an app update. The
  old "Export certificate" button is gone — with a properly trusted certificate there is
  nothing to install by hand.
- When a map server answers with something that is not an image — an error page, a data
  file, a vector tile — the proxy now reports the failure instead of passing the bytes
  on. Previously only a couple of shapes were caught, so other kinds of non-image
  response could reach DMD and be cached as though they were map tiles, leaving a hole
  that persisted until the cache was cleared. Images themselves are passed through
  untouched whatever their format, including unusual ones, so DMD decides what it can
  display rather than this app deciding for it.

### Fixed

- **ArcGIS Online WMTS sources now import and serve tiles correctly.** These services
  advertise KVP tile access but return their Capabilities document instead of a tile when
  it is used. The import now prefers a layer's own REST tile URL template over the
  constructed KVP URL whenever one is present, which is the form these servers actually
  honour.

- The proxy's front page, reached by opening its address in a browser, listed every
  source with a plain address even when **Serve over HTTPS** was on. It now lists the
  same addresses the Sources tab shows.
- **Tiles can no longer come from the wrong zoom level.** A server may offer the same
  map on several tile grids, and some number a grid's levels from zero while it starts
  partway down the pyramid — basemap.de's does, five levels down. The app took the first
  grid it recognised, which for such a server meant asking for a level five steps from
  the one wanted and drawing the wrong ground. It now checks that a grid's levels are the
  zoom they are named after, and picks the deepest grid that passes, so a source either
  draws the right place or is refused.

- **TopPlusOpen** (and other REST-only WMTS servers) can now be imported.
  Some servers — including BKG's — publish no `OperationsMetadata` section at
  all and instead carry a tile URL template on each layer. The import now reads
  those templates directly when no KVP endpoint is declared.

- More servers can be imported. Services that describe their image format in a way no
  fixed list anticipates — several national mapping agencies do — are now accepted if
  the format is a raster image at all. Services that number their zoom levels `00`,
  `01`, `02` rather than `0`, `1`, `2` work too. Between them this covers the US,
  German, Norwegian and Dutch national basemaps, all of which were previously refused.

- Layers now come with a tested zoom range. When a source is added, the app fetches a
  tile at a handful of zoom levels and remembers where the server answers quickly enough
  to be useful. Outside that range it hands DMD an empty tile straight away instead of
  waiting on a server that will not answer in time — so zooming out no longer stalls the
  map while a layer that cannot draw at that scale is asked anyway.
- The proxy no longer asks one map server for everything at once. A server that draws
  layers on demand gets slower the more it is asked simultaneously, which showed up as
  tiles failing at random rather than as the overload it was.
- One slow map server no longer stalls the others. A layer that takes many seconds to
  draw could previously occupy every connection the proxy had, so other layers received
  nothing at all until it finished.

### Known limitations

- The proxy currently accepts **any** certificate an upstream map server presents,
  without checking it. Without that, the first real source refused to work at all. The
  practical effect today is that tile traffic between this device and the map server is
  not protected from tampering — worth knowing on an untrusted network, harmless on your
  own. This is temporary and will be tightened before sources with logins or API keys
  can be added.
- The two sources set up on a fresh install are placeholders on servers run as a courtesy
  to the public, not services this app has any claim on — either could stop answering
  without warning, as an earlier built-in layer did. Replace them with sources of your
  own once you have some.
- The tested zoom range does not distinguish "this layer has nothing here" from "this
  server cannot draw it fast enough". Both show as an empty tile, so a layer that is
  merely slow when zoomed out looks empty rather than slow.
- Only WebMercator is ever requested from a server, because that is the grid the tiles
  are cut on and nothing here reprojects. A layer offered in some other projection is
  reported as unusable rather than fetched and placed wrongly.
