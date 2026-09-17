# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A **proxy service** you start from the main screen. While it runs, the app serves map
  data on this device only, and shows a notification you can stop it from.
- The main screen now shows **the URLs to add in DMD**, with a copy button for each: a
  WMS service address, and an XYZ template as a fallback if DMD will not take the WMS
  one. One OpenStreetMap layer is built in for now, so there is something to look at
  before source configuration exists.
- A **request log** of everything DMD asks for, with share and copy buttons. For each
  map request it records the tile it resolved to, or why it could not be served.
- A **Check for updates** button. It reports the build you are running, offers the
  latest published build when it differs, then downloads it and hands it to the system
  installer. This is how a new build gets onto the device.

Configuring your own map sources is not possible yet.
