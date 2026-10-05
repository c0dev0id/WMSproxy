# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

- Four services to the library: Waymarked Trails hiking routes, a worldwide overlay of
  signed hiking routes from OpenStreetMap; US Census boundaries, with state, county, city
  and tribal-land names; and for Poland, forest ownership and forest compartments from
  the national Forest Data Bank. The hiking routes are the first plain tile address in
  the library; picking it opens the editor straight away, since there is no layer list
  to fetch.
- 10 US terrain, fire, snow and land cover layers to the service library:
  USGS contours, USGS NHD detailed hydrography, NLCD national land cover (253 layers),
  USFWS National Wetlands Inventory, EPA ecoregions,
  NOAA NOHRSC snow analysis, USFS fire history, USFS fire current edition,
  USFS fire severity (MTBS), USFS fireshed registry.
- Seven Forest Service and BLM layers to the service library:
  USFS Motor Vehicle Use Map, forest roads, forest trails, campgrounds and trailheads,
  wilderness areas, BLM roads and trails, BLM campgrounds and recreation sites.

### Removed

- USGS topographic (both forms), the three PAD-US 3.0 protected-areas entries, USGS NAIP
  imagery, USDA Web Soil Survey and BLM land ownership, removed from the service library
  after review. The current PAD-US 4.1 layer stays.
- The California, Colorado, Texas and Wisconsin land-parcel layers: they draw parcel
  outlines without owner names, which is what a parcel layer is for.

### Fixed

- LANDFIRE — vegetation and fuels now uses the styled service. The earlier address was the
  download workspace, whose map output paints raw cell values in grey, so every layer
  looked near-black. The same fifteen layers now draw in LANDFIRE's own colours: fire
  scars red, other disturbance types blue, vegetation classes as on the LANDFIRE maps.
- BRGM — natural hazards URL updated from `mapsref.brgm.fr` to `geoservices.brgm.fr`.

### Changed

- Library layer counts refreshed and `verified` date stamped 2026-09-26.
