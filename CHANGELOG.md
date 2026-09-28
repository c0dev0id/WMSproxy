# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added

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

- BRGM — natural hazards URL updated from `mapsref.brgm.fr` to `geoservices.brgm.fr`.

### Changed

- Library layer counts refreshed and `verified` date stamped 2026-09-26.
