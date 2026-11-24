#!/usr/bin/env python3
"""
Phase World textures, 16x16, drawn in the vanilla manner:
- hue-shifted palettes (shadows cooler, highlights warmer), 4-6 tones per material;
- clustered, tileable noise for blocks instead of single-pixel speckle;
- items and plants are drawn as shapes with a light from the top left, then get a selective outline
  (a darker shade of the neighbouring colour, never flat black).
Every function returns a PIL RGBA image.
"""
import colorsys
import math
import random

from PIL import Image

S = 16


# ------------------------------------------------------------------------------------------ colour helpers
def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def shade(c, f, hue=0.0):
    """Lighten (f>1) or darken (f<1) with a small hue shift: darker goes cooler, lighter goes warmer."""
    r, g, b = c[0] / 255, c[1] / 255, c[2] / 255
    h, l, s = colorsys.rgb_to_hls(r, g, b)
    l = max(0.0, min(1.0, l * f))
    shift = hue if hue else (0.02 if f > 1 else -0.025)
    h = (h + shift) % 1.0
    if f < 1:
        s = min(1.0, s * (1 + (1 - f) * 0.25))
    r, g, b = colorsys.hls_to_rgb(h, l, s)
    return (int(r * 255), int(g * 255), int(b * 255), c[3] if len(c) > 3 else 255)


def ramp(base, n=5, lo=0.55, hi=1.35):
    """n tones from dark to light around a base colour."""
    c = hexc(base) if isinstance(base, str) else base
    return [shade(c, lo + (hi - lo) * i / (n - 1)) for i in range(n)]


def new():
    return Image.new('RGBA', (S, S), (0, 0, 0, 0))


# ------------------------------------------------------------------------------------------ tileable noise
class TNoise:
    """Value noise on a torus: tiles seamlessly at 16x16."""

    def __init__(self, seed, cells):
        r = random.Random(seed)
        self.n = cells
        self.v = [[r.random() for _ in range(cells)] for _ in range(cells)]

    def at(self, x, y):
        n = self.n
        fx, fy = x * n / S, y * n / S
        x0, y0 = int(math.floor(fx)) % n, int(math.floor(fy)) % n
        x1, y1 = (x0 + 1) % n, (y0 + 1) % n
        tx, ty = fx - math.floor(fx), fy - math.floor(fy)
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        a = self.v[y0][x0] + (self.v[y0][x1] - self.v[y0][x0]) * tx
        b = self.v[y1][x0] + (self.v[y1][x1] - self.v[y1][x0]) * tx
        return a + (b - a) * ty


def fbm(seed, x, y, octaves=((4, 0.6), (8, 0.3), (16, 0.1))):
    v = 0
    for i, (cells, w) in enumerate(octaves):
        v += TNoise(seed * 31 + i, cells).at(x, y) * w
    return v


def field(seed, octaves=((4, 0.6), (8, 0.3), (16, 0.1))):
    ns = [(TNoise(seed * 31 + i, c), w) for i, (c, w) in enumerate(octaves)]
    out = [[sum(n.at(x, y) * w for n, w in ns) for x in range(S)] for y in range(S)]
    # normalise to 0..1
    lo = min(min(r) for r in out)
    hi = max(max(r) for r in out)
    return [[(v - lo) / (hi - lo + 1e-9) for v in r] for r in out]


def quant(v, n, dither=0.0, x=0, y=0):
    bayer = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]
    v = v + (bayer[y % 4][x % 4] / 16 - 0.5) * dither
    return max(0, min(n - 1, int(v * n)))


def material(seed, pal, octaves=((4, 0.55), (8, 0.3), (16, 0.15)), dither=0.18, bias=0.0, contrast=1.0):
    f = field(seed, octaves)
    img = new()
    for y in range(S):
        for x in range(S):
            v = 0.5 + (f[y][x] - 0.5) * contrast + bias
            img.putpixel((x, y), pal[quant(v, len(pal), dither, x, y)])
    return img


# ------------------------------------------------------------------------------------------ drawing helpers
class Canvas:
    def __init__(self):
        self.img = new()

    def get(self, x, y):
        if 0 <= x < S and 0 <= y < S:
            return self.img.getpixel((x, y))
        return (0, 0, 0, 0)

    def px(self, x, y, c):
        if 0 <= x < S and 0 <= y < S and c is not None:
            if isinstance(c, str):
                c = hexc(c)
            self.img.putpixel((int(x), int(y)), c)

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.px(x, y, c)

    def line(self, x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n + 1):
            self.px(round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n), c)

    def ball(self, cx, cy, rx, ry, pal, light=(-0.6, -0.8), spec=True, rot=0.0):
        """Filled ellipse shaded like a lit sphere with the palette (dark -> light)."""
        n = len(pal)
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                if rot:
                    dx, dy = dx * math.cos(rot) + dy * math.sin(rot), -dx * math.sin(rot) + dy * math.cos(rot)
                d = (dx / rx) ** 2 + (dy / ry) ** 2
                if d > 1:
                    continue
                nz = math.sqrt(max(0, 1 - d))
                lit = 0.35 + 0.65 * max(0, (-dx / rx * light[0] - dy / ry * light[1]) * 0.7 + nz * 0.55)
                self.px(x, y, pal[max(0, min(n - 1, int(lit * n)))])
        if spec:
            sx, sy = cx + light[0] * rx * 0.45, cy + light[1] * ry * 0.45
            self.px(int(sx), int(sy), shade(pal[-1], 1.15))

    def poly(self, pts, c):
        """Fill a polygon (list of (x,y)) with a colour or a function (x,y)->colour."""
        for y in range(S):
            for x in range(S):
                if inside(pts, x + 0.5, y + 0.5):
                    self.px(x, y, c(x, y) if callable(c) else c)

    def outline(self, factor=0.55, only=None):
        """Selective outline: transparent pixels next to the sprite get a darker shade of their neighbour."""
        src = self.img.copy()
        for y in range(S):
            for x in range(S):
                if src.getpixel((x, y))[3] > 0:
                    continue
                best = None
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < S and 0 <= ny < S:
                        c = src.getpixel((nx, ny))
                        if c[3] > 0 and (only is None or only(nx, ny)):
                            if best is None or sum(c[:3]) < sum(best[:3]):
                                best = c
                if best is not None:
                    self.img.putpixel((x, y), shade(best, factor))
        return self

    def grid(self, rows, pal):
        for y, row in enumerate(rows):
            for x, ch in enumerate(row):
                if ch in pal and pal[ch] is not None:
                    self.px(x, y, pal[ch])
        return self


def inside(pts, x, y):
    c = False
    n = len(pts)
    for i in range(n):
        x1, y1 = pts[i]
        x2, y2 = pts[(i + 1) % n]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1 + 1e-12) + x1:
            c = not c
    return c


# ------------------------------------------------------------------------------------------ terrain blocks
def volcanic_ash():
    pal = ramp('#77716c', 5, 0.7, 1.22)
    img = material(11, pal, dither=0.25, contrast=0.9)
    r = random.Random(11)
    for _ in range(10):
        x, y = r.randrange(S), r.randrange(S)
        img.putpixel((x, y), hexc('#4f4a47'))
    return img


def black_sand():
    pal = [hexc(h) for h in ('#151415', '#1e1c1d', '#272425', '#322e2e', '#3e3937')]
    img = material(12, pal, dither=0.35, contrast=1.0)
    r = random.Random(12)
    for _ in range(7):   # tiny glints of olivine and glass
        img.putpixel((r.randrange(S), r.randrange(S)), hexc(r.choice(['#5d6a52', '#6a6a72', '#58504a'])))
    return img


def porous(seed, base, hole, rim, holes, warm=0.0):
    pal = ramp(base, 5, 0.72, 1.2)
    img = material(seed, pal, dither=0.15, contrast=1.1)
    r = random.Random(seed)
    for _ in range(holes):
        x, y = r.randrange(S), r.randrange(S)
        w = r.choice([1, 1, 2])
        for dx in range(w):
            img.putpixel(((x + dx) % S, y), hexc(hole))
            img.putpixel(((x + dx) % S, (y + 1) % S), hexc(rim))   # lit lower lip
            if r.random() < 0.6:
                img.putpixel(((x + dx) % S, (y - 1) % S), shade(img.getpixel(((x + dx) % S, (y - 1) % S)), 0.8))
    return img


def scoria():
    return porous(13, '#5b3a33', '#1d1110', '#7a5047', 13)


def pumice():
    return porous(14, '#d6cdbb', '#958a77', '#ece5d6', 15)


# ------------------------------------------------------------------------------------------ cave blocks
def hikari_moss(variant=0):
    # deep blue-green moss in soft clumps; a few clusters of luminous spores
    pal = [hexc(h) for h in ('#163a33', '#1d4a3d', '#245a45', '#2d6a4c', '#3a7d55')]
    img = material(20 + variant * 7, pal, octaves=((4, 0.5), (8, 0.35), (16, 0.15)), dither=0.2, contrast=1.25)
    r = random.Random(40 + variant)
    glow = [hexc('#5ff2c9'), hexc('#9dfde0'), hexc('#3fd6b6')]
    for _ in range(2 + variant):
        x, y = r.randrange(S), r.randrange(S)
        pts = [(0, 0), (1, 0), (0, 1), (1, 1), (-1, 0), (0, -1)]
        for i, (dx, dy) in enumerate(pts[:r.randint(2, 5)]):
            img.putpixel(((x + dx) % S, (y + dy) % S), glow[0] if i == 0 else glow[2])
        img.putpixel((x, y), glow[1])
    return img


def hikari_carpet_side():
    return hikari_moss(0)


def eclipse_crystal_block():
    # large faceted plates like amethyst, with violet-indigo gradient and bright edges
    c = Canvas()
    base = [hexc(h) for h in ('#1d1036', '#2c1752', '#3f2173', '#5a3399', '#7b4fc4', '#a983ea')]
    f = field(30, ((2, 0.6), (4, 0.3), (8, 0.1)))
    for y in range(S):
        for x in range(S):
            # facets: bands along two diagonal directions
            a = ((x * 2 + y) % 11) / 11
            b = ((x - y * 2) % 13) / 13
            v = 0.45 * f[y][x] + 0.3 * a + 0.25 * b
            c.px(x, y, base[quant(v, len(base), 0.15, x, y)])
    r = random.Random(31)
    for _ in range(6):
        x, y = r.randrange(S), r.randrange(S)
        c.px(x, y, hexc('#e2cfff'))
    return c.img


def eclipse_cluster():
    c = Canvas()
    dark, mid, light, hi = hexc('#3a1f6e'), hexc('#6a3fb5'), hexc('#9d74e6'), hexc('#e6d6ff')
    shards = [(8, 2, 13, 2), (4, 6, 11, 1), (12, 6, 11, 1), (6, 9, 13, 1), (10, 9, 13, 1)]
    for x, top, bottom, w in shards:
        for y in range(top, bottom + 1):
            t = (y - top) / max(1, bottom - top)
            hw = round(w * min(1, t * 2.2))
            for dx in range(-hw, hw + 1):
                col = light if dx < 0 else mid if dx == 0 else dark
                if dx == 0 and y < top + 3:
                    col = hi
                c.px(x + dx, y, col)
    c.rect(3, 14, 12, 15, hexc('#2a1550'))
    c.outline(0.6)
    return c.img


def glowcap_block():
    pal = [hexc(h) for h in ('#0f5a63', '#137480', '#1a929c', '#27b3b4', '#46d3c8')]
    img = material(50, pal, octaves=((4, 0.6), (8, 0.4)), dither=0.15, contrast=1.1)
    r = random.Random(50)
    spots = [hexc('#bfffee'), hexc('#7ff7da')]
    for _ in range(6):
        x, y = r.randrange(S), r.randrange(S)
        for dx, dy in ((0, 0), (1, 0), (0, 1), (1, 1)):
            img.putpixel(((x + dx) % S, (y + dy) % S), spots[1])
        img.putpixel((x, y), spots[0])
    return img


def glowcap_stem_side():
    pal = [hexc(h) for h in ('#a79f8c', '#bdb6a2', '#d3ccb8', '#e6e0cf')]
    img = new()
    f = field(51, ((2, 0.4), (16, 0.6)))
    for y in range(S):
        for x in range(S):
            v = 0.6 * f[(y // 3) % S][x] + 0.4 * ((x * 7) % 5) / 5
            img.putpixel((x, y), pal[quant(v, 4, 0.1, x, y)])
    return img


def glowcap_stem_top():
    c = Canvas()
    pal = [hexc(h) for h in ('#8f8775', '#bdb6a2', '#d3ccb8', '#e9e3d3')]
    for y in range(S):
        for x in range(S):
            d = math.hypot(x - 7.5, y - 7.5)
            ring = int(d) % 3
            c.px(x, y, pal[0] if d > 7 else pal[1 + (ring == 0)] if d > 2 else pal[3])
    return c.img


# ------------------------------------------------------------------------------------------ leaves
def leaves(seed, cols, holes=0.2, flowers=None):
    """Vanilla-like leaves: clumps of 2x2 / 3x2 leaf blobs with shadowed lower edges and see-through gaps."""
    pal = [hexc(c) for c in cols]
    img = new()
    f = field(seed, ((4, 0.5), (8, 0.35), (16, 0.15)))
    r = random.Random(seed)
    for y in range(S):
        for x in range(S):
            v = f[y][x]
            if v < holes:
                continue
            k = quant(v, len(pal), 0.3, x, y)
            img.putpixel((x, y), pal[k])
    # leaf clusters with highlight on top-left and dark at bottom-right
    for _ in range(18):
        x, y = r.randrange(S), r.randrange(S)
        if img.getpixel((x, y))[3]:
            img.putpixel((x, y), pal[-1])
        if img.getpixel(((x + 1) % S, (y + 1) % S))[3]:
            img.putpixel(((x + 1) % S, (y + 1) % S), pal[1])
    if flowers:
        fp = [hexc(c) for c in flowers]
        for _ in range(9):
            x, y = r.randrange(S), r.randrange(S)
            for dx, dy, k in ((0, 0, 2), (1, 0, 1), (0, 1, 1), (1, 1, 0)):
                img.putpixel(((x + dx) % S, (y + dy) % S), fp[k])
    return img


def momiji_leaves():
    # five-point maple leaves: red with orange and a few deep crimson
    img = leaves(60, ['#5e0f0c', '#8c1a14', '#b52a1c', '#d9472a', '#f06a36'], 0.4)
    r = random.Random(61)
    star = [(0, -1), (-1, 0), (1, 0), (0, 0), (-1, 1), (1, 1)]
    for _ in range(5):
        x, y = r.randrange(S), r.randrange(S)
        for dx, dy in star:
            if img.getpixel(((x + dx) % S, (y + dy) % S))[3]:
                img.putpixel(((x + dx) % S, (y + dy) % S), hexc('#f4823d') if dy < 0 else hexc('#d9472a'))
    return img


def ginkgo_leaves():
    img = leaves(62, ['#7a5a08', '#a87d10', '#d2a51e', '#efc83a', '#fbe274'], 0.47)
    r = random.Random(63)
    fan = [(-1, 0), (0, 0), (1, 0), (0, 1)]
    for _ in range(6):
        x, y = r.randrange(S), r.randrange(S)
        for dx, dy in fan:
            if img.getpixel(((x + dx) % S, (y + dy) % S))[3]:
                img.putpixel(((x + dx) % S, (y + dy) % S), hexc('#fde68a') if dy == 0 else hexc('#b98c14'))
    return img


def wisteria_leaves():
    """Mostly hanging purple flower clusters over a little green."""
    img = leaves(64, ['#244a1e', '#2f5f25', '#3d7530', '#4f8c3b', '#68a84a'], 0.4)
    fp = [hexc(c) for c in ('#3e2470', '#5d3a9e', '#8462c4', '#ad8ce6', '#d8c4fb')]
    f = field(65, ((4, 0.5), (8, 0.3), (16, 0.2)))
    for y in range(S):
        for x in range(S):
            v = f[y][x]
            if v > 0.38 and img.getpixel((x, y))[3]:
                # vertical streaks: racemes hang down, lighter at the top of each
                k = quant(v * 0.6 + ((y + x * 3) % 4) / 10, 5, 0.25, x, y)
                img.putpixel((x, y), fp[k])
    return img


def hydrangea(colors):
    """A leafy shrub cube covered in round flower heads made of 4-petal florets."""
    img = leaves(70, ['#1f4a22', '#2a5e2a', '#376f32', '#46853c', '#5c9c4a'], 0.12)
    fp = [hexc(c) for c in colors]
    r = random.Random(71)
    heads = [(3, 3), (11, 4), (6, 10), (13, 12), (1, 13)]
    for hx, hy in heads:
        hx += r.randint(-1, 1)
        hy += r.randint(-1, 1)
        for dy in range(-2, 3):
            for dx in range(-2, 3):
                if dx * dx + dy * dy > 5:
                    continue
                lit = 2 if dx + dy < -1 else 1 if dx + dy < 2 else 0
                k = max(0, min(3, lit + ((dx * 3 + dy * 5) % 2)))
                img.putpixel(((hx + dx) % S, (hy + dy) % S), fp[k])
        img.putpixel((hx % S, hy % S), fp[3])
    return img


# ------------------------------------------------------------------------------------------ shrine blocks
def bricks(seed, mortar, base, moss=False, crack=False):
    """Stone bricks with a bevel: light top/left edges, dark bottom/right, mortar lines."""
    pal = ramp(base, 5, 0.75, 1.18)
    img = material(seed, pal, dither=0.2, contrast=0.8)
    m = hexc(mortar)
    rows = [(0, 7, 0), (8, 15, 4)]
    for y0, y1, off in rows:
        for x in range(S):
            img.putpixel((x, y1), m)
        for x in (off % S, (off + 8) % S):
            for y in range(y0, y1):
                img.putpixel((x, y), m)
        for x in range(S):
            if img.getpixel((x, y0))[:3] != m[:3]:
                img.putpixel((x, y0), shade(img.getpixel((x, y0)), 1.12))
            if img.getpixel((x, y1 - 1))[:3] != m[:3]:
                img.putpixel((x, y1 - 1), shade(img.getpixel((x, y1 - 1)), 0.86))
    if crack:
        r = random.Random(seed + 1)
        x, y = 4, 1
        for _ in range(14):
            img.putpixel((x % S, y % S), hexc('#3a3a3c'))
            x += r.choice([0, 1, 1])
            y += 1
    if moss:
        f = field(seed + 2, ((4, 0.7), (8, 0.3)))
        mp = [hexc('#3d5a24'), hexc('#4e6e2c'), hexc('#628536')]
        for y in range(S):
            for x in range(S):
                if f[y][x] > 0.62:
                    img.putpixel((x, y), mp[quant((f[y][x] - 0.62) / 0.38, 3, 0.3, x, y)])
    return img


def vermilion_planks():
    """Lacquered vermilion boards: four rows, seams, grain lines, warm highlight."""
    pal = [hexc(h) for h in ('#7e1d12', '#a42a1a', '#c23a22', '#d64e2c', '#e8693a')]
    img = new()
    f = field(80, ((2, 0.5), (16, 0.5)))
    for y in range(S):
        row = y // 4
        for x in range(S):
            g = 0.5 * f[(row * 4 + 1) % S][x] + 0.5 * f[(y * 5 + 3) % S][(x * 2) % S]
            k = 2 + (1 if g > 0.66 else -1 if g < 0.28 else 0)
            if y % 4 == 0:
                k = max(0, k - 2)          # seam shadow
            elif y % 4 == 1:
                k = min(4, k + 1)          # lit board edge
            img.putpixel((x, y), pal[k])
        seam = (row * 7 + 3) % S
        img.putpixel((seam, y), pal[0])
    return img


def tatami():
    """Woven igusa straw with a cloth border (heri) along two edges."""
    pal = [hexc(h) for h in ('#7f8a3c', '#949f48', '#a9b356', '#bcc56a', '#cdd47e')]
    img = new()
    for y in range(S):
        for x in range(S):
            weave = (y % 2) * 2 + ((x + y // 2) % 3 == 0)
            k = 1 + weave % 3 + (1 if (x * 13 + y * 7) % 11 == 0 else 0)
            img.putpixel((x, y), pal[min(4, k)])
    heri = [hexc('#1e2a22'), hexc('#2c3b30'), hexc('#3c4f40')]
    for x in range(S):
        img.putpixel((x, 0), heri[0])
        img.putpixel((x, 1), heri[2] if x % 4 == 0 else heri[1])
        img.putpixel((x, 15), heri[0])
        img.putpixel((x, 14), heri[1])
    return img


def stone_lantern():
    pal = ramp('#9a9790', 5, 0.7, 1.15)
    img = material(90, pal, dither=0.2, contrast=0.9)
    r = random.Random(90)
    for _ in range(9):
        img.putpixel((r.randrange(S), r.randrange(S)), hexc('#556b3a'))
    return img


def stone_lantern_light():
    c = Canvas()
    for y in range(S):
        for x in range(S):
            d = abs(x - 7.5) + abs(y - 7.5)
            c.px(x, y, hexc('#fff3c4') if d < 5 else hexc('#ffd774') if d < 9 else hexc('#f0a845'))
    return c.img


# ------------------------------------------------------------------------------------------ plants (cross models)
G = {   # greens: dark -> light
    'leaf': [hexc(h) for h in ('#20461c', '#2c5e23', '#3b7a2c', '#4f9636', '#6bb046')],
    'dry': [hexc(h) for h in ('#6b5a24', '#8c7630', '#ad9640', '#c9b25a', '#e2cf7e')],
    'gold': [hexc(h) for h in ('#7a5a12', '#a67c1a', '#cfa128', '#e8c040', '#f6dc74')],
    'teal': [hexc(h) for h in ('#0e3d3a', '#145a52', '#1f7a6a', '#33a58a', '#5fd8b4')],
}


def blade(c, x, y0, h, lean, pal, w=1):
    """A grass blade from (x, y0) upward of height h, bending by lean."""
    for i in range(h):
        t = i / max(1, h - 1)
        xx = x + lean * t * t
        col = pal[min(len(pal) - 1, 1 + int(t * (len(pal) - 2)))]
        c.px(round(xx), y0 - i, col)
        if w > 1 and i < h * 0.6:
            c.px(round(xx) + 1, y0 - i, pal[max(0, int(t * (len(pal) - 2)))])


def leaf_shape(c, cx, cy, ang, length, width, pal, vein=True):
    """An oval leaf, lit from the top left, with a lighter midrib."""
    ca, sa = math.cos(ang), math.sin(ang)
    for y in range(S):
        for x in range(S):
            dx, dy = x + 0.5 - cx, y + 0.5 - cy
            u = dx * ca + dy * sa          # along the leaf
            v = -dx * sa + dy * ca         # across
            if u < 0 or u > length:
                continue
            half = width * math.sin(math.pi * u / length)
            if abs(v) > half:
                continue
            k = 2 + (1 if v < 0 else -1) + (1 if u < length * 0.4 else 0)
            col = pal[max(0, min(len(pal) - 1, k))]
            if vein and abs(v) < 0.5 and u > 1:
                col = pal[-1]
            c.px(x, y, col)


def crop_rice(stage):
    c = Canvas()
    pal = G['leaf']
    heights = [5, 9, 13, 14][stage]
    for i, x in enumerate([2, 4, 6, 8, 10, 12, 14]):
        if stage == 0 and i % 2:
            continue
        h = heights - (i * 3) % 4
        blade(c, x, 15, h, (-1.5 if i % 2 else 1.5) * (0.6 + stage * 0.3), pal if stage < 3 else G['dry'])
    if stage >= 2:
        pan = G['leaf'][2:] if stage == 2 else G['gold']
        for x in (3, 7, 11, 13):
            for k in range(5):
                c.px(x + (k // 2 if stage == 3 else 0), 15 - heights + 1 + k, pan[min(len(pan) - 1, 4 - k // 2)])
            if stage == 3:
                c.px(x + 2, 15 - heights + 5, pan[1])
    return c.img


def crop_soybean(stage):
    c = Canvas()
    pal = G['leaf']
    if stage == 0:
        for x in (4, 11):
            blade(c, x, 15, 4, 0, pal)
            leaf_shape(c, x - 3, 11, -0.2, 3.5, 1.4, pal, False)
            leaf_shape(c, x + 1, 11, 0.2 - math.pi, -3.5, 1.4, pal, False)
            c.px(x - 2, 11, pal[3]); c.px(x + 2, 11, pal[3])
        return c.img
    n = [0, 3, 5, 6][stage]
    rr = random.Random(100 + stage)
    for i in range(n):
        x = 2 + i * 12 // max(1, n - 1) if n > 1 else 8
        top = 15 - (5 + stage * 2 + rr.randint(0, 2))
        c.line(x, 15, x + rr.randint(-1, 1), top, pal[1])
        for a in (-2.2, -1.57, -0.9):
            leaf_shape(c, x + 0.5, top + 0.5, a, 3.8 + stage * 0.3, 1.5, pal)
        mid = (15 + top) // 2 + 1
        for a in (-2.8, -0.35):
            leaf_shape(c, x + 0.5, mid + 0.5, a, 3.0 + stage * 0.3, 1.3, pal)
    if stage == 2:
        for _ in range(5):
            c.px(rr.randrange(3, 13), rr.randrange(8, 13), hexc('#b58ad8'))
    if stage == 3:
        pod = [hexc('#6f7a2e'), hexc('#97a043'), hexc('#c2c66a')]
        for _ in range(7):
            x, y = rr.randrange(2, 13), rr.randrange(7, 14)
            for k in range(3):
                c.px(x + k // 2, y + k, pod[min(2, k)])
    return c.img


def crop_daikon(stage):
    c = Canvas()
    pal = G['leaf']
    if stage == 0:
        c.ball(5.5, 12.5, 2.0, 1.4, pal[1:], spec=False)
        c.ball(10.5, 12.5, 2.0, 1.4, pal[1:], spec=False)
        c.line(8, 15, 8, 13, pal[1])
        return c.img
    size = [0, 4.5, 6.0, 6.5][stage]
    for a in (-2.6, -2.0, -1.57, -1.1, -0.55):
        leaf_shape(c, 8, 14.5, a, size, 1.8 + stage * 0.2, pal)
    if stage == 3:
        root = [hexc(h) for h in ('#cfd6c8', '#e6ecdf', '#f7faf2')]
        c.rect(6, 13, 9, 15, root[1])
        c.px(6, 13, root[0]); c.px(9, 15, root[0]); c.px(7, 13, root[2])
    return c.img


def crop_wasabi(stage):
    c = Canvas()
    pal = [hexc(h) for h in ('#1d4a2e', '#286040', '#357a50', '#4a9662', '#6db47c')]
    n = [1, 2, 3, 4][stage]
    for i in range(n):
        x = 8 + (i - (n - 1) / 2) * 3.2
        top = 15 - (4 + stage * 2 + (i % 2))
        c.line(round(x), 15, round(x), top, pal[1])
        c.ball(x + 0.5, top - 0.5, 2.2 + stage * 0.35, 1.9 + stage * 0.3, pal, spec=False)
        c.px(round(x), top - 2, pal[0])  # heart notch
    if stage == 3:
        for x, y in ((3, 4), (12, 3), (7, 2)):
            c.px(x, y, hexc('#f4f4ea')); c.px(x + 1, y, hexc('#ffffff'))
        c.rect(7, 14, 9, 15, hexc('#5f9a5a'))
    return c.img


def crop_tea(stage):
    c = Canvas()
    pal = [hexc(h) for h in ('#16381c', '#1f4c24', '#2a632c', '#3a7d36', '#59a04a')]
    if stage == 0:
        blade(c, 8, 15, 5, 0, pal)
        leaf_shape(c, 8, 11, -2.6, 3, 1.2, pal)
        leaf_shape(c, 8, 11, -0.5, 3, 1.2, pal)
        return c.img
    r = [0, 4.0, 5.5, 6.5][stage]
    c.line(8, 15, 8, 12, hexc('#5a3e24'))
    c.ball(8, 15 - r, r + 1, r, pal, spec=False)
    rr = random.Random(130 + stage)
    for _ in range(10 + stage * 4):
        x, y = rr.randrange(S), rr.randrange(S)
        if c.get(x, y)[3]:
            c.px(x, y, pal[4] if rr.random() < 0.5 else pal[1])
    if stage == 3:
        for _ in range(6):
            x, y = rr.randrange(3, 13), rr.randrange(int(15 - 2 * r), 13)
            if c.get(x, y)[3]:
                c.px(x, y, hexc('#a6e05a'))
        for x, y in ((5, 8), (11, 7), (8, 5)):
            c.px(x, y, hexc('#fbfbf0')); c.px(x, y + 1, hexc('#f2c94c'))
    return c.img


CROP = {'rice': crop_rice, 'soybean': crop_soybean, 'daikon': crop_daikon, 'wasabi': crop_wasabi, 'tea': crop_tea}


def wild(kind):
    base = {'wild_rice': lambda: crop_rice(3), 'wild_soybean': lambda: crop_soybean(3), 'wild_daikon': lambda: crop_daikon(3),
            'wild_wasabi': lambda: crop_wasabi(3), 'wild_tea': lambda: crop_tea(3)}[kind]()
    if kind == 'wild_daikon':   # tiny white-violet flowers on stalks
        c = Canvas()
        c.img = base
        for x, y in ((4, 3), (11, 2), (13, 5)):
            c.line(x, y + 1, x, y + 5, G['leaf'][2])
            c.px(x, y, hexc('#e9dcf2')); c.px(x - 1, y, hexc('#c7a6dc')); c.px(x + 1, y, hexc('#c7a6dc'))
        return c.img
    return base


def shiitake():
    c = Canvas()
    cap = [hexc(h) for h in ('#3c2214', '#5a341e', '#7a4a2a', '#94603a', '#b07c52')]
    stem = hexc('#e3d6bf')
    for cx, cy, rx in ((5, 9, 4), (11, 11, 3.2), (8, 5, 3.4)):
        c.rect(int(cx) - 1, int(cy), int(cx), min(15, int(cy) + 5), stem)
        c.ball(cx, cy, rx, rx * 0.6, cap)
        for k in range(-int(rx) + 1, int(rx)):    # white cracks on the cap
            if (k * 7 + int(cx)) % 3 == 0:
                c.px(int(cx) + k, int(cy) - 1, hexc('#d8c4a6'))
    c.outline(0.6)
    return c.img


def higanbana():
    c = Canvas()
    stem = G['leaf']
    red = [hexc(h) for h in ('#6e0a10', '#9c1218', '#c81e22', '#e63a2e', '#ff6a4a')]
    for x, top in ((4, 5), (11, 3), (8, 7)):
        c.line(x, 15, x, top + 2, stem[2])
        cx, cy = x + 0.5, top + 0.5
        for k in range(6):       # curled petals radiating out and back
            a = k * math.pi / 3 + 0.3
            for t in range(1, 4):
                px, py = cx + math.cos(a) * t, cy + math.sin(a) * t * 0.7 - (t == 3) * 0.8
                c.px(int(px), int(py), red[4 - t])
        for k in range(5):       # long stamens arching up
            a = -math.pi / 2 + (k - 2) * 0.5
            c.px(int(cx + math.cos(a) * 4), int(cy + math.sin(a) * 3.5), red[4])
        c.px(int(cx), int(cy), red[1])
    return c.img


def lumen_fern():
    c = Canvas()
    pal = G['teal']
    for ang, ln, bend in ((-2.25, 9, -1), (-1.9, 12, -1), (-1.57, 14, 0), (-1.25, 12, 1), (-0.9, 9, 1)):
        pts = []
        for t in range(ln):
            x = 8 + math.cos(ang) * t + bend * 0.02 * t * t
            y = 15 + math.sin(ang) * t + 0.012 * t * t
            pts.append((x, y))
            c.px(int(x), int(y), pal[1 + t * 3 // ln])
        for t in range(2, ln - 1, 2):          # leaflets on both sides
            x, y = pts[t]
            c.px(int(x) - 1, int(y), pal[2])
            c.px(int(x) + 1, int(y), pal[3])
        x, y = pts[-1]
        c.px(int(x), int(y), hexc('#b6ffe9'))
    return c.img


def glowcap():
    c = Canvas()
    cap = [hexc(h) for h in ('#0b5560', '#127a84', '#1fa3a6', '#3fcbbf', '#9ff6e0')]
    stem = [hexc('#bdb6a2'), hexc('#e6e0cf')]
    for cx, cy, rx, h in ((5, 8, 3.2, 6), (11, 10, 2.6, 5), (8, 4, 2.2, 10)):
        c.rect(int(cx) - 1, int(cy), int(cx) - 1, min(15, int(cy) + h), stem[0])
        c.rect(int(cx), int(cy), int(cx), min(15, int(cy) + h), stem[1])
        c.ball(cx, cy, rx, rx * 0.65, cap)
        c.px(int(cx - rx * 0.4), int(cy - 0.6), hexc('#e6fff8'))
    return c.img


def spirit_vine():
    c = Canvas()
    pal = [hexc(h) for h in ('#123e50', '#1b5a70', '#2a7f95', '#5ec7d8', '#c8fbff')]
    for x, ph in ((4, 0), (8, 2), (12, 1)):
        for y in range(S):
            xx = x + round(math.sin((y + ph) / 2.5))
            c.px(xx, y, pal[1] if y % 3 else pal[2])
            if (y + ph * 3) % 5 == 0:                 # glowing bulbs
                c.px(xx + 1, y, pal[3])
                c.px(xx + 1, y + 1, pal[4])
    return c.img


def wisteria_vine():
    c = Canvas()
    pal = [hexc(h) for h in ('#3b2366', '#5b3a95', '#8462c4', '#ad8ce6', '#d8c4fb')]
    for x, length, ph in ((3, 13, 0), (7, 16, 1), (11, 12, 0), (14, 15, 1)):
        c.line(x, 0, x, 2, G['leaf'][2])
        for y in range(2, length):
            t = (y - 2) / max(1, length - 2)
            w = 1 if t < 0.75 else 0
            k = 4 - int(t * 4)
            c.px(x, y, pal[max(0, k)])
            if w and (y + ph) % 2 == 0:
                c.px(x - 1, y, pal[max(0, k - 1)])
                c.px(x + 1, y, pal[min(4, k)])
    return c.img


def susuki(top):
    c = Canvas()
    if not top:
        for i, x in enumerate((3, 5, 7, 8, 10, 12)):
            blade(c, x, 15, 16, (-2 if i % 2 else 2) * 0.6, G['leaf'])
        return c.img
    plume = [hexc(h) for h in ('#9c8f78', '#bdb29b', '#d9d0bc', '#efe9dc', '#ffffff')]
    for x, h, lean in ((4, 10, -3), (8, 14, 1), (12, 11, 3)):
        for i in range(16 - h, 16):
            t = (15 - i) / h
            xx = round(x + lean * t * t)
            if t < 0.45:
                c.px(xx, i, G['leaf'][2])
            else:
                c.px(xx, i, plume[2 + (i % 2)])
                c.px(xx - 1 + (i % 2) * 2, i, plume[1 + (i % 3 == 0) * 3])
    return c.img


def lotus():
    c = Canvas()
    pad = [hexc(h) for h in ('#1f4a1c', '#2b6224', '#3a7a2e', '#4c923a', '#62a84a')]
    c.ball(7.5, 8, 7.2, 7.2, pad, spec=False)
    for t in range(8):   # the notch of the pad
        c.px(7 + t // 4, 8 + t, (0, 0, 0, 0))
    petal = [hexc(h) for h in ('#b8487a', '#d8679a', '#ee93b8', '#fbc4da', '#ffffff')]
    for k in range(8):
        a = k * math.pi / 4
        for t in range(1, 4):
            c.px(int(6.5 + math.cos(a) * t), int(6.5 + math.sin(a) * t), petal[3 - t + (k % 2)])
    c.px(6, 6, hexc('#f4d35e')); c.px(7, 6, hexc('#e6b93a')); c.px(6, 7, hexc('#e6b93a'))
    return c.img


# ------------------------------------------------------------------------------------------ items
def lacquer_bowl(c, rim_y=8, fill=None):
    """A red-black lacquer bowl seen from the front-top; `fill` paints the visible contents (an ellipse)."""
    red = [hexc(h) for h in ('#3a0d0a', '#5e1510', '#8a2216', '#b33a22', '#d8613d')]
    black = [hexc('#1a1214'), hexc('#2a1f22')]
    for y in range(rim_y, 15):
        t = (y - rim_y) / (15 - rim_y)
        hw = 7 - round(t * t * 3.2)
        for x in range(8 - hw, 8 + hw):
            u = (x - (8 - hw)) / (2 * hw)
            k = 3 if u < 0.25 else 2 if u < 0.6 else 1
            if y > 12:
                k = max(0, k - 1)
            c.px(x, y, red[k])
    c.rect(5, 15, 10, 15, black[1])
    for x in range(1, 15):
        c.px(x, rim_y, red[4] if x < 6 else red[3])
    if fill:
        fill(c)


def contents(c, cx, cy, rx, ry, pal):
    c.ball(cx, cy, rx, ry, pal, light=(-0.5, -0.9), spec=False)


def item_cooked_rice():
    c = Canvas()
    rice = [hexc(h) for h in ('#bfb8a8', '#d9d3c4', '#ece8de', '#f8f6f0', '#ffffff')]
    lacquer_bowl(c, 9, lambda c: c.ball(8, 8.5, 6.5, 4.2, rice))
    r = random.Random(200)
    for _ in range(8):
        x, y = r.randrange(3, 13), r.randrange(5, 9)
        if c.get(x, y)[3]:
            c.px(x, y, rice[1])
    c.outline(0.55)
    return c.img


def item_miso_soup():
    c = Canvas()
    soup = [hexc(h) for h in ('#7a4a1e', '#94602a', '#ad7836', '#c48f48', '#d6a65e')]

    def fill(c):
        contents(c, 8, 8.6, 6.4, 2.0, soup)
        for x, y in ((5, 8), (9, 8)):   # tofu cubes
            c.rect(x, y, x + 1, y, hexc('#f3efe4'))
            c.px(x, y + 1, hexc('#d8d2c2')); c.px(x + 1, y + 1, hexc('#d8d2c2'))
        for x, y in ((7, 9), (11, 8), (3, 9)):   # green onion rings
            c.px(x, y, hexc('#6fbf4a'))
        c.px(10, 9, hexc('#2d4a24'))  # wakame
        c.px(4, 8, hexc('#2d4a24'))
    lacquer_bowl(c, 9, fill)
    c.outline(0.55)
    return c.img


def item_udon():
    c = Canvas()
    broth = [hexc(h) for h in ('#9a6a2c', '#b4843c', '#c99c52', '#dcb46a', '#e9c886')]

    def fill(c):
        contents(c, 8, 8.6, 6.4, 2.0, broth)
        for x in range(3, 13):   # thick noodles
            if x % 2 == 0:
                c.px(x, 8, hexc('#f4ecd4')); c.px(x, 9, hexc('#e6dcbe'))
        c.ball(10.5, 7.5, 1.7, 1.2, [hexc('#e9a3b0'), hexc('#f6d3da'), hexc('#ffffff')], spec=False)  # kamaboko
        c.px(10, 7, hexc('#e06a86'))
        c.px(5, 7, hexc('#6fbf4a')); c.px(6, 8, hexc('#6fbf4a'))
    lacquer_bowl(c, 9, fill)
    c.outline(0.55)
    return c.img


def item_ramen():
    c = Canvas()
    broth = [hexc(h) for h in ('#8a4a1c', '#a85e26', '#c47634', '#d68e46', '#e4a85e')]

    def fill(c):
        contents(c, 8, 8.6, 6.4, 2.0, broth)
        for x in range(3, 13):
            if x % 3 != 1:
                c.px(x, 9, hexc('#f0d47a'))
        c.rect(2, 4, 4, 8, hexc('#1f2a1c'))      # nori sheet standing in the bowl
        c.px(2, 4, hexc('#33452c'))
        c.ball(10, 7.6, 2.0, 1.4, [hexc('#e9a83a'), hexc('#f4c04a'), hexc('#fff3d6')], spec=False)  # half egg
        c.px(10, 7, hexc('#e07a14')); c.px(9, 7, hexc('#f29a2a'))
        c.rect(6, 7, 7, 8, hexc('#b56a4a')); c.px(6, 7, hexc('#d48d6a'))   # chashu
        c.px(12, 8, hexc('#6fbf4a')); c.px(5, 9, hexc('#6fbf4a'))
    lacquer_bowl(c, 9, fill)
    c.outline(0.55)
    return c.img


def item_onigiri():
    c = Canvas()
    rice = [hexc(h) for h in ('#b8b2a2', '#d6d0c0', '#ebe6da', '#f8f5ee', '#ffffff')]
    tri = [(8, 1.5), (14.8, 13.5), (1.2, 13.5)]
    def col(x, y):
        k = 4 - int((x * 0.35 + y * 0.6) / 3.2)
        return rice[max(1, min(4, k))]
    c.poly([(8, 1.2), (15, 13.2), (14, 14.8), (2, 14.8), (1, 13.2)], col)
    nori = [hexc('#16201a'), hexc('#223026'), hexc('#2f4234')]
    c.rect(5, 10, 10, 14, nori[1])
    c.rect(5, 10, 10, 10, nori[2])
    c.px(5, 14, nori[0]); c.px(10, 14, nori[0])
    r = random.Random(210)
    for _ in range(6):
        x, y = r.randrange(4, 12), r.randrange(4, 9)
        if c.get(x, y)[3]:
            c.px(x, y, rice[2])
    c.px(6, 12, hexc('#c23a2e'))  # umeboshi peek
    c.outline(0.55)
    return c.img


def item_salmon_nigiri():
    c = Canvas()
    rice = [hexc(h) for h in ('#bdb7a7', '#d9d3c4', '#eeeadf', '#fbfaf5')]
    c.ball(8, 11, 6.5, 3.2, rice + [hexc('#ffffff')], spec=False)
    fish = [hexc(h) for h in ('#b9471e', '#dc6330', '#f08246', '#f8a46a', '#ffd0b0')]
    for y in range(5, 11):
        for x in range(1, 15):
            dx, dy = (x - 7.5) / 7.2, (y - 8.5) / 3.0
            if dx * dx + dy * dy <= 1 and y < 10 + (x % 3 == 0):
                k = 3 if y < 7 else 2 if y < 9 else 1
                if (x + y * 2) % 5 == 0:
                    k = 4   # white fat lines
                c.px(x, y, fish[k])
    c.outline(0.55)
    return c.img


def item_maki_roll():
    c = Canvas()
    c.ball(8, 8, 6.5, 6.5, [hexc('#0f1712'), hexc('#18241b'), hexc('#223326'), hexc('#2f4634')], spec=False)
    c.ball(8, 8, 5.0, 5.0, [hexc('#cfc9b9'), hexc('#e6e1d4'), hexc('#f5f2ea'), hexc('#ffffff')], spec=False)
    c.ball(8, 8, 2.2, 2.2, [hexc('#c44a28'), hexc('#e06838'), hexc('#f28a50')], spec=False)
    c.px(9, 9, hexc('#6fae4a')); c.px(6, 7, hexc('#6fae4a'))
    c.outline(0.6)
    return c.img


def item_tofu():
    c = Canvas()
    top = [hexc('#fbf9f2'), hexc('#ffffff')]
    side = [hexc('#e9e4d6'), hexc('#d8d1c0'), hexc('#c4bca9')]
    c.poly([(2, 6), (9, 3), (14, 5), (7, 8)], lambda x, y: top[(x + y) % 4 == 0])
    c.poly([(2, 6), (7, 8), (7, 14), (2, 12)], side[0])
    c.poly([(7, 8), (14, 5), (14, 11), (7, 14)], side[1])
    c.line(7, 8, 7, 14, side[2])
    c.outline(0.6)
    return c.img


def item_edamame():
    c = Canvas()
    pod = [hexc(h) for h in ('#2f5a1e', '#3f7426', '#53902f', '#6ead42', '#93c862')]
    for i in range(3):
        c.ball(4 + i * 3.4, 11 - i * 3.0, 2.6, 2.2, pod, spec=True)
    c.line(2, 14, 13, 3, pod[1])
    c.px(13, 2, hexc('#5a4a2a')); c.px(14, 1, hexc('#5a4a2a'))
    c.outline(0.55)
    return c.img


def item_mochi(green=False):
    c = Canvas()
    if green:
        pal = [hexc(h) for h in ('#5f8f3a', '#76a548', '#8fbd5a', '#a9d276', '#cbe8a2')]
    else:
        pal = [hexc(h) for h in ('#cfc6bb', '#e3dbd1', '#f1ebe4', '#faf7f3', '#ffffff')]
    c.ball(8, 9.5, 6.3, 5.0, pal)
    if not green:
        c.px(10, 11, hexc('#f2c6d2')); c.px(11, 11, hexc('#f2c6d2'))   # a blush of anko inside
    else:
        for x, y in ((5, 8), (10, 7), (8, 11)):
            c.px(x, y, hexc('#e8f4d6'))   # dusting of sugar
    c.outline(0.6)
    return c.img


def item_dango():
    c = Canvas()
    c.line(1, 15, 14, 2, hexc('#b8925a'))
    c.line(2, 15, 15, 2, hexc('#8a6a3a'))
    pink = [hexc(h) for h in ('#c4627e', '#e2849e', '#f4a8bd', '#fcd3df', '#ffffff')]
    white = [hexc(h) for h in ('#c9c1b5', '#e1dad0', '#f2ede6', '#faf8f4', '#ffffff')]
    green = [hexc(h) for h in ('#4f7f32', '#679b40', '#80b352', '#a3cd72', '#cde6a8')]
    c.ball(11.5, 4.5, 2.8, 2.8, pink)
    c.ball(8, 8, 2.8, 2.8, white)
    c.ball(4.5, 11.5, 2.8, 2.8, green)
    c.outline(0.55)
    return c.img


def item_takuan():
    c = Canvas()
    yel = [hexc(h) for h in ('#b08410', '#d2a41a', '#ecc42c', '#f7dc5a', '#fff0a0')]
    for i, (cx, cy) in enumerate(((5, 10), (9, 8), (12, 5))):
        for y in range(S):
            for x in range(S):
                dx, dy = x + 0.5 - cx, y + 0.5 - cy
                if dx * dx + dy * dy <= 9 and dy <= 0.6:
                    k = 4 if dx * dx + dy * dy < 2 else 3 if dx < 0 else 2
                    c.px(x, y, yel[k])
        c.line(cx - 3, cy, cx + 2, cy, yel[1])
    c.outline(0.55)
    return c.img


def item_grilled_shiitake():
    c = Canvas()
    cap = [hexc(h) for h in ('#2c170c', '#47271a', '#653a24', '#82502f', '#a06a42')]
    c.rect(7, 10, 8, 14, hexc('#c9b08a'))
    c.ball(8, 8, 6.4, 4.2, cap)
    for x in range(3, 14, 3):   # grill marks
        c.line(x, 5, x + 2, 11, hexc('#1a0c06'))
    c.px(5, 6, hexc('#d2b48c'))
    c.outline(0.6)
    return c.img


def item_yakitori():
    c = Canvas()
    c.line(1, 15, 14, 2, hexc('#c8a46a'))
    meat = [hexc(h) for h in ('#6a3412', '#8e4a1c', '#b0662a', '#cc843e', '#e2a65e')]
    for cx, cy in ((11.5, 4.5), (8.5, 7.5), (5.5, 10.5)):
        c.ball(cx, cy, 2.3, 2.0, meat)
    c.px(13, 2, hexc('#5aa040')); c.px(14, 1, hexc('#5aa040'))  # negi
    c.outline(0.55)
    return c.img


def item_tamagoyaki():
    c = Canvas()
    egg = [hexc(h) for h in ('#c48a10', '#e0a41c', '#f2bf34', '#f9d562', '#fde89a')]
    c.rect(1, 6, 14, 12, egg[2])
    for x in range(1, 15):
        c.px(x, 6, egg[4]); c.px(x, 12, egg[0])
    c.rect(1, 6, 1, 12, egg[3])
    for y in (8, 10):   # rolled layers on the cut face
        c.line(1, y, 4, y, egg[1])
    c.line(4, 7, 4, 11, egg[1])
    c.px(9, 8, hexc('#c97a10'))
    c.outline(0.55)
    return c.img


def item_sushi_set():
    c = Canvas()
    wood = [hexc(h) for h in ('#6a4422', '#86582e', '#a3713e', '#bd8b52')]
    c.rect(0, 11, 15, 13, wood[2])
    for x in range(0, 16):
        c.px(x, 11, wood[3]); c.px(x, 13, wood[1])
    c.rect(2, 14, 3, 15, wood[0]); c.rect(12, 14, 13, 15, wood[0])
    rice = [hexc('#d9d3c4'), hexc('#eeeadf'), hexc('#fbfaf5')]
    fish = [hexc('#dc6330'), hexc('#f08246'), hexc('#ffd0b0')]
    for x0 in (1, 6):
        c.rect(x0, 8, x0 + 3, 10, rice[1]); c.px(x0, 10, rice[0])
        c.rect(x0, 6, x0 + 3, 7, fish[1]); c.px(x0 + 1, 6, fish[2])
    c.ball(13, 8.5, 2.4, 2.4, [hexc('#0f1712'), hexc('#223326'), hexc('#2f4634')], spec=False)
    c.ball(13, 8.5, 1.4, 1.4, [hexc('#f5f2ea'), hexc('#ffffff')], spec=False)
    c.px(13, 8, hexc('#e06838'))
    c.px(11, 4, hexc('#7fbf4a')); c.px(12, 4, hexc('#9ad05e'))   # wasabi dab
    c.outline(0.55)
    return c.img


def item_green_tea():
    c = Canvas()
    cup = [hexc(h) for h in ('#4a5560', '#66737e', '#8995a0', '#aeb8c0', '#d2d9de')]
    for y in range(5, 15):
        hw = 5 if y < 13 else 4
        for x in range(8 - hw, 8 + hw):
            u = (x - (8 - hw)) / (2 * hw)
            c.px(x, y, cup[3 if u < 0.3 else 2 if u < 0.7 else 1])
    for x in range(3, 13):   # glaze drip band
        c.px(x, 7 + (x % 3 == 0), hexc('#5a7a6a'))
    contents(c, 8, 5.2, 4.6, 1.1, [hexc('#5f8a2a'), hexc('#7aa636'), hexc('#97c04a')])
    c.px(6, 2, hexc('#e8eef0')); c.px(7, 1, hexc('#e8eef0')); c.px(9, 2, hexc('#e8eef0'))   # steam
    c.outline(0.6)
    return c.img


def item_rice():
    c = Canvas()
    grain = [hexc('#c9bfa2'), hexc('#e6ddc4'), hexc('#f6f0de'), hexc('#ffffff')]
    r = random.Random(300)
    pts = [(4, 5), (7, 4), (10, 6), (5, 8), (8, 8), (11, 9), (6, 11), (9, 11), (3, 10), (12, 12), (7, 13)]
    for x, y in pts:
        a = r.choice([0, 1])
        c.px(x, y, grain[2]); c.px(x + 1 - a, y + a, grain[1]); c.px(x, y, grain[3] if r.random() < 0.3 else grain[2])
    c.outline(0.62)
    return c.img


def item_soybeans():
    c = Canvas()
    bean = [hexc(h) for h in ('#8a6a32', '#a88540', '#c4a258', '#dcbd76', '#efd89c')]
    for cx, cy in ((5, 6), (10, 5), (7, 10), (12, 11)):
        c.ball(cx, cy, 2.3, 2.0, bean)
        c.px(int(cx) + 1, int(cy) + 1, hexc('#5a3e1e'))
    c.outline(0.55)
    return c.img


def item_seeds(pal_hex, seed):
    c = Canvas()
    pal = [hexc(h) for h in pal_hex]
    r = random.Random(seed)
    for x, y in ((4, 5), (9, 4), (12, 8), (6, 9), (3, 12), (10, 12), (7, 14)):
        x += r.randint(-1, 1)
        c.px(x, y, pal[1]); c.px(x + 1, y, pal[2]); c.px(x, y + 1, pal[0])
    c.outline(0.6)
    return c.img


def item_tea_seeds():
    c = Canvas()
    nut = [hexc(h) for h in ('#3e2412', '#5a361c', '#7a4c28', '#986438', '#b6804e')]
    for cx, cy in ((5.5, 6.5), (10.5, 7.5), (7.5, 11.5)):
        c.ball(cx, cy, 2.6, 2.4, nut)
    c.outline(0.6)
    return c.img


def item_wasabi_root():
    c = Canvas()
    root = [hexc(h) for h in ('#3e6a32', '#557f3c', '#6e9a4a', '#8cb560', '#b2d488')]
    for i in range(6):
        c.ball(5 + i * 1.2, 13 - i * 1.6, 2.4, 1.6, root, spec=False)
    for i in range(6):
        c.px(5 + int(i * 1.2), 13 - int(i * 1.6), root[1])     # bumpy scars
    c.line(11, 4, 12, 1, hexc('#4a8a3a'))
    c.line(12, 4, 15, 2, hexc('#4a8a3a'))
    c.outline(0.55)
    return c.img


def item_daikon():
    c = Canvas()
    root = [hexc(h) for h in ('#b9c0b2', '#d7ddd0', '#ecf0e6', '#f8faf4', '#ffffff')]
    for i in range(10):
        r = 2.4 - i * 0.18
        c.ball(5 + i * 0.75, 13 - i * 0.9, r, r, root, spec=False)
    c.px(2, 15, root[1])
    for a, ln in ((-2.2, 5), (-1.6, 6), (-1.0, 5)):
        for t in range(ln):
            c.px(int(12 + math.cos(a) * t), int(5 + math.sin(a) * t), G['leaf'][2 + (t % 2)])
    c.outline(0.55)
    return c.img


def item_tea_leaves():
    c = Canvas()
    pal = [hexc(h) for h in ('#1d4a1c', '#2a6224', '#3a7c2e', '#55a040', '#7ec45e')]
    leaf_shape(c, 1.5, 14.5, -0.95, 13, 3.2, pal)
    leaf_shape(c, 7.0, 15.0, -0.3, 8.5, 2.4, pal)
    c.line(1, 15, 3, 13, hexc('#5a4a24'))
    c.outline(0.55)
    return c.img


def item_nori():
    c = Canvas()
    pal = [hexc(h) for h in ('#0d1610', '#152219', '#1d2f22', '#28402e', '#36533c')]
    f = field(320, ((4, 0.6), (16, 0.4)))
    for y in range(2, 14):
        for x in range(2, 14):
            c.px(x, y, pal[1 + quant(f[y][x], 3, 0.3, x, y)])
    for x in range(2, 14):
        c.px(x, 2, pal[4])
    c.outline(0.7)
    return c.img


def item_rice_flour():
    c = Canvas()
    sack = [hexc(h) for h in ('#a89f8a', '#c7bfa8', '#ddd6c2', '#eeeadb', '#fbf9f2')]
    c.ball(8, 10, 5.6, 4.8, sack)
    c.rect(6, 3, 9, 5, sack[2])
    c.rect(5, 5, 10, 5, hexc('#b23a2a'))   # red string
    c.px(10, 4, hexc('#b23a2a'))
    for x, y in ((8, 8), (8, 9), (8, 10), (8, 11), (8, 12), (6, 10), (7, 10), (9, 10), (10, 10), (6, 8), (10, 8), (6, 12), (10, 12)):
        c.px(x, y, hexc('#b23a2a'))   # a red 米 stamp
    c.outline(0.55)
    return c.img


def item_miso_paste():
    c = Canvas()
    wood = [hexc(h) for h in ('#6a4422', '#8a5a2e', '#a8733e', '#c48e56', '#dcaa72')]
    for y in range(7, 15):
        for x in range(2, 14):
            c.px(x, y, wood[3 if x < 5 else 2 if x < 10 else 1])
    for y in (8, 12):
        c.line(2, y, 13, y, hexc('#4a3a2a'))
    contents(c, 8, 7, 6, 1.6, [hexc('#7a4a1a'), hexc('#9a6224'), hexc('#b67c34'), hexc('#c8924a')])
    c.outline(0.55)
    return c.img


def item_matcha():
    c = Canvas()
    bowl = [hexc(h) for h in ('#3e3a34', '#5a544a', '#7a7262', '#9a917e')]
    for y in range(8, 15):
        t = (y - 8) / 7
        hw = 7 - round(t * t * 3)
        for x in range(8 - hw, 8 + hw):
            c.px(x, y, bowl[2 if x < 6 else 1])
    green = [hexc(h) for h in ('#4f7a1c', '#6a9a26', '#86b632', '#a6d04e', '#c8e47e')]
    c.ball(8, 8, 6, 2.6, green)
    c.outline(0.55)
    return c.img


def item_noodles():
    c = Canvas()
    nd = [hexc(h) for h in ('#c9a85a', '#dcc070', '#ead48c', '#f5e6b0')]
    for x in range(3, 13):
        for y in range(2, 15):
            if (x + (y // 4)) % 2 == 0:
                c.px(x, y, nd[2 if x < 7 else 1])
            else:
                c.px(x, y, nd[3] if y % 5 == 0 else nd[0])
    c.rect(3, 7, 12, 8, hexc('#3a6ea8'))   # paper band
    c.rect(3, 7, 12, 7, hexc('#5a8ec8'))
    c.outline(0.55)
    return c.img


ITEM_FN = {
    'rice': item_rice, 'soybeans': item_soybeans,
    'daikon_seeds': lambda: item_seeds(['#5a3a1e', '#7a5230', '#9c6e44'], 330),
    'wasabi_root': item_wasabi_root, 'tea_seeds': item_tea_seeds, 'daikon': item_daikon, 'tea_leaves': item_tea_leaves,
    'nori': item_nori, 'rice_flour': item_rice_flour, 'miso_paste': item_miso_paste, 'matcha': item_matcha, 'noodles': item_noodles,
    'cooked_rice': item_cooked_rice, 'onigiri': item_onigiri, 'salmon_nigiri': item_salmon_nigiri, 'maki_roll': item_maki_roll,
    'tofu': item_tofu, 'edamame': item_edamame, 'mochi': item_mochi, 'dango': item_dango, 'takuan': item_takuan,
    'grilled_shiitake': item_grilled_shiitake, 'yakitori': item_yakitori, 'tamagoyaki': item_tamagoyaki,
    'matcha_mochi': lambda: item_mochi(True), 'miso_soup': item_miso_soup, 'udon': item_udon, 'ramen': item_ramen,
    'sushi_set': item_sushi_set, 'green_tea': item_green_tea,
}

BLOCK_FN = {
    'volcanic_ash': volcanic_ash, 'black_sand': black_sand, 'scoria': scoria, 'pumice': pumice,
    'hikari_moss': lambda: hikari_moss(0), 'hikari_moss_1': lambda: hikari_moss(1), 'hikari_moss_2': lambda: hikari_moss(2),
    'eclipse_crystal_block': eclipse_crystal_block, 'eclipse_crystal_cluster': eclipse_cluster,
    'glowcap_block': glowcap_block, 'glowcap_stem': glowcap_stem_side, 'glowcap_stem_top': glowcap_stem_top,
    'momiji_leaves': momiji_leaves, 'ginkgo_leaves': ginkgo_leaves, 'wisteria_leaves': wisteria_leaves,
    'hydrangea_blue': lambda: hydrangea(['#2a3f8f', '#3f5fc0', '#6f8fe4', '#b4c6fa']),
    'hydrangea_purple': lambda: hydrangea(['#4a2a7f', '#6f45b0', '#9c74db', '#d2bdf6']),
    'hydrangea_pink': lambda: hydrangea(['#8f2a5a', '#c04a80', '#e47aa6', '#fbc0d6']),
    'shrine_bricks': lambda: bricks(5, '#57534f', '#a5a19a'),
    'mossy_shrine_bricks': lambda: bricks(5, '#57534f', '#a5a19a', moss=True),
    'cracked_shrine_bricks': lambda: bricks(5, '#57534f', '#a5a19a', crack=True),
    'vermilion_planks': vermilion_planks, 'tatami': tatami,
    'stone_lantern': stone_lantern, 'stone_lantern_light': stone_lantern_light,
    'shiitake': shiitake, 'higanbana': higanbana, 'lumen_fern': lumen_fern, 'glowcap': glowcap,
    'spirit_vine': spirit_vine, 'wisteria_vine': wisteria_vine,
    'susuki_bottom': lambda: susuki(False), 'susuki_top': lambda: susuki(True), 'lotus': lotus,
}
for _crop, _fn in CROP.items():
    for _s in range(4):
        BLOCK_FN[f'{_crop}_stage{_s}'] = (lambda f, s: (lambda: f(s)))(_fn, _s)
for _w in ['wild_rice', 'wild_soybean', 'wild_daikon', 'wild_wasabi', 'wild_tea']:
    BLOCK_FN[_w] = (lambda k: (lambda: wild(k)))(_w)

# LabPBR specular maps (_s): emission in the alpha channel for shader packs (Photon, Complementary, BSL...)
EMISSIVE = {'hikari_moss': 'glow', 'hikari_moss_1': 'glow', 'hikari_moss_2': 'glow', 'glowcap_block': 'spots',
            'glowcap': 'all', 'lumen_fern': 'all', 'spirit_vine': 'all', 'stone_lantern_light': 'all',
            'eclipse_crystal_block': 'bright', 'eclipse_crystal_cluster': 'all'}


def specular(img, mode):
    out = Image.new('RGBA', img.size, (0, 0, 0, 0))
    for y in range(img.size[1]):
        for x in range(img.size[0]):
            r, g, b, a = img.getpixel((x, y))
            if a == 0:
                continue
            lum = (r * 0.3 + g * 0.59 + b * 0.11) / 255
            if mode == 'all':
                e = 0.55 + 0.4 * lum
            elif mode == 'glow':
                e = 0.95 if (g > 180 and b > 150) else 0.0
            elif mode == 'spots':
                e = 0.95 if lum > 0.7 else 0.35
            else:   # bright
                e = 0.9 if lum > 0.55 else 0.0
            ev = max(0, min(254, int(e * 254)))   # 255 means "no emission" in LabPBR
            out.putpixel((x, y), (40, 10, 0, ev if e > 0 else 255))
    return out
