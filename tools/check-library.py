#!/usr/bin/env python3
"""Check every service in app/src/main/assets/library.json still has usable layers.

The bundled list claims only that a service had layers this proxy can serve. Services
move, withdraw and change their layer sets — two already have during this project — and
a bundled list needs a new build to correct, so knowing an entry broke matters before a
user finds out.

This applies the same rules as CapabilitiesParser: WebMercator must be on offer, a
raster image format must be advertised, and WMTS tile levels must be addressable as
plain or zero-padded numbers. It is a reimplementation, not the Kotlin, so treat a
disagreement as a reason to check both — and it answers only yes or no, never which
format or template the Kotlin would pick, so that there is as little to keep in step as
the question allows.

Exit status is 1 if any entry has no usable layer, so it can gate a scheduled job.

With --update it also writes each entry's layer counts back into the file, and stamps
`verified` with today's date when every entry passed. Those counts are shown next to each
service in the app, which is why they are measured here rather than typed: a hand-written
"very large layer list" only appears where someone remembers it, and goes stale silently.
An entry that could not be reached on this run keeps the counts it already had.
"""

import datetime, json, re, subprocess, sys, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

LIBRARY = Path(__file__).resolve().parent.parent / "app/src/main/assets/library.json"
WEB_MERCATOR = {"3857", "900913", "102100", "102113", "41001"}
NOT_RASTER = {"svg+xml"}
MAX_ZOOM = 30
# WebMercatorQuad's zoom-0 scale denominator (OGC 17-083r2). Every level halves it, so
# what a server declares says which zoom a level really is, whatever it calls that level.
WEB_MERCATOR_SCALE_0 = 559082264.0287178
UA = "WMSproxy-library-check (+https://github.com/c0dev0id/WMSproxy)"


class Unusable(Exception):
    """The document itself rules the service out, so its layers were never counted."""


def fetch(url, timeout=60):
    r = subprocess.run(
        ["curl", "-sSL", "--max-time", str(timeout), "-H", f"User-Agent: {UA}", url],
        capture_output=True,
    )
    return r.stdout


def local(e):
    return e.tag.split("}")[-1]


def namespace(e):
    return e.tag[1:e.tag.index("}")] if e.tag.startswith("{") else ""


def kids(e, name):
    return [c for c in e if local(c) == name]


def kid(e, name):
    found = kids(e, name)
    return found[0] if found else None


def text(e):
    return (e.text or "").strip() if e is not None else ""


def crs_code(value):
    return value.strip().rsplit(":", 1)[-1].rsplit("/", 1)[-1]


def is_raster(media_type):
    base = media_type.split(";")[0].strip().lower()
    if not base.startswith("image/"):
        return False
    subtype = base.split("/", 1)[1]
    return bool(subtype) and subtype not in NOT_RASTER


def has_raster(advertised):
    return any(is_raster(f) for f in advertised)


def numbered(ids, prefix=""):
    """True when every id is prefix + a zoom number, ascending and evenly zero-padded."""
    pairs = []
    for identifier in ids:
        if not identifier.startswith(prefix):
            return False
        rest = identifier[len(prefix):]
        if not rest.isdigit() or not 0 <= int(rest) <= MAX_ZOOM:
            return False
        pairs.append((rest, int(rest)))
    if not all(b[1] > a[1] for a, b in zip(pairs, pairs[1:])):
        return False
    if all(rest == str(value) for rest, value in pairs):
        return True
    width = len(pairs[0][0])
    return all(len(r) == width and r == str(v).zfill(width) for r, v in pairs)


def levels_for(ids):
    """The zoom levels these identifiers denote, however they are written."""
    if not ids:
        return None
    direct = numbered_levels(ids)
    if direct is not None:
        return direct
    prefix = re.sub(r"\d+$", "", ids[0])
    return numbered_levels(ids, prefix) if prefix else None


def numbered_levels(ids, prefix=""):
    out = []
    for identifier in ids:
        if not identifier.startswith(prefix):
            return None
        rest = identifier[len(prefix):]
        if not rest.isdigit() or not 0 <= int(rest) <= MAX_ZOOM:
            return None
        out.append(int(rest))
    return out if all(b > a for a, b in zip(out, out[1:])) else None


def names_its_true_zoom(ids, denominators):
    """Level N must really be zoom N, which only the scale denominator can confirm.

    Not MatrixWidth: ArcGIS pads its matrices to 2^z + 1 while numbering levels
    correctly, so measuring the width condemns four working USGS services. A set that
    declares no denominators cannot be checked and is taken at its word.
    """
    levels = levels_for(ids)
    if levels is None:
        return False
    for i, level in enumerate(levels):
        declared = denominators[i] if i < len(denominators) else None
        if declared is None:
            continue
        expected = WEB_MERCATOR_SCALE_0 / (2 ** level)
        if abs(declared - expected) > expected * 0.01:
            return False
    return True


def addressable(ids):
    """True when a {z} template can name every one of these tile levels."""
    if not ids:
        return False
    if numbered(ids):
        return True
    prefix = re.sub(r"\d+$", "", ids[0])
    return bool(prefix) and numbered(ids, prefix)


def check_wms(root):
    capability = kid(root, "Capability")
    if capability is None:
        raise Unusable("no Capability section")
    version = root.get("version", "1.1.1")
    crs_tag = "CRS" if version.startswith("1.3") else "SRS"
    request = kid(capability, "Request")
    get_map = kid(request, "GetMap") if request is not None else None
    if get_map is None:
        raise Unusable("no GetMap")
    if not has_raster(text(f) for f in kids(get_map, "Format")):
        raise Unusable("no raster format")

    usable = refused = 0

    def walk(layer, inherited):
        nonlocal usable, refused
        own = {text(c).upper() for c in kids(layer, crs_tag)}
        other = {text(c).upper() for c in kids(layer, "SRS" if crs_tag == "CRS" else "CRS")}
        crs = inherited | own | other
        if text(kid(layer, "Name")):
            if any(crs_code(c) in WEB_MERCATOR for c in crs):
                usable += 1
            else:
                refused += 1
        for child in kids(layer, "Layer"):
            walk(child, crs)

    for layer in kids(capability, "Layer"):
        walk(layer, set())
    return usable, refused


def check_wmts(root):
    contents = kid(root, "Contents")
    if contents is None:
        raise Unusable("no Contents")

    # Only WebMercator sets are collected, so a layer linked to anything else simply
    # finds nothing and is refused for it.
    mercator = {}
    for ms in kids(contents, "TileMatrixSet"):
        if crs_code(text(kid(ms, "SupportedCRS"))) not in WEB_MERCATOR:
            continue
        mats = kids(ms, "TileMatrix")
        ids = [text(kid(m, "Identifier")) for m in mats]
        sds = []
        for m in mats:
            raw = text(kid(m, "ScaleDenominator"))
            try:
                sds.append(float(raw))
            except ValueError:
                sds.append(None)
        mercator[text(kid(ms, "Identifier"))] = (ids, sds)

    usable = refused = 0
    for layer in kids(contents, "Layer"):
        links = [text(kid(k, "TileMatrixSet")) for k in kids(layer, "TileMatrixSetLink")]
        # The deepest set that can actually be addressed, mirroring the Kotlin: taking
        # the first WebMercator set picks basemap.de's offset one, whose level 00 is
        # really zoom 5.
        candidates = [mercator[s] for s in links if s in mercator]
        valid = [c for c in candidates if addressable(c[0]) and names_its_true_zoom(*c)]
        best = max(valid, key=lambda c: len(c[0])) if valid else None
        formats = [text(f) for f in kids(layer, "Format")]
        if best and has_raster(formats):
            usable += 1
        else:
            refused += 1
    return usable, refused


def check(entry, retry=True):
    body = fetch(entry["url"])
    if not body:
        return entry, "unreachable", 0, 0
    try:
        root = ET.fromstring(body)
    except ET.ParseError as e:
        # A body that stops mid-document is a cut connection, not a broken service; one
        # large catalogue here does it about half the time. Retried once, because a
        # service that really is serving malformed XML fails the same way twice.
        if retry:
            return check(entry, retry=False)
        return entry, f"not XML ({e})", 0, 0
    try:
        name = local(root)
        if name in ("WMS_Capabilities", "WMT_MS_Capabilities"):
            usable, refused = check_wms(root)
        elif name == "Capabilities" and "/wcs/" not in namespace(root):
            usable, refused = check_wmts(root)
        elif name in ("Capabilities", "WCS_Capabilities"):
            # WCS 2.x calls its root Capabilities too; mirrors the app, which refuses it.
            raise Unusable("WCS, not a map service")
        else:
            raise Unusable(f"unexpected root <{name}>")
    except Unusable as e:
        return entry, str(e), 0, 0
    return entry, None, usable, refused


def update(library, results):
    """Writes measured counts back, leaving anything unreachable as it was."""
    by_url = {e["url"]: e for e in library["entries"]}
    for entry, problem, usable, refused in results:
        if problem:
            continue
        stored = by_url[entry["url"]]
        stored["usable"] = usable
        stored["refused"] = refused
    if all(not problem and usable for _, problem, usable, _ in results):
        library["verified"] = datetime.date.today().isoformat()
    LIBRARY.write_text(
        json.dumps(library, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main():
    library = json.loads(LIBRARY.read_text(encoding="utf-8"))
    entries = library["entries"]
    print(f"{len(entries)} entries, last verified {library.get('verified', 'never')}\n")

    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(check, entries))

    broken = []
    for entry, problem, usable, refused in sorted(results, key=lambda r: r[0]["name"]):
        if problem or usable == 0:
            broken.append(entry)
            print(f"BROKEN  {entry['name']}: {problem or 'no usable layers'}")
        else:
            print(f"ok      {entry['name']}: {usable} usable, {refused} refused")

    if "--update" in sys.argv:
        update(library, results)
        print(f"\nWrote counts for {sum(1 for r in results if not r[1])} entries.")

    if broken:
        print(f"\n{len(broken)} of {len(entries)} entries need attention.")
        return 1
    print(f"\nAll {len(entries)} entries have usable layers.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
