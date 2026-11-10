"""Print on-screen control positions from a uiautomator dump."""

from __future__ import annotations

import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "ui.xml"
xml = open(path, encoding="utf-8", errors="replace").read()

pattern = re.compile(
    r'<node[^>]*?text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)
for m in pattern.finditer(xml):
    text = m.group(1).strip()
    if not text:
        continue
    x1, y1, x2, y2 = (int(g) for g in m.groups()[1:])
    print(f"{text[:24]!r:30s} center=({(x1 + x2) // 2},{(y1 + y2) // 2})")
