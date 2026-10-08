#!/usr/bin/env python3
"""Write the library section of docs/service-catalogue.md from the bundled library.

The list of what the app ships is generated so that it cannot drift from
app/src/main/assets/library.json: the Catalogue workflow runs this after every library
change and commits the result with the catalogue asset. Only the text between the two
markers is replaced; everything else in the document is written by hand.

Regions come in the order the app shows them (the library's own wide regions first, then
the rest alphabetically) and services by name within a region. Layers is the usable count
tools/check-library.py measured, the number the app shows next to each service.
"""

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LIBRARY = ROOT / "app/src/main/assets/library.json"
DOC = ROOT / "docs/service-catalogue.md"
BEGIN = "<!-- library:begin -->"
END = "<!-- library:end -->"


def cell(text):
    return text.replace("|", "\\|").replace("\n", " ")


def link(name, url):
    # A destination with spaces or brackets in it needs CommonMark's angle-bracket form.
    target = f"<{url}>" if any(c in url for c in " ()<>") else url
    return f"[{cell(name)}]({target})"


def render(library):
    entries = library["entries"]
    wide = library.get("regions", [])
    regions = sorted({e["region"] for e in entries},
                     key=lambda r: (wide.index(r) if r in wide else len(wide), r))
    lines = [f"{len(entries)} services in {len(regions)} regions. Layers is how many of a "
             "service's layers the app can serve, as `tools/check-library.py` measured them.", ""]
    for region in regions:
        lines += [f"### {region}", "", "| Service | Category | Layers | What it shows |",
                  "|---|---|--:|---|"]
        for e in sorted((e for e in entries if e["region"] == region), key=lambda e: e["name"]):
            lines.append(f"| {link(e['name'], e['url'])} | {e['category']} | {e['usable']} | {cell(e['note'])} |")
        lines.append("")
    return "\n".join(lines)


def main():
    library = json.loads(LIBRARY.read_text(encoding="utf-8"))
    doc = DOC.read_text(encoding="utf-8")
    head, rest = doc.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    DOC.write_text(f"{head}{BEGIN}\n{render(library)}{END}{tail}", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
