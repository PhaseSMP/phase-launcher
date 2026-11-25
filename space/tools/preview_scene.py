#!/usr/bin/env python3
"""Предпросмотр космоса без игры: трассировка лучей по тем же текстурам, орбитам и свету, что в моде.

    python3 tools/preview_scene.py              # набор кадров в build/preview/scene_*.png
    python3 tools/preview_scene.py --t 123456   # другой момент времени (тики)

Это не скриншот игры: нет ванильной карты освещения, тумана и шейдеров. Нужен, чтобы быстро проверить
композицию, размеры, цвета и как ложится свет Солнца. Настоящие снимки делает CI (SpaceShots).
"""
import json
import math
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
import gen_assets as G  # noqa: E402

TEX = os.path.join(G.ASSETS, 'textures', 'item')
OUT = os.path.join(G.ROOT, 'build', 'preview')
W, H, FOV = 1280, 720, 80.0
PLANE_Y = 0.0
_cache = {}


def tex(rel):
    if rel not in _cache:
        _cache[rel] = np.asarray(Image.open(os.path.join(TEX, rel + '.png')).convert('RGBA'), np.float32) / 255
    return _cache[rel]


def sample(img, u, v):
    h, w = img.shape[:2]
    x = np.clip((u * w).astype(int), 0, w - 1)
    y = np.clip((v * h).astype(int), 0, h - 1)
    return img[y, x]


class Body:
    def __init__(self, o, parent=None):
        self.o, self.parent = o, parent
        self.id = o['id']
        g = o.get
        self.orbit = g('orbit', 0)
        self.period = g('period', 144000 * (max(1, self.orbit) / 3000) ** 1.5)
        self.shape = np.array(g('shape', [1, 1, 1]), float)
        self.phase, self.yamp, self.node = math.radians(g('phase', 0)), g('yAmp', 0), math.radians(g('node', 0))
        self.size, self.spin, self.tilt = g('size', 10), g('spin', 24000), g('tilt', 0)

    def pos(self, t):
        if self.id == 'sun':
            return np.array([0, PLANE_Y, 0])
        a = self.phase + 2 * math.pi * ((t / self.period) % 1)
        x, z = math.cos(a) * self.orbit, math.sin(a) * self.orbit
        y = self.yamp * math.sin(a - self.node)
        if self.parent:
            return self.parent.pos(t) + np.array([x, y, z])
        return np.array([x, PLANE_Y + y, z])

    def spin_deg(self, t):
        return 360 * ((t / self.spin) % 1)


def comet_pos(c, t):
    q, Q = c['q'], c['Q']
    a, e = (q + Q) / 2, (Q - q) / (Q + q)
    b = a * math.sqrt(1 - e * e)
    m = 2 * math.pi * ((t / c['period'] + c['phase']) % 1)
    E = m + e * math.sin(m)
    for _ in range(12):
        E -= (E - e * math.sin(E) - m) / (1 - e * math.cos(E))
    x, z = a * (math.cos(E) - e), b * math.sin(E)
    w = math.radians(c['omega'])
    return np.array([x * math.cos(w) - z * math.sin(w), PLANE_Y + c['yAmp'] * math.sin(E), x * math.sin(w) + z * math.cos(w)])


def rot_mats(tilt, spin):
    a, b = math.radians(spin), math.radians(tilt)
    ry = np.array([[math.cos(a), 0, math.sin(a)], [0, 1, 0], [-math.sin(a), 0, math.cos(a)]])
    rz = np.array([[math.cos(b), -math.sin(b), 0], [math.sin(b), math.cos(b), 0], [0, 0, 1]])
    return rz @ ry


FACE_N = {'n': (0, 0, -1), 's': (0, 0, 1), 'e': (1, 0, 0), 'w': (-1, 0, 0), 'u': (0, 1, 0), 'd': (0, -1, 0)}


def face_uv(p):
    """p — точка на кубе [-1,1]^3 → (индекс грани, u, v) по той же развёртке, что в моделях."""
    ax = np.abs(p)
    k = np.argmax(ax, -1)
    x, y, z = p[..., 0], p[..., 1], p[..., 2]
    face = np.where(k == 0, np.where(x > 0, 2, 3), np.where(k == 1, np.where(y > 0, 4, 5), np.where(z > 0, 1, 0)))
    a = np.select([face == 0, face == 1, face == 2, face == 3, face == 4, face == 5], [-x, x, -z, z, x, x])
    b = np.select([face == 0, face == 1, face == 2, face == 3, face == 4, face == 5], [-y, -y, -y, -y, z, -z])
    return face, (a + 1) / 2, (b + 1) / 2


def brightness(n_world, to_sun, ambient):
    d = float(n_world @ to_sun)
    b = max(0.0, (d + 0.18) / 1.18) ** 0.85
    return max(ambient, b)


class Frame:
    def __init__(self, cam, target):
        self.cam = np.array(cam, float)
        f = np.array(target, float) - self.cam
        f /= np.linalg.norm(f)
        r = np.cross(f, [0, 1, 0]); r /= np.linalg.norm(r)
        u = np.cross(r, f)
        th = math.tan(math.radians(FOV) / 2)
        xs = (np.arange(W) + 0.5) / W * 2 - 1
        ys = 1 - (np.arange(H) + 0.5) / H * 2
        X, Y = np.meshgrid(xs * th * W / H, ys * th)
        d = f + X[..., None] * r + Y[..., None] * u
        self.dir = d / np.linalg.norm(d, axis=-1, keepdims=True)
        self.f, self.r, self.u, self.th = f, r, u, th
        self.img = np.zeros((H, W, 3), np.float32)
        self.depth = np.full((H, W), np.inf, np.float32)

    def sky(self):
        for name, _ in [(f'wall_{k}', 0) for k in range(8)] + [('cap_top', 0), ('cap_bottom', 0)]:
            t = tex(f'sky/{name}')
            row, col, ok = G.project_to_panel(name, self.dir.reshape(-1, 3))
            flat = self.img.reshape(-1, 3)
            flat[ok] = t[row[ok], col[ok], :3]

    def cube(self, center, size, tilt, spin, texs, light=None, alpha=False, emissive=False):
        """texs: dict face->rgba image. light: dict face->brightness (None = полная яркость)."""
        R = rot_mats(tilt, spin)
        half = np.broadcast_to(np.asarray(size, float), (3,)) / 2
        oc = R.T @ (self.cam - center) / half
        d = self.dir @ R / half
        size = float(np.max(half) * 2)
        rel = center - self.cam
        dist = np.linalg.norm(rel)
        if dist > size:
            c = self.dir @ (rel / dist)
            mask = c > math.cos(math.asin(min(1, size * 0.9 / dist)))
            if not mask.any():
                return
        else:
            mask = np.ones((H, W), bool)
        dm = d[mask]
        inv = 1 / np.where(np.abs(dm) < 1e-12, 1e-12, dm)
        t1, t2 = (-1 - oc) * inv, (1 - oc) * inv
        tn = np.minimum(t1, t2).max(-1)
        tf = np.maximum(t1, t2).min(-1)
        hit = (tn <= tf) & (tf > 0) & (tn > 0)
        if not hit.any():
            return
        p = oc + dm[hit] * tn[hit][:, None]
        face, u, v = face_uv(np.clip(p, -1, 1))
        col = np.zeros((len(p), 4), np.float32)
        for fi, fname in enumerate(G.FACES):
            m = face == fi
            if m.any() and fname in texs:
                col[m] = sample(texs[fname], u[m], 1 - (1 - v[m]))
                if light is not None:
                    col[m, :3] *= light[fname]
        tt = (tn[hit] * size / 2) * 1.0
        idx = np.flatnonzero(mask)[hit]
        flat = self.img.reshape(-1, 3)
        dep = self.depth.reshape(-1)
        dist_w = tn[hit]
        front = dist_w < dep[idx]
        idx, col, dist_w = idx[front], col[front], dist_w[front]
        if alpha:
            a = col[:, 3:4]
            flat[idx] = flat[idx] * (1 - a) + col[:, :3] * a
        else:
            flat[idx] = col[:, :3]
            dep[idx] = dist_w

    def ring(self, center, size, tilt, spin, img, inner, outer):
        R = rot_mats(tilt, spin)
        oc = R.T @ (self.cam - center)
        d = self.dir @ R
        with np.errstate(divide='ignore', invalid='ignore'):
            t = -oc[1] / d[..., 1]
        p = oc + d * t[..., None]
        ext = size * outer
        ok = (t > 0) & (np.abs(p[..., 0]) < ext) & (np.abs(p[..., 2]) < ext)
        if not ok.any():
            return
        u = (p[..., 0][ok] / ext + 1) / 2
        v = (p[..., 2][ok] / ext + 1) / 2
        c = sample(img, u, v)
        dist = t[ok]
        flat = self.img.reshape(-1, 3)
        dep = self.depth.reshape(-1)
        idx = np.flatnonzero(ok)
        front = dist < dep[idx]
        idx, c = idx[front], c[front]
        a = c[:, 3:4]
        flat[idx] = flat[idx] * (1 - a) + c[:, :3] * a

    def glow(self, center, size, color, amp=1.0):
        rel = np.asarray(center, float) - self.cam
        z = rel @ self.f
        if z <= 0.1:
            return
        x, y = rel @ self.r, rel @ self.u
        sx = (x / (z * self.th) * H / W + 1) / 2 * W
        sy = (1 - y / (z * self.th)) / 2 * H
        rad = size / 2 / (z * self.th) * H / 2
        if rad < 0.3 or sx < -rad or sx > W + rad or sy < -rad or sy > H + rad:
            return
        x0, x1 = int(max(0, sx - rad)), int(min(W, sx + rad + 1))
        y0, y1 = int(max(0, sy - rad)), int(min(H, sy + rad + 1))
        if x0 >= x1 or y0 >= y1:
            return
        X, Y = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
        r = np.sqrt((X - sx) ** 2 + (Y - sy) ** 2) / max(rad, 1e-3)
        a = (np.exp(-(r / 0.55) ** 2 * 2.0) * 0.85 + np.exp(-(r / 0.16) ** 2 * 2.5) * 0.4) * amp
        a = np.clip(a * G.smooth(1.0, 0.8, r), 0, 1)
        dist = np.linalg.norm(rel)
        hidden = self.depth[y0:y1, x0:x1] < dist - size * 0.1
        a = np.where(hidden, 0, a)[..., None]
        reg = self.img[y0:y1, x0:x1]
        self.img[y0:y1, x0:x1] = reg * (1 - a) + np.asarray(color) * a

    def sprite(self, center, size, img, amp=1.0):
        """Спрайт с текстурой (как в игре), повернутый к камере."""
        rel = np.asarray(center, float) - self.cam
        z = rel @ self.f
        if z <= 0.1:
            return
        x, y = rel @ self.r, rel @ self.u
        sx = (x / (z * self.th) * H / W + 1) / 2 * W
        sy = (1 - y / (z * self.th)) / 2 * H
        rad = size / 2 / (z * self.th) * H / 2
        if rad < 0.3 or sx < -rad or sx > W + rad or sy < -rad or sy > H + rad:
            return
        x0, x1 = int(max(0, sx - rad)), int(min(W, sx + rad + 1))
        y0, y1 = int(max(0, sy - rad)), int(min(H, sy + rad + 1))
        if x0 >= x1 or y0 >= y1:
            return
        X, Y = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
        u = (X - sx) / (2 * rad) + 0.5
        v = (Y - sy) / (2 * rad) + 0.5
        c = sample(img, u, v)
        a = (c[..., 3:4] * amp) * (np.linalg.norm(rel) < self.depth[y0:y1, x0:x1])[..., None]
        reg = self.img[y0:y1, x0:x1]
        self.img[y0:y1, x0:x1] = reg * (1 - a) + c[..., :3] * a

    def disc(self, center, radius):
        """Чёрный круг (горизонт событий) с записью глубины."""
        rel = np.asarray(center, float) - self.cam
        z = rel @ self.f
        if z <= 1:
            return
        x, y = rel @ self.r, rel @ self.u
        sx = (x / (z * self.th) * H / W + 1) / 2 * W
        sy = (1 - y / (z * self.th)) / 2 * H
        rad = radius / (z * self.th) * H / 2
        X, Y = np.meshgrid(np.arange(W) + 0.5, np.arange(H) + 0.5)
        m = (X - sx) ** 2 + (Y - sy) ** 2 < rad ** 2
        self.img[m] = 0
        self.depth[m] = np.minimum(self.depth[m], np.linalg.norm(rel))

    def save(self, name):
        img = np.clip(self.img, 0, 1) ** (1 / 1.0)
        Image.fromarray((img * 255).astype(np.uint8)).save(os.path.join(OUT, f'scene_{name}.png'))


def load():
    sysj = G.SYS
    sun = Body(dict(sysj['sun'], orbit=0))
    bodies, by = [], {}
    for o in sysj['bodies']:
        b = Body(o, by.get(o.get('parent')))
        bodies.append(b)
        by[b.id] = b
    return sun, bodies, by, sysj['comets']


def render(name, cam, target, t):
    sun, bodies, by, comets = load()
    fr = Frame(cam, target)
    fr.sky()
    sunp = sun.pos(t)
    # Солнце
    sun_tex = {f: tex(f'sun/sun_{f}') for f in G.FACES}
    fr.glow(sunp, sun.size * 4.6, G.hexc('#ff8a2a'), 0.55)
    fr.glow(sunp, sun.size * 2.5, G.hexc('#ffc24a'), 1.0)
    fr.cube(sunp, sun.size, sun.tilt, sun.spin_deg(t), sun_tex)
    fr.cube(sunp, sun.size * 1.1, sun.tilt, -sun.spin_deg(t) * 1.7, {f: tex('sun/corona_a') for f in G.FACES}, alpha=True)
    fr.cube(sunp, sun.size * 1.24, sun.tilt, sun.spin_deg(t) * 2.3 + 40, {f: tex('sun/corona_b') for f in G.FACES}, alpha=True)
    fr.glow(sunp, sun.size * 1.6, G.hexc('#fff4c2'), 0.55)
    # тела — от дальних к ближним
    order = sorted(bodies, key=lambda b: -np.linalg.norm(b.pos(t) - fr.cam))
    for b in order:
        o = b.o
        p = b.pos(t)
        to_sun = sunp - p
        to_sun /= np.linalg.norm(to_sun)
        spin = b.spin_deg(t)
        R = rot_mats(b.tilt, spin)
        amb = 0.05 if o.get('atmo') else 0.0
        light = {}
        for f in G.FACES:
            bb = brightness(R @ np.array(FACE_N[f], float), to_sun, amb)
            light[f] = 0.05 + 0.95 * bb
        if b.id == 'earth':
            fr.glow(p, b.size * 1.7, G.hexc('#8fc8ff'), 0.3)
        if b.id == 'florana':
            fr.glow(p, b.size * 2.4, G.hexc('#c27dff'), 0.3)
            fr.glow(p, b.size * 1.6, G.hexc('#c27dff'), 0.55)
        fr.cube(p, b.size * b.shape, b.tilt, spin, {f: tex(f'b/{b.id}_{f}') for f in G.FACES}, light)
        if o.get('lights'):
            lt = {f: tex(f'b/{b.id}_lights_{f}') for f in G.FACES if light[f] < 0.36}
            if lt:
                fr.cube(p, b.size * 1.006, b.tilt, spin, lt, alpha=True)
        if o.get('clouds'):
            cs = spin * 1.12 + 25
            Rc = rot_mats(b.tilt, cs)
            cl = {f: tex(f'b/{b.id}_clouds_{f}').copy() for f in G.FACES}
            for f in G.FACES:
                cl[f][..., :3] *= 0.08 + 0.92 * brightness(Rc @ np.array(FACE_N[f], float), to_sun, 0.08)
            fr.cube(p, b.size * 1.035, b.tilt, cs, cl, alpha=True)
        if o.get('atmo'):
            at = {}
            for f in G.FACES:
                img = tex(f'b/{b.id}_atmo').copy()
                img[..., :3] *= max(0.4, light[f])
                at[f] = img
            fr.cube(p, b.size * o.get('atmoScale', 1.08) * b.shape, b.tilt, spin, at, alpha=True)
        if o.get('ring'):
            fr.ring(p, b.size, b.tilt, spin * 0.05, tex(f'ring/{o["ring"]}'), o.get('ringInner', 0.7), o.get('ringOuter', 1.2))
    # кометы
    for c in comets:
        p = comet_pos(c, t)
        q = comet_pos(c, t + 60)
        r = np.linalg.norm(p - sunp)
        away = (p - sunp) / r
        vel = (q - p) / max(1e-6, np.linalg.norm(q - p))
        act = min(1.8, (3600 / max(400, r)) ** 1.6)
        ln = c['tail'] * max(60, min(4200, 1300 * act))
        n = 34
        for ion in (True, False):
            for i in range(n):
                f = (i + 0.5) / n
                dd = away if ion else (away - vel * 0.5 * f)
                dd = dd / np.linalg.norm(dd)
                L = ln * f * (1.0 if ion else 0.75)
                gap = ln * (1.0 if ion else 0.75) / n
                sz = max((10 + 70 * f if ion else 18 + 160 * f) * c['tail'] * (0.5 + 0.5 * act), gap * (2.0 if ion else 2.6))
                amp = 0.55 if f < 0.15 else 0.3 if f < 0.5 else 0.14
                fr.glow(p + dd * L, sz, G.hexc('#7fc8ff' if ion else '#fff1d0'), amp)
        fr.glow(p, c['size'] * 7 + 40 * act, G.hexc('#cdf5ff'), 0.3)
        fr.glow(p, c['size'] * 2.6 + 10 * act, G.hexc('#ffffff'), 1.0)
    for dpo in G.SYS['deep']:
        deep(fr, dpo, t)
    fr.save(name)
    print('scene', name)


def deep(fr, o, t):
    c = np.array(o['pos'], float)
    if np.linalg.norm(c - fr.cam) < 1:
        return
    col = G.hexc(o.get('color', '#ffffff'))
    sz = o['size']
    if o['type'] == 'black_hole':
        fr.glow(c, sz * 9, col, 0.14)
        fr.glow(c, sz * 2.9, col, 0.3)
        fr.disc(c, sz * 0.51)
        fr.ring(c, sz, 12, t * 0.9, tex('deep/accretion'), 0.3, o['disk'])
        fr.glow(c, sz * 1.2, np.ones(3), 0.2)
    elif o['type'] == 'quasar':
        fr.glow(c, sz * 14, col, 0.14)
        fr.glow(c, sz * 5, col, 0.3)
        fr.ring(c, sz, 8, t * 0.25, tex('deep/quasar_disk'), 0.18, o['disk'])
        fr.glow(c, sz * 1.6, np.ones(3), 1.0)
        for side in (-1, 1):
            for i in range(30):
                f = (i + 0.5) / 30
                fr.glow(c + np.array([-0.139, 0.99, 0]) * o['jet'] * f * side, max(sz * 0.5 + o['jet'] * 0.07 * f, o['jet'] / 30 * 2.2), col, 0.55 if f < 0.2 else 0.3 if f < 0.6 else 0.14)
    elif o['type'] == 'nebula':
        rng = np.random.default_rng(5)
        cols = ['neb_pink', 'neb_pink', 'neb_violet', 'neb_blue', 'neb_teal', 'neb_gold']
        pts = []
        for i in range(420):
            s_ = rng.uniform(-1, 1)
            sp = 0.12 + 0.12 * (1 - abs(s_))
            p = np.array([s_ * 0.42 + rng.normal() * sp, np.sin(s_ * 3) * 0.08 + rng.normal() * sp * 0.55, np.sin(s_ * 2.2) * 0.18 + rng.normal() * sp])
            al = rng.random()
            edge = abs(s_) + math.hypot(p[1], p[2]) * 1.5
            ci = (5 if rng.random() < 0.6 else 0) if edge < 0.25 else ((0 if rng.random() < 0.6 else 2) if edge < 0.6 else (3 if rng.random() < 0.5 else 4))
            pts.append((c + p * o['size'], rng.uniform(0.10, 0.26) * o['size'], cols[ci], 'b' if al < 0.2 else 'c' if al < 0.65 else 'd'))
        pts.sort(key=lambda q: -np.linalg.norm(q[0] - fr.cam))
        for p, size, cc, a in pts:
            fr.sprite(p, size, tex(f'fx/puff_{cc}_{a}'))
    elif o['type'] == 'binary':
        a = 2 * math.pi * ((t / o['period']) % 1)
        for k, (cc, rf, sf) in enumerate(((o['color'], 0.42, 1.0), (o['color2'], 0.58, 0.76))):
            ang = a + (0 if k == 0 else math.pi)
            p = c + np.array([math.cos(ang), math.sin(ang) * 0.25, math.sin(ang)]) * o['sep'] * rf
            fr.glow(p, sz * sf * 5, G.hexc(cc), 0.55)
            fr.glow(p, sz * sf * 2.2, G.hexc(cc), 1.0)
            fr.glow(p, sz * sf * 1.1, np.ones(3), 1.0)
    elif o['type'] == 'pulsar':
        fr.glow(c, sz * 40, col, 0.55)
        for side in (-1, 1):
            for i in range(22):
                f = (i + 0.5) / 22
                fr.glow(c + np.array([0.4, 0.82, 0.4]) * o['beam'] * f * side, sz * 2 + o['beam'] * 0.12 * f, col, 0.55 if f < 0.3 else 0.14)


def main():
    t = 0.0
    if '--t' in sys.argv:
        t = float(sys.argv[sys.argv.index('--t') + 1])
    os.makedirs(OUT, exist_ok=True)
    sun, bodies, by, comets = load()

    def near(bid, dx, dy, dz):
        p = by[bid].pos(t)
        return (p + np.array([dx, dy, dz])).tolist(), p.tolist()

    render('01_earth', *near('earth', -110, 30, 70), t)
    render('02_florana', *near('florana', -140, 35, 90), t)
    render('03_saturn', *near('saturn', 380, 120, -420), t)
    render('04_jupiter', *near('jupiter', 520, 60, -380), t)
    render('05_sun', [1000, 300, 760], [0, PLANE_Y, 0], t)
    render('06_sakura', *near('sakura', -40, 12, 30), t)
    render('08_black_hole', [-42000 + 4200, 1300, 31000 + 2600], [-42000, 0, 31000], t)
    render('09_nebula', [64000 - 9000, 1500, 52000 - 9000], [64000, 0, 52000], t)
    render('10_quasar', [110000 - 26000, 300 + 7000, -70000 + 14000], [110000, 300 + 4000, -70000], t)
    render('11_binary', [-88000 + 9000, 2200, 76000 + 6000], [-88000, 200, 76000], t)
    # комета ближе всех к Солнцу — сбоку
    best = min(comets, key=lambda c: np.linalg.norm(comet_pos(c, t) - [0, PLANE_Y, 0]))
    p = comet_pos(best, t)
    r = np.linalg.norm(p[[0, 2]])
    nrm = np.array([-p[2] / r, 0, p[0] / r])
    render('07_comet', (p + nrm * 1400 + [0, 120, 0]).tolist(), (p + (p - [0, PLANE_Y, 0]) / np.linalg.norm(p - [0, PLANE_Y, 0]) * 900).tolist(), t)


if __name__ == '__main__':
    main()
