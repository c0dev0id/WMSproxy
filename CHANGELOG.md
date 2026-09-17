# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A **proxy service** you start from the main screen. While it runs, the app serves map
  tiles on this device only, and shows a notification you can stop it from.
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
- Only plain tile templates can be configured so far. WMS and WMTS servers are not yet
  accepted as sources.
