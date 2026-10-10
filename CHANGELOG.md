# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- Layers a service publishes twice under one title can be told apart: the title gains the
  group that differs, "Ranger Stations (Labels)" beside "Ranger Stations (Features)", or the
  layer's identifier where no group does. Sixteen library services list such pairs, among
  them USGS structures and contours, NEXRAD radar and swisstopo.
- An address pasted with its placeholders percent-encoded, the way a browser copies a tile
  template, is understood: `%7Bz%7D/%7Bx%7D/%7By%7D.png` is read as `{z}/{x}/{y}.png`.
- The preview opens where the layer is. A layer that covers a region opens at the phone
  when the phone is inside it and at the region's middle otherwise; a layer that covers
  the world opens at the phone. The shipped catalogue carries each layer's declared extent.
- One list instead of three tabs. Every service the app knows — the bundled library, the
  user's own addresses, and layers stored by hand — is one list, narrowed by Favorites,
  Loaded, Region and Category chips and a search over name and note. A row shows how many
  of a service's layers are loaded, `5/22`, and whether any of them needs the proxy.
- A screen per service: its description, the address it is read from, and its layers
  with a checkbox each. Ticking a layer stores it at once; a row says whether DMD gets
  the layer's own address or the proxy's, and why. Long layer lists get their own search.
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

- Seven reference layers to the library: the 3DEP lidar grid and elevation coverage, NAIP and US
  Topo coverage, the USGS map sheet indices and reference polygons, and the BLM survey grid.
- Five services from The National Map to the library: USGS transportation, with road classes,
  route shields, 4WD roads and trails; USGS trails; place and feature names from the national
  gazetteer; structures such as trailheads, campgrounds and ranger stations; and aerial imagery
  with the topographic map drawn over it.
- Three USGS mine layers to the library: mine and prospect symbols from the topographic
  maps, mines and mineral plants active in 2003, and the worldwide Mineral Resources Data
  System of deposits.
- National Park Service roads and trails to the library, from the Park Service's own
  inventories, as two layers.
- BLM wilderness to the library: wilderness areas and wilderness study areas on Bureau of
  Land Management land, outlined and named.
- Black Marble 2024 to the library: NASA's picture of the Earth at night, lit towns, roads
  and industry, as hosted by Open Infrastructure Map. Its tiles stop at zoom 8.
- OpenRailwayMap to the library, in three styles: every railway line from OpenStreetMap,
  worldwide, as a transparent overlay with stations and track details at close zoom; the
  same lines coloured by electrification; and by permitted speed.
- An icon of the app's own: a grid of map tiles with a route through them, adaptive to
  the launcher's shape, themed on Android 13+, and in the status bar while the proxy
  runs in place of the borrowed download tick.
- The layer lists of every library service ship with the app, built by the Catalogue
  workflow with the app's own parser. A library service opens at once, a layer is loaded
  with no request, and a server with padded zoom levels is already stored in the plain
  form where it takes one, so it goes to DMD direct. Rescan still reads the live document.
- The overview search finds the service that has a layer: it matches layer titles and
  names beside service names and notes, the row says how many layers matched, and
  opening it shows those layers first.
- Select all on a service's layers: a checkbox over the rows loads or unloads every
  layer shown, so with a search typed only the matching layers are affected.
- Read service documents are kept, so a service opened again costs nothing. Rescan on
  the service screen reads the document afresh and follows a changed address.
- Addresses as previews: the GetCapabilities, service description or tile template on the
  service screen, and a layer's GetMap or tile address and its proxy address on its row,
  shortened to server and tail. Tapping one shows the whole address with Copy and Open.
- Own services: the + button takes a WMS, WMTS or ArcGIS address or a tile template,
  which then lives under Mine with the same screen and states as a library entry.
  Layers stored by earlier builds appear there too, grouped by source.
- Preview: a button on every layer, and on a one-layer template service, opens the layer
  full screen over the library's OpenStreetMap map, starting at the phone's position.
  Pinch and pan to see where it draws; a readout in the corner shows the tile level, so
  the levels it appears and disappears at can be read off.
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

- Zoom measurement. Ticking a layer no longer probes the server level by level, the
  `z8–z16` labels, the Measuring line and the preview's range readout are gone, and the
  proxy no longer answers a blank tile outside a measured range. The one thing the
  measurement decided, whether a padded-zoom server takes the plain form, is now asked
  once when a document is read.
- Three duplicate library entries. USGS aerial imagery, shaded relief and hydrography were
  each listed twice, as a WMTS document and as the same cache's own tile service; the
  tile service stays, under the plain name.
- The Sources, Library and DMD tabs, the import dialog and the source editor, and the
  per-source Sync and Direct switches: see Changed.
- USGS topographic (both forms), the three PAD-US 3.0 protected-areas entries, USGS NAIP
  imagery, USDA Web Soil Survey and BLM land ownership, removed from the service library
  after review. The current PAD-US 4.1 layer stays.
- The California, Colorado, Texas and Wisconsin land-parcel layers: they draw parcel
  outlines without owner names, which is what a parcel layer is for.

### Fixed

- The preview's zoom pill and attribution are readable on the dark theme; they drew
  black text on a dark surface.
- A WMS layer the document names twice, as a group and as the group's one child, is one
  layer in the list rather than two rows with the same GetMap. MapServer publishes this
  shape; GEBCO's bathymetry showed four of its eight layers doubled.
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
