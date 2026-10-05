# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- One list instead of three tabs. Every service the app knows — the bundled library, the
  user's own addresses, and layers stored by hand — is one list, narrowed by Favorites,
  Loaded, Region and Category chips and a search over name and note. A row shows how many
  of a service's layers are loaded, `5/22`, and whether any of them needs the proxy.
- A screen per service: its description, the address it is read from, and its layers
  with a checkbox each. Ticking a layer stores it at once and measures its zoom range in
  the background; a row says whether DMD gets the layer's own address or the proxy's, and
  why. Long layer lists get their own search.
- Direct is the only mode. Every loaded layer goes to DMD with its own address wherever
  DMD can fill the template in, and with the proxy's only where a rewrite needs it: padded
  zoom, flipped rows, quadkeys, subdomains, a Referer, or a plain-HTTP address. The row
  names the reason. The per-source Sync and Direct switches are gone.
- The proxy's on/off switch, the DMD Hub account and Full sync moved to Settings, with the
  update check and the request log. The Sync button sits at the top of the list and asks
  for the account when there is no session.
- Favorites replace the Sources tab as a shortlist: a star on a row, nothing fetched. The
  Loaded chip shows what the next sync pushes, which the DMD tab used to list.

### Added

- Select all on a service's layers: a checkbox over the rows loads or unloads every
  layer shown, so with a search typed only the matching layers are affected. Layers
  loaded this way are measured by Rescan rather than one by one on load.
- Read service documents and measured zoom ranges are kept, so a service opened again
  costs nothing and a layer loaded again is not measured again. Rescan on the service
  screen reads the document afresh and re-measures the loaded layers.
- Addresses as previews: the GetCapabilities, service description or tile template on the
  service screen, and a layer's GetMap or tile address and its proxy address on its row,
  shortened to server and tail. Tapping one shows the whole address with Copy and Open.
- Own services: the + button takes a WMS, WMTS or ArcGIS address or a tile template,
  which then lives under Mine with the same screen and states as a library entry.
  Layers stored by earlier builds appear there too, grouped by source.
- Preview: a button on every layer, and on a one-layer template service, opens the layer
  full screen over the library's OpenStreetMap map, starting at the phone's position.
  Pinch and pan to see where it draws; a readout in the corner shows the tile level and
  the measured range, so the levels it appears and disappears at can be read off.
- Four services to the library: Waymarked Trails hiking routes, a worldwide overlay of
  signed hiking routes from OpenStreetMap; US Census boundaries, with state, county, city
  and tribal-land names; and for Poland, forest ownership and forest compartments from
  the national Forest Data Bank. The hiking routes are the first plain tile address in
  the library.
- 10 US terrain, fire, snow and land cover layers to the service library:
  USGS contours, USGS NHD detailed hydrography, NLCD national land cover (253 layers),
  USFWS National Wetlands Inventory, EPA ecoregions,
  NOAA NOHRSC snow analysis, USFS fire history, USFS fire current edition,
  USFS fire severity (MTBS), USFS fireshed registry.
- Seven Forest Service and BLM layers to the service library:
  USFS Motor Vehicle Use Map, forest roads, forest trails, campgrounds and trailheads,
  wilderness areas, BLM roads and trails, BLM campgrounds and recreation sites.

### Removed

- The Sources, Library and DMD tabs, the import dialog and the source editor, and the
  per-source Sync and Direct switches: see Changed.
- USGS topographic (both forms), the three PAD-US 3.0 protected-areas entries, USGS NAIP
  imagery, USDA Web Soil Survey and BLM land ownership, removed from the service library
  after review. The current PAD-US 4.1 layer stays.
- The California, Colorado, Texas and Wisconsin land-parcel layers: they draw parcel
  outlines without owner names, which is what a parcel layer is for.

### Fixed

- Plain-HTTP tile servers can now be served and previewed. The app did not permit
  cleartext connections, so a layer whose address starts with `http://` failed in the
  proxy and in the preview, although its row said the proxy would carry it.
- LANDFIRE — vegetation and fuels now uses the styled service. The earlier address was the
  download workspace, whose map output paints raw cell values in grey, so every layer
  looked near-black. The same fifteen layers now draw in LANDFIRE's own colours: fire
  scars red, other disturbance types blue, vegetation classes as on the LANDFIRE maps.
- BRGM — natural hazards URL updated from `mapsref.brgm.fr` to `geoservices.brgm.fr`.

### Changed

- Library layer counts refreshed and `verified` date stamped 2026-09-26.
