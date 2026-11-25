#!/usr/bin/env python3
"""Phase Space: генерирует все текстуры, модели и JSON датапака из src/main/resources/phasespace/bodies.json.

    python3 tools/gen_assets.py            # всё
    python3 tools/gen_assets.py --no-sky   # без неба (оно считается дольше всего)

Пишет в src/main/resources/assets/phasespace и src/main/resources/data/phasespace,
плюс контакт-лист build/preview/*.png, чтобы посмотреть результат глазами.
Нужны numpy и Pillow.
"""
import json
import math
import os
import shutil
import sys

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))
RES = os.path.join(ROOT, 'src', 'main', 'resources')
ASSETS = os.path.join(RES, 'assets', 'phasespace')
DATA = os.path.join(RES, 'data', 'phasespace')
PREVIEW = os.path.join(ROOT, 'build', 'preview')
NS = 'phasespace'

SYS = json.load(open(os.path.join(RES, 'phasespace', 'bodies.json'), encoding='utf-8'))
FACES = ['n', 's', 'e', 'w', 'u', 'd']
FACE_DIR = {'n': 'north', 's': 'south', 'e': 'east', 'w': 'west', 'u': 'up', 'd': 'down'}


# ───────────────────────── файлы ─────────────────────────

def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write('\n')


def save_png(rel, rgba, emission=None):
    """rel — путь текстуры без .png относительно textures/item; rgba float 0..1 (H,W,4).
    emission (H,W) 0..1 → LabPBR _s (альфа = сила свечения, для Photon/Iris)."""
    path = os.path.join(ASSETS, 'textures', 'item', rel + '.png')
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img = np.clip(rgba * 255 + 0.5, 0, 255).astype(np.uint8)
    # спрайты меньше 16 px снижают уровень мипмапов всего атласа предметов — увеличиваем без сглаживания
    k = 1
    while min(img.shape[0], img.shape[1]) * k < 16:
        k *= 2
    if k > 1:
        img = np.kron(img, np.ones((k, k, 1), np.uint8))
        if emission is not None:
            emission = np.kron(emission, np.ones((k, k)))
    Image.fromarray(img, 'RGBA').save(path, optimize=True)
    if emission is not None:
        e = np.clip(emission, 0, 1)
        s = np.zeros(img.shape, np.uint8)
        s[..., 3] = np.clip(e * 254 + 0.5, 0, 254).astype(np.uint8)
        Image.fromarray(s, 'RGBA').save(path[:-4] + '_s.png', optimize=True)
    return img


def item_def(model):
    write_json(os.path.join(ASSETS, 'items', model + '.json'),
               {'model': {'type': 'minecraft:model', 'model': f'{NS}:item/{model}'}})


def model(model_id, textures, elements):
    write_json(os.path.join(ASSETS, 'models', 'item', model_id + '.json'),
               {'textures': textures, 'elements': elements})
    item_def(model_id)


def face_model(model_id, tex, face):
    """Куб 0..16 с одной гранью — каждая грань планеты рисуется отдельно со своим светом от Солнца."""
    model(model_id, {'t': f'{NS}:item/{tex}', 'particle': f'{NS}:item/{tex}'}, [{
        'from': [0, 0, 0], 'to': [16, 16, 16], 'shade': False,
        'faces': {FACE_DIR[face]: {'uv': [0, 0, 16, 16], 'texture': '#t'}}}])


def cube_model(model_id, texs):
    """Куб со своей текстурой на каждой грани (texs: dict face->tex) или одной на все (str)."""
    if isinstance(texs, str):
        texs = {f: texs for f in FACES}
    t = {f: f'{NS}:item/{texs[f]}' for f in FACES}
    t['particle'] = t['n']
    model(model_id, t, [{'from': [0, 0, 0], 'to': [16, 16, 16], 'shade': False,
                         'faces': {FACE_DIR[f]: {'uv': [0, 0, 16, 16], 'texture': '#' + f} for f in FACES}}])


def sprite_model(model_id, tex):
    """Плоский спрайт: одна грань south (нормаль +Z), отрисовщик поворачивает её к камере."""
    model(model_id, {'t': f'{NS}:item/{tex}', 'particle': f'{NS}:item/{tex}'}, [{
        'from': [0, 0, 8], 'to': [16, 16, 8], 'shade': False,
        'faces': {'south': {'uv': [0, 0, 16, 16], 'texture': '#t'}}}])


def plane_model(model_id, tex):
    """Горизонтальная плоскость (кольца): две модели — верхняя (_u) и нижняя (_d) сторона,
    чтобы у каждой была своя «плоская» нормаль при отрисовке."""
    for suf, face in (('_u', 'up'), ('_d', 'down')):
        model(model_id + suf, {'t': f'{NS}:item/{tex}', 'particle': f'{NS}:item/{tex}'}, [{
            'from': [0, 8, 0], 'to': [16, 8, 16], 'shade': False,
            'faces': {face: {'uv': [0, 0, 16, 16], 'texture': '#t'}}}])


# ───────────────────────── шум ─────────────────────────

def _hash3(ix, iy, iz, seed):
    h = (ix.astype(np.int64) * 73856093) ^ (iy.astype(np.int64) * 19349663) ^ (iz.astype(np.int64) * 83492791) ^ (seed * 2654435761)
    h = h & 0xFFFFFFFF
    h = (h ^ (h >> 13)) * 1274126177 & 0xFFFFFFFF
    h = h ^ (h >> 16)
    return (h & 0xFFFFFF).astype(np.float32) / float(0xFFFFFF)


def vnoise(p, seed=0):
    """Value noise 3D, p (...,3) → (...) 0..1"""
    f = np.floor(p)
    t = p - f
    t = t * t * (3 - 2 * t)
    i = f.astype(np.int64)
    out = 0
    for dx in (0, 1):
        wx = t[..., 0] if dx else 1 - t[..., 0]
        for dy in (0, 1):
            wy = t[..., 1] if dy else 1 - t[..., 1]
            for dz in (0, 1):
                wz = t[..., 2] if dz else 1 - t[..., 2]
                out = out + wx * wy * wz * _hash3(i[..., 0] + dx, i[..., 1] + dy, i[..., 2] + dz, seed)
    return out


def fbm(p, octaves=5, seed=0, lac=2.03, gain=0.5):
    s, a, norm = 0, 1.0, 0
    q = p
    for o in range(octaves):
        s = s + a * vnoise(q, seed + o * 101)
        norm += a
        a *= gain
        q = q * lac + 17.31
    return s / norm


def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def hexc(h):
    h = h.lstrip('#')
    return np.array([int(h[i:i + 2], 16) / 255.0 for i in (0, 2, 4)], np.float32)


def mix(a, b, t):
    t = np.asarray(t)[..., None]
    return a * (1 - t) + b * t


# ───────────────────────── грани куба ─────────────────────────

def face_points(face, res):
    """Точки на кубе [-1,1]^3 для пикселей грани (как FaceBakery кладёт uv [0,0,16,16])."""
    u = (np.arange(res) + 0.5) / res
    U, V = np.meshgrid(u, u)          # U — по горизонтали (столбцы), V — сверху вниз (строки)
    a, b = 2 * U - 1, 2 * V - 1
    one = np.ones_like(a)
    if face == 'n':   x, y, z = -a, -b, -one
    elif face == 's': x, y, z = a, -b, one
    elif face == 'e': x, y, z = one, -b, -a
    elif face == 'w': x, y, z = -one, -b, a
    elif face == 'u': x, y, z = a, one, b
    else:             x, y, z = a, -one, -b
    return np.stack([x, y, z], -1).astype(np.float32)


def face_dirs(face, res):
    p = face_points(face, res)
    return p / np.linalg.norm(p, axis=-1, keepdims=True)


def face_edge(res):
    u = (np.arange(res) + 0.5) / res
    U, V = np.meshgrid(u, u)
    return np.maximum(np.abs(2 * U - 1), np.abs(2 * V - 1))


# ───────────────────────── поверхности планет ─────────────────────────

def craters(d, seed, count, rmin, rmax, depth=0.25):
    rng = np.random.default_rng(seed)
    out = np.zeros(d.shape[:-1], np.float32)
    for _ in range(count):
        c = rng.normal(size=3)
        c /= np.linalg.norm(c)
        r = rng.uniform(rmin, rmax)
        ang = np.arccos(np.clip(d @ c, -1, 1))
        x = ang / r
        out += np.where(x < 1, -depth * (1 - x ** 2), 0) + depth * 0.6 * np.exp(-((x - 1.0) / 0.18) ** 2)
    return out


def paint(style, d, seed):
    """d (...,3) — направления на сфере; вращение вокруг Y. Возвращает rgb, emission(или None)."""
    lat = d[..., 1]
    n = fbm(d * 2.2 + seed, 5, seed)
    n2 = fbm(d * 5.0 + seed * 3, 4, seed + 7)
    if style == 'earth':
        land = n + (n2 - 0.5) * 0.18 > 0.52
        coast = np.abs(n - 0.52) < 0.03
        ocean = mix(hexc('#1747b8'), hexc('#2f7fe8'), smooth(0.36, 0.52, n))
        green = mix(hexc('#2f9a35'), hexc('#5fd14f'), smooth(0.3, 0.75, n2))
        desert = (np.abs(lat) < 0.38) & (n2 < 0.42)
        lnd = np.where(desert[..., None], mix(hexc('#cdb46a'), hexc('#e0c98a'), n2), green)
        col = np.where(land[..., None], lnd, ocean)
        col = np.where((coast & ~land)[..., None], hexc('#3f97f0'), col)
        ice = np.abs(lat) > 0.9 - (n2 - 0.5) * 0.1
        col = np.where(ice[..., None], mix(hexc('#e8f2ff'), hexc('#ffffff'), n2), col)
        return col, None
    if style == 'mercury':
        base = mix(hexc('#6f665f'), hexc('#b3a79b'), n)
        c = craters(d, seed, 26, 0.08, 0.32)
        return np.clip(base + c[..., None] * 0.9, 0, 1), None
    if style == 'venus':
        sw = fbm(np.stack([d[..., 0] * 1.6, lat * 5.5 + n * 2.0, d[..., 2] * 1.6], -1) + seed, 5, seed)
        return mix(hexc('#c98d3a'), hexc('#f2d28f'), smooth(0.25, 0.8, sw)), None
    if style == 'moon':
        maria = smooth(0.48, 0.56, fbm(d * 1.7 + 9, 4, seed + 3))
        base = mix(hexc('#b9b9b9'), hexc('#6a6a70'), maria)
        c = craters(d, seed, 22, 0.06, 0.26, 0.18)
        return np.clip(base + c[..., None], 0, 1), None
    if style == 'mars':
        dark = smooth(0.45, 0.62, n)
        col = mix(hexc('#d0643a'), hexc('#8a3a22'), dark)
        col = mix(col, hexc('#e8955f'), smooth(0.62, 0.8, n2) * 0.6)
        cap = np.abs(lat) > 0.89 - (n2 - 0.5) * 0.08
        col = np.where(cap[..., None], hexc('#f4ecea'), col)
        return col, None
    if style in ('rock_dark', 'rock_light', 'callisto', 'ganymede', 'charon'):
        pal = {'rock_dark': ('#5a5049', '#8a7d72'), 'rock_light': ('#7e746b', '#b0a598'),
               'callisto': ('#3e352f', '#6d6157'), 'ganymede': ('#6f6558', '#b5ab9b'),
               'charon': ('#6f6a66', '#a9a29d')}[style]
        col = mix(hexc(pal[0]), hexc(pal[1]), smooth(0.3, 0.75, n))
        col = np.clip(col + craters(d, seed, 14, 0.1, 0.35, 0.16)[..., None], 0, 1)
        if style == 'callisto':
            sp = _hash3((d[..., 0] * 40).astype(np.int64), (d[..., 1] * 40).astype(np.int64), (d[..., 2] * 40).astype(np.int64), seed) > 0.9
            col = np.where(sp[..., None], hexc('#c9c0b6'), col)
        if style == 'charon':
            col = np.where((lat > 0.7)[..., None], mix(col, hexc('#7a3e2e'), 0.7), col)
        return col, None
    if style == 'io':
        col = mix(hexc('#e6cf3e'), hexc('#f5e88a'), n2)
        spots = fbm(d * 6 + 3, 3, seed + 11)
        col = np.where((spots > 0.68)[..., None], hexc('#c4552b'), col)
        col = np.where((spots > 0.76)[..., None], hexc('#2a1d12'), col)
        col = np.where((spots < 0.27)[..., None], hexc('#ffffff'), col)
        return col, np.where(spots > 0.76, 0.5, 0.0)
    if style == 'europa':
        col = mix(hexc('#d8c8ab'), hexc('#f2ebdc'), n2)
        crack = np.abs(fbm(d * 3.4 + 5, 4, seed + 2) - 0.5) < 0.025
        crack2 = np.abs(fbm(d * 5.1 + 1, 3, seed + 9) - 0.5) < 0.02
        col = np.where((crack | crack2)[..., None], hexc('#8a5034'), col)
        return col, None
    if style == 'titan':
        col = mix(hexc('#c97a2c'), hexc('#f0b060'), smooth(0.2, 0.8, fbm(np.stack([d[..., 0], lat * 4, d[..., 2]], -1) + 2, 4, seed)))
        return col, None
    if style == 'enceladus':
        col = mix(hexc('#e9eef2'), hexc('#ffffff'), n2)
        stripes = (lat < -0.55) & (np.abs(np.sin(d[..., 0] * 18 + d[..., 2] * 6)) < 0.22)
        col = np.where(stripes[..., None], hexc('#8fc4ff'), col)
        return col, None
    if style == 'triton':
        cant = np.abs(fbm(d * 7, 3, seed + 4) - 0.5) < 0.05
        col = mix(hexc('#d9b8a8'), hexc('#f2e2da'), n2)
        col = np.where(cant[..., None], hexc('#b08a7a'), col)
        col = np.where((lat < -0.4)[..., None], mix(col, hexc('#f7d6c8'), 0.6), col)
        return col, None
    if style == 'pluto':
        col = mix(hexc('#8a6a52'), hexc('#d8bf9e'), smooth(0.35, 0.7, n))
        # «сердце» Плутона — светлая равнина Спутника на южной грани
        h1 = np.arccos(np.clip(d @ np.array([0.18, 0.05, 0.98]) / 1.0, -1, 1))
        h2 = np.arccos(np.clip(d @ np.array([-0.18, 0.05, 0.98]) / 1.0, -1, 1))
        tip = np.arccos(np.clip(d @ np.array([0.0, -0.32, 0.95]), -1, 1))
        heart = (h1 < 0.26) | (h2 < 0.26) | ((tip < 0.32) & (lat < 0.05) & (lat > -0.35))
        col = np.where(heart[..., None], hexc('#f4ece0'), col)
        return col, None
    if style in ('jupiter', 'saturn'):
        if style == 'jupiter':
            pal = [hexc(c) for c in ('#f2ebd9', '#c9a27a', '#e8d6b4', '#9c6b4a', '#f0e2c6', '#c98a5a', '#e9dcc0', '#8a5d42', '#d8b892')]
            freq = 9.0
        else:
            pal = [hexc(c) for c in ('#f4e7c2', '#dcc38c', '#efdcac', '#c8a86a', '#f6ecd0', '#d8bd84')]
            freq = 6.0
        turb = fbm(np.stack([d[..., 0] * 2.5, lat * 6, d[..., 2] * 2.5], -1) + seed, 4, seed) - 0.5
        band = (lat * 0.5 + 0.5) * freq + turb * (1.1 if style == 'jupiter' else 0.6)
        idx = np.floor(band).astype(int) % len(pal)
        fr = band - np.floor(band)
        nxt = (idx + 1) % len(pal)
        P = np.stack(pal)
        col = mix(P[idx], P[nxt], smooth(0.75, 1.0, fr))
        if style == 'jupiter':
            c = np.array([0.42, -0.34, -0.84]); c /= np.linalg.norm(c)
            dd = d - c
            e = np.sqrt((dd[..., 0] * 0.9) ** 2 + (dd[..., 1] * 2.1) ** 2 + (dd[..., 2] * 0.9) ** 2)
            col = np.where((e < 0.2)[..., None], hexc('#d9a27a'), col)
            col = np.where((e < 0.15)[..., None], hexc('#c4553a'), col)
            col = np.where((e < 0.08)[..., None], hexc('#a83c2a'), col)
        return col, None
    if style == 'uranus':
        col = mix(hexc('#8fd6e0'), hexc('#c4f3f8'), smooth(-0.9, 0.9, lat * 0.6 + (n2 - 0.5) * 0.3))
        return col, None
    if style == 'neptune':
        turb = fbm(np.stack([d[..., 0] * 2, lat * 7, d[..., 2] * 2], -1) + 4, 4, seed) - 0.5
        b = np.sin((lat + turb * 0.25) * 11)
        col = mix(hexc('#2c58c8'), hexc('#4f7ff0'), b * 0.5 + 0.5)
        c = np.array([-0.5, -0.3, 0.8]); c /= np.linalg.norm(c)
        spot = np.arccos(np.clip(d @ c, -1, 1)) < 0.2
        col = np.where(spot[..., None], hexc('#1a3486'), col)
        streak = (np.abs(lat + 0.05 - turb * 0.1) < 0.035) & (n2 > 0.55)
        col = np.where(streak[..., None], hexc('#dfe9ff'), col)
        return col, None
    if style == 'florana':
        # вся фиолетовая: глубокие моря, сиреневые материки, пурпурные джунгли и светящиеся рощи
        land = n + (n2 - 0.5) * 0.2 > 0.5
        sea = mix(hexc('#240b52'), hexc('#4a1f99'), smooth(0.32, 0.5, n))
        jungle = mix(hexc('#7b2cbf'), hexc('#c77dff'), smooth(0.3, 0.8, n2))
        meadow = mix(hexc('#9d4edd'), hexc('#e0aaff'), smooth(0.4, 0.9, fbm(d * 8, 3, seed + 21)))
        lnd = np.where((n2 > 0.55)[..., None], jungle, meadow)
        col = np.where(land[..., None], lnd, sea)
        shore = (np.abs(n - 0.5) < 0.025) & ~land
        col = np.where(shore[..., None], hexc('#6a35d0'), col)
        cap = np.abs(lat) > 0.9
        col = np.where(cap[..., None], hexc('#dcc6ff'), col)
        return col, None
    if style == 'sakura':
        # розовая планетка: цветущие леса, белые поляны, пруды
        land = n > 0.4
        pond = mix(hexc('#5a7fd8'), hexc('#7fa8ff'), n2)
        bloom = mix(hexc('#ff8fc0'), hexc('#ffd6e8'), smooth(0.3, 0.8, n2))
        white = (fbm(d * 4, 3, seed + 30) > 0.6)
        col = np.where(land[..., None], np.where(white[..., None], hexc('#fff2f7'), bloom), pond)
        trunk = (fbm(d * 6, 2, seed + 31) > 0.74) & land
        col = np.where(trunk[..., None], hexc('#7a4a52'), col)
        return col, None
    if style == 'lumi':
        base = mix(hexc('#0e3a44'), hexc('#2a8a96'), smooth(0.3, 0.8, n))
        cr = fbm(d * 7, 3, seed + 40) > 0.6
        col = np.where(cr[..., None], mix(hexc('#8ff6ff'), hexc('#e8ffff'), n2), base)
        return col, np.where(cr, 0.9, 0.0)
    if style in ('ceres', 'vesta', 'amalthea', 'mimas', 'iapetus', 'miranda', 'haumea', 'makemake', 'eris', 'sedna', 'rogue'):
        pal = {'ceres': ('#4e4a47', '#8a8580'), 'vesta': ('#7d7266', '#c2b6a8'), 'amalthea': ('#6e2a22', '#c0614c'),
               'mimas': ('#9a9894', '#dcdad6'), 'iapetus': ('#c9c2b4', '#ece6da'), 'miranda': ('#8c8882', '#cfcac2'),
               'haumea': ('#d8e2ec', '#ffffff'), 'makemake': ('#a8664a', '#e0a882'), 'eris': ('#dcdcdf', '#ffffff'),
               'sedna': ('#7a1e14', '#e05a3a'), 'rogue': ('#0c0b0d', '#2a2629')}[style]
        col = mix(hexc(pal[0]), hexc(pal[1]), smooth(0.25, 0.8, n))
        if style not in ('haumea', 'eris', 'rogue'):
            col = np.clip(col + craters(d, seed, 16, 0.08, 0.3, 0.16)[..., None], 0, 1)
        if style == 'ceres':
            spot = np.arccos(np.clip(d @ np.array([0.3, 0.35, 0.89]) / np.linalg.norm([0.3, 0.35, 0.89]), -1, 1)) < 0.09
            col = np.where(spot[..., None], hexc('#ffffff'), col)
        if style == 'vesta':
            col = np.where((lat < -0.75)[..., None], mix(col, hexc('#4a4038'), 0.6), col)
        if style == 'mimas':
            c = np.array([0.0, 0.1, -1.0]); c /= np.linalg.norm(c)
            a = np.arccos(np.clip(d @ c, -1, 1))
            col = np.where((a < 0.42)[..., None], mix(col, hexc('#6f6d6a'), 0.7), col)
            col = np.where((np.abs(a - 0.42) < 0.05)[..., None], hexc('#f0eeea'), col)
        if style == 'iapetus':
            dark = d[..., 0] + (n2 - 0.5) * 0.4 > 0.15
            col = np.where(dark[..., None], mix(hexc('#2a1e16'), hexc('#4a3626'), n2), col)
        if style == 'miranda':
            chev = (np.abs(np.sin(d[..., 0] * 14 + d[..., 1] * 9)) < 0.3) & (d[..., 2] > 0.2)
            col = np.where(chev[..., None], hexc('#efe9e0'), col)
        if style == 'haumea':
            c = np.array([0.6, 0.2, 0.77]); c /= np.linalg.norm(c)
            col = np.where((np.arccos(np.clip(d @ c, -1, 1)) < 0.35)[..., None], hexc('#a8443a'), col)
        return col, None
    raise ValueError(style)


def body_textures(b):
    bid, res, style = b['id'], b.get('tex', 16), b.get('style', b['id'])
    seed = (sum(map(ord, bid)) * 7) % 997
    sheet = []
    for f in FACES:
        d = face_dirs(f, res)
        rgb, em = paint(style, d, seed)
        rgba = np.concatenate([rgb, np.ones(rgb.shape[:-1] + (1,), np.float32)], -1)
        sheet.append(save_png(f'b/{bid}_{f}', rgba, em))
        face_model(f'b/{bid}_{f}', f'b/{bid}_{f}', f)
    if b.get('clouds'):
        for f in FACES:
            d = face_dirs(f, res)
            if style == 'venus':
                c = fbm(np.stack([d[..., 0] * 2, d[..., 1] * 7, d[..., 2] * 2], -1) + 30, 4, seed + 40)
                a = smooth(0.42, 0.7, c) * 0.85
                col = hexc('#fff0c8')
            elif style == 'florana':
                c = fbm(d * 3.2 + 50, 4, seed + 41)
                a = smooth(0.6, 0.72, c) * 0.8
                col = hexc('#f6e8ff')
            else:
                c = fbm(d * 3.0 + 50, 5, seed + 41)
                a = smooth(0.56, 0.66, c) * 0.92
                col = hexc('#ffffff')
            rgba = np.concatenate([np.broadcast_to(col, a.shape + (3,)), a[..., None]], -1)
            save_png(f'b/{bid}_clouds_{f}', rgba)
            face_model(f'b/{bid}_clouds_{f}', f'b/{bid}_clouds_{f}', f)
    if b.get('lights'):
        for f in FACES:
            d = face_dirs(f, res)
            rgb, _ = paint(style, d, seed)
            lat = d[..., 1]
            n = fbm(d * 2.2 + seed, 5, seed)
            n2 = fbm(d * 5.0 + seed * 3, 4, seed + 7)
            land = n + (n2 - 0.5) * (0.18 if style == 'earth' else 0.2) > (0.52 if style == 'earth' else 0.5)
            pts = face_points(f, res)
            rnd = _hash3((pts[..., 0] * 977).astype(np.int64), (pts[..., 1] * 977).astype(np.int64), (pts[..., 2] * 977).astype(np.int64), seed + 5)
            if style == 'earth':
                on = land & (np.abs(lat) < 0.7) & (rnd < 0.2)
                col = np.where((rnd < 0.07)[..., None], hexc('#fff2c0'), hexc('#ffc865'))
            elif style == 'rogue':  # лавовые трещины — светятся всегда
                crack = (np.abs(fbm(d * 3.0, 4, seed + 50) - 0.5) < 0.035) | (np.abs(fbm(d * 5.5, 3, seed + 51) - 0.5) < 0.025)
                on = crack
                col = np.where((rnd < 0.3)[..., None], hexc('#ffd27a'), hexc('#ff5a1f'))
            else:  # Флорана: светящиеся рощи — биолюминесценция
                on = land & (n2 > 0.5) & (rnd < 0.3)
                col = np.where((rnd < 0.12)[..., None], hexc('#9ff6ff'), hexc('#f0a8ff'))
            a = on.astype(np.float32)
            rgba = np.concatenate([col * np.ones(a.shape + (1,)), a[..., None]], -1)
            save_png(f'b/{bid}_lights_{f}', rgba, a)
            face_model(f'b/{bid}_lights_{f}', f'b/{bid}_lights_{f}', f)
    if b.get('atmo'):
        r = 16
        e = face_edge(r)
        k = 1.0 / b.get('atmoScale', 1.08)              # где на грани оболочки проходит край планеты
        a = 0.04 + 0.5 * smooth(k - 0.35, k, e) * (1 - smooth(k + 0.01, 1.0, e))
        col = hexc(b['atmo'])
        rgba = np.concatenate([np.broadcast_to(col, a.shape + (3,)), a[..., None]], -1)
        save_png(f'b/{bid}_atmo', rgba, a * (0.6 if bid == 'florana' else 0.15))
        for f in FACES:
            face_model(f'b/{bid}_atmo_{f}', f'b/{bid}_atmo', f)
    # превью: развёртка куба крестом
    s = sheet[0].shape[0]
    cross = np.zeros((s * 3, s * 4, 4), np.uint8)
    pos = {'u': (0, 1), 'w': (1, 0), 's': (1, 1), 'e': (1, 2), 'n': (1, 3), 'd': (2, 1)}
    for f, img in zip(FACES, sheet):
        r0, c0 = pos[f]
        cross[r0 * s:(r0 + 1) * s, c0 * s:(c0 + 1) * s] = img
    return cross


# ───────────────────────── Солнце, кольца, камни, эффекты ─────────────────────────

def sun_textures():
    res = 32
    imgs = []
    for f in FACES:
        d = face_dirs(f, res)
        g = fbm(d * 9 + 3, 4, 401)
        cells = fbm(d * 18 + 1, 2, 402)
        t = smooth(0.25, 0.85, g * 0.7 + cells * 0.3)
        col = mix(hexc('#ff8a1a'), hexc('#ffd84a'), t)
        col = mix(col, hexc('#fff6c8'), smooth(0.72, 0.95, cells) * 0.8)
        spots = fbm(d * 4 + 8, 3, 409)
        col = np.where((spots > 0.74)[..., None], hexc('#b5481a'), col)
        col = np.where((spots > 0.79)[..., None], hexc('#5a1e0a'), col)
        rgba = np.concatenate([col, np.ones(col.shape[:-1] + (1,), np.float32)], -1)
        em = np.where(spots > 0.74, 0.5, 1.0)
        imgs.append(save_png(f'sun/sun_{f}', rgba, em))
        face_model(f'sun/sun_{f}', f'sun/sun_{f}', f)
    for name, colr, a0, a1 in (('corona_a', '#ffe28a', 0.06, 0.85), ('corona_b', '#ffb347', 0.02, 0.5)):
        r = 32
        e = face_edge(r)
        u = (np.arange(r) + 0.5) / r
        U, V = np.meshgrid(u, u)
        wisps = fbm(np.stack([U * 6, V * 6, np.full_like(U, 3.3 if name == 'corona_a' else 7.1)], -1), 4, 431)
        k = 1.0 / (1.10 if name == 'corona_a' else 1.24)
        a = (a0 + (a1 - a0) * smooth(k - 0.3, k, e) * (1 - smooth(k + 0.02, 1.0, e))) * (0.55 + 0.9 * wisps)
        rgba = np.concatenate([np.broadcast_to(hexc(colr), a.shape + (3,)), np.clip(a, 0, 1)[..., None]], -1)
        save_png(f'sun/{name}', rgba, np.clip(a * 1.5, 0, 1))
        for f in FACES:
            face_model(f'sun/{name}_{f}', f'sun/{name}', f)
    return imgs


def disk_texture(name, inner_rel, hot, cool, res=128):
    """Круглый аккреционный диск (чёрная дыра, квазар): горячо у края горизонта, спиральные потоки."""
    u = (np.arange(res) + 0.5) / res * 2 - 1
    U, V = np.meshgrid(u, u)
    r = np.sqrt(U * U + V * V)
    ang = np.arctan2(V, U)
    t = np.clip((r - inner_rel) / (1 - inner_rel), 0, 1)
    spiral = 0.5 + 0.5 * np.sin(ang * 3 + np.log(np.maximum(r, 1e-3)) * 14)
    streak = fbm(np.stack([np.cos(ang) * 3, np.sin(ang) * 3, r * 9], -1), 4, 1300)
    heat = (1 - t) ** 1.6
    col = mix(hexc(cool), hexc(hot), heat)
    col = mix(col, np.ones(3, np.float32), heat ** 3 * 0.7)
    a = (0.25 + 0.75 * heat) * (0.55 + 0.45 * spiral * streak * 1.6)
    a = a * (r >= inner_rel) * smooth(1.0, 0.82, r)
    rgba = np.concatenate([col, np.clip(a, 0, 1)[..., None]], -1)
    save_png(f'deep/{name}', rgba, np.clip(a * 1.2, 0, 1))
    plane_model(f'deep/{name}', f'deep/{name}')


def ring_texture(name, inner_rel, res=64):
    """Квадратное кольцо (развёртка как у кубических планет): координата — расстояние Чебышёва."""
    u = (np.arange(res) + 0.5) / res * 2 - 1
    U, V = np.meshgrid(u, u)
    dch = np.maximum(np.abs(U), np.abs(V))
    t = (dch - inner_rel) / (1 - inner_rel)         # 0 у внутреннего края, 1 у внешнего
    inside = (t >= 0) & (t <= 1)
    if name == 'saturn':
        bands = 0.55 + 0.45 * np.sin(t * 38) * np.sin(t * 11 + 1)
        col = mix(hexc('#a89272'), hexc('#ead9ae'), np.clip(bands, 0, 1))
        a = 0.45 + 0.4 * np.clip(bands, 0, 1)
        a = np.where((t > 0.58) & (t < 0.66), 0.06, a)            # щель Кассини
        a = np.where(t < 0.12, a * 0.35, a)                         # тусклое кольцо C
        a = a * smooth(0.0, 0.03, t) * smooth(1.0, 0.96, t)
    elif name in ('uranus', 'haumea'):
        col = np.broadcast_to(hexc('#c9dbe6'), U.shape + (3,))
        a = np.zeros_like(t)
        for c in (0.15, 0.35, 0.55, 0.9):
            a = np.maximum(a, 0.7 * np.exp(-((t - c) / 0.025) ** 2))
    else:  # florana: пыльца и споры
        sp = _hash3(((U + 1) * 300).astype(np.int64), ((V + 1) * 300).astype(np.int64), np.zeros_like(U, dtype=np.int64), 77)
        base = 0.28 * np.exp(-((t - 0.5) / 0.32) ** 2)
        a = base + np.where(sp > 0.92, 0.55, 0) * np.exp(-((t - 0.5) / 0.4) ** 2)
        col = mix(hexc('#b47cff'), hexc('#ffd6ff'), (sp > 0.92).astype(np.float32))
    a = np.where(inside, a, 0)
    rgba = np.concatenate([col, np.clip(a, 0, 1)[..., None]], -1)
    em = a * (0.9 if name == 'florana' else 0.0)
    save_png(f'ring/{name}', rgba, em if name == 'florana' else None)
    plane_model(f'ring/{name}', f'ring/{name}')


ROCKS = {
    'rock_0': ('#5e5a56', '#9a948c'), 'rock_1': ('#4a3c33', '#8a7462'), 'rock_2': ('#2c2a2c', '#5a5458'),
    'rock_3': ('#9fc4dc', '#f1f8ff'), 'rock_4': ('#6a3a2a', '#b06a48'), 'rock_5': ('#3a2458', '#9a6ad0'),
}


def rock_textures():
    for i, (name, (c0, c1)) in enumerate(ROCKS.items()):
        texs = {}
        for f in FACES:
            d = face_dirs(f, 8)
            n = fbm(d * 3 + i * 5, 3, 600 + i)
            col = mix(hexc(c0), hexc(c1), smooth(0.25, 0.8, n))
            col = np.clip(col + craters(d, 610 + i, 4, 0.2, 0.5, 0.1)[..., None], 0, 1)
            save_png(f'rock/{name}_{f}', np.concatenate([col, np.ones(col.shape[:-1] + (1,), np.float32)], -1))
            texs[f] = f'rock/{name}_{f}'
        cube_model(f'rock/{name}', texs)
    # ядро кометы: тёмный лёд с белыми проплешинами
    texs = {}
    for f in FACES:
        d = face_dirs(f, 8)
        n = fbm(d * 3 + 40, 3, 650)
        col = np.where((n > 0.58)[..., None], hexc('#e8f6ff'), mix(hexc('#2e3238'), hexc('#5d6670'), n))
        save_png(f'comet/nucleus_{f}', np.concatenate([col, np.ones(col.shape[:-1] + (1,), np.float32)], -1))
        texs[f] = f'comet/nucleus_{f}'
    cube_model('comet/nucleus', texs)


FX_COLORS = {
    'white': '#ffffff', 'sun_core': '#fff4c2', 'sun_mid': '#ffc24a', 'sun_outer': '#ff8a2a', 'prom': '#ff5a22',
    'ion': '#7fc8ff', 'dust': '#fff1d0', 'coma': '#cdf5ff', 'violet': '#c27dff', 'spore': '#f0b0ff',
    'meteor': '#ffd9a0', 'mote': '#cfe0ff', 'earth': '#8fc8ff', 'aurora': '#9ff6ff',
    'aurora_green': '#7dffb4', 'aurora_violet': '#d68bff', 'aurora_blue': '#9fb8ff', 'petal': '#ffb7d5',
    'lava': '#ff6a3d', 'hole': '#ffb060', 'quasar': '#bfe0ff', 'pulsar': '#a8d8ff', 'star_blue': '#9fc6ff',
    'star_orange': '#ff9a5a', 'neb_pink': '#ff5aa8', 'neb_blue': '#5ac8ff', 'neb_violet': '#9a6aff',
    'neb_teal': '#40e0c0', 'neb_gold': '#ffcf6a', 'geyser': '#e0f4ff', 'gamma': '#e8f4ff',
}
FX_ALPHA = {'a': 1.0, 'b': 0.55, 'c': 0.3, 'd': 0.14}


def fx_textures():
    res = 32
    u = (np.arange(res) + 0.5) / res * 2 - 1
    U, V = np.meshgrid(u, u)
    r = np.sqrt(U * U + V * V)
    glow = np.exp(-(r / 0.55) ** 2 * 2.0) * smooth(1.0, 0.8, r)
    core = np.exp(-(r / 0.16) ** 2 * 2.5)
    spark = np.maximum(np.exp(-(np.abs(U) / 0.06) ** 2) * np.exp(-(np.abs(V) / 0.7) ** 2),
                       np.exp(-(np.abs(V) / 0.06) ** 2) * np.exp(-(np.abs(U) / 0.7) ** 2))
    spark = np.maximum(spark, core)
    ray = np.exp(-(U / 0.07) ** 2) * np.clip(1 - np.abs(V), 0, 1) ** 1.6                 # длинный луч (вертикальный)
    ring = np.exp(-((r - 0.82) / 0.05) ** 2) * smooth(1.0, 0.95, r) + 0.25 * np.exp(-((r - 0.82) / 0.16) ** 2)
    puff_n = fbm(np.stack([U * 2.5, V * 2.5, np.full_like(U, 4.2)], -1), 4, 1400)
    puff = np.exp(-(r / 0.62) ** 2 * 1.6) * smooth(0.25, 0.75, puff_n) * smooth(1.0, 0.75, r)
    puff = puff / max(puff.max(), 1e-3)
    shapes = {'glow': (glow * 0.85 + core * 0.4, FX_ALPHA), 'puff': (puff, FX_ALPHA),
              'spark': (spark, {'a': 1.0, 'c': 0.3}), 'ray': (ray, {'a': 1.0, 'c': 0.3}), 'ring': (ring, {'a': 1.0, 'c': 0.3})}
    for cname, hx in FX_COLORS.items():
        col = hexc(hx)
        for shape, (base, alphas) in shapes.items():
            for akey, amul in alphas.items():
                a = np.clip(base * amul, 0, 1)
                rgb = mix(np.broadcast_to(col, a.shape + (3,)), np.ones(3, np.float32), core * (0.6 if shape in ('glow', 'spark') else 0.15))
                name = f'fx/{shape}_{cname}' + ('' if shape == 'spark' and akey == 'a' else f'_{akey}')
                save_png(name, np.concatenate([rgb, a[..., None]], -1), a)
                sprite_model(name, name)
    # чёрный диск (горизонт событий, тень затмения): непрозрачный круг с мягким краем
    a = smooth(1.0, 0.94, r)
    save_png('fx/disc', np.concatenate([np.zeros(r.shape + (3,), np.float32), a[..., None]], -1))
    sprite_model('fx/disc', 'fx/disc')
    # затемнение мира в затмении: ровная чёрная вуаль 8 уровней и виньетка по краям
    for k in range(1, 9):
        a = np.full(r.shape, k * 0.08, np.float32)
        save_png(f'fx/shade_{k}', np.concatenate([np.zeros(r.shape + (3,), np.float32), a[..., None]], -1))
        sprite_model(f'fx/shade_{k}', f'fx/shade_{k}')
    a = 0.6 * smooth(0.35, 1.3, r)
    save_png('fx/vignette', np.concatenate([np.full(r.shape + (3,), 0.02, np.float32), a[..., None]], -1))
    sprite_model('fx/vignette', 'fx/vignette')
    # лепесток сакуры
    pet = np.clip(1 - ((U / 0.55) ** 2 + ((V + 0.1) / 0.85) ** 2), 0, 1) ** 0.4 * (1 - np.exp(-((U) / 0.08) ** 2) * (V < -0.6))
    rgb = mix(np.broadcast_to(hexc('#ff9cc8'), r.shape + (3,)), np.broadcast_to(hexc('#fff0f6'), r.shape + (3,)), smooth(0.9, 0.1, r))
    save_png('fx/petal', np.concatenate([rgb, pet[..., None]], -1))
    sprite_model('fx/petal', 'fx/petal')
    # «призрачный» блик объектива — шестиугольник
    hexd = np.maximum(np.abs(U) * 0.866 + np.abs(V) * 0.5, np.abs(V))
    hexa = smooth(0.85, 0.7, hexd) * (0.35 + 0.65 * smooth(0.3, 0.8, hexd))
    for cname, hx in (('flare_a', '#9fc6ff'), ('flare_b', '#ffcf8a'), ('flare_c', '#c8a0ff')):
        save_png(f'fx/{cname}', np.concatenate([np.broadcast_to(hexc(hx), r.shape + (3,)), (hexa * 0.5)[..., None]], -1), hexa * 0.3)
        sprite_model(f'fx/{cname}', f'fx/{cname}')


def torii():
    """Врата Фазы — тории на орбите Земли."""
    u = (np.arange(8) + 0.5) / 8
    U, V = np.meshgrid(u, u)
    n = fbm(np.stack([U * 4, V * 4, np.zeros_like(U)], -1), 2, 700)
    red = mix(hexc('#b8321e'), hexc('#ef5a36'), n)
    dark = mix(hexc('#141218'), hexc('#2c2830'), n)
    save_png('torii/red', np.concatenate([red, np.ones(U.shape + (1,), np.float32)], -1))
    save_png('torii/dark', np.concatenate([dark, np.ones(U.shape + (1,), np.float32)], -1))

    def box(f, t, tex):
        return {'from': f, 'to': t, 'shade': False, 'faces': {d: {'texture': '#' + tex} for d in FACE_DIR.values()}}
    els = [
        box([3, 0, 7], [5, 12.5, 9], 'r'), box([11, 0, 7], [13, 12.5, 9], 'r'),
        box([2.75, 0, 6.75], [5.25, 1, 9.25], 'k'), box([10.75, 0, 6.75], [13.25, 1, 9.25], 'k'),
        box([1.5, 9.5, 7.25], [14.5, 10.75, 8.75], 'r'),
        box([7.25, 10.75, 7.5], [8.75, 12.5, 8.5], 'k'),
        box([0.5, 12.5, 7], [15.5, 13.5, 9], 'r'),
        box([-0.5, 13.5, 6.75], [16.5, 14.5, 9.25], 'k'),
        box([-1.5, 14, 6.75], [0.5, 15.25, 9.25], 'k'), box([15.5, 14, 6.75], [17.5, 15.25, 9.25], 'k'),
    ]
    model('torii', {'r': f'{NS}:item/torii/red', 'k': f'{NS}:item/torii/dark', 'particle': f'{NS}:item/torii/red'}, els)


# ───────────────────────── звёздное небо ─────────────────────────
# Небо — восьмиугольная призма вокруг камеры: 8 стен + верх и низ. Призма, а не куб, потому что
# туман дальности в игре считается «цилиндрически» (max(горизонталь, вертикаль)): у призмы всё небо
# на одном расстоянии и не тонет в тумане по углам. Все стены — одна модель (sky/wall_k), повёрнутая
# в коде на k·45°; здесь для каждой стены считается своя картинка.

SKY_W, SKY_H, SKY_CAP = 448, 1024, 1024
WALL_HALF = 8 * math.tan(math.radians(22.5))       # полуширина стены в единицах модели (апофема 8)


def rot_y(v, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    x, y, z = v[..., 0], v[..., 1], v[..., 2]
    return np.stack([x * c + z * s, y, -x * s + z * c], -1)


def sky_panels():
    """[(name, dirs(H,W,3))] — направления взгляда для каждого пикселя каждой стены/крышки."""
    out = []
    u = (np.arange(SKY_W) + 0.5) / SKY_W
    v = (np.arange(SKY_H) + 0.5) / SKY_H
    U, V = np.meshgrid(u, v)
    # стена: грань south элемента на плоскости z=0 (центр модели 8,8,8): x растёт с u, y падает с v
    local = np.stack([(U * 2 - 1) * WALL_HALF, 8 - V * 16, np.full_like(U, -8.0)], -1)
    for k in range(8):
        p = rot_y(local, k * 45)
        out.append((f'wall_{k}', p / np.linalg.norm(p, axis=-1, keepdims=True)))
    c = (np.arange(SKY_CAP) + 0.5) / SKY_CAP
    U, V = np.meshgrid(c, c)
    top = np.stack([U * 16 - 8, np.full_like(U, 8.0), (16 - V * 16) - 8], -1)      # грань down: z = 16 − v
    bot = np.stack([U * 16 - 8, np.full_like(U, -8.0), V * 16 - 8], -1)            # грань up: z = v
    out.append(('cap_top', top / np.linalg.norm(top, axis=-1, keepdims=True)))
    out.append(('cap_bottom', bot / np.linalg.norm(bot, axis=-1, keepdims=True)))
    return out


def project_to_panel(name, dirs):
    """Звёзды (N,3) → пиксели (row, col, mask) этой стены."""
    if name.startswith('wall_'):
        k = int(name[5:])
        loc = rot_y(dirs, -k * 45)
        ok = loc[..., 2] < -1e-6
        t = np.where(ok, -8 / np.where(ok, loc[..., 2], -1), 0)
        x, y = loc[..., 0] * t, loc[..., 1] * t
        ok &= (np.abs(x) <= WALL_HALF) & (np.abs(y) <= 8)
        col = ((x / WALL_HALF + 1) / 2 * SKY_W).astype(int)
        row = ((8 - y) / 16 * SKY_H).astype(int)
        return np.clip(row, 0, SKY_H - 1), np.clip(col, 0, SKY_W - 1), ok
    up = name == 'cap_top'
    dy = dirs[..., 1]
    ok = dy > 1e-6 if up else dy < -1e-6
    t = np.where(ok, (8 if up else -8) / np.where(ok, dy, 1), 0)
    x, z = dirs[..., 0] * t, dirs[..., 2] * t
    ok &= (np.abs(x) <= 8) & (np.abs(z) <= 8)
    col = ((x + 8) / 16 * SKY_CAP).astype(int)
    row = (((8 - z) if up else (z + 8)) / 16 * SKY_CAP).astype(int)
    return np.clip(row, 0, SKY_CAP - 1), np.clip(col, 0, SKY_CAP - 1), ok


def unit(v):
    v = np.asarray(v, np.float64)
    return v / np.linalg.norm(v)


GAL_N = unit([0.52, 0.74, 0.42])           # нормаль плоскости Млечного Пути (лента проходит и через «потолок»)
GAL_C = unit(np.cross(GAL_N, [0, 0, 1]))   # направление на центр Галактики (лежит в плоскости)
NEBULAE = [  # центр, угловой радиус, цвет A, цвет B, зерно
    (unit([0.75, 0.25, -0.6]), 0.55, '#c040ff', '#4a2cff', 11),     # фиолетовая — в цветах Флораны
    (unit([-0.6, -0.1, -0.78]), 0.48, '#2fd8c8', '#2a5dff', 12),    # бирюзовая
    (unit([-0.4, 0.45, 0.8]), 0.42, '#ff6a2a', '#ff2a6a', 13),      # огненная
    (unit([0.45, -0.55, 0.7]), 0.36, '#ff8ac8', '#7a4cff', 14),     # розовая
    (unit([-0.85, 0.35, -0.2]), 0.30, '#7affc8', '#3aa0ff', 15),    # зелёная дымка
    (unit([0.1, 0.95, -0.2]), 0.40, '#a04cff', '#ff4cc8', 16),      # над головой
    (unit([0.2, -0.9, -0.3]), 0.38, '#3a6aff', '#a04cff', 17),      # под ногами
]
ECLIPSE = unit([0.86, 0.12, 0.49])        # «Кольцо затмения» — туманность-кольцо


def sky():
    rng = np.random.default_rng(2026)
    # звёзды: равномерно по небу + вдвое больше вдоль Млечного Пути + несколько скоплений
    n1, n2 = 30000, 30000
    s1 = rng.normal(size=(n1, 3))
    s2 = rng.normal(size=(n2 * 3, 3))
    s2 /= np.linalg.norm(s2, axis=1, keepdims=True)
    keep = np.abs(s2 @ GAL_N) < rng.uniform(0, 0.25, len(s2))
    parts = [s1 / np.linalg.norm(s1, axis=1, keepdims=True), s2[keep][:n2]]
    for _ in range(7):
        c = unit(rng.normal(size=3))
        cl = c + rng.normal(size=(260, 3)) * rng.uniform(0.012, 0.03)
        parts.append(cl / np.linalg.norm(cl, axis=1, keepdims=True))
    stars = np.concatenate(parts)
    mag = rng.random(len(stars)) ** 7.5                # почти все тусклые, единицы — яркие
    temp = rng.choice(5, len(stars), p=[0.12, 0.38, 0.28, 0.15, 0.07])
    tcol = np.stack([hexc(c) for c in ('#9bb4ff', '#f4f6ff', '#fff3e0', '#ffd2a1', '#ffab7a')])
    previews = {}
    for name, d in sky_panels():
        H, W = d.shape[:2]
        dz = d @ GAL_N
        band = np.exp(-(dz / 0.19) ** 2)
        core = np.exp(-(np.arccos(np.clip(d @ GAL_C, -1, 1)) / 0.85) ** 2)
        cloud = fbm(d * 3.0 + 5, 5, 801)
        fine = fbm(d * 12.0 + 9, 3, 802)
        mw = band * (0.25 + 0.75 * cloud ** 1.6) * (0.3 + 1.2 * core) * (0.35 + 1.1 * fine ** 2)
        lanes = smooth(0.48, 0.64, fbm(d * 6.0 + 1, 4, 803)) * np.exp(-(dz / 0.07) ** 2)
        mw *= 1 - 0.85 * lanes
        mwcol = mix(mix(hexc('#6f8cff'), hexc('#c4b2ff'), smooth(0.0, 0.5, core)), hexc('#ffcf98'), smooth(0.4, 1.0, core))
        img = np.broadcast_to(hexc('#010207'), d.shape).astype(np.float32).copy()
        img += hexc('#05081c') * (0.3 + 0.7 * fbm(d * 1.5, 3, 804))[..., None]    # еле заметная глубина
        img += mwcol * (mw * 0.36)[..., None]
        img += hexc('#2a1606') * (lanes * band * 0.2)[..., None]
        # туманности
        for c, rad, ca, cb, sd in NEBULAE:
            ang = np.arccos(np.clip(d @ c, -1, 1))
            m = smooth(rad, rad * 0.25, ang)
            if not m.any():
                continue
            w = fbm(d * 3.5 + sd, 3, 900 + sd)
            q = d * 4.5 + (w[..., None] - 0.5) * 2.6
            shape = fbm(q + sd * 3, 5, 950 + sd)
            neb = m * smooth(0.40, 0.82, shape) ** 1.4
            tint = mix(hexc(ca), hexc(cb), smooth(0.3, 0.8, fbm(d * 6 + sd, 3, 990 + sd)))
            img += tint * (neb * 0.95)[..., None]
            img += np.ones(3) * (m * smooth(0.8, 0.96, shape) * 0.3)[..., None]
            dark = smooth(0.58, 0.7, fbm(d * 9 + sd, 3, 1100 + sd)) * m
            img *= (1 - 0.45 * dark)[..., None]
        # «Кольцо затмения»: золотое кольцо вокруг чёрного диска, корона и «бриллиант» на краю
        ang = np.arccos(np.clip(d @ ECLIPSE, -1, 1))
        ring = np.exp(-((ang - 0.04) / 0.0035) ** 2)
        corona = np.exp(-((ang - 0.04) / 0.03) ** 2) * (0.5 + 0.8 * smooth(0.5, 0.85, fbm(d * 60, 2, 1200)))
        img += hexc('#ffd890') * (ring * 0.95)[..., None] + hexc('#ffb860') * (corona * 0.22)[..., None]
        img = np.where((ang < 0.037)[..., None], img * 0.04, img)
        t1 = unit(np.cross(ECLIPSE, [0, 1, 0])); t2 = np.cross(ECLIPSE, t1)
        dia = unit(ECLIPSE + 0.04 * (t1 * 0.6 + t2 * 0.8))
        da = np.arccos(np.clip(d @ dia, -1, 1))
        x, y = d @ t1, d @ t2
        dx, dy = x - (dia @ t1), y - (dia @ t2)
        spike = np.exp(-(np.abs(dx) / 0.0012) ** 2) * np.exp(-(np.abs(dy) / 0.03) ** 2) + np.exp(-(np.abs(dy) / 0.0012) ** 2) * np.exp(-(np.abs(dx) / 0.03) ** 2)
        img += np.ones(3) * (np.exp(-(da / 0.004) ** 2) * 1.5 + spike * (d @ ECLIPSE > 0.9) * 0.8)[..., None]
        # большие спиральные галактики (как Андромеда) — видны как пятна с рукавами
        for gc, gsz, gtilt, gcol, gseed in ((unit([-0.7, 0.32, 0.64]), 0.11, 0.35, '#ffe2c4', 3),
                                              (unit([0.15, -0.35, -0.92]), 0.06, 0.8, '#d8e4ff', 4),
                                              (unit([0.92, 0.38, 0.05]), 0.045, 0.55, '#ffd8f0', 5)):
            ga = np.arccos(np.clip(d @ gc, -1, 1))
            if ga.min() > gsz * 1.6:
                continue
            t1 = unit(np.cross(gc, [0.2, 1, 0.1])); t2 = np.cross(gc, t1)
            x, y = d @ t1, d @ t2
            y = y / gtilt
            rr = np.sqrt(x * x + y * y) / gsz
            th = np.arctan2(y, x)
            arms = 0.5 + 0.5 * np.cos(2 * th - np.log(np.maximum(rr, 1e-3)) * 5.5)
            disk = np.exp(-rr * 3.2) * (0.35 + 0.9 * arms ** 2) * (d @ gc > 0)
            bulge = np.exp(-(rr / 0.12) ** 2) * (d @ gc > 0)
            clump = smooth(0.55, 0.8, fbm(d * 260 + gseed, 2, 1500 + gseed)) * disk
            img += hexc(gcol) * (disk * 0.55 + bulge * 0.9)[..., None] + hexc('#a8c8ff') * (clump * 0.5)[..., None]
        # далёкие галактики — размытые эллипсы
        for gi in range(9):
            c = unit(rng.normal(size=3))
            a = np.arccos(np.clip(d @ c, -1, 1))
            if a.min() > 0.03:
                continue
            t1 = unit(np.cross(c, [0, 1, 0.3])); t2 = np.cross(c, t1)
            ang_rot = rng.uniform(0, math.pi)
            e1 = t1 * math.cos(ang_rot) + t2 * math.sin(ang_rot); e2 = np.cross(c, e1)
            x, y = d @ e1, d @ e2
            sz = rng.uniform(0.003, 0.009)
            g = np.exp(-((x / sz) ** 2 + (y / (sz * 0.35)) ** 2)) * (d @ c > 0)
            img += hexc('#ffe8d0') * (g * 0.5)[..., None]
        # звёзды
        row, col, ok = project_to_panel(name, stars)
        r, cc, mg, tc = row[ok], col[ok], mag[ok], temp[ok]
        bright = 0.18 + 0.95 * mg
        np.add.at(img, (r, cc), tcol[tc] * bright[:, None])
        big = mg > 0.35
        for dr, dc in ((0, 1), (0, -1), (1, 0), (-1, 0)):
            rr, c2 = np.clip(r[big] + dr, 0, H - 1), np.clip(cc[big] + dc, 0, W - 1)
            np.add.at(img, (rr, c2), tcol[tc[big]] * (0.45 * bright[big])[:, None])
        huge = mg > 0.75
        for dr, dc in ((0, 2), (0, -2), (2, 0), (-2, 0), (0, 3), (0, -3), (3, 0), (-3, 0)):
            rr, c2 = np.clip(r[huge] + dr, 0, H - 1), np.clip(cc[huge] + dc, 0, W - 1)
            np.add.at(img, (rr, c2), tcol[tc[huge]] * (0.22 * bright[huge] * (1.6 if abs(dr) + abs(dc) == 2 else 0.8))[:, None])
        for dr, dc in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
            rr, c2 = np.clip(r[huge] + dr, 0, H - 1), np.clip(cc[huge] + dc, 0, W - 1)
            np.add.at(img, (rr, c2), tcol[tc[huge]] * (0.18 * bright[huge])[:, None])
        img = np.clip(img, 0, 1)
        lum = img.max(-1)
        rgba = np.concatenate([img, np.ones((H, W, 1), np.float32)], -1)
        previews[name] = save_png(f'sky/{name}', rgba, smooth(0.25, 0.9, lum))
        face = 'south' if name.startswith('wall') else ('down' if name == 'cap_top' else 'up')
        if name.startswith('wall'):
            el = {'from': [8 - WALL_HALF, 0, 0], 'to': [8 + WALL_HALF, 16, 0], 'shade': False,
                  'faces': {'south': {'uv': [0, 0, 16, 16], 'texture': '#t'}}}
        elif name == 'cap_top':
            el = {'from': [0, 16, 0], 'to': [16, 16, 16], 'shade': False, 'faces': {'down': {'uv': [0, 0, 16, 16], 'texture': '#t'}}}
        else:
            el = {'from': [0, 0, 0], 'to': [16, 0, 16], 'shade': False, 'faces': {'up': {'uv': [0, 0, 16, 16], 'texture': '#t'}}}
        model(f'sky/{name}', {'t': f'{NS}:item/sky/{name}', 'particle': f'{NS}:item/sky/{name}'}, [el])
        print('  sky', name, face)
    return previews


# ───────────────────────── датапак ─────────────────────────

def datapack():
    base_attrs = {
        'minecraft:audio/background_music': {'default': {'max_delay': 24000, 'min_delay': 6000, 'sound': 'minecraft:music.end'}},
        'minecraft:gameplay/bed_rule': {'can_set_spawn': 'never', 'can_sleep': 'never', 'destroy_on_use': True},
        'minecraft:gameplay/respawn_anchor_works': False,
        'minecraft:gameplay/straw_bed_rule': {'can_set_spawn': 'never', 'can_sleep': 'never', 'destroy_on_use': True},
        'minecraft:visual/ambient_light_color': '#101322',
        'minecraft:visual/cloud_color': '#00ffffff',
        'minecraft:visual/cloud_height': 4000.0,
        'minecraft:visual/fog_color': '#000000',
        'minecraft:visual/fog_start_distance': 4000.0,
        'minecraft:visual/fog_end_distance': 8000.0,
        'minecraft:visual/sky_color': '#000000',
        'minecraft:visual/sky_light_color': '#f2f4ff',
        'minecraft:visual/sky_light_factor': 1.0,
    }
    # космос: по образцу ванильного the_end (26.3), но без битвы с драконом и со светом «солнца» на астероидах
    write_json(os.path.join(DATA, 'dimension_type', 'space.json'), {
        'ambient_light': 0.0,
        'attributes': base_attrs,
        'coordinate_scale': 1.0,
        'default_clock': 'minecraft:the_end',
        'has_ceiling': False,
        'has_ender_dragon_fight': False,
        'has_fixed_time': True,
        'has_skylight': True,
        'height': 4064,
        'infiniburn': '#minecraft:infiniburn_end',
        'logical_height': 4064,
        'min_y': -2032,
        'monster_spawn_block_light_limit': 0,
        'monster_spawn_light_level': 15,
        'skybox': 'end',
        'timelines': '#minecraft:in_end',
    })
    # планеты: по образцу ванильного overworld (26.3): день и ночь, кровати работают, высота 256
    write_json(os.path.join(DATA, 'dimension_type', 'planet.json'), {
        'ambient_light': 0.0,
        'attributes': {
            'minecraft:audio/background_music': {'default': {'max_delay': 24000, 'min_delay': 12000, 'sound': 'minecraft:music.game'}},
            'minecraft:gameplay/bed_rule': {'can_set_spawn': 'always', 'can_sleep': 'when_dark',
                                            'error_message': {'translate': 'block.minecraft.bed.no_sleep'}},
            'minecraft:gameplay/respawn_anchor_works': False,
            'minecraft:visual/ambient_light_color': '#0a0a0a',
            'minecraft:visual/cloud_color': '#ccffffff',
            'minecraft:visual/cloud_height': 230.33,
            'minecraft:visual/fog_color': '#c0d8ff',
            'minecraft:visual/sky_color': '#78a7ff',
        },
        'coordinate_scale': 1.0,
        'default_clock': 'minecraft:overworld',
        'has_ceiling': False,
        'has_ender_dragon_fight': False,
        'has_skylight': True,
        'height': 256,
        'infiniburn': '#minecraft:infiniburn_overworld',
        'logical_height': 256,
        'min_y': 0,
        'monster_spawn_block_light_limit': 0,
        'monster_spawn_light_level': {'type': 'minecraft:uniform', 'max_inclusive': 7, 'min_inclusive': 0},
        'timelines': '#minecraft:in_overworld',
    })
    empty_spawns = {'argument': {'spawn_costs': {}, 'spawns_by_category': {k: [] for k in (
        'ambient', 'axolotls', 'creature', 'misc', 'monster', 'underground_water_creature', 'water_ambient', 'water_creature')}},
        'modifier': 'overlay'}

    def biome(name, attrs, features, water='#3f76e4'):
        write_json(os.path.join(DATA, 'worldgen', 'biome', name + '.json'), {
            'attributes': dict({'minecraft:gameplay/natural_mob_spawns': empty_spawns}, **attrs),
            'carvers': [], 'downfall': 0.0, 'effects': {'water_color': water},
            'features': features, 'has_precipitation': False, 'temperature': 0.5})

    space_features = [[] for _ in range(11)]
    space_features[2] = [f'{NS}:asteroid_field']
    biome('space', {'minecraft:visual/sky_color': '#000000', 'minecraft:visual/fog_color': '#000000'}, space_features)
    write_json(os.path.join(DATA, 'worldgen', 'feature', 'asteroid.json'), {'type': f'{NS}:asteroid'})
    write_json(os.path.join(DATA, 'worldgen', 'placed_feature', 'asteroid_field.json'),
               {'feature': f'{NS}:asteroid', 'placement': []})
    write_json(os.path.join(DATA, 'dimension', 'space.json'), {
        'type': f'{NS}:space',
        'generator': {'type': 'minecraft:flat', 'settings': {
            'biome': f'{NS}:space', 'features': True, 'lakes': False,
            'layers': [{'block': 'minecraft:structure_void', 'height': 1}], 'structure_overrides': []}}})
    for b in SYS['bodies']:
        if 'surface' not in b:
            continue
        s = b['surface']
        attrs = {'minecraft:visual/sky_color': s['sky'], 'minecraft:visual/fog_color': s['fog']}
        if s['sky'] == '#000000':  # без атмосферы: чёрное небо, звёзды и днём
            attrs['minecraft:visual/cloud_color'] = '#00ffffff'
            attrs['minecraft:visual/sunrise_sunset_color'] = '#00000000'
        biome(b['id'], attrs, [[] for _ in range(11)], s.get('water', '#3f76e4'))
        layers = [{'block': 'minecraft:' + blk, 'height': h} for blk, h in s['layers']]
        write_json(os.path.join(DATA, 'dimension', b['id'] + '.json'), {
            'type': f'{NS}:planet',
            'generator': {'type': 'minecraft:flat', 'settings': {
                'biome': f'{NS}:{b["id"]}', 'features': False, 'lakes': False,
                'layers': layers, 'structure_overrides': []}}})


def contact_sheet(name, tiles, scale=4, cols=8):
    os.makedirs(PREVIEW, exist_ok=True)
    tiles = [np.kron(t, np.ones((scale, scale, 1), np.uint8)) if t.shape[0] <= 64 else t for t in tiles]
    h = max(t.shape[0] for t in tiles)
    w = max(t.shape[1] for t in tiles)
    rows = (len(tiles) + cols - 1) // cols
    sheet = np.zeros((rows * (h + 8), cols * (w + 8), 4), np.uint8)
    sheet[..., 3] = 255
    for i, t in enumerate(tiles):
        r, c = divmod(i, cols)
        y, x = r * (h + 8), c * (w + 8)
        a = t[..., 3:4].astype(np.float32) / 255
        sheet[y:y + t.shape[0], x:x + t.shape[1], :3] = (t[..., :3] * a + 24 * (1 - a)).astype(np.uint8)
    Image.fromarray(sheet).save(os.path.join(PREVIEW, name + '.png'))


def celestial():
    """Солнце и Луна на небе Земли — наши кубические, вместо ванильных (атлас celestials).
    Небесные тела рисуются аддитивно: чёрное = прозрачное, поэтому тёмная сторона Луны просто не видна."""
    base = os.path.join(RES, 'assets', 'minecraft', 'textures', 'environment', 'celestial')
    os.makedirs(os.path.join(base, 'moon'), exist_ok=True)
    R = 32
    u = (np.arange(R) + 0.5) / R * 2 - 1
    U, V = np.meshgrid(u, u)
    # Солнце: пиксельная грань куба в центре, вокруг — квадратное свечение и лучи
    d = face_dirs('s', 16)
    g = fbm(d * 9 + 3, 4, 401)
    face = mix(hexc('#ff9a1f'), hexc('#ffe27a'), smooth(0.25, 0.85, g))
    img = np.zeros((R, R, 3), np.float32)
    cheb = np.maximum(np.abs(U), np.abs(V))
    glow = np.exp(-((cheb - 0.5).clip(0) / 0.22) ** 2) * (cheb > 0.5)
    rays = (np.exp(-(np.abs(U) / 0.06) ** 2) + np.exp(-(np.abs(V) / 0.06) ** 2)) * np.exp(-cheb * 2.2) * 0.6
    img += hexc('#ffb347') * (glow * 0.7 + rays)[..., None]
    img[8:24, 8:24] = face
    Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGB').save(os.path.join(base, 'sun.png'))
    # Луна: грань нашего кубического спутника, фаза — тень по кубу (пиксельно)
    d = face_dirs('s', 16)
    m = paint('moon', d, (sum(map(ord, 'moon')) * 7) % 997)[0]
    phases = ['full_moon', 'waning_gibbous', 'third_quarter', 'waning_crescent', 'new_moon', 'waxing_crescent', 'first_quarter', 'waxing_gibbous']
    lit_x = [(-1, 1), (-0.5, 1), (0, 1), (0.5, 1), (2, 2), (-1, -0.5), (-1, 0), (-1, 0.5)]
    col = (np.arange(16) + 0.5) / 16 * 2 - 1
    for name, (a, b) in zip(phases, lit_x):
        lit = ((col >= a) & (col <= b)).astype(np.float32)
        shade = np.broadcast_to(0.04 + 0.96 * lit, (16, 16))[..., None]
        img = np.zeros((R, R, 3), np.float32)
        img[8:24, 8:24] = m * shade
        img += hexc('#9fb8ff') * (np.exp(-((cheb - 0.5).clip(0) / 0.12) ** 2) * (cheb > 0.5) * 0.12 * (name != 'new_moon'))[..., None]
        Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGB').save(os.path.join(base, 'moon', name + '.png'))


def main():
    no_sky = '--no-sky' in sys.argv
    for sub in ('models/item', 'items', 'textures/item'):
        p = os.path.join(ASSETS, sub)
        if not os.path.isdir(p):
            continue
        for e in os.listdir(p):
            if no_sky and e == 'sky':
                continue
            shutil.rmtree(os.path.join(p, e), ignore_errors=True)
    shutil.rmtree(DATA, ignore_errors=True)
    print('bodies')
    crosses = [body_textures(b) for b in SYS['bodies']]
    contact_sheet('bodies', crosses, 3, 4)
    print('sun, rings, rocks, fx, torii')
    contact_sheet('sun', sun_textures(), 4, 6)
    for b in SYS['bodies']:
        if b.get('ring'):
            ring_texture(b['ring'], b.get('ringInner', 0.7) / b.get('ringOuter', 1.2))
    rock_textures()
    fx_textures()
    disk_texture('accretion', 0.3, '#fff2d8', '#ff6a1a')
    disk_texture('quasar_disk', 0.18, '#ffffff', '#5a8cff')
    body_textures({'id': 'rogue', 'tex': 16, 'style': 'rogue', 'lights': True})
    torii()
    if not no_sky:
        print('sky (slow)')
        pv = sky()
        order = [f'wall_{k}' for k in range(8)]
        strip = np.concatenate([pv[n] for n in order], 1)
        Image.fromarray(strip).resize((strip.shape[1] // 2, strip.shape[0] // 2)).save(os.path.join(PREVIEW, 'sky_walls.png'))
        Image.fromarray(np.concatenate([pv['cap_top'], pv['cap_bottom']], 1)).resize((1024, 512)).save(os.path.join(PREVIEW, 'sky_caps.png'))
    print('datapack')
    datapack()
    celestial()
    n_models = sum(len(f) for _, _, f in os.walk(os.path.join(ASSETS, 'models')))
    n_tex = sum(len(f) for _, _, f in os.walk(os.path.join(ASSETS, 'textures')))
    print(f'done: {n_models} models, {n_tex} textures')


if __name__ == '__main__':
    main()
