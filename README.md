# WMSproxy

An Android app that re-serves map sources as plain XYZ tile URLs on this device, for
map clients that only accept simple tile templates.

It rewrites requests and passes the server's response through unchanged. It never
redraws or reprojects a tile, so a source it cannot serve by rewriting alone is
refused rather than approximated.

## The URL you paste into your map app

```
http://127.0.0.1:8088/tileproxy/<source>/{z}/{x}/{y}.png
https://<host>:8443/tileproxy/<source>/{z}/{x}/{y}.png
```

Each source's row shows its own address, the one to paste wherever DMD can read the
source itself; the Copy menu offers that and the two proxy addresses above. Sources with
several layers get an extra segment: `/tileproxy/<source>/<layer>/{z}/{x}/{y}.png`.
The proxy is reachable from this device only.

## Adding a source

The app ships a list of map services known to work, grouped by country. Pick one and it
fetches the server's layer list for you to choose from. Anything not in the list can be
added by hand, or by pasting the server's own `GetCapabilities` URL.

The list records when it was last checked. A service can move or withdraw at any time
after that.

## Sending sources to DMD

The DMD tab signs in to your DMD Hub account and uploads your sources as custom layers,
so they appear in DMD's layer list without pasting a URL for each. The password is kept
encrypted on this device and the connection renews itself when it lapses; you sign in
once.

Each source has two switches:

- **Sync** decides whether the source is uploaded at all. Switch it off and the next
  sync removes that layer from the account.
- **Direct** sends the source's own address instead of the proxy's, so the layer keeps
  working in DMD and in the online route planner when this app is not running. It is
  offered when DMD can read the source by itself: plain tile URLs and WMS servers.
  Sources that need rewriting — reversed row order, subdomains, quadkeys, a Referer, or
  zoom levels written with a leading zero — keep the proxy, and the card says which of
  those it is.

The online route planner reads the same account and renders most of the same layers.
Two kinds it cannot: a WMS server that does not allow cross-origin requests, which the
browser refuses to draw although the address is right, and a map service drawn on
request, which the planner asks for as a WMS. Both work in DMD on the phone.

A sync replaces only the layers this app put there. Layers you added to the account
some other way are left as they are. The request log on the Settings tab lists those
other layers each time the DMD tab confirms the connection, should you want to see what
the account holds.

Two buttons under the request log exist to look at DMD from the outside. **Log DMD
layers** writes every custom layer in the account into the log, every field as the
server sent it, so a layer DMD made by hand can be compared with one the sync pushed.
**Copy probe URL** copies an address on the proxy that names every placeholder a tile
client might substitute; pasted into DMD as a layer, it answers every request with a
blank tile and the log shows which placeholders DMD filled and with what — or, with the
WMS box ticked, the exact map request DMD composes.

## Sources that work

| Source | Example |
|---|---|
| Tile URL, path style | `https://server/{z}/{x}/{y}.png` |
| Tile URL, query style | `https://server/tiles?x={x}&y={y}&z={z}` |
| Row order reversed (TMS) | same URL, with the Flip Y switch on |
| Subdomains | `https://{s}.server/{z}/{x}/{y}.png`, plus the list of subdomains |
| Quadkey | `https://server/tiles/{q}.jpeg` |
| Coordinates in another order | `https://server/MapServer/tile/{z}/{y}/{x}` |
| WMS server | paste its `GetCapabilities` URL and pick layers |
| WMTS server | paste its `GetCapabilities` URL and pick layers — both the query-parameter and the RESTful template form |
| ArcGIS map service | paste the service address with `?f=json`, e.g. `https://server/arcgis/rest/services/<name>/MapServer?f=json`. A service with a tile cache is accepted when that cache is on the standard web map grid; a service drawn on request offers each of its layers, rendered by the server over the tile's extent |
| Servers that require a Referer | set it on the source |

Importing from a `GetCapabilities` URL lists the layers it cannot serve, with the
reason for each.

## Sources that don't work

| Source | Why |
|---|---|
| Anything needing a login, API key or token | Not implemented yet |
| Layers not offered in WebMercator (EPSG:3857) | Would need reprojecting, which would place the image wrongly |
| Vector tile endpoints (`.pbf`, Mapbox vector tiles) | Would need drawing, not rewriting |
| WMTS levels named something other than the zoom number, or numbered inconsistently | No way to address them |
| Several layers combined into one image | Add them as separate sources and stack them |
| WCS and WFS endpoints | They serve raw data and vector features, not map images. Use the same server's WMS |

## Known limitations

- When a source is added, the app measures which zoom levels it can serve quickly and
  returns an empty tile outside that range. A layer that has data but draws slowly
  when zoomed out is treated the same as one with no data there, so it looks empty
  rather than slow.
- Map server certificates are not checked. Traffic between this device and the map
  server is not protected from tampering.
- Sources are stored on this device only and cannot be exported.

## Planned

- Logins, API keys and tokens.
- Checking map server certificates.
- Exporting and importing your list of sources.
- Telling "nothing here" apart from "too slow" outside a source's zoom range.
