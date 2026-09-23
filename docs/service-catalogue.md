# Service catalogue — German and Baden-Württemberg map services

What the public catalogues publish, what this proxy can serve, and what the app already
ships. Gathered so the catalogues do not have to be scraped again; the point of keeping
it is that the *editorial* choice — what belongs on a screen being read while riding —
is the slow part, and it can now be made from a list instead of from a crawl.

Surveyed 2026-09-19. Every endpoint below was fetched and run through the same acceptance
rule the app uses, so the status is measured, not guessed.

## How to read it

| Status | Meaning |
|---|---|
| **shipped** | Already in `app/src/main/assets/library.json` |
| ok | Passes the check and could be added — this is the column to review |
| refused | Answers, but offers no layer in WebMercator with a raster format |
| unreachable | No answer from the network this was run on; may work from yours |

*Layers* is how many of the service's layers the proxy could serve / had to refuse.
A service with many layers is a catalogue in itself — you pick layers when adding it.

On *unreachable*: two LUBW hosts answered nothing at all — `rips-rasterdaten.lubw.bwl.de`
(88 of 88, the topographic raster archive: DTK10/25/50/100/500 and DOP20) and
`ripswebgis.lubw.bwl.de` (18 of 18). A third, `rips-gdi.lubw.baden-wuerttemberg.de`, serves
61 of 71; the 10 that fail were re-probed one at a time and still failed, so those are dead
endpoints rather than a rate limit. A whole host being silent is more likely a network path
than a withdrawal, so the raster archive is worth retrying from a different connection.

## Where it came from

| Catalogue | Route |
|---|---|
| LGL-BW, LAD | `lgl-bw.de/Produkte/Open-Data/` links to per-record pages in the GDI-BW GeoNetwork; the endpoints are in those records, not on the LGL pages. 204 records read via `metadaten.geoportal-bw.de/geonetwork/srv/api/records/<uuid>` |
| LUBW, LGRB | CSW at `rips-metadaten.lubw.de/csw` — `GetRecords`, 657 records |
| BKG | Product pages under `gdz.bkg.bund.de/index.php/default/open-data.html`; services are `sgx.geodatenzentrum.de/<name>` |
| MobiData-BW | CKAN at `mobidata-bw.de/api/3/action/package_search` |
| Heritage | CSW at `metadaten.geoportal-bw.de/geonetwork/srv/eng/csw` with a CQL `AnyText` constraint |
| Roadworks by state | CSW at `gdk.gdi-de.org/gdi-de/srv/eng/csw` (the national catalogue), `GetRecords` with `AnyText` constraints for *Baustellen*, *Baustelleninformation*, *Umleitung*, *Sperrung*, *Verkehrslage*, *Verkehrsinformation* and *Straßensperrung*; endpoints are in the records' online-resource links |

## Summary

| Catalogue | Endpoints | shipped | ok | refused | unreachable |
|---|--:|--:|--:|--:|--:|
| LGL-BW | 101 | 4 | 95 | 2 | 0 |
| LAD (heritage) | 4 | 2 | 2 | 0 | 0 |
| LUBW / RIPS | 191 | 2 | 46 | 27 | 116 |
| LGRB (geology) | 10 | 2 | 7 | 1 | 0 |
| BKG | 15 | 4 | 11 | 0 | 0 |
| MobiData-BW | 3 | 0 | 3 | 0 | 0 |
| Karlsruhe TRK | 1 | 1 | 0 | 0 | 0 |
| Freiburg | 2 | 0 | 2 | 0 | 0 |
| UBA | 1 | 0 | 0 | 1 | 0 |
| **Total** | **328** | **15** | **166** | **31** | **116** |

## LGL-BW

Base: `https://owsproxy.lgl-bw.de/owsproxy/ows/` — append the name below, then `?SERVICE=WMS&REQUEST=GetCapabilities`
(or `SERVICE=WMTS` where marked).

| Status | Layers | Service | Title |
|---|--:|---|---|
| **shipped** | 1/1 | `WMS_LGL-BW_ASK_200_K` | WMS LGL-BW Amtliche Strassenkarte 1:200 000 Farbkombination |
| **shipped** | 3/3 | `WMS_LGL-BW_ATKIS_DTK_25_K_A` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:25 000 Kombination |
| **shipped** | 1/1 | `WMS_LGL-BW_ATKIS_DGM_025_Schummerung` | WMS LGL-BW ATKIS Digitales Geländemodell Schummerung 25cm |
| **shipped** | 1/1 | `WMTS_LGL-BW_ATKIS_DOP_20_C` | WMTS LGL-BW ATKIS DOP 20cm Bodenauflösung |
| ok | 1/1 | `WMS_INSP_BW_Adr_Hauskoord_ALKIS` | INSPIRE-WMS BW Adressen Hauskoordinaten ALKIS |
| ok | 4/4 | `WMS_INSP_BW_Boden_ALKIS` | INSPIRE-WMS BW Boden ALKIS |
| ok | 1/1 | `WMS_INSP_BW_Bodenbedeckung_ATKIS_DLM50` | INSPIRE-WMS BW Bodenbedeckung ATKIS DLM50 |
| ok | 1/1 | `WMS_INSP_BW_Bodenbedeckung_ALKIS` | INSPIRE-WMS BW Bodenbedeckungsvektor ALKIS |
| ok | 1/1 | `WMS_INSP_BW_Bodennutzung_ATKIS_DLM50` | INSPIRE-WMS BW Bodennutzung ATKIS DLM50 |
| ok | 1/1 | `WMS_INSP_BW_Bodennutzung_ALKIS` | INSPIRE-WMS BW Existierende Bodennutzung ALKIS |
| ok | 2/2 | `WMS_INSP_BW_Flst_ALKIS` | INSPIRE-WMS BW Flurstücke/Grundstücke ALKIS |
| ok | 2/2 | `WMS_INSP_BW_Gebaeude_ALKIS` | INSPIRE-WMS BW Gebäude ALKIS |
| ok | 1/1 | `WMS_INSP_BW_Geogr_Bez_ALKIS` | INSPIRE-WMS BW Geografische Bezeichnungen ALKIS |
| ok | 1/1 | `WMS_INSP_BW_Geogr_Bez_ATKIS_DLM50` | INSPIRE-WMS BW Geografische Bezeichnungen ATKIS DLM50 |
| ok | 1/1 | `WMS_INSP_BW_Hydro_Netzwerk_ATKIS_DLM50` | INSPIRE-WMS BW Hydro - Netzwerk ATKIS DLM50 |
| ok | 3/3 | `WMS_INSP_BW_Hydro_PhysGew_ALKIS` | INSPIRE-WMS BW Hydro - Physische Gewässer ALKIS |
| ok | 9/9 | `WMS_INSP_BW_Hydro_PhysGew_ATKIS_DLM50` | INSPIRE-WMS BW Hydro - Physische Gewässer ATKIS DLM50 |
| ok | 1/1 | `WMS_INSP_BW_Orthofoto_DOP20` | INSPIRE-WMS BW Orthofotografie DOP20 |
| ok | 3/3 | `WMS_INSP_BW_Verkehrsnetz_ALKIS` | INSPIRE-WMS BW Verkehrsnetze ALKIS |
| ok | 13/13 | `WMS_INSP_BW_Verkehrsnetz_ATKIS_DLM50` | INSPIRE-WMS BW Verkehrsnetze ATKIS DLM50 |
| ok | 2/2 | `WMS_INSP_BW_Verwaltungseinheiten_ATKIS_DLM50` | INSPIRE-WMS BW Verwaltungseinheiten ATKIS DLM50 |
| ok | 1/1 | `WMS_LGL-BW_PHY_500_K` | Physische Karte BW 1:500 000 |
| ok | 1/1 | `WMS_LGL-BW_VBW_350` | Verwaltungsgliederungskarte Baden-Württemberg 1:350 000 |
| ok | 1/1 | `WMS_LGL-BW_ALKIS_Grenzpunkte_Koordinatenqualitaet` | WMS Koordinatenqualität von gültigen Grenzpunkten |
| ok | 7/7 | `WMS_LGL-BW_AFIS_Hoehenfestpunkte` | WMS LGL-BW AFIS Höhenfestpunkte |
| ok | 6/6 | `WMS_LGL-BW_AFIS_Schwerefestpunkte` | WMS LGL-BW AFIS Schwerefestpunkte |
| ok | 2/2 | `WMS_LGL-BW_ALKIS_Basis_Vertrieb` | WMS LGL-BW ALKIS Basis |
| ok | 4/4 | `WMS_LGL-BW_ALKIS_Basis_transparent` | WMS LGL-BW ALKIS Basis transparent |
| ok | 1/1 | `WMS_LGL-BW_ALKIS_Hauskoordinaten` | WMS LGL-BW ALKIS Hauskoordinaten |
| ok | 1/1 | `WMS_LGL-BW_ALKIS_Hausumringe` | WMS LGL-BW ALKIS Hausumringe |
| ok | 13/13 | `WMS_LGL-BW_ALKIS_VGr_KatGr_E` | WMS LGL-BW ALKIS Verwaltungs- und Katasterbezirksgrenzen |
| ok | 2/2 | `WMS_LGL-BW_ATKIS_Basis-DLM_K` | WMS LGL-BW ATKIS Basis-DLM Kombination |
| ok | 15/15 | `WMS_LGL-BW_ATKIS_BasisDLM_VerwGr_E` | WMS LGL-BW ATKIS BasisDLM Verwaltungsgrenzen Einzellayer |
| ok | 1/1 | `WMS_LGL-BW_ATKIS_DOP_20_Bearbeitungsuebersicht` | WMS LGL-BW ATKIS Digitale Orthophotos 20cm Bearbeitungsübersicht |
| ok | 1/1 | `WMS_LGL-BW_ATKIS_DOP_20_Bildflugkacheln_Aktualitaet` | WMS LGL-BW ATKIS Digitale Orthophotos 20cm Bildflugkacheln Aktualität |
| ok | 23/23 | `WMS_LGL-BW_ATKIS_DOP_20_Bildfluglose` | WMS LGL-BW ATKIS Digitale Orthophotos 20cm Bildfluglose |
| ok | 1/1 | `WMS_LGL-BW_ATKIS_DOP_20_Bildflugplanung` | WMS LGL-BW ATKIS Digitale Orthophotos 20cm Bildflugplanung |
| ok | 1/1 | `WMS_LGL-BW_ATKIS_DOP_20_C` | WMS LGL-BW ATKIS Digitale Orthophotos in Farbe 20 cm Bodenauflösung |
| ok | 1/1 | `WMS_LGL-BW_ATKIS_DOP_20_GRAU` | WMS LGL-BW ATKIS Digitale Orthophotos in Graustufen 20 cm Bodenauflösung |
| ok | 1/1 | `WMS_LGL-BW_ATKIS_DOP_20_CIR` | WMS LGL-BW ATKIS Digitale Orthophotos Infrarot (CIR) 20 cm Bodenauflösung |
| ok | 23/23 | `WMS_LGL-BW_ATKIS_DTK_10_E` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:10 000 Einzelebenen |
| ok | 3/3 | `WMS_LGL-BW_ATKIS_DTK_10_K` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:10 000 Kombination |
| ok | 24/24 | `WMS_LGL-BW_ATKIS_DTK_100_E` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:100 000 Einzelebenen |
| ok | 3/3 | `WMS_LGL-BW_ATKIS_DTK_100_K_A` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:100 000 Kombination |
| ok | 23/23 | `WMS_LGL-BW_ATKIS_DTK_25_E` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:25 000 Einzelebenen |
| ok | 24/24 | `WMS_LGL-BW_ATKIS_DTK_50_E` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:50 000 Einzelebenen |
| ok | 3/3 | `WMS_LGL-BW_ATKIS_DTK_50_K_A` | WMS LGL-BW ATKIS Digitale Topographische Karte 1:50 000 Kombination |
| ok | 4/4 | `WMS_LGL-BW_ATKIS_DTK_Aktualitaet` | WMS LGL-BW ATKIS Digitale Topographische Karten Aktualität |
| ok | 2/2 | `WMS_LGL-BW_ATKIS_DGM_025` | WMS LGL-BW ATKIS Digitales Geländemodell 25 cm Lagegenauigkeit |
| ok | 4/4 | `WMS_LGL-BW_ATKIS_DGM_Aktualitaet` | WMS LGL-BW ATKIS Digitales Geländemodell Aktualität |
| ok | 1/1 | `WMS_LGL-BW_ATKIS_DOM_1_Schummerung` | WMS LGL-BW ATKIS Digitales Oberflächenmodell Schummerung 1m |
| ok | 15/15 | `WMS_LGL-BW_ATKIS_HIST_DOP_20_RGB` | WMS LGL-BW ATKIS Historische Digitale Orthophotos in Farbe 20 cm Bodenauflösung |
| ok | 3/3 | `WMS_LGL-BW_ATKIS_TK_Blattschnitte` | WMS LGL-BW ATKIS TK Blattschnitte |
| ok | 4/4 | `WMS_LGL-BW_DGK_5_E` | WMS LGL-BW Deutsche Grundkarte 1:5000 Einzelebenen |
| ok | 1/1 | `WMS_LGL-BW_DGK_5_Rahmengitter` | WMS LGL-BW Deutsche Grundkarte 1:5000 Rahmengitter |
| ok | 1/1 | `WMS_LGL-BW_FNO_Verf_25_K` | WMS LGL-BW Flurneuordnung Verfahrensgebiet 25000 |
| ok | 1/1 | `WMS_LGL-BW_HIST_DOP_TIME` | WMS LGL-BW HIST Digitale Orthophotos für Zeitschieberegler |
| ok | 10/10 | `WMS_LGL-BW_HIST_DOP_2010-2019` | WMS LGL-BW HIST Digitale Orthophotos in Farbe 2010-2019 |
| ok | 6/6 | `WMS_LGL-BW_HIST_DOP_2020-2029` | WMS LGL-BW HIST Digitale Orthophotos in Farbe 2020-2029 |
| ok | 11/11 | `WMS_LGL-BW_HIST_DOP_2000-2009` | WMS LGL-BW HIST Digitale Orthophotos in Farbe/Grau 2000-2009 |
| ok | 10/10 | `WMS_LGL-BW_HIST_DOP_1950-1959` | WMS LGL-BW HIST Digitale Orthophotos in Grau 1950-1959 |
| ok | 10/10 | `WMS_LGL-BW_HIST_DOP_1960-1969` | WMS LGL-BW HIST Digitale Orthophotos in Grau 1960-1969 |
| ok | 10/10 | `WMS_LGL-BW_HIST_DOP_1970-1979` | WMS LGL-BW HIST Digitale Orthophotos in Grau 1970-1979 |
| ok | 10/10 | `WMS_LGL-BW_HIST_DOP_1980-1989` | WMS LGL-BW HIST Digitale Orthophotos in Grau 1980-1989 |
| ok | 10/10 | `WMS_LGL-BW_HIST_DOP_1990-1999` | WMS LGL-BW HIST Digitale Orthophotos in Grau 1990-1999 |
| ok | 1/1 | `WMS_LGL-BW_HIST_FKWue_25_K` | WMS LGL-BW Historische Flurkarte Württemberg 1:2 500 |
| ok | 2/2 | `WMS_LGL-BW_HIST_FK_Rahmengitter` | WMS LGL-BW Historische Flurkartenwerke Rahmengitter |
| ok | 1/1 | `WMS_LGL-BW_HIST_GMKUeBad_10_K` | WMS LGL-BW Historische Gemarkungsübersicht Baden 1:10 000 Farbkombination |
| ok | 1/1 | `WMS_LGL-BW_KREISK_1200_K` | WMS LGL-BW Kreiskarte 1 : 1 200 000 Farbkombination |
| ok | 1/1 | `WMS_LGL-BW_Landnutzung` | WMS LGL-BW Landnutzung |
| ok | 4/4 | `WMS_LGL-BW_KM_Kacheln_GK3` | WMS LGL-BW Quadratische-KM-Kacheln in GK3 |
| ok | 4/4 | `WMS_LGL-BW_KM_Kacheln_UTM32` | WMS LGL-BW Quadratische-KM-Kacheln in UTM32 |
| ok | 4/4 | `WMS_LGL-BW_RK_600_E` | WMS LGL-BW Reliefkarte 1 : 600 000 Einzelebenen |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2017-04_1H` | WMS LGL-BW SAT Sentinel-2 2017-04 1H |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2017-04_2H` | WMS LGL-BW SAT Sentinel-2 2017-04 2H |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2017-05_1H` | WMS LGL-BW SAT Sentinel-2 2017-05 1H |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2017-05_2H` | WMS LGL-BW SAT Sentinel-2 2017-05 2H |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2018-04_1H` | WMS LGL-BW SAT Sentinel-2 2018-04 1H |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2018-04_2H` | WMS LGL-BW SAT Sentinel-2 2018-04 2H |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2018-05_1H` | WMS LGL-BW SAT Sentinel-2 2018-05 1H |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2018-05_2H` | WMS LGL-BW SAT Sentinel-2 2018-05 2H |
| ok | 9/9 | `WMS_LGL-BW_SAT_Sentinel-2_2020_CIR_10m` | WMS LGL-BW SAT Sentinel-2 2020 CIR 10m Bodenauflösung |
| ok | 9/9 | `WMS_LGL-BW_SAT_Sentinel-2_2020_RGB_10m` | WMS LGL-BW SAT Sentinel-2 2020 RGB 10m Bodenauflösung |
| ok | 5/5 | `WMS_LGL-BW_SAT_Sentinel-2_2021_CIR_10m` | WMS LGL-BW SAT Sentinel-2 2021 CIR 10m |
| ok | 5/5 | `WMS_LGL-BW_SAT_Sentinel-2_2021_RGB_10m` | WMS LGL-BW SAT Sentinel-2 2021 RGB 10m Bodenauflösung |
| ok | 7/7 | `WMS_LGL-BW_SAT_Sentinel-2_2022_RGB_10m` | WMS LGL-BW SAT Sentinel-2 2022 RGB 10m Bodenauflösung |
| ok | 7/7 | `WMS_LGL-BW_SAT_Sentinel-2_2023_CIR_10m` | WMS LGL-BW SAT Sentinel-2 2023 CIR 10m Bodenauflösung |
| ok | 7/7 | `WMS_LGL-BW_SAT_Sentinel-2_2023_RGB_10m` | WMS LGL-BW SAT Sentinel-2 2023 RGB 10m Bodenauflösung |
| ok | 7/7 | `WMS_LGL-BW_SAT_Sentinel-2_2024_CIR_10m` | WMS LGL-BW SAT Sentinel-2 2024 CIR 10m Bodenauflösung |
| ok | 7/7 | `WMS_LGL-BW_SAT_Sentinel-2_2024_RGB_10m` | WMS LGL-BW SAT Sentinel-2 2024 RGB 10m Bodenauflösung |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2025_CIR_10m` | WMS LGL-BW SAT Sentinel-2 2025 CIR 10m Bodenauflösung |
| ok | 8/8 | `WMS_LGL-BW_SAT_Sentinel-2_2025_RGB_10m` | WMS LGL-BW SAT Sentinel-2 2025 RGB 10m Bodenauflösung |
| ok | 2/2 | `WMS_LGL-BW_SAT_Sentinel-2_2026_CIR_10m` | WMS LGL-BW SAT Sentinel-2 2026 CIR 10m Bodenauflösung |
| ok | 1/1 | `WMS_LGL-BW_SAT_Sentinel-2_TIME` | WMS LGL-BW SAT Sentinel-2 für Zeitschieberegler |
| ok | 1/1 | `WMS_LGL-BW_SNK_100_K` | WMS LGL-BW Straßennetzkarte 1 : 100 000 Farbkombination |
| ok | 2/2 | `WMS_LGL-BW_HIST_TopAtlas_Blattschnitte` | WMS LGL-BW Topographischer Atlas Blattschnitte |
| ok | 3/3 | `WMS_LGL-BW_Touristische_Karten_Blattschnitte` | WMS LGL-BW Touristische Kartenwerke Blattschnitte |
| ok | 2/2 | `WMTS_LGL-BW_ALKIS_Basis` | WMTS LGL-BW ALKIS Basis |
| ok | 2/2 | `WMTS_LGL-BW_ALKIS_Basis_transparent` | WMTS LGL-BW ALKIS Basis transparent |
| refused | unexpected root <html> | `WMS_INSP_BW_Hoehe_Coverage_DOM5` | INSPIRE-WMS BW Höhenlage - Gitter-Coverage DOM5 |
| refused | unexpected root <html> | `WMS_LGL-BW_ATKIS_DOM_5` | WMS LGL-BW ATKIS Digitales Oberflächenmodell 5 m Lagegenauigkeit |

## LAD (heritage)

Base: `https://owsproxy.lgl-bw.de/owsproxy/ows/` — append the name below, then `?SERVICE=WMS&REQUEST=GetCapabilities`
(or `SERVICE=WMTS` where marked).

| Status | Layers | Service | Title |
|---|--:|---|---|
| **shipped** | 2/2 | `WMS_LAD_Archaeologische_Kulturdenkmale_BW` | WMS LAD Archäologische Kulturdenkmale in Baden-Württemberg |
| **shipped** | 1/1 | `WMS_LAD_UNESCO_Welterbestaetten_BW` | WMS LAD UNESCO Welterbestätten Baden-Württemberg |
| ok | 1/1 | `WMS_LAD_Kulturdenkmale_raumwirksam_hM` | WMS LAD Kulturdenkmale - in höchstem Maße raumwirksam für Windenergie |
| ok | 2/2 | `WMS_LAD_Kulturdenkmale_Bau_Kunstdenkmalpflege` | WMS LAD Kulturdenkmale der Bau- und Kunstdenkmalpflege in Baden-Württemberg |

## LUBW / RIPS

| Status | Layers | Title | Endpoint |
|---|--:|---|---|
| **shipped** | 11/11 | INSPIRE Schutzgebiete im UIS Baden-Württemberg Darstellungsdienst | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Schutzgebiete/MapServer/WMSServer` |
| **shipped** | 2/2 | WMS Überschwemmungsgebiet | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000003900001/MapServer/WMSServer` |
| ok | 1/1 | Baumart (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071000001/MapServer/WMSServer` |
| ok | 1/1 | Baumkronenhöhenmodell (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071500001/MapServer/WMSServer` |
| ok | 1/1 | Blattschnitt DOP20 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000017200001/MapServer/WMSServer` |
| ok | 1/1 | Blattschnitt Flurkarte 1:1.500 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000016900001/MapServer/WMSServer` |
| ok | 1/1 | Blattschnitt Flurkarte 1:2.500 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000017300001/MapServer/WMSServer` |
| ok | 1/1 | Flussdeich, Längsdamm, Schutzeinrichtung | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000004200001/MapServer/WMSServer` |
| ok | 1/1 | Flächenhaftes Naturdenkmal (FND) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000012200002/MapServer/WMSServer` |
| ok | 1/1 | Globalstrahlung | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/solareinstrahlung_wms/MapServer/WMSServer` |
| ok | 1/1 | INSPIRE Bewirtschaftungsgebiete Abfalldeponie im UIS Baden-Württemberg | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Bewirtschaftungsgebiete_Abfalldeponie/MapServer/WmsServer` |
| ok | 4/4 | INSPIRE Bewirtschaftungsgebiete Trinkwasserschutz im UIS Baden-Württem | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Bewirtschaftungsgebiete_Trinkwasserschutz/MapServer/WMSServer` |
| ok | 4/4 | INSPIRE Energiequellen im UIS Baden-Württemberg | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Energiequellen/MapServer/WMSServer` |
| ok | 7/7 | INSPIRE Gesundheit Lärm im UIS Baden-Württemberg | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Gesundheit_Laerm/MapServer/WMSServer` |
| ok | 9/9 | INSPIRE Gewässernetz im UIS Baden-Württemberg Darstellungsdienst | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Gewaessernetz/MapServer/WMSServer` |
| ok | 3/3 | INSPIRE Lebensräume im UIS Baden-Württemberg Darstellungsdienst | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Lebensraeume/MapServer/WMSServer` |
| ok | 3/3 | INSPIRE Produktionsanlagen im UIS Baden-Württemberg Darstellungsdienst | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/GDI/INSPIRE_Produktionsanlagen/MapServer/WMSServer` |
| ok | 1/1 | Landschaftsschutzgebiet (LSG) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000001300001/MapServer/WMSServer` |
| ok | 1/1 | Naturpark (NPK) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000006700001/MapServer/WMSServer` |
| ok | 1/1 | Naturraum | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000006900001/MapServer/WMSServer` |
| ok | 3/3 | Schummerungskarte 30 Meter | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000017700001/MapServer/WMSServer` |
| ok | 1/1 | Sohlenbauwerk (inkl. Absturz) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000019300001/MapServer/WMSServer` |
| ok | 1/1 | Vogelschutzgebiet (SPA) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000013200001/MapServer/WMSServer` |
| ok | 1/1 | WMS Biosphärengebiet | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000033500001/MapServer/WMSServer` |
| ok | 1/1 | WMS Biosphärengebiet Zone | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000065900001/MapServer/WMSServer` |
| ok | 1/1 | WMS Biotopkartierung | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000030200001/MapServer/WMSServer` |
| ok | 1/1 | WMS Blattschnitt DGK5 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000017400001/MapServer/WMSServer` |
| ok | 1/1 | WMS Blattschnitt TK100 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000013500001/MapServer/WMSServer` |
| ok | 1/1 | WMS Blattschnitt TK25 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000013300001/MapServer/WMSServer` |
| ok | 1/1 | WMS Blattschnitt TK25 Quadrante | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000017100001/MapServer/WMSServer` |
| ok | 1/1 | WMS Blattschnitt TÜK200 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000013600001/MapServer/WMSServer` |
| ok | 3/3 | WMS DHM 200 Meter, Schummerung | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000017800001/MapServer/WMSServer` |
| ok | 1/1 | WMS FFH-Gebiet | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000013100001/MapServer/WMSServer` |
| ok | 1/1 | WMS FFH-Mähwiese | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000055500001/MapServer/WMSServer` |
| ok | 1/1 | WMS Fliessgewässer (AWGN) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000001500001/MapServer/WMSServer` |
| ok | 5/5 | WMS Gemessene Windstatistik | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/brsweb_windrosen_messwerte_wms/MapServer/WMSServer` |
| ok | 1/1 | WMS Heilquellenschutzgebiet | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100007400200035/MapServer/WMSServer` |
| ok | 1/1 | WMS Hochwasserrückhaltebecken Talsperre | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000003800001/MapServer/WMSServer` |
| ok | 1/1 | WMS Kommunale Kläranlage | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000002800001/MapServer/WMSServer` |
| ok | 1/1 | WMS Naturdenkmal Einzelgebilde | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000006800002/MapServer/WMSServer` |
| ok | 1/1 | WMS Naturschutzgebiet | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000001200001/MapServer/WMSServer` |
| ok | 1/1 | WMS Regelungsbauwerk | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000019400001/MapServer/WMSServer` |
| ok | 1/1 | WMS Schöpfwerk | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000019500001/MapServer/WMSServer` |
| ok | 1/1 | WMS Stehendes Gewässer (AWGN) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000001400001/MapServer/WMSServer` |
| ok | 1/1 | WMS Waldstruktur (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071100001/MapServer/WMSServer` |
| ok | 1/1 | WMS Wasserkraftanlage | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000004100001/MapServer/WMSServer` |
| ok | 1/1 | WMS Wasserschutzgebiet | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100004000200031/MapServer/WMSServer` |
| ok | 1/1 | WMS Wasserschutzgebietszone | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100007500200036_AGGR/MapServer/WMSServer` |
| refused | 0/5 | Batteriespeicher aggregiert | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/Batteriespeicher_aggregiert/MapServer/WMSServer` |
| refused | 0/12 | Grünfläche mit Sonderfunktion am Tage (Klimaanalyse) | `https://rips-gdi.lubw.baden-wuerttemberg.de/ArcGIS/services/wms/Klimaatlas_Planungshinweiskarte/MapServer/WMSServer` |
| refused | 0/35 | Kaltluftvolumenstromdichte (Klimaanalyse) | `https://rips-gdi.lubw.baden-wuerttemberg.de/ArcGIS/services/wms/Klimaatlas_erweiterteParameter/MapServer/WMSServer` |
| refused | 0/1 | Kommunale Wärmeplanung Übersicht | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/Kommunale_Waermeplanung_Uebersicht/MapServer/WMSServer` |
| refused | 0/5 | Mittlere meteorologische Turbulenzintensität | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_mittlere_meteorologische_umgebungsturbulenz/MapServer/WMSServer` |
| refused | 0/5 | Mittlere Windgeschwindigkeit | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_mittlere_windgeschwindigkeit/MapServer/WMSServer` |
| refused | 0/1 | Standardunsicherheit mittlere Windgeschwindigkeit | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_unsicherheit_mittlere_windgeschwindigkeit/MapServer/WMSServer` |
| refused | 0/5 | Standortgüte WEA Enercon E-138 | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_standortguete_e138/MapServer/WMSServer` |
| refused | 0/5 | Standortgüte WEA Vestas V-126 | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_standortguete_v126/MapServer/WMSServer` |
| refused | 0/1 | Streuobsterhebung (Fernerkundung) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/Streuobsterhebung_Fernerkundung/MapServer/WMSServer` |
| refused | 0/1 | Windenergieanlage (Potenzial) | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_Ermittelte_Windpotenzialflaeche/MapServer/WMSServer` |
| refused | 0/30 | Windgeschwindigkeit und - Richtung (Klimaanalyse) | `https://rips-gdi.lubw.baden-wuerttemberg.de/ArcGIS/services/wms/Klimaatlas_Klimaanalysekarte/MapServer/WMSServer` |
| refused | 0/1 | WMS 110kV-Verteilnetz Netze BW | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/110KV_Verteilernetz/MapServer/WMSServer` |
| refused | 0/1 | WMS Batteriespeicher Industrie-/ Großspeicher (MaStR) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/Batteriespeicher_Industrie_Grossspeicher/MapServer/WMSServer` |
| refused | 0/1 | WMS Blattschnitt TK50 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000013400001/MapServer/WMSServer` |
| refused | 0/4 | WMS Ermittelte Windpotenzialfläche (Gebietsebene) | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_Ermittelte_Windpotenzialflaeche_Gebietsebene/MapServer/WMSServer` |
| refused | 0/1 | WMS Gewässereinzugsgebiet (AWGN) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000001600001/MapServer/WMSServer` |
| refused | 0/1 | WMS Hellmann-Exponent 100m bis 160m über Grund | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_scherung_hellmann/MapServer/WMSServer` |
| refused | 0/1 | WMS Historische Moorbodenkarte | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000007700002/MapServer/WMSServer` |
| refused | 0/5 | WMS Jahresertrag WEA Enercon E-138 | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_jahresertrag_e138/MapServer/WMSServer` |
| refused | 0/5 | WMS Jahresertrag WEA Vestas V-126 | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_jahresertrag_v126/MapServer/WMSServer` |
| refused | 0/5 | WMS Jahresertrag WEA Vestas V-150 | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_jahresertrag_v150/MapServer/WMSServer` |
| refused | 0/5 | WMS Mittlere gekappte Windleistungsdichte | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_mittlere_gekappte_windleistungsdichte/MapServer/WMSServer` |
| refused | 0/5 | WMS Mittlere Windleistungsdichte | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_mittlere_windleistungsdichte/MapServer/WMSServer` |
| refused | 0/1 | WMS Netzausbauplanung | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/Netzausbauplan/MapServer/WMSServer` |
| refused | 0/1 | WMS Pumpspeicher | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/Pumpspeicher/MapServer/WMSServer` |
| refused | 0/5 | WMS Standortgüte WEA Vestas V-150 | `https://rips-dienste.lubw.baden-wuerttemberg.de/arcgis/services/wms/wms_windatlas_standortguete_v150/MapServer/WMSServer` |
| unreachable | unreachable | Biomethaneinspeiseanlage (Bestand) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000060800001/MapServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:1.000.000 (DTK1000) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000624002/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:10.000 (DTK10) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000618012_2020/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:100.000 (DTK100) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000621010_2018/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:25.000 (DTK25) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000619001/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:25.000 (DTK25) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000619002/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:25.000 (DTK25) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000619010_2018/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:25.000 (DTK25) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000619011_2019/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:25.000 (DTK25) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000619012_2020/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:25.000 (DTK25) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000619013_2021/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:50.000 (DTK50) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000620001/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:50.000 (DTK50) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000620010_2018/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:50.000 (DTK50) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000620013_2021/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:50.000 (DTK50) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000620014_2022/ImageServer/WMSServer` |
| unreachable | unreachable | Digitale Topographische Karte 1:500.000 (DTK500) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000623010_2018/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Geländemodell 1 Meter (DGM1) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242001/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Geländemodell 1 Meter (DGM1) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242004/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Geländemodell 1 Meter (DGM1) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242005/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Geländemodell 1 Meter (DGM1) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242006/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Geländemodell 1 Meter (DGM1) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices\GEO_UIS_01000242002/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Orthophoto 20cm (DOP20) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000211009_143/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Orthophoto 20cm (DOP20) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000211009_432/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Orthophoto 20cm (DOP20) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000211009_443/ImageServer/WMSServer` |
| unreachable | unreachable | Digitales Orthophoto 20cm (DOP20) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000211009_co/ImageServer/WMSServer` |
| unreachable | unreachable | Ergebnis aus der Hydrogeologischen Kartierung (HGK) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/hydrologische_karten_mod_Teil_HGK/MapServer/WMSServer` |
| unreachable | unreachable | Fotostandort Gewässerstrukturkartierung | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000059200001/MapServer/WMSServer` |
| unreachable | unreachable | Geländepunkt | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000035400001/MapServer/WMSServer` |
| unreachable | unreachable | Mittlere jährliche Sickerwasserrate und Grundwasserneubildung 1991-202 | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000066200001/MapServer/WMSServer` |
| unreachable | unreachable | PV-Freifläche, benachteiligtes Gebiet | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/Benachteiligte_Gebiete_EEG/MapServer/WMSServer` |
| unreachable | unreachable | Schummerungskarte 5 Meter | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000306001/ImageServer/WMSServer` |
| unreachable | unreachable | Topographische Karte 1:100.000 (TK 100) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329003_2004/ImageServer/WMSServer` |
| unreachable | unreachable | Topographische Karte 1:100.000 (TK 100) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329007_2009/ImageServer/WMSServer` |
| unreachable | unreachable | Topographische Karte 1:100.000 (TK 100) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329011_2013/ImageServer/WMSServer` |
| unreachable | unreachable | Topographische Karte 1:100.000 (TK 100) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329012_UML/ImageServer/WMSServer` |
| unreachable | unreachable | Topographische Karte 1:50.000 (TK 50) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328002_2003/ImageServer/WMSServer` |
| unreachable | unreachable | Topographische Karte 1:50.000 (TK 50) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328005_2006/ImageServer/WMSServer` |
| unreachable | unreachable | True Orthophoto (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300001/ImageServer/WMSServer` |
| unreachable | unreachable | True Orthophoto (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300002/ImageServer/WMSServer` |
| unreachable | unreachable | True Orthophoto (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300003/ImageServer/WMSServer` |
| unreachable | unreachable | True Orthophoto (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300004/ImageServer/WMSServer` |
| unreachable | unreachable | True Orthophoto (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300005/ImageServer/WMSServer` |
| unreachable | unreachable | True Orthophoto (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300006/ImageServer/WMSServer` |
| unreachable | unreachable | True Orthophoto (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300007/ImageServer/WMSServer` |
| unreachable | unreachable | Veränderung der Vegetationshöhe (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071200001/MapServer/WMSServer` |
| unreachable | unreachable | WMS 4. BImSchV Windenergieanlage | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000045100001/MapServer/WMSServer` |
| unreachable | unreachable | WMS Abwasserkanal Typ | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000029700002/MapServer/WMSServer` |
| unreachable | unreachable | WMS Blattschnitt DOP20 (bis 2017) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000017200002/MapServer/WMSServer` |
| unreachable | unreachable | WMS Blattschnitt TK100 (Intranet) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000013500002/MapServer/WMSServer` |
| unreachable | unreachable | WMS Blattschnitt TK25 (Intranet) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000013300002/MapServer/WMSServer` |
| unreachable | unreachable | WMS Blattschnitt TK50 (Intranet) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000013400002/MapServer/WMSServer` |
| unreachable | unreachable | WMS Digitales Geländemodell (NLP) | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071400001/ImageServer/WMSServer` |
| unreachable | unreachable | WMS Digitales Geländemodell 1 Meter (DGM1), 2000 - 2005 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242010/ImageServer/WMSServer` |
| unreachable | unreachable | WMS Digitales Geländemodell 1 Meter (DGM1), Hangneigung Grad | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242003/ImageServer/WMSServer` |
| unreachable | unreachable | WMS Digitales Geländemodell 1 Meter (DGM1), Isolinien 1 m | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242008/ImageServer/WMSServer` |
| unreachable | unreachable | WMS Digitales Geländemodell 1 Meter (DGM1), Isolinien 100 m | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000242009/ImageServer/WMSServer` |
| unreachable | unreachable | WMS Digitales Geländemodell 5 Meter (DGM5), Schummerung | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices\GEO_UIS_01000306001/ImageServer/WMSServer` |
| unreachable | unreachable | WMS Digitales Orthophoto 20 cm (sw) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000211009_sw/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK10 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000618001/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK10 (sw) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000618002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK10-HIST Auslieferung 2018 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000618010_2018/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK10-HIST Auslieferung 2019 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000618011_2019/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK10-HIST Auslieferung 2021 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000618013_2021/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK10-HIST Auslieferung 2022 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000618014_2022/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK100 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000621001/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK100 (sw) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000621002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK100-HIST Auslieferung 2019 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000621011_2019/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK100-HIST Auslieferung 2020 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000621012_2020/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK100-HIST Auslieferung 2021 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000621013_2021/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK100-HIST Auslieferung 2022 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000621014_2022/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK1000 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000624001/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK1000-HIST Auslieferung 2018 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000624010_2018/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK250 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000622001/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK250 (sw) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000622002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK250-HIST Auslieferung 2018 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000622010_2018/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK50 (sw) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000620002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK50-HIST Auslieferung 2019 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000620011_2019/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK50-HIST Auslieferung 2020 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000620012_2020/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK500 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000623001/ImageServer/WMSServer` |
| unreachable | unreachable | WMS DTK500 (sw) | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000623002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS Fotostandort Gewässerprofildatenbank (GPro) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000035500001/MapServer/WMSServer` |
| unreachable | unreachable | WMS Luftmessstelle | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000004900001/MapServer/WMSServer` |
| unreachable | unreachable | WMS Metadaten Digitales Geländemodell | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000066300001/MapServer/WMSServer` |
| unreachable | unreachable | WMS Metadaten Digitales Geländemodell, 2000 - 2005 | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000066300002/MapServer/WMSServer` |
| unreachable | unreachable | WMS Profil (Gewässer, Vorland, Deich/Damm) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000035300001/MapServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2002 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329001_2002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2003 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329002_2003/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2005 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329004_2005/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2006 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329005_2006/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2008 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329006_2008/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2010 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329008_2010/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2011 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329009_2011/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK100-HIST Auslieferung 2012 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000329010_2012/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2002 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327001_2002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2003 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327002_2003/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2004 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327003_2004/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2005 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327004_2005/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2006 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327005_2006/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2008 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327006_2008/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2009 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327007_2009/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2010 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327008_2010/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2011 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327033_2011/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2012 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327034_2012/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Auslieferung 2013 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327035_2013/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK25-HIST Umland | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000327036_UML/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2002 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328001_2002/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2004 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328003_2004/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2005 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328004_2005/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2008 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328006_2008/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2009 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328007_2009/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2010 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328008_2010/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2011 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328009_2011/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2012 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328010_2012/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Auslieferung 2013 | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328011_2013/ImageServer/WMSServer` |
| unreachable | unreachable | WMS TK50-HIST Umland | `https://rips-rasterdaten.lubw.bwl.de/arcgis/services/Imageservices/GEO_UIS_01000328012_UML/ImageServer/WMSServer` |
| unreachable | unreachable | WMS True Orthophoto (NLP) Stand 2021 | `https://rips-gdi.lubw.baden-wuerttemberg.de/arcgis/services/wms/UIS_0100000071300008/ImageServer/WMSServer` |
| unreachable | unreachable | WMS ÜK 500 (farbig) | `https://ripswebgis.lubw.bwl.de/arcgis/services/wms/UIS_0100000006200001/MapServer/WMSServer` |

## LGRB (geology)

Base: `https://services.lgrb-bw.de/` — append the name below, then `?SERVICE=WMS&REQUEST=GetCapabilities`
(or `SERVICE=WMTS` where marked).

| Status | Layers | Service | Title |
|---|--:|---|---|
| **shipped** | 10/10 | `ms/lgrb_geotope` | Geotope und Geotouristische Objekte |
| **shipped** | 9/9 | `ms/lgrb_geola_ing` | Ingenieurgeologische Gefahrenhinweiskarte (IGHK50) |
| ok | 17/17 | `ms/lgrb_berechtsamskarte` | Berechtsamskarte: Konzessionen für Bodenschätze 1:5.000 |
| ok | 6/6 | `ms/lgrb_bergbau` | Bergbau (RISBinBW) |
| ok | 11/11 | `bodschis/services/bs_v` | Bodenfunktionsbewertung auf Grundlage der digitalen Bodenschätzungsdaten (FESCH  |
| ok | 20/20 | `ms/lgrb_geola_bod` | Bodenkarte (BK 50) |
| ok | 12/12 | `ms/lgrb_geola_hyd` | Hydrogeologische Deckschicht (HK50) |
| ok | 8/8 | `ms/lgrb_roh_rsg` | Rohstoffgewinnung (ROH) |
| ok | 10/10 | `ms/lgrb_hyd_sf` | Schutzfunktion der Grundwasserüberdeckung 1:50.000 |
| refused | 0/0 | `ms/lgrb_zbdb` | Zentrale Bohrdatenbank (ZBDB) |

## BKG

Base: `https://sgx.geodatenzentrum.de/` — append the name below, then `?SERVICE=WMS&REQUEST=GetCapabilities`
(or `SERVICE=WMTS` where marked).

| Status | Layers | Service | Title |
|---|--:|---|---|
| **shipped** | 2/2 | `wms_basemapde` | WMS basemap.de |
| **shipped** | 2/2 | `wms_dtk100` | WMS Digitale Topographische Karte 1:100 000 |
| **shipped** | 2/2 | `wms_dtk250` | WMS Digitale Topographische Karte 1:250 000 |
| **shipped** | 6/6 | `wmts_topplus_open/1.0.0/WMTSCapabilities.xml` | WMTS TopPlusOpen |
| ok | 1/1 | `wms_clc5_2012_inspire` | INSPIRE-WMS CORINE Land Cover 5 ha 2012 |
| ok | 1/1 | `wms_clc5_2015_inspire` | INSPIRE-WMS CORINE Land Cover 5 ha 2015 |
| ok | 1/1 | `wms_clc5_2018_inspire` | INSPIRE-WMS CORINE Land Cover 5 ha 2018 |
| ok | 26/26 | `wms_dlm250_inspire` | INSPIRE-WMS Digital Landscape Model 1:250 000 |
| ok | 1/1 | `wms_dgm200_inspire` | INSPIRE-WMS Elevation DGM200 |
| ok | 1/1 | `wms_gn250_inspire` | INSPIRE-WMS Geographical Names 1:250 000 |
| ok | 2/2 | `wms_dtk1000` | WMS Digitale Topographische Karte 1:1 000 000 |
| ok | 2/2 | `wms_dtk500` | WMS Digitale Topographische Karte 1:500 000 |
| ok | 18/18 | `wms_lb-de` | WMS Landbedeckung Deutschland |
| ok | 6/6 | `wms_topplus_open` | WMS TopPlusOpen |
| ok | 2/2 | `wmts_basemapde/1.0.0/WMTSCapabilities.xml` | WMTS basemap.de Web Raster |

## Standing faults

| Since | Service | What the server sends | Still works |
|---|---|---|---|
| 2026-09-21 | USFS Motor Vehicle Use Map (`EDW_MVUM_02`) | WMS capabilities with no named layer and no CRS, under 1.3.0 and 1.1.1; the REST description shows the map layers on ArcGIS Server 11.5, and for an hour every render was blank and every feature query failed | `GetMap` by layer id (`LAYERS=1,2`) once the data is back; the library now reaches the service through its REST description (`MapServer?f=json`), one export source per layer |

## Roadworks by state

Searched 2026-09-21 for what the other states publish beside Baden-Württemberg's
MobiData, Rhineland-Palatinate's Mobilitätsatlas and Karlsruhe's TRK. Every hit that
answered is shipped; the point of the table is the misses, so the next pass does not
repeat the search.

| Status | Layers | State | Endpoint |
|---|--:|---|---|
| **shipped** | 13/13 | Schleswig-Holstein (also HH, NI, MV roadworks, traffic disruptions) | `https://dienste.gdi-sh.de/WMS_SH_Baustelleninformationen` |
| **shipped** | 2/2 | Hamburg — roadworks | `https://geodienste.hamburg.de/hh_wms_baustellen` |
| **shipped** | 2/2 | Hamburg — motorway diversion routes | `https://geodienste.hamburg.de/HH_WMS_Bedarfsumleitungen` |
| **shipped** | 2/2 | Hamburg — live traffic | `https://geodienste.hamburg.de/wms_hh_verkehrslage` |
| **shipped** | 5/5 | Hamburg — police traffic reports | `https://geodienste.hamburg.de/wms_verkehrsinformation` |
| **shipped** | 2/2 | Saxony — closures and diversions | `https://geodienste.sachsen.de/wms_list_baustellen/guest` |
| **shipped** | 3/3 | Brandenburg | `https://isk.geobasis-bb.de/ows/baustelleninfo_wms` |
| **shipped** | 3/3 | Mecklenburg-Vorpommern | `https://www.geodaten-mv.de/dienste/baustellen_lsbv_wms` |
| unreachable | — | Saxony — planned roadworks | `gdi-sbv.list.smwa.sachsen.de` answered nothing |
| refused | — | North Rhine-Westphalia (NWSIB) | self-signed certificate chain, so the import cannot fetch it |
| — | — | Lower Saxony | no state-level service found; its roadworks appear inside Schleswig-Holstein's |

Not found under these terms at state level: Bavaria, Hesse, Berlin, Thuringia,
Saxony-Anhalt, Saarland, Bremen. Cologne, Dortmund, Bottrop and the KRZN municipalities
publish city-level roadworks, which the list does not carry.

## MobiData-BW

| Status | Layers | Title | Endpoint |
|---|--:|---|---|
| ok | 1/1 | Haltestellen Baden-Württemberg | `https://haleconnect.com/ows/services/org.1473.15f035da-725b-4f16-854a-3e72ef6352a3_wms` |
| ok | 12/12 | Parkdaten Freiburg im Breisgau | `https://geoportal.freiburg.de/wms/gdm_pls/gdm_pls` |
| ok | 2/2 | RadNETZ Baden-Württemberg | `https://haleconnect.com/ows/services/org.1473.5952e213-cdf2-42f3-a3be-f8110719cb57_wms` |

## Karlsruhe TRK

Base: `https://mobil.trk.de/geoserver/` — append the name below, then `?SERVICE=WMS&REQUEST=GetCapabilities`
(or `SERVICE=WMTS` where marked).

| Status | Layers | Service | Title |
|---|--:|---|---|
| **shipped** | 43/43 | `ows` | GeoServer: Baustellen, Fähren, Umweltzonen, Motorradparken |

## Freiburg

Base: `https://geoportal.freiburg.de/` — append the name below, then `?SERVICE=WMS&REQUEST=GetCapabilities`
(or `SERVICE=WMTS` where marked).

| Status | Layers | Service | Title |
|---|--:|---|---|
| ok | 21/21 | `wms/verma_stadtplan_hist/verma_stadtplan_hist` | Historischer Stadtplan Freiburg |
| ok | 17/17 | `wms/uwsa_naturschutz/uwsa_naturschutz` | Naturdenkmale Freiburg |

## UBA

Base: `https://datahub.uba.de/server/services/VeLa/LK/MapServer/` — append the name below, then `?SERVICE=WMS&REQUEST=GetCapabilities`
(or `SERVICE=WMTS` where marked).

| Status | Layers | Service | Title |
|---|--:|---|---|
| refused | unexpected root <html> | `WMSServer` | Lärmkartierung: Schienenverkehrslärm Nacht (LNight) |

