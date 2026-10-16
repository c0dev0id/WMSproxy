<p align="center">
  <img src="docs/banner.svg" alt="WMSproxy" width="100%"/>
</p>

<p align="center">
  <b>WMS, WMTS, ArcGIS and tile map services for your phone's navigation app.</b>
</p>

WMSproxy is an Android app that reads map services and turns their layers into tile
addresses a navigation app can use. Where an app cannot address a service itself, a
proxy on the phone does it.

## Features

- **Service library**: over a hundred services by region and category, with their layer lists built in.
- **Any address**: add a WMS, WMTS or ArcGIS REST address or a tile template, and its layers are read from the service.
- **One list**: search services and their layers, and narrow the list by favorites, loaded layers, region and category.
- **Direct or proxied**: each layer says whether DMD can use its address as is or needs the proxy, and why.
- **Loopback proxy**: rewrites padded zoom, TMS rows, quadkeys, subdomains, WMS boxes, Referer and plain HTTP into requests the server accepts.
- **Preview**: shows any layer full screen over OpenStreetMap, opening where the layer is.
- **Addresses to copy**: every layer's tile address and proxy address, in full, with Copy and Open.
- **Survives reboots**: the proxy comes back after a restart if it was running.
- **In-app updates**: check for and install new builds from Settings.

## Requirements

- Android 8.0 or later

## Getting started

1. Download the APK from the [Development Build](https://github.com/c0dev0id/WMSproxy/releases/tag/dev) release and install it.
2. Open WMSproxy, tap a service, and tick the layers you want.
3. If a layer is marked **Proxy**, switch on the proxy in **Settings**.
4. Tap the layer to see its addresses, and copy one into your navigation app, DMD2 for example.

## Settings

| Item | Description |
|---|---|
| **Serve tiles on this device** | Starts or stops the proxy |
| **Proxy (HTTPS)**, **Proxy (HTTP)** | The proxy's addresses. Use HTTPS for an app that refuses plain HTTP. |
| **Request log** | The proxy's last requests and what became of each |
| **Check for updates** | Downloads and installs a new build |

## More

- [User guide](docs/usage.md)
- [Service catalogue](docs/service-catalogue.md): what ships, what was left out, and why
