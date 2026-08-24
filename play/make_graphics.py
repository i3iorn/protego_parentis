# -*- coding: utf-8 -*-
"""
Draws the two store graphics Play insists on, from the same geometry as the icon.

Committed rather than hand-drawn once and forgotten. The launcher icon lives in
res/drawable/ic_launcher_*.xml as vectors; these are raster copies of the same
shapes at the sizes Play demands. Keeping the recipe means a change to the icon
can be carried across in a minute instead of being reproduced by eye in an image
editor, which is how the store icon and the launcher icon come to disagree.

    python play/make_graphics.py

Needs Pillow. Writes into play/ next to this file.
"""
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))

# Supersampling factor. Every shape is drawn large and reduced with a good filter
# at the end, because PIL has no anti-aliasing of its own and a 512px circle drawn
# directly has visibly ragged edges.
SS = 4

# The brand, from res/values/colors.xml. Kept as literals rather than parsed out
# of the XML: this script runs on a laptop, not in the build, and a parser here
# would be a second thing to keep working.
DUSK_DARK = (0x25, 0x39, 0x5F)
DUSK_MID = (0x3A, 0x5A, 0x93)
DUSK_LIGHT = (0x4C, 0x74, 0xB4)
MOONLIGHT = (0xED, 0xF2, 0xFA)

# The icon's own 108-unit viewport, so the numbers below match the vector exactly.
VIEWPORT = 108.0
MOON_CENTRE = (54.0, 55.0)
MOON_RADIUS = 20.0
CUT_CENTRE = (64.0, 45.0)
CUT_RADIUS = 25.0
TICKS = [
    (52.25, 23.0, 55.75, 30.0),
    (52.25, 78.0, 55.75, 85.0),
    (23.0, 52.25, 30.0, 55.75),
    (78.0, 52.25, 85.0, 55.75),
]

FONT_DIR = os.path.join(os.environ.get('WINDIR', 'C:/Windows'), 'Fonts')


def font(name, size):
    """A real typeface if one is installed, PIL's bitmap default if not."""
    for candidate in (name, 'arialbd.ttf', 'arial.ttf'):
        path = os.path.join(FONT_DIR, candidate)
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def gradient(size, start, end):
    """A diagonal wash, drawn small and enlarged — the cheap way to a smooth ramp."""
    width, height = size
    small = Image.new('RGB', (64, 64))
    pixels = small.load()
    for y in range(64):
        for x in range(64):
            t = (x + y) / 126.0
            pixels[x, y] = tuple(
                int(start[i] + (end[i] - start[i]) * t) for i in range(3)
            )
    return small.resize((width, height), Image.BICUBIC)


def moon(size, scale=1.0, colour=MOONLIGHT):
    """The crescent and its four clock marks, on transparency."""
    canvas = int(size * SS)
    unit = canvas / VIEWPORT * scale
    offset = (canvas - VIEWPORT * unit) / 2

    def place(x, y):
        return offset + x * unit, offset + y * unit

    layer = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    def disc(target, centre, radius, fill):
        cx, cy = place(*centre)
        r = radius * unit
        ImageDraw.Draw(target).ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill)

    disc(layer, MOON_CENTRE, MOON_RADIUS, colour + (255,))

    # The bite. Composited as a mask rather than painted in the background colour,
    # so the crescent stays transparent where it is cut and can sit on anything.
    cutter = Image.new('L', (canvas, canvas), 0)
    disc(cutter, CUT_CENTRE, CUT_RADIUS, 255)
    layer = Image.composite(Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0)), layer, cutter)

    draw = ImageDraw.Draw(layer)
    for x0, y0, x1, y1 in TICKS:
        draw.rectangle([*place(x0, y0), *place(x1, y1)], fill=colour + (255,))

    return layer.resize((int(size), int(size)), Image.LANCZOS)


def store_icon(path):
    """512x512, square and full-bleed. Play rounds the corners itself."""
    size = 512
    image = gradient((size, size), DUSK_DARK, DUSK_LIGHT).convert('RGBA')
    image.alpha_composite(moon(size))
    image.convert('RGB').save(path)
    return path


def feature_graphic(path):
    """
    1024x500, the banner at the top of the listing.

    Two thirds words, one third mark. Play crops and overlays this differently
    depending on where it appears, so nothing important goes near an edge and the
    text sits on the calm end of the gradient rather than across the whole ramp.
    """
    width, height = 1024, 500
    image = gradient((width, height), DUSK_DARK, DUSK_MID).convert('RGBA')

    mark = 300
    image.alpha_composite(moon(mark), (width - mark - 70, (height - mark) // 2))

    draw = ImageDraw.Draw(image)
    draw.text((72, 178), 'Dementor Watch', font=font('segoeuib.ttf', 68), fill=MOONLIGHT)
    draw.text(
        (74, 268),
        'Screen time a teenager can actually see',
        font=font('segoeui.ttf', 32),
        fill=(0xBF, 0xCF, 0xE8),
    )

    image.convert('RGB').save(path)
    return path


if __name__ == '__main__':
    print(store_icon(os.path.join(HERE, 'icon-512.png')))
    print(feature_graphic(os.path.join(HERE, 'feature-graphic-1024x500.png')))
