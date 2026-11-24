#!/usr/bin/env python3
"""Textures, models, blockstates, item definitions, loot tables, recipes, tags and translations for Phase World."""
import json
import os
import random

import numpy as np
from PIL import Image

BASE = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
NS = 'phaseworld'
A = os.path.join(BASE, 'assets', NS)
D = os.path.join(BASE, 'data', NS)


def wj(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)


def P(n):
    return f'{NS}:{n}'


def hexc(h, a=255):
    h = h.lstrip('#')
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def save(img, kind, name):
    p = os.path.join(A, 'textures', kind, name + '.png')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)


# ------------------------------------------------------------------------------------------ procedural block textures
def vnoise(seed, scale=4):
    rng = np.random.default_rng(seed)
    g = rng.random((16 // scale + 1, 16 // scale + 1))
    out = np.zeros((16, 16))
    for y in range(16):
        for x in range(16):
            gx, gy = x / scale, y / scale
            x0, y0 = int(gx) % (16 // scale), int(gy) % (16 // scale)
            x1, y1 = (x0 + 1) % (16 // scale), (y0 + 1) % (16 // scale)
            tx, ty = gx - int(gx), gy - int(gy)
            tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
            a = g[y0, x0] * (1 - tx) + g[y0, x1] * tx
            b = g[y1, x0] * (1 - tx) + g[y1, x1] * tx
            out[y, x] = a * (1 - ty) + b * ty
    return out


def ramp(values, colors):
    """values in 0..1 -> palette of hex colors."""
    img = Image.new('RGBA', (16, 16))
    n = len(colors)
    for y in range(16):
        for x in range(16):
            i = min(n - 1, int(values[y, x] * n))
            img.putpixel((x, y), hexc(colors[i]))
    return img


def speckle(seed, colors, weights=None, smooth=0.5):
    rng = np.random.default_rng(seed)
    v = smooth * vnoise(seed, 4) + (1 - smooth) * rng.random((16, 16))
    v = (v - v.min()) / (v.max() - v.min() + 1e-9)
    return ramp(v, colors)


def porous(seed, colors, hole, holes=9):
    img = speckle(seed, colors)
    rng = random.Random(seed)
    for _ in range(holes):
        x, y = rng.randrange(16), rng.randrange(16)
        img.putpixel((x, y), hexc(hole))
        if rng.random() < 0.5:
            img.putpixel(((x + 1) % 16, y), hexc(hole))
    return img


def bricks(seed, mortar, faces, crack=False, moss=False):
    rng = random.Random(seed)
    img = Image.new('RGBA', (16, 16), hexc(mortar))
    for row in range(4):
        y0 = row * 4
        off = 0 if row % 2 == 0 else 4
        for bx in range(-1, 3):
            x0 = bx * 8 + off
            base = hexc(rng.choice(faces))
            for y in range(y0, y0 + 3):
                for x in range(x0, x0 + 7):
                    if 0 <= x < 16:
                        j = rng.randint(-10, 10)
                        c = tuple(max(0, min(255, base[i] + j)) for i in range(3)) + (255,)
                        if y == y0:
                            c = tuple(min(255, v + 18) for v in c[:3]) + (255,)
                        img.putpixel((x, y), c)
    if crack:
        x, y = 3, 1
        for _ in range(14):
            img.putpixel((x % 16, y % 16), hexc('#3a3a3d'))
            x += rng.choice((0, 1, 1))
            y += 1
    if moss:
        for _ in range(55):
            x, y = rng.randrange(16), rng.randrange(16)
            if rng.random() < (1 - y / 22):
                img.putpixel((x, y), hexc(rng.choice(['#4f7a30', '#5f8f38', '#3f6428'])))
    return img


def planks(seed, colors, dark):
    rng = random.Random(seed)
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = hexc(colors[(x * 7 + y * 3 + rng.randrange(3)) % len(colors)])
            if y % 4 == 3:
                c = hexc(dark)
            img.putpixel((x, y), c)
    for row in range(4):
        x = rng.randrange(16)
        img.putpixel((x, row * 4 + 1), hexc(dark))
    return img


def leaves_tex(seed, colors, holes=0.18):
    rng = np.random.default_rng(seed)
    v = 0.55 * vnoise(seed, 4) + 0.45 * rng.random((16, 16))
    img = ramp((v - v.min()) / (v.max() - v.min()), colors)
    for y in range(16):
        for x in range(16):
            if rng.random() < holes:
                img.putpixel((x, y), (0, 0, 0, 0))
    return img


def tatami():
    img = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            c = hexc('#b9b46b') if (x + (y // 1)) % 2 == 0 else hexc('#a8a45d')
            if y % 2 == 1:
                c = hexc('#c4bf75') if x % 2 else hexc('#b0ab63')
            if x in (0, 15):
                c = hexc('#2f3b2a')
            img.putpixel((x, y), c)
    return img


# ------------------------------------------------------------------------------------------ hand-drawn sprites
def sprite(rows, pal):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        for x, ch in enumerate(row):
            if ch != '.':
                img.putpixel((x, y), hexc(pal[ch]))
    return img


OUT = '#2b1f18'
SPR = {}

SPR['onigiri'] = (["................",
                   ".......oo.......",
                   "......owwo......",
                   ".....owwwwo.....",
                   "....owwwwwwo....",
                   "....owwwswwo....",
                   "...owwwwwwwwo...",
                   "...owswwwwwwo...",
                   "..owwwwwwwswwo..",
                   "..owwwwwwwwwwo..",
                   ".owwwnnnnnnwwso.",
                   ".owwnnnnnnnnwwo.",
                   ".oswnnnnnnnnwwo.",
                   "..oonnnnnnnnoo..",
                   "....oooooooo....",
                   "................"], {'o': OUT, 'w': '#f6f3ea', 's': '#d9d3c2', 'n': '#1d3326'})
SPR['cooked_rice'] = (["................",
                       "................",
                       "....oooooooo....",
                       "...owwswwwsww...",
                       "..owwwwwswwwwo..",
                       "..owswwwwwwsw...",
                       ".owwwwwswwwwwwo.",
                       ".obbbbbbbbbbbbo.",
                       ".obbrbbbbbbrbbo.",
                       "..obbbbbbbbbbo..",
                       "..obbbbbbbbbbo..",
                       "...obbbbbbbbo...",
                       "....oooooooo....",
                       "................",
                       "................",
                       "................"], {'o': OUT, 'w': '#f6f3ea', 's': '#d9d3c2', 'b': '#8c2f25', 'r': '#c9a24a'})
SPR['rice'] = (["................",
                "................",
                "......w.........",
                "...w.....w......",
                ".......s....w...",
                "..s..w......s...",
                "......w..w......",
                "...w.......s....",
                ".......w........",
                "..w..s.....w....",
                ".........w......",
                "....w..s.....w..",
                "..........w.....",
                "......w.........",
                "................",
                "................"], {'w': '#f2ecd6', 's': '#d6c99c'})
SPR['soybeans'] = (["................",
                    "................",
                    "................",
                    "....oo....oo....",
                    "...oyyo..oyyo...",
                    "...oyho..oyho...",
                    "....oo....oo....",
                    "......oo........",
                    ".....oyyo..oo...",
                    ".....oyho.oyyo..",
                    "......oo..oyho..",
                    "...oo......oo...",
                    "..oyyo..........",
                    "..oyho..........",
                    "...oo...........",
                    "................"], {'o': '#5d4a1e', 'y': '#d9c27a', 'h': '#f0e2a8'})
SPR['daikon_seeds'] = (["................"] * 4 +
                       ["......o.........",
                        "...o.......o....",
                        "........o.......",
                        ".....o......o...",
                        "..o......o......",
                        ".......o........",
                        "....o.......o...",
                        "..........o.....",
                        "................",
                        "................",
                        "................",
                        "................"], {'o': '#6b3d2a'})
SPR['tea_seeds'] = (["................"] * 3 +
                    ["......oo........",
                     ".....obbo...oo..",
                     ".....obho..obbo.",
                     "......oo...obho.",
                     "............oo..",
                     "...oo...........",
                     "..obbo....oo....",
                     "..obho...obbo...",
                     "...oo....obho...",
                     "..........oo....",
                     "................",
                     "................",
                     "................"], {'o': '#2e1c12', 'b': '#6a4a2e', 'h': '#9c7552'})
SPR['wasabi_root'] = (["................",
                       "......gg..g.....",
                       "....g.gglgg.....",
                       ".....gglgg......",
                       "......ggg.......",
                       "......owwo......",
                       ".....owlwwo.....",
                       ".....owwlwo.....",
                       ".....owwwlo.....",
                       ".....owlwwo.....",
                       "......owwwo.....",
                       "......owlo......",
                       "......owwo......",
                       ".......oo.......",
                       "................",
                       "................"], {'o': '#3c5a2a', 'w': '#9fcf6a', 'l': '#c9ec98', 'g': '#4f8f35'})
SPR['daikon'] = (["......g..g......",
                  ".....ggggg......",
                  "....g.ggg.g.....",
                  "......gg........",
                  ".....owwo.......",
                  ".....owwso......",
                  ".....owwwo......",
                  ".....owwso......",
                  ".....owwwo......",
                  "......owso......",
                  "......owwo......",
                  "......owso......",
                  ".......owo......",
                  ".......owo......",
                  "........o.......",
                  "................"], {'o': '#8a8577', 'w': '#f7f5ee', 's': '#dedad0', 'g': '#4f9a3a'})
SPR['tea_leaves'] = (["................",
                      "................",
                      "..........oo....",
                      ".........oggo...",
                      "........ogllgo..",
                      ".......ogglgo...",
                      "......ogglgo....",
                      ".....oggglo.....",
                      "....oglggo......",
                      "...ogglgo.......",
                      "...oglgo........",
                      "..oglgo.........",
                      "..ooo...........",
                      ".s..............",
                      "................",
                      "................"], {'o': '#1f4a1f', 'g': '#3f8f3a', 'l': '#76c25a', 's': '#5a3a20'})
SPR['nori'] = (["................",
                "................",
                "..oooooooooooo..",
                "..onnnnnnnnnno..",
                "..onhnnnnnhnno..",
                "..onnnnnnnnnno..",
                "..onnnnhnnnnno..",
                "..onnnnnnnnnho..",
                "..onhnnnnnnnno..",
                "..onnnnnnhnnno..",
                "..onnnnnnnnnno..",
                "..onnnhnnnnnno..",
                "..oooooooooooo..",
                "................",
                "................",
                "................"], {'o': '#0c1a12', 'n': '#1d3326', 'h': '#2f4f3a'})
SPR['rice_flour'] = (["................",
                      "................",
                      "......oooo......",
                      ".....obbbbo.....",
                      "......obbo......",
                      ".....owwwwo.....",
                      "....owwwwwwo....",
                      "...owwwswwwwo...",
                      "...owwwwwwswo...",
                      "...owswwwwwwo...",
                      "...owwwwwwwwo...",
                      "...owwwwswwwo...",
                      "....owwwwwwo....",
                      ".....oooooo.....",
                      "................",
                      "................"], {'o': '#7a6a52', 'w': '#f4efe2', 's': '#ddd4c0', 'b': '#a8875a'})
SPR['miso_paste'] = (["................",
                      "................",
                      "................",
                      "....oooooooo....",
                      "...ommmmmmmmo...",
                      "..ommhmmmmhmmo..",
                      "..ommmmmmmmmmo..",
                      "..oddddddddddo..",
                      "..oddddddddddo..",
                      "..oddddddddddo..",
                      "..oddddddddddo..",
                      "...oddddddddo...",
                      "....oooooooo....",
                      "................",
                      "................",
                      "................"], {'o': OUT, 'm': '#b0702e', 'h': '#d39a52', 'd': '#6a4a2a'})
SPR['matcha'] = (["................",
                  "................",
                  "................",
                  "................",
                  "......gggg......",
                  "....gglgggg.....",
                  "...ggggglggg....",
                  "..ggglggggggg...",
                  ".oooooooooooooo.",
                  ".obbbbbbbbbbbbo.",
                  "..obbbbbbbbbbo..",
                  "...obbbbbbbbo...",
                  "....oooooooo....",
                  "................",
                  "................",
                  "................"], {'o': OUT, 'g': '#6fae3a', 'l': '#a6d86a', 'b': '#2b2b2b'})
SPR['noodles'] = (["................",
                   "................",
                   "...y.y.y.y.y....",
                   "..y.y.y.y.y.y...",
                   "...y.y.y.y.y.y..",
                   "..y.y.y.y.y.y...",
                   "...y.y.y.y.y.y..",
                   "..y.y.y.y.y.y...",
                   "..oooooooooooo..",
                   "..orrrrrrrrrro..",
                   "..oooooooooooo..",
                   "...y.y.y.y.y.y..",
                   "..y.y.y.y.y.y...",
                   "...y.y.y.y.y....",
                   "................",
                   "................"], {'y': '#efd98a', 'o': '#5a2a20', 'r': '#b8392a'})
SPR['salmon_nigiri'] = (["................",
                         "................",
                         "................",
                         "................",
                         "...ooooooooo....",
                         "..ossssssssso...",
                         ".osllslllslsso..",
                         ".ossssssssssso..",
                         ".onnnnnnnnnnno..",
                         ".owwwwwwwwwwwo..",
                         ".owwswwwwswwwo..",
                         "..owwwwwwwwwo...",
                         "...ooooooooo....",
                         "................",
                         "................",
                         "................"], {'o': OUT, 's': '#f08a54', 'l': '#ffd0b0', 'n': '#1d3326', 'w': '#f6f3ea'})
SPR['maki_roll'] = (["................",
                     "................",
                     "................",
                     ".....oooooo.....",
                     "....onnnnnno....",
                     "...onwwwwwwno...",
                     "...onwwsswwno...",
                     "...onwsffswno...",
                     "...onwsffswno...",
                     "...onwwsswwno...",
                     "...onwwwwwwno...",
                     "....onnnnnno....",
                     ".....oooooo.....",
                     "................",
                     "................",
                     "................"], {'o': '#0c1a12', 'n': '#1d3326', 'w': '#f6f3ea', 's': '#d9d3c2', 'f': '#e2704a'})
SPR['tofu'] = (["................",
                "................",
                "................",
                "......oooooo....",
                ".....owwwwwwo...",
                "....owwwwwwwwo..",
                "...owwwwwwwwso..",
                "...oooooooooso..",
                "...owwwwwwwwso..",
                "...owwwwwwwwso..",
                "...owwwwwwwwso..",
                "...owwwwwwwwo...",
                "...oooooooooo...",
                "................",
                "................",
                "................"], {'o': '#9c968a', 'w': '#f8f6ee', 's': '#dcd8cc'})
SPR['edamame'] = (["................",
                   "................",
                   "............o...",
                   "..........ooo...",
                   ".........oggo...",
                   "........oglgo...",
                   ".......ogggo....",
                   "......oglgo.....",
                   ".....ogggo......",
                   "....oglgo.......",
                   "...ogggo........",
                   "...oggo.........",
                   "...ooo..........",
                   "................",
                   "................",
                   "................"], {'o': '#2f5a1f', 'g': '#6fb84a', 'l': '#a8e07a'})
SPR['mochi'] = (["................",
                 "................",
                 "................",
                 "................",
                 ".....oooooo.....",
                 "....owwwwwwo....",
                 "...owwwhwwwwo...",
                 "..owwwwwwwwwwo..",
                 "..owwwwwwwwwso..",
                 "..owwwwwwwwsso..",
                 "...osssssssso...",
                 "....oooooooo....",
                 "................",
                 "................",
                 "................",
                 "................"], {'o': '#a49a8c', 'w': '#fbf7f0', 'h': '#ffffff', 's': '#e4ddd0'})
SPR['matcha_mochi'] = (SPR['mochi'][0], {'o': '#4a6a2a', 'w': '#9ccf6a', 'h': '#c8ec98', 's': '#7fb04f'})
SPR['dango'] = (["..............o.",
                 ".............o..",
                 "..........ooo...",
                 ".........oppo...",
                 "........oppppo..",
                 "........opppo...",
                 "......ooowoo....",
                 ".....owwwwo.....",
                 ".....owwwwo.....",
                 "...ooogooo......",
                 "..ogggggo.......",
                 "..oggggo........",
                 "..ooooo.........",
                 ".o..............",
                 "o...............",
                 "................"], {'o': '#5a3a20', 'p': '#f4a6c0', 'w': '#fbf7f0', 'g': '#8fcf6a'})
SPR['takuan'] = (["................",
                  "................",
                  "................",
                  "..oo....oo......",
                  ".oyyo..oyyo.....",
                  "oyhyyooyhyyo....",
                  "oyyyyooyyyyo....",
                  ".oyyo..oyyo.....",
                  "..oo....oo..oo..",
                  "...........oyyo.",
                  "..........oyhyyo",
                  "..........oyyyyo",
                  "...........oyyo.",
                  "............oo..",
                  "................",
                  "................"], {'o': '#8a6a10', 'y': '#f2d23a', 'h': '#fff08a'})
SPR['grilled_shiitake'] = (["................",
                            "................",
                            "................",
                            ".....oooooo.....",
                            "...oobbbbbboo...",
                            "..obbbhbbbbbbo..",
                            ".obbhbbbbbhbbbo.",
                            ".obbbbbbbbbbbbo.",
                            ".oooootttooooo..",
                            "......otto......",
                            "......otto......",
                            "......otto......",
                            "......oooo......",
                            "................",
                            "................",
                            "................"], {'o': '#2e1a0e', 'b': '#6a3e20', 'h': '#a8784a', 't': '#e8d4b0'})
SPR['yakitori'] = (["..............o.",
                    ".............o..",
                    "...........oo...",
                    "..........obbo..",
                    ".........obhbo..",
                    "........oobbo...",
                    ".......obbo.....",
                    "......obhbo.....",
                    ".....oobbo......",
                    "....obbo........",
                    "...obhbo........",
                    "...oobo.........",
                    "...o............",
                    "..o.............",
                    ".o..............",
                    "................"], {'o': '#3a2410', 'b': '#a4612a', 'h': '#d89a52'})
SPR['tamagoyaki'] = (["................",
                      "................",
                      "................",
                      "....oooooooo....",
                      "...oyyyyyyyyo...",
                      "..oyhyyhyyhyyo..",
                      "..oyyyyyyyyyyo..",
                      "..oddddddddddo..",
                      "..oyyyyyyyyyyo..",
                      "..oyyhyyyhyyyo..",
                      "..oddddddddddo..",
                      "..oyyyyyyyyyyo..",
                      "...oooooooooo...",
                      "................",
                      "................",
                      "................"], {'o': '#8a6a10', 'y': '#f5d64a', 'h': '#fff09a', 'd': '#d8a830'})
BOWL = ["................",
        "................",
        "................",
        "................",
        "................",
        "...cccccccccc...",
        "..cxxxxxxxxxxc..",
        ".ckkkkkkkkkkkkc.",
        ".obbbbbbbbbbbbo.",
        ".obrbbbbbbbbrbo.",
        "..obbbbbbbbbbo..",
        "..obbbbbbbbbbo..",
        "...obbbbbbbbo...",
        "....oooooooo....",
        "................",
        "................"]


def bowl(top_rows, pal):
    rows = list(BOWL)
    for i, r in top_rows.items():
        rows[i] = r
    base = {'o': OUT, 'b': '#8c2f25', 'r': '#c9a24a'}
    base.update(pal)
    return rows, base


SPR['miso_soup'] = bowl({5: "...cccccccccc...", 6: "..cmmgmmmwmmmc..", 7: ".ckmmmmwmmgmmkc."},
                        {'c': OUT, 'm': '#c08a4a', 'g': '#4f9a3a', 'w': '#f8f6ee', 'k': '#a06a30', 'x': '#c08a4a'})
SPR['udon'] = bowl({4: "....y.y..y......", 5: "...cycyccyccc...", 6: "..cyyyyyyyyyyc..", 7: ".ckyyyyyyyyyykc."},
                   {'c': OUT, 'y': '#f4ecc8', 'k': '#c9a46a', 'x': '#f4ecc8'})
SPR['ramen'] = bowl({3: ".......ee.......", 4: "......ewye......", 5: "...ccceyecccc...", 6: "..cnyypppyyygc..", 7: ".ckyyyppyyyyykc."},
                    {'c': OUT, 'y': '#f0d27a', 'p': '#c85a4a', 'n': '#1d3326', 'g': '#4f9a3a', 'e': '#fbf7f0', 'w': '#f5c030',
                     'k': '#b07a3a', 'x': '#f0d27a'})
SPR['sushi_set'] = (["................",
                     "................",
                     "................",
                     "................",
                     "..ss..nn..ff....",
                     ".ossonwwoofffo..",
                     ".owwonwfwowwwo.g",
                     ".owwonwwoowwwogg",
                     "dddddddddddddddd",
                     "dhhhhhhhhhhhhhhd",
                     "dddddddddddddddd",
                     ".dd..........dd.",
                     "................",
                     "................",
                     "................",
                     "................"], {'o': OUT, 's': '#f08a54', 'w': '#f6f3ea', 'n': '#1d3326', 'f': '#e85a4a', 'g': '#7fc04a',
                                          'd': '#5a3a20', 'h': '#8a5a30'})
SPR['green_tea'] = (["................",
                     "................",
                     "................",
                     "................",
                     "................",
                     "...oooooooooo...",
                     "..ogggggggggggo.",
                     "..oclllllllllo.o",
                     "..occcccccccco.o",
                     "..occcccccccco.o",
                     "...occcccccco.o.",
                     "...occcccccco...",
                     "....oooooooo....",
                     "................",
                     "................",
                     "................"], {'o': '#3a3a40', 'g': '#8fcf5a', 'l': '#bfe88a', 'c': '#d9d4c8'})


def crop_stage(kind, stage):
    rng = random.Random(hash((kind, stage)) & 0xffff)
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    h = [4, 8, 12, 15][stage]
    stem = {'rice': '#6fae3a', 'soybean': '#4f9a3a', 'daikon': '#4f9a3a', 'wasabi': '#3f8f45', 'tea': '#2f7a35'}[kind]
    light = {'rice': '#a8d36a', 'soybean': '#7fcf5a', 'daikon': '#7fcf5a', 'wasabi': '#7fcf6a', 'tea': '#5aaf4a'}[kind]
    for x in range(1, 15, 2 if kind == 'rice' else 3):
        hh = max(2, h - rng.randint(0, 3))
        for y in range(16 - hh, 16):
            img.putpixel((x, y), hexc(stem if (y + x) % 3 else light))
        if kind != 'rice' and hh > 4:
            for dx in (-1, 1):
                yy = 16 - hh + rng.randint(1, 3)
                if 0 <= x + dx < 16:
                    img.putpixel((x + dx, yy), hexc(light))
    if stage == 3:
        if kind == 'rice':
            for x in range(1, 15, 2):
                for y in range(1, 6):
                    if rng.random() < 0.7:
                        img.putpixel((min(15, x + (y > 3)), y), hexc('#e8c95a' if y % 2 else '#d4b040'))
        if kind == 'soybean':
            for _ in range(7):
                x, y = rng.randrange(1, 15), rng.randrange(5, 13)
                img.putpixel((x, y), hexc('#c9d86a'))
                img.putpixel((x, min(15, y + 1)), hexc('#a8b84a'))
        if kind == 'daikon':
            for x in (4, 10):
                for y in (13, 14, 15):
                    img.putpixel((x, y), hexc('#f7f5ee'))
                    img.putpixel((x + 1, y), hexc('#dedad0'))
        if kind == 'wasabi':
            for x in (5, 9):
                for y in (14, 15):
                    img.putpixel((x, y), hexc('#9fcf6a'))
        if kind == 'tea':
            for _ in range(10):
                img.putpixel((rng.randrange(1, 15), rng.randrange(3, 12)), hexc('#9be07a'))
    return img


def wild(kind):
    base = crop_stage({'wild_rice': 'rice', 'wild_soybean': 'soybean', 'wild_daikon': 'daikon', 'wild_wasabi': 'wasabi',
                       'wild_tea': 'tea'}[kind], 3)
    return base


def shiitake_tex():
    rows = ["................", "................", "................", "................",
            "................", "................", "....oooo........", "..obbbhbo.......",
            ".obbhbbbbo..ooo.", ".oooottooo.obhbo", "....otto..oootto", "....otto....otto",
            "....otto....otto", "................", "................", "................"]
    return sprite(rows, {'o': '#2e1a0e', 'b': '#6a3e20', 'h': '#a8784a', 't': '#e8d4b0'})


def cluster_tex():
    rows = ["................", ".......o........", "......oco.......", "......ocjo......", ".....occjo......",
            ".....ocjjo...o..", "..o..ocjjo..oco.", ".oco.ocjjo..ocjo", ".ocjooccjjo.ocjo", ".ocjoocjjjoocjo.",
            "..ocjocjjjoocjo.", "..ocjocjjjoocjo.", "..ocjocjjjoocj..", "...ojjjjjjjjj...", "....oooooooo....", "................"]
    return sprite(rows, {'o': '#1b0f2a', 'c': '#d8b4ff', 'j': '#6a3fa8'})


def crystal_block():
    img = speckle(77, ['#1a0f2a', '#2a1745', '#3d2266', '#57318f', '#7a4fc0'])
    for (x, y) in [(3, 3), (11, 5), (6, 11), (13, 13), (1, 9)]:
        img.putpixel((x, y), hexc('#f4c36a'))   # the eclipse corona glints
    return img


def lantern_textures():
    stone = speckle(31, ['#7f7f7a', '#8c8c86', '#999992', '#a6a69f'])
    light = Image.new('RGBA', (16, 16), hexc('#8c8c86'))
    for y in range(4, 12):
        for x in range(4, 12):
            light.putpixel((x, y), hexc('#ffd36a' if (x + y) % 3 else '#ffe9a8'))
    return stone, light


# ------------------------------------------------------------------------------------------ definitions
CUBES = {
    'volcanic_ash': lambda: speckle(1, ['#5e5a57', '#6e6a66', '#7a7672', '#8a8581']),
    'black_sand': lambda: speckle(2, ['#1d1b1b', '#262423', '#2f2c2b', '#45403d'], smooth=0.3),
    'scoria': lambda: porous(3, ['#3a1d18', '#4a241c', '#5a2c20', '#6e3726'], '#160a08', 12),
    'pumice': lambda: porous(4, ['#cfc6b4', '#d9d1c0', '#e3dccc', '#c2b8a4'], '#9e9482', 14),
    'hikari_moss': None,
    'eclipse_crystal_block': crystal_block,
    'shrine_bricks': lambda: bricks(5, '#5c5c5e', ['#a3a39d', '#9a9a94', '#acaca6']),
    'mossy_shrine_bricks': lambda: bricks(5, '#5c5c5e', ['#a3a39d', '#9a9a94', '#acaca6'], moss=True),
    'cracked_shrine_bricks': lambda: bricks(5, '#5c5c5e', ['#a3a39d', '#9a9a94', '#acaca6'], crack=True),
    'vermilion_planks': lambda: planks(6, ['#c9402a', '#d4482e', '#bf3a26', '#d85434'], '#7a1f14'),
    'tatami': tatami,
}
FALLING = {'volcanic_ash', 'black_sand'}
LEAVES = {'momiji_leaves': ['#7a160e', '#a3241a', '#c63a22', '#e0602e', '#f08a3a'],
          'ginkgo_leaves': ['#a87a10', '#c99a1a', '#e2b82a', '#f2d24a', '#fae27a']}
CROPS = ['rice', 'soybean', 'daikon', 'wasabi', 'tea']
WILD = ['wild_rice', 'wild_soybean', 'wild_daikon', 'wild_wasabi', 'wild_tea', 'shiitake']
SEED_OF = {'rice': 'rice', 'soybean': 'soybeans', 'daikon': 'daikon_seeds', 'wasabi': 'wasabi_root', 'tea': 'tea_seeds'}
PRODUCE_OF = {'rice': 'rice', 'soybean': 'soybeans', 'daikon': 'daikon', 'wasabi': 'wasabi_root', 'tea': 'tea_leaves'}

ITEMS = ['rice', 'soybeans', 'daikon_seeds', 'wasabi_root', 'tea_seeds', 'daikon', 'tea_leaves', 'nori', 'rice_flour', 'miso_paste',
         'matcha', 'noodles', 'cooked_rice', 'onigiri', 'salmon_nigiri', 'maki_roll', 'tofu', 'edamame', 'mochi', 'dango', 'takuan',
         'grilled_shiitake', 'yakitori', 'tamagoyaki', 'matcha_mochi', 'miso_soup', 'udon', 'ramen', 'sushi_set', 'green_tea']

NAMES = {
    # blocks
    'volcanic_ash': ('Вулканический пепел', 'Volcanic Ash'), 'black_sand': ('Чёрный песок', 'Black Sand'),
    'scoria': ('Шлак', 'Scoria'), 'pumice': ('Пемза', 'Pumice'),
    'hikari_moss': ('Хикаригокэ', 'Hikari Moss'), 'hikari_moss_carpet': ('Ковёр хикаригокэ', 'Hikari Moss Carpet'),
    'eclipse_crystal_block': ('Блок кристалла затмения', 'Eclipse Crystal Block'),
    'eclipse_crystal_cluster': ('Друза кристалла затмения', 'Eclipse Crystal Cluster'),
    'momiji_leaves': ('Листва момидзи', 'Momiji Leaves'), 'ginkgo_leaves': ('Листва гинкго', 'Ginkgo Leaves'),
    'shrine_bricks': ('Кирпичи святилища', 'Shrine Bricks'), 'mossy_shrine_bricks': ('Замшелые кирпичи святилища', 'Mossy Shrine Bricks'),
    'cracked_shrine_bricks': ('Треснувшие кирпичи святилища', 'Cracked Shrine Bricks'),
    'vermilion_planks': ('Киноварные доски', 'Vermilion Planks'), 'tatami': ('Татами', 'Tatami'),
    'stone_lantern': ('Каменный фонарь торо', 'Toro Stone Lantern'),
    'rice_crop': ('Рис', 'Rice'), 'soybean_crop': ('Соя', 'Soybeans'), 'daikon_crop': ('Дайкон', 'Daikon'),
    'wasabi_crop': ('Васаби', 'Wasabi'), 'tea_crop': ('Чайный куст', 'Tea Bush'),
    'wild_rice': ('Дикий рис', 'Wild Rice'), 'wild_soybean': ('Дикая соя', 'Wild Soybean'), 'wild_daikon': ('Дикий дайкон', 'Wild Daikon'),
    'wild_wasabi': ('Дикий васаби', 'Wild Wasabi'), 'wild_tea': ('Дикий чай', 'Wild Tea'), 'shiitake': ('Шиитаке', 'Shiitake'),
    # items
    'rice': ('Рис', 'Rice'), 'soybeans': ('Соевые бобы', 'Soybeans'), 'daikon_seeds': ('Семена дайкона', 'Daikon Seeds'),
    'wasabi_root': ('Корень васаби', 'Wasabi Root'), 'tea_seeds': ('Семена чая', 'Tea Seeds'), 'daikon': ('Дайкон', 'Daikon'),
    'tea_leaves': ('Чайные листья', 'Tea Leaves'), 'nori': ('Нори', 'Nori'), 'rice_flour': ('Рисовая мука', 'Rice Flour'),
    'miso_paste': ('Паста мисо', 'Miso Paste'), 'matcha': ('Матча', 'Matcha'), 'noodles': ('Лапша', 'Noodles'),
    'cooked_rice': ('Варёный рис', 'Cooked Rice'), 'onigiri': ('Онигири', 'Onigiri'), 'salmon_nigiri': ('Нигири с лососем', 'Salmon Nigiri'),
    'maki_roll': ('Маки', 'Maki Roll'), 'tofu': ('Тофу', 'Tofu'), 'edamame': ('Эдамамэ', 'Edamame'), 'mochi': ('Моти', 'Mochi'),
    'dango': ('Данго', 'Dango'), 'takuan': ('Такуан', 'Takuan'), 'grilled_shiitake': ('Жареные шиитаке', 'Grilled Shiitake'),
    'yakitori': ('Якитори', 'Yakitori'), 'tamagoyaki': ('Тамагояки', 'Tamagoyaki'), 'matcha_mochi': ('Моти с матчей', 'Matcha Mochi'),
    'miso_soup': ('Мисо-суп', 'Miso Soup'), 'udon': ('Удон', 'Udon'), 'ramen': ('Рамен', 'Ramen'), 'sushi_set': ('Сет суши', 'Sushi Set'),
    'green_tea': ('Зелёный чай', 'Green Tea'),
}


def block_loot(name, drop=None):
    return {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'condition': {'type': 'minecraft:survives_explosion'},
                                                  'entries': [{'type': 'minecraft:item', 'name': P(drop or name)}]}],
            'random_sequence': f'{NS}:blocks/{name}'}


def silk_or(name, other, count=None):
    child2 = {'type': 'minecraft:item', 'name': other}
    if count:
        child2['modifier'] = [{'type': 'minecraft:set_count', 'count': count}, {'type': 'minecraft:explosion_decay'}]
    return {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:alternatives', 'children': [
        {'type': 'minecraft:item', 'condition': 'minecraft:tool/can_silk_touch', 'name': P(name)}, child2]}]}],
            'random_sequence': f'{NS}:blocks/{name}'}


def leaves_loot(name):
    shear = {'type': 'minecraft:any_of', 'terms': ['minecraft:tool/can_shear', 'minecraft:tool/can_silk_touch']}
    return {'type': 'minecraft:block', 'pools': [
        {'rolls': 1, 'condition': shear, 'entries': [{'type': 'minecraft:item', 'name': P(name)}]},
        {'rolls': 1, 'condition': {'type': 'minecraft:inverted', 'term': shear}, 'entries': [{
            'type': 'minecraft:item', 'name': 'minecraft:stick',
            'condition': {'type': 'minecraft:table_bonus', 'chances': [0.02, 0.022222223, 0.025, 0.033333335, 0.1], 'enchantment': 'minecraft:fortune'},
            'modifier': [{'type': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': 1, 'max': 2}}, {'type': 'minecraft:explosion_decay'}]}]}],
            'random_sequence': f'{NS}:blocks/{name}'}


def crop_loot(crop):
    block = P(crop + '_crop')
    ripe = {'type': 'minecraft:match_block', 'blocks': block, 'state': {'age': '7'}}
    seed, produce = P(SEED_OF[crop]), P(PRODUCE_OF[crop])
    pools = [{'rolls': 1, 'entries': [{'type': 'minecraft:alternatives', 'children': [
        {'type': 'minecraft:item', 'condition': ripe, 'name': produce}, {'type': 'minecraft:item', 'name': seed}]}]},
        {'rolls': 1, 'condition': ripe, 'entries': [{'type': 'minecraft:item', 'name': seed, 'modifier': {
            'type': 'minecraft:apply_bonus', 'enchantment': 'minecraft:fortune', 'formula': 'minecraft:binomial_with_bonus_count',
            'parameters': {'extra': 2, 'probability': 0.5714286}}}]}]
    if produce == seed:   # rice, soy, wasabi: the grain is the seed, give a decent harvest
        pools[1]['entries'][0]['modifier'] = {'type': 'minecraft:apply_bonus', 'enchantment': 'minecraft:fortune',
                                               'formula': 'minecraft:binomial_with_bonus_count', 'parameters': {'extra': 3, 'probability': 0.5714286}}
    return {'type': 'minecraft:block', 'modifier': {'type': 'minecraft:explosion_decay'}, 'pools': pools,
            'random_sequence': f'{NS}:blocks/{crop}_crop'}


def wild_loot(name, drops):
    entries = []
    for item, lo, hi in drops:
        entries.append({'type': 'minecraft:item', 'name': item, 'modifier': [
            {'type': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': lo, 'max': hi}},
            {'type': 'minecraft:explosion_decay'}]})
    pools = [{'rolls': 1, 'entries': [e]} for e in entries]
    pools.insert(0, {'rolls': 1, 'condition': 'minecraft:tool/can_shear', 'entries': [{'type': 'minecraft:item', 'name': P(name)}]})
    for p in pools[1:]:
        p['condition'] = {'type': 'minecraft:inverted', 'term': 'minecraft:tool/can_shear'}
    return {'type': 'minecraft:block', 'pools': pools, 'random_sequence': f'{NS}:blocks/{name}'}


def shaped(pattern, key, result, n=1):
    r = {'type': 'minecraft:crafting_shaped', 'category': 'misc', 'pattern': pattern, 'key': key, 'result': {'id': result}}
    if n > 1:
        r['result']['count'] = n
    return r


def shapeless(ingr, result, n=1, cat='misc'):
    r = {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': ingr, 'result': {'id': result}}
    if n > 1:
        r['result']['count'] = n
    return r


def cook(ingr, result, xp=0.35, kinds=('smelting', 'smoking', 'campfire_cooking')):
    out = {}
    times = {'smelting': 200, 'smoking': 100, 'campfire_cooking': 600}
    for k in kinds:
        out[k] = {'type': 'minecraft:' + k, 'category': 'food', 'cookingtime': times[k], 'experience': xp,
                  'ingredient': ingr, 'result': {'id': result}}
    return out


def main():
    # ---------------- textures
    for name, fn in CUBES.items():
        if fn:
            save(fn(), 'block', name)
    hik = speckle(9, ['#1f3a22', '#2a4c2a', '#356032', '#2f5a30'])
    rng = random.Random(9)
    for _ in range(16):
        hik.putpixel((rng.randrange(16), rng.randrange(16)), hexc(rng.choice(['#7cffd2', '#b6ffe8', '#5ef0b0'])))
    save(hik, 'block', 'hikari_moss')
    for name, cols in LEAVES.items():
        save(leaves_tex(hash(name) & 0xffff, cols), 'block', name)
    save(cluster_tex(), 'block', 'eclipse_crystal_cluster')
    stone, light = lantern_textures()
    save(stone, 'block', 'stone_lantern')
    save(light, 'block', 'stone_lantern_light')
    for c in CROPS:
        for s in range(4):
            save(crop_stage(c, s), 'block', f'{c}_stage{s}')
    for w in WILD:
        save(shiitake_tex() if w == 'shiitake' else wild(w), 'block', w)
    for name in ITEMS:
        rows, pal = SPR.get(name, (None, None))
        if rows is None:
            raise SystemExit('missing sprite ' + name)
        save(sprite(rows, pal), 'item', name)

    # ---------------- block models, blockstates, item definitions
    def simple_state(name, model=None):
        wj(f'{A}/blockstates/{name}.json', {'variants': {'': {'model': model or P('block/' + name)}}})

    def item_def(name, model):
        wj(f'{A}/items/{name}.json', {'model': {'type': 'minecraft:model', 'model': model}})

    for name in list(CUBES) + list(LEAVES):
        parent = 'minecraft:block/leaves' if name in LEAVES else 'minecraft:block/cube_all'
        wj(f'{A}/models/block/{name}.json', {'parent': parent, 'textures': {'all': P('block/' + name)}})
        simple_state(name)
        item_def(name, P('block/' + name))
    wj(f'{A}/models/block/hikari_moss_carpet.json', {'parent': 'minecraft:block/carpet', 'textures': {'wool': P('block/hikari_moss')}})
    simple_state('hikari_moss_carpet')
    item_def('hikari_moss_carpet', P('block/hikari_moss_carpet'))
    wj(f'{A}/models/block/eclipse_crystal_cluster.json', {'parent': 'minecraft:block/cross', 'textures': {'cross': P('block/eclipse_crystal_cluster')}})
    rot = {'down': {'x': 180}, 'east': {'x': 90, 'y': 90}, 'north': {'x': 90}, 'south': {'x': 90, 'y': 180}, 'up': {}, 'west': {'x': 90, 'y': 270}}
    wj(f'{A}/blockstates/eclipse_crystal_cluster.json', {'variants': {f'facing={k}': dict(model=P('block/eclipse_crystal_cluster'), **v) for k, v in rot.items()}})
    wj(f'{A}/models/item/eclipse_crystal_cluster.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': P('block/eclipse_crystal_cluster')}})
    item_def('eclipse_crystal_cluster', P('item/eclipse_crystal_cluster'))
    # toro lantern: base, pillar, light box, roof
    S, L = '#stone', '#light'

    def box(f, t, tex=S):
        return {'from': f, 'to': t, 'faces': {d: {'texture': tex} for d in ('north', 'south', 'east', 'west', 'up', 'down')}}
    wj(f'{A}/models/block/stone_lantern.json', {
        'parent': 'minecraft:block/block', 'textures': {'stone': P('block/stone_lantern'), 'light': P('block/stone_lantern_light'), 'particle': P('block/stone_lantern')},
        'elements': [box([3, 0, 3], [13, 2, 13]), box([6, 2, 6], [10, 7, 10]), box([4, 7, 4], [12, 8, 12]),
                     box([4.5, 8, 4.5], [11.5, 12, 11.5], L), box([2, 12, 2], [14, 14, 14]), box([5, 14, 5], [11, 15, 11]), box([7, 15, 7], [9, 16, 9])]})
    simple_state('stone_lantern')
    item_def('stone_lantern', P('block/stone_lantern'))
    for c in CROPS:
        for s in range(4):
            wj(f'{A}/models/block/{c}_stage{s}.json', {'parent': 'minecraft:block/crop', 'textures': {'crop': P(f'block/{c}_stage{s}')}})
        stage_of = [0, 0, 1, 1, 2, 2, 2, 3]
        wj(f'{A}/blockstates/{c}_crop.json', {'variants': {f'age={a}': {'model': P(f'block/{c}_stage{stage_of[a]}')} for a in range(8)}})
    for w in WILD:
        wj(f'{A}/models/block/{w}.json', {'parent': 'minecraft:block/cross', 'textures': {'cross': P('block/' + w)}})
        simple_state(w)
        wj(f'{A}/models/item/{w}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': P('block/' + w)}})
        item_def(w, P('item/' + w))
    for it in ITEMS:
        wj(f'{A}/models/item/{it}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': P('item/' + it)}})
        item_def(it, P('item/' + it))

    # ---------------- translations
    ru, en = {'itemGroup.phaseworld': 'Phase: Япония'}, {'itemGroup.phaseworld': 'Phase: Japan'}
    for k, (r, e) in NAMES.items():
        for prefix in ('block', 'item'):
            ru[f'{prefix}.{NS}.{k}'] = r
            en[f'{prefix}.{NS}.{k}'] = e
    from gen_worldgen import B
    bnames = {
        'deep_ocean': ('Глубокий океан', 'Deep Ocean'), 'ocean': ('Океан', 'Ocean'), 'inner_sea': ('Внутреннее море', 'Inner Sea'),
        'beach': ('Пляж', 'Beach'), 'black_sand_beach': ('Чёрный пляж', 'Black Sand Beach'), 'rocky_shore': ('Скалистый берег', 'Rocky Shore'),
        'river': ('Река', 'River'), 'sakura_valley': ('Долина сакуры', 'Sakura Valley'), 'momiji_highlands': ('Нагорье момидзи', 'Momiji Highlands'),
        'sugi_forest': ('Кедровый лес сугi', 'Sugi Forest'), 'bamboo_grove': ('Бамбуковая роща', 'Bamboo Grove'),
        'rice_terraces': ('Рисовые террасы', 'Rice Terraces'), 'tea_hills': ('Чайные холмы', 'Tea Hills'),
        'alpine_peaks': ('Японские Альпы', 'Japanese Alps'), 'snowy_highlands': ('Снежное нагорье', 'Snowy Highlands'),
        'onsen_valley': ('Долина онсэнов', 'Onsen Valley'), 'pine_cliffs': ('Сосновые утёсы', 'Pine Cliffs'),
        'reed_marsh': ('Тростниковые топи', 'Reed Marsh'), 'sand_dunes': ('Песчаные дюны', 'Sand Dunes'),
        'frozen_summits': ('Ледяные вершины', 'Frozen Summits'), 'jukai_forest': ('Дзюкай — море деревьев', 'Jukai Forest'),
        'fuji_subalpine': ('Предгорья Фудзи', 'Fuji Subalpine'), 'fuji_slopes': ('Склоны Фудзи', 'Fuji Slopes'),
        'fuji_summit': ('Вершина Фудзи', 'Fuji Summit'), 'deep_caverns': ('Глубинные пещеры', 'Deep Caverns'),
        'hikari_grotto': ('Грот хикаригокэ', 'Hikari Grotto'), 'eclipse_hollows': ('Пустоты затмения', 'Eclipse Hollows'),
        'magma_tubes': ('Лавовые трубки', 'Magma Tubes'), 'flooded_caves': ('Затопленные пещеры', 'Flooded Caves'),
        'ice_caves': ('Ледяные пещеры', 'Ice Caves')}
    bnames['sugi_forest'] = ('Кедровый лес суги', 'Sugi Forest')
    for b in B:
        ru[f'biome.{NS}.{b}'], en[f'biome.{NS}.{b}'] = bnames[b]
    wj(f'{A}/lang/ru_ru.json', ru)
    wj(f'{A}/lang/en_us.json', en)

    # ---------------- loot tables
    for name in CUBES:
        wj(f'{D}/loot_table/blocks/{name}.json', block_loot(name))
    wj(f'{D}/loot_table/blocks/hikari_moss_carpet.json', block_loot('hikari_moss_carpet'))
    wj(f'{D}/loot_table/blocks/stone_lantern.json', block_loot('stone_lantern'))
    wj(f'{D}/loot_table/blocks/eclipse_crystal_cluster.json', silk_or('eclipse_crystal_cluster', 'minecraft:amethyst_shard', 2))
    for name in LEAVES:
        wj(f'{D}/loot_table/blocks/{name}.json', leaves_loot(name))
    for c in CROPS:
        wj(f'{D}/loot_table/blocks/{c}_crop.json', crop_loot(c))
    wj(f'{D}/loot_table/blocks/wild_rice.json', wild_loot('wild_rice', [(P('rice'), 1, 3)]))
    wj(f'{D}/loot_table/blocks/wild_soybean.json', wild_loot('wild_soybean', [(P('soybeans'), 1, 3)]))
    wj(f'{D}/loot_table/blocks/wild_daikon.json', wild_loot('wild_daikon', [(P('daikon'), 1, 1), (P('daikon_seeds'), 1, 2)]))
    wj(f'{D}/loot_table/blocks/wild_wasabi.json', wild_loot('wild_wasabi', [(P('wasabi_root'), 1, 2)]))
    wj(f'{D}/loot_table/blocks/wild_tea.json', wild_loot('wild_tea', [(P('tea_leaves'), 1, 3), (P('tea_seeds'), 1, 2)]))
    wj(f'{D}/loot_table/blocks/shiitake.json', block_loot('shiitake'))

    # ---------------- recipes
    R = {}
    R['nori'] = shaped(['##', '##'], {'#': 'minecraft:dried_kelp'}, P('nori'), 2)
    R['rice_flour'] = shapeless([P('rice'), P('rice')], P('rice_flour'))
    R['miso_paste'] = shapeless([P('soybeans'), P('soybeans'), P('cooked_rice'), 'minecraft:brown_mushroom'], P('miso_paste'), 2)
    R['noodles'] = shapeless(['minecraft:wheat', 'minecraft:wheat', 'minecraft:wheat', 'minecraft:water_bucket'], P('noodles'), 3)
    R['tofu'] = shapeless([P('soybeans'), P('soybeans'), P('soybeans'), 'minecraft:water_bucket'], P('tofu'), 2, 'food')
    R['onigiri'] = shapeless([P('cooked_rice'), P('nori')], P('onigiri'), 1, 'food')
    R['salmon_nigiri'] = shapeless([P('cooked_rice'), 'minecraft:salmon'], P('salmon_nigiri'), 2, 'food')
    R['maki_roll'] = shapeless([P('cooked_rice'), P('nori'), 'minecraft:cod'], P('maki_roll'), 3, 'food')
    R['mochi'] = shapeless([P('rice_flour'), 'minecraft:sugar'], P('mochi'), 2, 'food')
    R['matcha_mochi'] = shapeless([P('mochi'), P('matcha')], P('matcha_mochi'), 1, 'food')
    R['dango'] = shaped([' M', ' M', 'S '], {'M': P('mochi'), 'S': 'minecraft:stick'}, P('dango'))
    R['takuan'] = shapeless([P('daikon'), P('daikon'), 'minecraft:sugar'], P('takuan'), 3, 'food')
    R['yakitori'] = shapeless(['minecraft:cooked_chicken', 'minecraft:stick'], P('yakitori'), 2, 'food')
    R['tamagoyaki'] = shapeless(['minecraft:egg', 'minecraft:egg', 'minecraft:sugar'], P('tamagoyaki'), 1, 'food')
    R['miso_soup'] = shapeless([P('miso_paste'), P('tofu'), 'minecraft:kelp', 'minecraft:bowl'], P('miso_soup'), 1, 'food')
    R['udon'] = shapeless([P('noodles'), P('daikon'), P('miso_paste'), 'minecraft:bowl'], P('udon'), 1, 'food')
    R['ramen'] = shapeless([P('noodles'), 'minecraft:cooked_porkchop', 'minecraft:egg', P('nori'), 'minecraft:bowl'], P('ramen'), 1, 'food')
    R['sushi_set'] = shapeless([P('salmon_nigiri'), P('salmon_nigiri'), P('maki_roll'), P('maki_roll'), P('wasabi_root')], P('sushi_set'), 1, 'food')
    R['green_tea'] = shapeless([P('tea_leaves'), P('tea_leaves'), 'minecraft:potion'], P('green_tea'), 1, 'food')
    R['daikon_seeds'] = shapeless([P('daikon')], P('daikon_seeds'), 2)
    R['shrine_bricks'] = shaped(['##', '##'], {'#': 'minecraft:polished_andesite'}, P('shrine_bricks'), 4)
    R['mossy_shrine_bricks'] = shapeless([P('shrine_bricks'), 'minecraft:moss_block'], P('mossy_shrine_bricks'))
    R['vermilion_planks'] = shapeless(['#minecraft:planks', 'minecraft:red_dye'], P('vermilion_planks'))
    R['tatami'] = shaped(['WWW', 'W#W', 'WWW'], {'W': 'minecraft:wheat', '#': 'minecraft:green_dye'}, P('tatami'), 2)
    R['stone_lantern'] = shaped([' S ', 'STS', ' S '], {'S': P('shrine_bricks'), 'T': 'minecraft:torch'}, P('stone_lantern'))
    R['hikari_moss_carpet'] = shaped(['##'], {'#': P('hikari_moss')}, P('hikari_moss_carpet'), 3)
    R['eclipse_crystal_block'] = shaped(['##', '##'], {'#': P('eclipse_crystal_cluster')}, P('eclipse_crystal_block'))
    for name, r in R.items():
        wj(f'{D}/recipe/{name}.json', r)
    for src, dst, xp in [('rice', 'cooked_rice', 0.35), ('soybeans', 'edamame', 0.2), ('shiitake', 'grilled_shiitake', 0.35)]:
        for kind, r in cook(P(src), P(dst), xp).items():
            wj(f'{D}/recipe/{dst}_from_{kind}.json', r)
    wj(f'{D}/recipe/matcha.json', cook(P('tea_leaves'), P('matcha'), 0.2, ('smelting',))['smelting'])
    wj(f'{D}/recipe/cracked_shrine_bricks.json', {'type': 'minecraft:smelting', 'category': 'blocks', 'cookingtime': 200, 'experience': 0.1,
                                                  'ingredient': P('shrine_bricks'), 'result': {'id': P('cracked_shrine_bricks')}})

    # ---------------- tags
    def tag(kind, name, values, ns='minecraft'):
        wj(os.path.join(BASE, 'data', ns, 'tags', kind, name + '.json'), {'replace': False, 'values': values})
    tag('block', 'mineable/pickaxe', [P(n) for n in ['scoria', 'pumice', 'eclipse_crystal_block', 'eclipse_crystal_cluster', 'shrine_bricks',
                                                       'mossy_shrine_bricks', 'cracked_shrine_bricks', 'stone_lantern']])
    tag('block', 'mineable/shovel', [P('volcanic_ash'), P('black_sand')])
    tag('block', 'mineable/hoe', [P(n) for n in ['hikari_moss', 'hikari_moss_carpet', 'momiji_leaves', 'ginkgo_leaves', 'tatami']])
    tag('block', 'mineable/axe', [P('vermilion_planks')])
    tag('block', 'leaves', [P('momiji_leaves'), P('ginkgo_leaves')])
    tag('item', 'leaves', [P('momiji_leaves'), P('ginkgo_leaves')])
    tag('block', 'crops', [P(c + '_crop') for c in CROPS])
    tag('block', 'maintains_farmland', [P(c + '_crop') for c in CROPS])
    tag('block', 'replaceable_by_trees', [P(w) for w in WILD])
    tag('item', 'villager_plantable_seeds', [P('rice'), P('soybeans'), P('daikon_seeds')])
    tag('block', 'base_stone_overworld', [P('scoria'), P('pumice')])
    tag('block', 'stone_ore_replaceables', [P('scoria'), P('pumice')])
    tag('block', 'dripstone_replaceable_blocks', [P('scoria'), P('pumice')])
    tag('block', 'moss_replaceable', [P('scoria'), P('pumice'), P('volcanic_ash')])
    tag('block', 'substrate_overworld', [P('volcanic_ash'), P('black_sand')])
    print('assets: %d items, %d recipes' % (len(ITEMS), len(R)))


if __name__ == '__main__':
    main()
