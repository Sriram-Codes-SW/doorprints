#!/usr/bin/env python3
# Copyright 2026 Sriram (Sriram-Codes-SW)
#
# This file is part of Doorprints.
#
# Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
# Public License as published by the Free Software Foundation, version 3 of the License.
#
# Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
# warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
# details.
#
# You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
# the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""Numbers the places shots.cjs recorded on each raw picture and shrinks it to a small palette PNG.

    python3 tools/guide-shots/annotate.py [outDir] [imagesDir]

Needs Pillow; the pictures go to guide/docs/images/<name>.png. A mark may say `badge: 'top-left'`, `'top-right'` or `'left'` (in the left margin, for boxes stacked close together). A number's badge sits on the top right corner of its
box, clear of the label text; a box with room to its right (a button) gets it beside it.
"""
import glob
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, 'out')
images = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, '..', '..', 'guide', 'docs', 'images')
gold, dark = (242, 184, 75), (31, 111, 92)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 20)

for spec in sorted(glob.glob(os.path.join(out, '*.json'))):
    name = os.path.basename(spec)[: -len('.json')]
    data = json.load(open(spec))
    ox, oy = data['clip']['x'], data['clip']['y']
    im = Image.open(os.path.join(out, f'{name}.raw.png')).convert('RGB')
    draw = ImageDraw.Draw(im)
    for number, box in enumerate(data['marks'], start=1):
        x0, y0 = box['x'] - ox - 4, box['y'] - oy - 4
        x1, y1 = x0 + box['width'] + 8, y0 + box['height'] + 8
        draw.rounded_rectangle([x0, y0, x1, y1], radius=8, outline=gold, width=4)
        # The badge sits just right of a box that has room beside it (a button), else on its top right corner (a wide field).
        if box.get('badge') == 'top-left':
            cx, cy = x0 + 6, y0 - 2
        elif box.get('badge') == 'top-right':
            cx, cy = x1 - 6, y0 - 2
        elif box.get('badge') == 'left':
            cx, cy = 14, (y0 + y1) / 2
        else:
            cx, cy = (x1 + 20, (y0 + y1) / 2) if x1 + 40 < im.width else (x1 - 4, y0 - 2)
        draw.ellipse([cx - 15, cy - 15, cx + 15, cy + 15], fill=gold, outline=dark, width=2)
        draw.text((cx, cy), str(number), fill=(26, 26, 26), font=font, anchor='mm')
    target = os.path.join(images, f'{name}.png')
    im.quantize(colors=64, method=Image.Quantize.MEDIANCUT).convert('P').save(target, optimize=True)
    print(f'{name}: {os.path.getsize(target)} bytes')
