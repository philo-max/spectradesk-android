"""List clickable nodes with their text from a uiautomator dump."""

from __future__ import annotations

import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "ui.xml"
xml = open(path, encoding="utf-8", errors="replace").read()

for m in re.finditer(r"<node[^>]*?/?>", xml):
    node = m.group(0)
    if 'clickable="true"' not in node:
        continue
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    if not b:
        continue
    x1, y1, x2, y2 = (int(g) for g in b.groups())
    text = re.search(r'text="([^"]*)"', node)
    desc = re.search(r'content-desc="([^"]*)"', node)
    label = (text.group(1) if text else "") or (desc.group(1) if desc else "")
    print(f"center=({(x1 + x2) // 2:5d},{(y1 + y2) // 2:5d})  {label[:40]!r}")
