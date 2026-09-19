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
"""

import json, re, subprocess, sys, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

LIBRARY = Path(__file__).resolve().parent.parent / "app/src/main/assets/library.json"
WEB_MERCATOR = {"3857", "900913", "102100", "102113", "41001"}
NOT_RASTER = {"svg+xml"}
MAX_ZOOM = 30
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
    mercator = {
        text(kid(ms, "Identifier")): [text(kid(m, "Identifier")) for m in kids(ms, "TileMatrix")]
        for ms in kids(contents, "TileMatrixSet")
        if crs_code(text(kid(ms, "SupportedCRS"))) in WEB_MERCATOR
    }

    usable = refused = 0
    for layer in kids(contents, "Layer"):
        links = (text(kid(k, "TileMatrixSet")) for k in kids(layer, "TileMatrixSetLink"))
        levels = next((mercator[s] for s in links if mercator.get(s)), None)
        formats = [text(f) for f in kids(layer, "Format")]
        if levels and has_raster(formats) and addressable(levels):
            usable += 1
        else:
            refused += 1
    return usable, refused


def check(entry):
    body = fetch(entry["url"])
    if not body:
        return entry, "unreachable", 0, 0
    try:
        root = ET.fromstring(body)
        name = local(root)
        if name in ("WMS_Capabilities", "WMT_MS_Capabilities"):
            usable, refused = check_wms(root)
        elif name == "Capabilities":
            usable, refused = check_wmts(root)
        else:
            raise Unusable(f"unexpected root <{name}>")
    except ET.ParseError as e:
        return entry, f"not XML ({e})", 0, 0
    except Unusable as e:
        return entry, str(e), 0, 0
    return entry, None, usable, refused


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

    if broken:
        print(f"\n{len(broken)} of {len(entries)} entries need attention.")
        return 1
    print(f"\nAll {len(entries)} entries have usable layers.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
