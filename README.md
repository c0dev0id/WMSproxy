# WMSproxy

An Android app that puts map services on a phone's navigation app. It reads WMS, WMTS,
ArcGIS REST and plain tile services, serves them as XYZ tiles on loopback where a
navigation app cannot address them itself, and pushes the chosen layers into a DMD Hub
account so they appear in DMD2.

## How it is used

1. **Browse** the one list: a bundled library of services by region and category, plus
   anything added by address. Narrow it with the Favorites, Loaded, Region and Category
   chips or the search.
2. **Star** what is worth coming back to. A favorite is a shortlist, nothing more.
3. **Open** a service to see what it offers. Its document is read once and kept; Rescan
   reads it again.
4. **Tick** the layers to use. Each is stored at once and measured for the zoom levels it
   answers at; Preview shows it full screen over an OpenStreetMap base map.
5. **Sync** from the top of the list. Every loaded layer goes to the DMD Hub account with
   its own address, or with the proxy's where DMD cannot fill the template in itself:
   padded zoom levels, flipped rows, quadkeys, subdomains, a Referer, a plain-HTTP server.

The proxy runs only when switched on in Settings, and only matters for layers marked
"via proxy". It serves on `127.0.0.1` over HTTP and, for DMD, over HTTPS with a
certificate for a loopback hostname.

## Layout

- `core/` — everything that is not Android: service documents, tile templates, the list
  and detail models, the DMD wire form, the request log. Unit-tested with JUnit.
- `app/` — the Android app: the proxy service, the stores, the Compose screens.
- `tools/check-library.py` — measures every library entry; the counts in `library.json`
  come from it.

## Building

Builds run in CI. A push to any branch runs lint, the unit tests and a debug build; a
push to `main` produces the signed release and the `dev` pre-release the in-app update
check installs.
