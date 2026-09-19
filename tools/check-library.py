#!/usr/bin/env python3
"""Check every service in app/src/main/assets/library.json still has usable layers.

The bundled list claims only that a service had layers this proxy can serve. Services
move, withdraw and change their layer sets — two already have during this project — and
a bundled list needs a new build to correct, so knowing an entry broke matters before a
user finds out.

This applies the same rules as CapabilitiesParser: WebMercator must be on offer, a
raster image format must be advertised, and WMTS tile levels must be addressable as
plain or zero-padded numbers. It is a reimplementation, not the Kotlin, so treat a
disagreement as a reason to check both.

Exit status is 1 if any entry has no usable layer, so it can gate a scheduled job.
"""

import json, re, subprocess, sys, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

LIBRARY = Path(__file__).resolve().parent.parent / "app/src/main/assets/library.json"
WEB_MERCATOR = {"3857", "900913", "102100", "102113", "41001"}
PREFERRED = ("image/png", "image/jpeg", "image/webp", "image/gif")
NOT_RASTER = {"svg+xml"}
MAX_ZOOM = 30
UA = "WMSproxy-library-check (+https://github.com/c0dev0id/WMSproxy)"


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


def choose_format(advertised):
    for preferred in PREFERRED:
        for offered in advertised:
            if offered.startswith(preferred):
                return offered
    return next((f for f in advertised if is_raster(f)), None)


def zoom_placeholder(ids, prefix=""):
    pairs = []
    for identifier in ids:
        if not identifier.startswith(prefix):
            return None
        rest = identifier[len(prefix):]
        if not rest or not rest.isdigit():
            return None
        value = int(rest)
        if not 0 <= value <= MAX_ZOOM:
            return None
        pairs.append((rest, value))
    if not all(b[1] > a[1] for a, b in zip(pairs, pairs[1:])):
        return None
    if all(rest == str(value) for rest, value in pairs):
        return "{z}"
    width = len(pairs[0][0])
    if all(len(r) == width and r == str(v).zfill(width) for r, v in pairs):
        return "{z:0%d}" % width
    return None


def zoom_template(ids):
    if not ids:
        return None
    direct = zoom_placeholder(ids)
    if direct:
        return direct
    prefix = re.sub(r"\d+$", "", ids[0])
    if not prefix:
        return None
    placeholder = zoom_placeholder(ids, prefix)
    return prefix + placeholder if placeholder else None


def check_wms(root):
    capability = kid(root, "Capability")
    if capability is None:
        return "no Capability section", 0, 0
    version = root.get("version", "1.1.1")
    crs_tag = "CRS" if version.startswith("1.3") else "SRS"
    request = kid(capability, "Request")
    get_map = kid(request, "GetMap") if request is not None else None
    if get_map is None:
        return "no GetMap", 0, 0
    if not choose_format([text(f) for f in kids(get_map, "Format")]):
        return "no raster format", 0, 0

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
    return None, usable, refused


def check_wmts(root):
    contents = kid(root, "Contents")
    if contents is None:
        return "no Contents", 0, 0
    sets = {}
    for matrix_set in kids(contents, "TileMatrixSet"):
        sets[text(kid(matrix_set, "Identifier"))] = (
            crs_code(text(kid(matrix_set, "SupportedCRS"))) in WEB_MERCATOR,
            [text(kid(m, "Identifier")) for m in kids(matrix_set, "TileMatrix")],
        )

    usable = refused = 0
    for layer in kids(contents, "Layer"):
        if not choose_format([text(f) for f in kids(layer, "Format")]):
            refused += 1
            continue
        linked = [sets.get(text(kid(k, "TileMatrixSet"))) for k in kids(layer, "TileMatrixSetLink")]
        mercator = next((s for s in linked if s and s[0]), None)
        if not mercator or not zoom_template(mercator[1]):
            refused += 1
            continue
        usable += 1
    return None, usable, refused


def check(entry):
    body = fetch(entry["url"])
    if not body:
        return entry, "unreachable", 0, 0
    try:
        root = ET.fromstring(body)
    except ET.ParseError as e:
        return entry, f"not XML ({e})", 0, 0
    name = local(root)
    if name in ("WMS_Capabilities", "WMT_MS_Capabilities"):
        problem, usable, refused = check_wms(root)
    elif name == "Capabilities":
        problem, usable, refused = check_wmts(root)
    else:
        return entry, f"unexpected root <{name}>", 0, 0
    return entry, problem, usable, refused


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
