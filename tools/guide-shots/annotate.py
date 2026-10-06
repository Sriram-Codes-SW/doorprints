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

"""Numbers the field (1) and the button (2) on tools/guide-shots/out/raw.png and shrinks it to a small palette PNG.

    python3 tools/guide-shots/annotate.py [outDir] [target.png]

Needs Pillow (a development machine's Python usually has it); the picture goes to guide/docs/images/web-connect-url.png.
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

here = os.path.dirname(os.path.abspath(__file__))
out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(here, 'out')
target = sys.argv[2] if len(sys.argv) > 2 else os.path.join(here, '..', '..', 'guide', 'docs', 'images', 'web-connect-url.png')
ox, oy = 296, 196  # the clip's origin in the page, as in web-connect-url.cjs
boxes = json.load(open(os.path.join(out, 'boxes.json')))
im = Image.open(os.path.join(out, 'raw.png')).convert('RGB')
draw = ImageDraw.Draw(im)
gold, dark = (242, 184, 75), (31, 111, 92)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 20)


def mark(box, number):
    x0, y0 = box['x'] - ox - 4, box['y'] - oy - 4
    x1, y1 = x0 + box['width'] + 8, y0 + box['height'] + 8
    draw.rounded_rectangle([x0, y0, x1, y1], radius=8, outline=gold, width=4)
    cx, cy = x1 - 4, y0 - 2  # the badge sits on the top right corner, clear of the label text
    draw.ellipse([cx - 15, cy - 15, cx + 15, cy + 15], fill=gold, outline=dark, width=2)
    draw.text((cx, cy), str(number), fill=(26, 26, 26), font=font, anchor='mm')


mark(boxes['url'], 1)
mark(boxes['btn'], 2)
im.quantize(colors=64, method=Image.Quantize.MEDIANCUT).convert('P').save(target, optimize=True)
print(f'{target}: {os.path.getsize(target)} bytes')
