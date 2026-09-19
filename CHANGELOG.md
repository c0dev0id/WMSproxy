# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A **proxy service** you start from the main screen. While it runs, the app serves map
  tiles on this device only, and shows a notification you can stop it from.
- **A list of map services that work**, grouped by country and shipped with the app.
  Pick one instead of hunting for a URL; the app fetches its layers and you choose from
  those. 61 services to begin with, chosen for what helps while travelling — topographic
  maps, aerial imagery and hillshade; roadworks, detours and charging points; weather
  radar, flood and rockfall hazards; waterways and nautical charts; and places worth
  stopping at, such as world heritage sites, archaeological monuments, caves and
  waterfalls. Germany is covered down to the individual states. Anything not listed can
  still be added by hand.
- **Import layers from a map server.** Paste the server's GetCapabilities URL and the app reads
  what the server offers, then you tick the layers you want. Layers it cannot serve are
  listed with the reason — no WebMercator, vector tiles only, a tile grid that cannot be
  addressed — rather than quietly left out. The URL is used exactly as you type it, so a
  server needing its own parameters still works. WMS servers work as sources now: a tile is
  requested as a map image of exactly that tile's extent, so nothing is redrawn or
  reprojected on the way through.
- **Your own map sources.** Add, edit and delete tile sources in the app: a name, the
  tile URL template, and the awkward bits DMD cannot handle on its own — `{s}` subdomain
  rotation, TMS row order, quadkeys, a required Referer. Each source shows its HTTP and
  HTTPS tile URL with a copy button; paste that into the Tile URL field of DMD's Add
  Custom Layer dialog, WMS checkbox unticked. Entries are checked as you save them, so a
  template missing `{y}` or a name that would change the URL is caught there rather than
  halfway up a mountain. Sources are kept on the device and survive restarts, and edits
  apply to the next tile without restarting the proxy. Two sources are set up to begin
  with, and **Restore starting sources** brings them back.
- The proxy now also serves **HTTPS**, alongside the plain address, for clients that
  refuse unencrypted connections to this device. The main screen shows both addresses.
  The HTTPS address uses a real hostname that resolves back to this device, so the
  certificate is one the system already trusts and nothing has to be installed.
- A **live request log** on its own tab, showing everything DMD asks for as it happens.
  It follows new entries as they arrive, and stops following the moment you scroll up to
  read something — then picks up again when you return to the bottom. The tab header
  counts what has been recorded. Share and copy hand over a whole session verbatim.
- A **Check for updates** button. It reports the build you are running, offers the
  latest published build when it differs, then downloads it and hands it to the system
  installer. This is how a new build gets onto the device.

### Changed

- When a map server answers with something that is not an image — an error page, a data
  file, a vector tile — the proxy now reports the failure instead of passing the bytes
  on. Previously only a couple of shapes were caught, so other kinds of non-image
  response could reach DMD and be cached as though they were map tiles, leaving a hole
  that persisted until the cache was cleared. Images themselves are passed through
  untouched whatever their format, including unusual ones, so DMD decides what it can
  display rather than this app deciding for it.

### Fixed

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
