# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A **proxy service** you start from the main screen. While it runs, the app serves map
  tiles on this device only, and shows a notification you can stop it from.
- The main screen shows **the tile URL to paste into DMD**, with a copy button. Paste it
  into the Tile URL field of DMD's Add Custom Layer dialog and leave the WMS checkbox
  unticked — it is a plain XYZ tile template. One OpenStreetMap layer is built in for
  now, so there is something to look at before source configuration exists.
- A **request log** of everything DMD asks for, with share and copy buttons. Each entry
  shows the tile requested and what came back, so a session can be handed over verbatim.
- A **Check for updates** button. It reports the build you are running, offers the
  latest published build when it differs, then downloads it and hands it to the system
  installer. This is how a new build gets onto the device.

Configuring your own map sources is not possible yet.
