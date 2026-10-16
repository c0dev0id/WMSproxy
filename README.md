<p align="center">
  <img src="docs/banner.svg" alt="WMSproxy" width="100%"/>
</p>

<p align="center">
  <b>WMS, WMTS, ArcGIS and tile map services as layers in DMD2.</b>
</p>

WMSproxy is an Android app for DMD2 users. It turns map services into layers DMD2 can
show and pushes them into your DMD Hub account.

## Features

- **Service library**: over a hundred services by region and category, with their layer lists built in.
- **Any address**: add a WMS, WMTS or ArcGIS REST address or a tile template, and its layers are read from the service.
- **One list**: search services and their layers, and narrow the list by favorites, loaded layers, region and category.
- **Direct or proxied**: each layer says whether DMD can fetch it by itself or needs the proxy, and why.
- **Loopback proxy**: rewrites padded zoom, TMS rows, quadkeys, subdomains, WMS boxes, Referer and plain HTTP into requests the server accepts.
- **Preview**: shows any layer full screen over OpenStreetMap, opening where the layer is.
- **DMD Hub sync**: one tap pushes every loaded layer to your account, for DMD2 and the route planner.
- **Survives reboots**: the proxy comes back after a restart if it was running.
- **In-app updates**: check for and install new builds from Settings.

## Requirements

- Android 8.0 or later
- DMD2 and a DMD Hub account

## Getting started

1. Download the APK from the [Development Build](https://github.com/c0dev0id/WMSproxy/releases/tag/dev) release and install it.
2. Open WMSproxy, tap a service, and tick the layers you want.
3. Tap the sync button at the top of the list and sign in to DMD Hub.
4. If a loaded layer is marked **via proxy**, switch on the proxy in **Settings**.

## Settings

| Item | Description |
|---|---|
| **Serve tiles on this device** | Starts or stops the proxy |
| **Proxy (HTTPS)**, **Proxy (HTTP)** | The proxy's addresses. DMD uses the HTTPS one. |
| **DMD Hub** | Sign in or out |
| **Full sync** | Removes every DMD layer that WMSproxy did not push |
| **Check for updates** | Downloads and installs a new build |
| **Request log** | The proxy's last requests and what became of each |

## More

- [User guide](docs/usage.md)
- [Service catalogue](docs/service-catalogue.md): what ships, what was left out, and why
