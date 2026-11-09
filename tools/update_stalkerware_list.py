#!/usr/bin/env python3
"""
Rebuilds app/src/main/assets/stalkerware.tsv from the Echap stalkerware indicators.

Source: https://github.com/AssoEchap/stalkerware-indicators (CC BY 4.0).
OwlEar keeps only two things from it: Android package names and signing-certificate
SHA-1 fingerprints, each with the family name. No websites, servers or samples.

Usage (needs PyYAML):
    git clone --depth 1 https://github.com/AssoEchap/stalkerware-indicators /tmp/echap
    python3 tools/update_stalkerware_list.py /tmp/echap/ioc.yaml

Then rebuild the app. The list only changes when a new OwlEar release ships, because
OwlEar has no internet access to download it by itself.

Mechanic version: a printed recall list taped inside the shop door. It's only as new as
the last time someone reprinted it, so reprint it before every release.
"""
import datetime
import pathlib
import re
import subprocess
import sys

import yaml

OUT = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/assets/stalkerware.tsv"
PKG = re.compile(r"^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$")
CERT = re.compile(r"^[0-9A-F]{40}$")


def clean(text: str) -> str:
    return " ".join(str(text).split()).replace("\t", " ")


def main(path: str) -> None:
    entries = yaml.safe_load(open(path, encoding="utf-8"))
    rows = set()
    for e in entries:
        if e.get("type") not in (None, "stalkerware"):
            continue
        family = clean(e.get("name", "")) or "unknown"
        for p in e.get("packages") or []:
            p = str(p).strip()
            if PKG.match(p):
                rows.add(("p", p, family))
        for c in e.get("certificates") or []:
            c = str(c).strip().upper()
            if CERT.match(c):
                rows.add(("c", c, family))

    src = pathlib.Path(path).resolve().parent
    try:
        rev = subprocess.check_output(["git", "-C", str(src), "log", "-1", "--format=%h %cs"], text=True).strip()
    except Exception:
        rev = "unknown"

    lines = [
        "# Known stalkerware apps, from the Echap stalkerware indicators",
        "# https://github.com/AssoEchap/stalkerware-indicators  (CC BY 4.0)",
        "# Changed by OwlEar: kept only package names (p) and signing-certificate SHA-1s (c).",
        f"# Source version: {rev}. Rebuilt {datetime.date.today().isoformat()} by tools/update_stalkerware_list.py",
        "# kind<TAB>value<TAB>family",
    ]
    lines += ["\t".join(r) for r in sorted(rows)]
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    pk = sum(1 for r in rows if r[0] == "p")
    print(f"wrote {OUT}: {pk} packages, {len(rows) - pk} certificates, "
          f"{len({r[2] for r in rows})} families")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
