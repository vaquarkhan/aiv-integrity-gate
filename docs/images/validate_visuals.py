# Copyright 2026 Vaquar Khan
# SPDX-License-Identifier: Apache-2.0
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image

failed = False
for path in sorted(Path(__file__).parent.glob("*.svg")):
    try:
        ET.parse(path)
        print("SVG OK", path.name)
    except Exception as exc:
        failed = True
        print("SVG FAIL", path.name, exc)
for path in sorted(Path(__file__).parent.glob("*.png")):
    try:
        with Image.open(path) as image:
            image.verify()
            print("PNG OK", path.name, image.size)
    except Exception as exc:
        failed = True
        print("PNG FAIL", path.name, exc)
raise SystemExit(1 if failed else 0)
