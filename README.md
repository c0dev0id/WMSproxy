# WMSproxy

A WMS/WMTS normalization gateway for Android.

DMD2 speaks WMS, but only the simple unauthenticated subset, and it substitutes
`{x}`/`{y}`/`{z}` into tile URL templates. That rules out authenticated services,
WMTS with non-integer TileMatrix identifiers, flipped-Y (TMS) schemes, `{s}` subdomain
rotation and quadkeys.

WMSproxy runs a foreground service with a small HTTP server on `127.0.0.1`, and
re-exposes whatever map services you configure as plain, unauthenticated endpoints
DMD2 can consume:

- `/wms` — one WMS service listing every configured layer.
- `/t/{layer}/{z}/{x}/{y}` — an XYZ endpoint for tile-backed sources.

It rewrites requests and passes the upstream response through untouched. It does not
re-render, re-project or cache anything — DMD2 already caches, and a tile that cannot
be served by rewriting alone returns an error rather than a blank square.

## Status

Early development. The build pipeline is in place; the proxy service is not
implemented yet.

## Building

Builds run in GitHub Actions, not locally. Branch pushes produce a debug APK as the
`app-debug` artifact; pushes to `main` publish a signed APK to the `dev` pre-release.

