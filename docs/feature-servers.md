# Feature services asked for and left out

A FeatureServer hands out geometry and attributes for a client to draw itself. It has no
endpoint that returns a picture, so neither WMSproxy nor DMD, which draw pictures only,
can use one. Each of these was asked for during development and refused for that reason,
with what stands in for it in the library where anything does. All were probed; the
capabilities column is what the service itself declares.

| Data | Address | Capabilities | In the library instead |
| --- | --- | --- | --- |
| PAD-US 4.1 fee managers (USGS) | `https://services.arcgis.com/v01gqwM5QqNysAAi/arcgis/rest/services/Fee_Managers_PADUS/FeatureServer` | Query | USGS — protected areas by manager (PAD-US 4.1), the map service of the same data on `edits.nationalmap.gov`. The ArcGIS Online item `a516f406610b42d8a755c25665324201` is a view of this FeatureServer. |
| North American Rail Network lines (NTAD, BTS) | `https://services.arcgis.com/xOi1kZaI0eWDREZv/arcgis/rest/services/NTAD_North_American_Rail_Network_Lines/FeatureServer` | Query, Extract | OpenRailwayMap in three styles. US DOT's Class 1 rail tile cache draws the same lines but stops at zoom 7. |
| North American Rail Network nodes (NTAD, BTS) | `https://services.arcgis.com/xOi1kZaI0eWDREZv/arcgis/rest/services/NTAD_North_American_Rail_Network_Nodes/FeatureServer` | Query, Extract | Nothing. OpenRailwayMap shows stations at close zoom. |
| Every rail item on geodata.bts.gov | `https://geodata.bts.gov/search?q=Rail` | FeatureServers throughout | As above. BTS publishes no map service for them. |
| US electric power transmission lines (HIFLD) | `https://services2.arcgis.com/FiaPA4ga0iQKduv3/arcgis/rest/services/US_Electric_Power_Transmission_Lines/FeatureServer` | Query, Extract, ChangeTracking | Nothing. The `U.S._Electric_Power_Transmission_Lines_WMTS/MapServer` address does not exist; OpenInfraMap draws the lines from OpenStreetMap as vector tiles only. |
| AirNow air-quality index contours (EPA) | `https://services.arcgis.com/cJ9YHowT8TU7DUyn/arcgis/rest/services/AirNowLatestContoursCombined/FeatureServer` | Query | NASA GIBS aerosol index and optical depth layers, where smoke shows. |
| Current wildfire perimeters (NIFC WFIGS) | `https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer` | Query, Extract | USFS — fire, current edition: Forest Service fires, not the interagency set. |

Of the same kind, though not an ArcGIS service: Amsterdam's real-time traffic feed
(`data.overheid.nl` dataset `8a6e16fb-39f6-482c-8e63-a30baa243655`) is a GeoJSON file of
travel times per road stretch, with no map service behind it.

What would change this is a vector renderer in the app that draws the layer, DMD or
WMSproxy. Neither has one, and the proxy is built not to render.
