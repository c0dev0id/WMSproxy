# Overview

## The problem

A navigation app such as DMD2 takes a custom map layer as a tile template in which it
fills `{X}`, `{Y}` and `{Z}`, or as a WMS GetMap in which it fills `{BBOX}`. Nothing else
is substituted.

Most map services do not fit that shape. They publish a capabilities document, not a
template. Their templates use padded zoom levels, rows numbered from the south,
quadkeys or rotating subdomains. Some require a Referer header, and some speak only plain
HTTP, which DMD refuses. Finding the one usable layer address in a capabilities
document of a thousand layers is work in its own right.

## The solution

WMSproxy is an Android app that runs on the same phone as the navigation app.

1. **A list of services.** A bundled library of services by region and category ships
   with the layer lists of every service already read. Any WMS, WMTS, ArcGIS REST or tile
   template address can be added by hand.
2. **Layers reduced to tile templates.** A service's document is parsed into layers the
   app can serve on the WebMercator tile grid. Layers it cannot serve are listed with
   the reason.
3. **Direct or proxied.** For each loaded layer the app decides whether DMD can fill the
   template itself. If it can, the layer's own address is the one to use. If it cannot,
   the layer gets an address on the app's own loopback proxy, which rewrites each tile
   request for the upstream and relays the image unchanged.
4. **Copy.** Each layer's address, its own or the proxy's, is shown in full and can be
   copied into the navigation app.

## Out of scope

- Reprojection. Only EPSG:3857 is requested.
- Rendering or decoding images. The proxy rewrites requests and relays bytes.
- Vector tiles, features and other non-raster responses.
- Serving anything beyond the phone. The proxy binds to `127.0.0.1` only.
