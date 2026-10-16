# Using WMSproxy

## Find a service

The app opens on one list of every service it knows, grouped by region. Services you
added yourself come first, under **Mine**.

- Type in **Search** to match service names, notes and layer names. A row shows how
  many of its layers matched.
- Narrow the list with the **Favorites**, **Loaded**, **Region** and **Category** chips.
  **Clear** resets them. The chips are remembered, the search is not.
- Tap the star on a row to keep the service on your shortlist. A favorite only marks
  the service and loads nothing.

A row shows how many layers a service offers. Once you load some, it shows loaded out of
offered, for example `5/22`. **via proxy** means at least one loaded layer needs the
proxy.

The **DMD** pill says how much of the service DMD can fetch without the proxy: green for
every layer, orange for some, red for none. A service whose layers are not known yet has
no pill.

## Load layers

1. Tap a service. Library services open at once. A service you added is read when you
   open it.
2. Tick the layers you want. Each one is stored as soon as it is ticked.
3. On a long list, search the layers first. The checkbox above the rows loads or unloads
   every layer shown, and only those.

Each layer row says where the layer goes:

| Marker | Meaning |
|---|---|
| **Direct** | DMD fetches the tiles from the server itself. |
| **Proxy: …** | DMD fetches the tiles through the proxy on this phone. The text names the reason, for example `z → padded z` or `http → https`. |
| **No longer offered by the service** | The layer is loaded, but the service's current document no longer lists it. |

Layers the app cannot serve are listed at the bottom with the reason, for example that
a layer is not offered in WebMercator.

Tap a row to see its addresses: the tile or GetMap address and the proxy address. Tap
an address to see it in full, then **Copy** or **Open**.

## Check a layer on the map

Tap the map icon on a layer row, or **Preview** on a single-layer tile service. The
layer opens full screen over an OpenStreetMap base map.

- The first time, the app asks for your location. The map opens at your position when
  you are inside the layer's area, and at the middle of the area otherwise.
- Pinch and pan. The corner shows the zoom level, so you can see where the layer starts
  and stops drawing.

## Send the layers to DMD

1. Tap the sync button at the top of the list.
2. The first time, sign in with your DMD Hub email and password.
3. Every loaded layer is pushed to your DMD Hub account. A message confirms how many.

The layers then appear in DMD2 and in the DMD route planner. Layers you added to the
account yourself are kept. To have the account hold only WMSproxy's layers, switch on
**Full sync** in Settings.

To remove a layer from DMD, untick it and sync again.

Layers that go through the proxy draw only on the phone that runs the proxy.

An ArcGIS map service that is drawn on request arrives twice in DMD, marked
**(DMD App)** and **(Hub Planner)**. Switch off the planner entry in DMD2 and the
app entry in the planner.

## Run the proxy

Layers marked **Proxy** need the proxy running on the phone.

1. Open **Settings**.
2. Switch on **Serve tiles on this device**.

A notification shows while the proxy runs. Its **Stop** action stops the proxy. If the
phone restarts while the proxy is running, the proxy starts again by itself. If you
stopped it, it stays stopped.

When loaded layers need the proxy and it is off, the list and the service screen show a
warning with a button that starts it.

Settings shows both proxy addresses. DMD uses the HTTPS one.

## Add a service that is not in the library

1. Tap **+** on the list.
2. Paste the address. Any of these work:
   - a WMS or WMTS GetCapabilities address, or just the service's base address,
   - an ArcGIS REST `MapServer` address,
   - a tile template such as `https://tile.example.org/{z}/{x}/{y}.png`.
3. Tap **Add**. The service opens and is read.

If the library already has the service, under any spelling of the address, the dialog
says so and offers **Open library entry**. If your address is different from the
library's, **Add anyway** adds it as your own service.

Tile templates accept these placeholders:

| Placeholder | Meaning |
|---|---|
| `{z}` `{x}` `{y}` | Zoom, column, row, in either case |
| `{z:02}` | Zoom padded to two digits: `00`, `01` … |
| `{-y}` | Row counted from the south (TMS) |
| `{q}` | Quadkey |
| `{s}` | Subdomain |
| `{bbox}` | WMS bounding box |

A template copied from a browser with its braces encoded (`%7Bz%7D`) works too.

## Keep a service current

On a service's screen:

- **Rescan** reads the service again. Loaded layers whose address changed are updated.
- **Unload all**, in the menu, unloads every layer of the service.
- **Remove service**, in the menu, deletes a service you added, together with its
  layers.

## When a layer does not draw

**Settings → Show request log** lists the last 300 requests the proxy received and what
became of each: the tile size on success, or the reason for a failure. A layer marked
**Proxy** that leaves no entries while DMD shows it is not reaching the proxy.

## Update the app

**Settings → Check for updates** looks for a new build. If there is one, tap it to
download it, then confirm the install.
