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

Both are shown in the app with a copy button; a switch picks which one. Sources with
several layers get an extra segment: `/tileproxy/<source>/<layer>/{z}/{x}/{y}.png`.
The proxy is reachable from this device only.

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
| WMTS server | paste its `GetCapabilities` URL and pick layers |
| Servers that require a Referer | set it on the source |

Importing from a `GetCapabilities` URL lists the layers it cannot serve, with the
reason for each.

## Sources that don't work

| Source | Why |
|---|---|
| Anything needing a login, API key or token | Not implemented yet |
| Layers not offered in WebMercator (EPSG:3857) | Would need reprojecting, which would place the image wrongly |
| Vector tile endpoints (`.pbf`, Mapbox vector tiles) | Would need drawing, not rewriting |
| WMTS RESTful URL templates | Only the query-parameter form is read |
| WMTS levels named something other than the zoom number | No way to address them |
| Several layers combined into one image | Add them as separate sources and stack them |

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
- WMTS RESTful URL templates.
- Telling "nothing here" apart from "too slow" outside a source's zoom range.
