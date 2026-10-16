# Features

## The list

- **One list.** It holds the bundled library, the services added by address ("Mine"),
  and layers stored by earlier builds, grouped by region.
- **Filters.** The Favorites, Loaded, Region and Category chips narrow the list. The
  chip state survives a restart.
- **Search.** The search matches service names, notes, and the titles and names of
  every layer a service is known to offer. A row says how many of its layers matched.
  When only layers matched, opening the service seeds its layer search with the same
  text.
- **Status per row.** A row shows loaded layers out of offered layers (`5/22`), or the
  offered count when nothing is loaded. It is marked "via proxy" when a loaded layer
  needs the proxy.
- **Proxy warning.** When loaded layers need the proxy and it is off, a banner says so
  and starts it.

## Services

- **Bundled library.** Each entry has a name, region, category, note and a measured
  count of usable layers. The layer lists of all library services ship with the app, so
  a library service opens with no request.
- **Add by address.** WMS, WMTS, ArcGIS REST (tiled caches and dynamic map services)
  and tile templates are accepted. An address that reaches a library service, however
  it is spelled, is recognised, and the dialog offers the library entry instead.
- **Tile template placeholders.** `{z}`, `{x}` and `{y}` are understood in either case.
  So are `{z:02}` (padded zoom), `{-y}` (TMS rows), `{q}` (quadkey), `{s}`
  (subdomains) and `{bbox}`. Percent-encoded placeholders are decoded.
- **Service screen.** It shows the description, the address the document was read
  from and when it was read. It also has Rescan, favorite, Unload all, and Remove
  service for own services.
- **Rescan.** It reads the document again and updates the address of every loaded layer
  whose template changed.
- **Layers.** Each layer can be ticked to load and unticked to unload. A layer search
  appears for long lists, and select all is scoped to the search. Each row says
  "Direct", or which rewrite sends it through the proxy. A layer the service no longer
  offers is marked stale.
- **Skipped layers.** Layers the app cannot serve are listed with the reason.
- **Address previews.** The source address, each layer's tile or GetMap address and its
  proxy address are shown shortened. A tap opens the whole address with Copy and Open.

## Preview

- **Full-screen preview.** It shows one layer over an OpenStreetMap base map, using the
  same template expansion the proxy and DMD use.
- **Start position.** It opens at the phone's position when that lies inside the layer's
  extent, at the middle of the extent otherwise, and at the phone for world layers.
- **Zoom readout.** Tiles are requested from zoom 0 to 22, and a readout shows the
  current level, so it can be read off where a layer appears and stops.

## DMD Hub

- **Sign in.** Email and password. The password is stored encrypted so an expired
  session renews without asking.
- **Sync.** The button at the top of the list pushes every loaded layer, with its own
  address or the proxy's. Other layers in the account are kept.
- **Full sync.** An option in Settings that removes every layer in the account that
  WMSproxy did not push.

## Proxy

- **Loopback tile server.** It serves HTTP on `127.0.0.1:8088` and HTTPS on port 8443
  under `local.codevoid.de`, a name that resolves to loopback.
- **Rewrites.** Padded zoom, flipped rows, quadkey, WMS bounding box, subdomains,
  Referer, and plain-HTTP upstreams.
- **Foreground service.** It runs with a notification and a Stop action. After a reboot
  it starts again if it was running when the phone went down.
- **Request log.** Settings shows the last 300 requests and what became of each. Debug
  builds add Share, Copy and Clear, a dump of the account's DMD layers, and a probe
  address that records which placeholders a client fills in.

## Updates

- **In-app update.** Settings checks the `dev` pre-release on GitHub, downloads a build
  that differs from the installed one, and hands it to the system installer.
