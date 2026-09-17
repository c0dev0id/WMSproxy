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
  unticked — it is a plain XYZ tile template. Two layers are built in for now, so there
  is something to look at before source configuration exists: OpenStreetMap, and CARTO's
  Positron basemap.
- The proxy now also serves **HTTPS**, alongside the plain address, for clients that
  refuse unencrypted connections to this device. The main screen shows both addresses.
  The HTTPS address uses a real hostname that resolves back to this device, so the
  certificate is one the system already trusts and nothing has to be installed.
- A **request log** of everything DMD asks for, with share and copy buttons. Each entry
  shows the tile requested and what came back, so a session can be handed over verbatim.
- A **Check for updates** button. It reports the build you are running, offers the
  latest published build when it differs, then downloads it and hands it to the system
  installer. This is how a new build gets onto the device.

### Known limitations

- The proxy currently accepts **any** certificate an upstream map server presents,
  without checking it. Without that, the first real source refused to work at all. The
  practical effect today is that tile traffic between this device and the map server is
  not protected from tampering — worth knowing on an untrusted network, harmless on your
  own. This is temporary and will be tightened before sources with logins or API keys
  can be added.
- Configuring your own map sources is not possible yet. The two built-in layers are
  placeholders on servers run as a courtesy to the public, not services this app has any
  claim on — either could stop answering without warning, as the previous built-in layer
  did. Keep your use light until you can point the proxy at a source of your own.
