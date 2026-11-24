#!/usr/bin/env python3
"""
Built-in Phase dungeons as vanilla jigsaw structures (NBT templates + JSON).
Every dungeon is: structure/<name>.nbt files + template_pool + structure + structure_set + biome tag + loot.
New dungeons can be added the same way by hand (see DUNGEONS.md) - no Java needed.
"""
import gzip
import io
import json
import os
import random
import struct

BASE = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'data', 'phaseworld')
DATA_VERSION = 5023  # Minecraft 26.3


# ------------------------------------------------------------------------------------ minimal NBT writer
class Int(int):
    pass


class Long(int):
    pass


class Short(int):
    pass


def _name(s):
    b = s.encode('utf-8')
    return struct.pack('>H', len(b)) + b


def _payload(v):
    if isinstance(v, Long):
        return 4, struct.pack('>q', v)
    if isinstance(v, Short):
        return 2, struct.pack('>h', v)
    if isinstance(v, bool):
        return 1, struct.pack('>b', int(v))
    if isinstance(v, int):
        return 3, struct.pack('>i', v)
    if isinstance(v, float):
        return 6, struct.pack('>d', v)
    if isinstance(v, str):
        return 8, _name(v)
    if isinstance(v, list):
        if not v:
            return 9, struct.pack('>bi', 0, 0)
        tid = _payload(v[0])[0]
        return 9, struct.pack('>bi', tid, len(v)) + b''.join(_payload(x)[1] for x in v)
    if isinstance(v, dict):
        out = b''
        for k, x in v.items():
            tid, pl = _payload(x)
            out += struct.pack('>b', tid) + _name(k) + pl
        return 10, out + b'\x00'
    raise TypeError(type(v))


def nbt_bytes(root):
    tid, pl = _payload(root)
    raw = struct.pack('>b', 10) + _name('') + pl
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode='wb', mtime=0) as g:
        g.write(raw)
    return buf.getvalue()


# ------------------------------------------------------------------------------------ builder
class Build:
    def __init__(self, sx, sy, sz, seed):
        self.size = (sx, sy, sz)
        self.b = {}
        self.r = random.Random(seed)

    def set(self, x, y, z, name, props=None, nbt=None):
        if 0 <= x < self.size[0] and 0 <= y < self.size[1] and 0 <= z < self.size[2]:
            self.b[(x, y, z)] = (name, tuple(sorted((props or {}).items())), nbt)

    def fill(self, x0, y0, z0, x1, y1, z1, name, props=None):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    self.set(x, y, z, name, props)

    def shell(self, x0, y0, z0, x1, y1, z1, wall):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                for z in range(z0, z1 + 1):
                    edge = x in (x0, x1) or y in (y0, y1) or z in (z0, z1)
                    self.set(x, y, z, wall() if edge else 'minecraft:air')

    def get(self, x, y, z):
        v = self.b.get((x, y, z))
        return v[0] if v else None

    def nbt(self):
        palette, index, blocks = [], {}, []
        for (x, y, z), (name, props, nbt) in sorted(self.b.items()):
            key = (name, props)
            if key not in index:
                index[key] = len(palette)
                entry = {'Name': name}
                if props:
                    entry['Properties'] = dict(props)
                palette.append(entry)
            blk = {'pos': [Int(x), Int(y), Int(z)], 'state': Int(index[key])}
            if nbt:
                blk['nbt'] = nbt
            blocks.append(blk)
        return {'DataVersion': Int(DATA_VERSION), 'size': [Int(v) for v in self.size], 'palette': palette,
                'blocks': blocks, 'entities': []}


def chest(loot, facing='north'):
    return ('minecraft:chest', {'facing': facing, 'type': 'single', 'waterlogged': 'false'},
            {'id': 'minecraft:chest', 'LootTable': loot})


def spawner(mob):
    return ('minecraft:spawner', None, {'id': 'minecraft:mob_spawner', 'SpawnData': {'entity': {'id': mob}},
                                         'MinSpawnDelay': Int(220), 'MaxSpawnDelay': Int(700), 'SpawnCount': Short(3),
                                         'MaxNearbyEntities': Short(5), 'RequiredPlayerRange': Short(14), 'SpawnRange': Short(4),
                                         'Delay': Short(20)})


def put(b, x, y, z, spec):
    b.set(x, y, z, spec[0], spec[1], spec[2])


# ------------------------------------------------------------------------------------ forgotten shrine
def shrine(seed):
    W, H, L = 23, 12, 29
    b = Build(W, H, L, seed)
    r = b.r

    def brick():
        v = r.random()
        return 'phaseworld:mossy_shrine_bricks' if v < 0.22 else 'phaseworld:cracked_shrine_bricks' if v < 0.36 else 'phaseworld:shrine_bricks'
    b.shell(0, 0, 0, W - 1, H - 1, L - 1, brick)
    # floor: stone aisle with tatami prayer area near the altar
    b.fill(1, 0, 1, W - 2, 0, L - 2, 'minecraft:polished_andesite')
    b.fill(8, 0, 18, 14, 0, 25, 'phaseworld:tatami')
    # pillars of vermilion wood along the hall
    for z in range(4, 24, 5):
        for x in (4, W - 5):
            b.fill(x, 1, z, x, H - 2, z, 'phaseworld:vermilion_planks')
            b.set(x, 1, z + 1 if z + 1 < L - 1 else z, 'minecraft:air')
    # torii gate across the aisle
    tz = 8
    for x in (8, 14):
        b.fill(x, 1, tz, x, 7, tz, 'phaseworld:vermilion_planks')
    b.fill(7, 7, tz, 15, 7, tz, 'phaseworld:vermilion_planks')
    b.fill(6, 8, tz, 16, 8, tz, 'minecraft:blackstone')
    b.fill(9, 6, tz, 13, 6, tz, 'phaseworld:vermilion_planks')
    # altar (honden)
    b.fill(7, 1, 24, 15, 1, 27, 'phaseworld:shrine_bricks')
    b.fill(8, 2, 25, 14, 2, 27, 'phaseworld:vermilion_planks')
    put(b, 11, 3, 26, chest('phaseworld:chests/shrine_altar'))
    put(b, 9, 3, 26, ('phaseworld:stone_lantern', None, None))
    put(b, 13, 3, 26, ('phaseworld:stone_lantern', None, None))
    # stone lanterns along the aisle
    for z in range(12, 23, 5):
        for x in (8, 14):
            if r.random() < 0.85:
                put(b, x, 1, z, ('phaseworld:stone_lantern', None, None))
    # hanging lanterns
    for z in range(3, 26, 6):
        b.set(11, H - 2, z, 'minecraft:lantern', {'hanging': 'true', 'waterlogged': 'false'})
    # side cells with the guardians and supplies
    for side, xs in (('w', (1, 3)), ('e', (W - 4, W - 2))):
        for z0 in (2, 13):
            put(b, (xs[0] + xs[1]) // 2, 1, z0 + 3, spawner('minecraft:skeleton' if side == 'w' else 'minecraft:zombie'))
            put(b, xs[0], 1, z0 + 1, chest('phaseworld:chests/shrine', 'east' if side == 'w' else 'west'))
            break
    # entrances on both long walls and the front
    for (x0, x1, z0, z1) in ((0, 0, 13, 15), (W - 1, W - 1, 13, 15), (10, 12, 0, 0)):
        b.fill(x0, 1, z0, x1, 4, z1, 'minecraft:air')
    # time has passed: cobwebs, collapsed spots
    for _ in range(60):
        x, y, z = r.randrange(1, W - 1), r.randrange(1, H - 1), r.randrange(1, L - 1)
        if b.get(x, y, z) == 'minecraft:air' and (y > 6 or r.random() < 0.3):
            b.set(x, y, z, 'minecraft:cobweb')
    for _ in range(4):
        x, z = r.randrange(2, W - 2), r.randrange(2, L - 6)
        b.fill(x, H - 1, z, min(W - 2, x + r.randint(0, 2)), H - 1, min(L - 2, z + r.randint(0, 2)), 'minecraft:air')
        b.set(x, 1, z, 'minecraft:gravel')
    return b


# ------------------------------------------------------------------------------------ volcano forge
def forge(seed):
    W, H, L = 21, 11, 21
    b = Build(W, H, L, seed)
    r = b.r

    def wall():
        v = r.random()
        return 'minecraft:basalt' if v < 0.3 else 'phaseworld:scoria' if v < 0.45 else 'minecraft:polished_blackstone_bricks'
    b.shell(0, 0, 0, W - 1, H - 1, L - 1, wall)
    b.fill(1, 0, 1, W - 2, 0, L - 2, 'minecraft:polished_blackstone')
    # lava channel with two bridges
    b.fill(1, 0, 9, W - 2, 0, 11, 'minecraft:lava')
    b.fill(1, -0 + 0, 9, W - 2, 0, 11, 'minecraft:lava')
    for x0 in (5, 14):
        b.fill(x0, 0, 9, x0 + 1, 0, 11, 'minecraft:polished_blackstone_bricks')
    # forge corner
    b.set(3, 1, 3, 'minecraft:blast_furnace', {'facing': 'south', 'lit': 'false'})
    b.set(4, 1, 3, 'minecraft:blast_furnace', {'facing': 'south', 'lit': 'false'})
    b.set(6, 1, 3, 'minecraft:anvil', {'facing': 'east'})
    b.set(8, 1, 3, 'minecraft:smithing_table')
    b.set(2, 1, 5, 'minecraft:magma_block')
    b.set(2, 1, 6, 'minecraft:magma_block')
    put(b, 10, 1, 2, chest('phaseworld:chests/forge', 'south'))
    put(b, 10, 1, L - 3, chest('phaseworld:chests/forge_hoard', 'north'))
    put(b, 16, 1, 15, spawner('minecraft:magma_cube'))
    put(b, 4, 1, 15, spawner('minecraft:skeleton'))
    for z in (5, 15):
        for x in (1, W - 2):
            b.set(x, 3, z, 'minecraft:soul_lantern', {'hanging': 'false', 'waterlogged': 'false'})
    for (x0, x1, z0, z1) in ((0, 0, 4, 6), (W - 1, W - 1, 14, 16)):
        b.fill(x0, 1, z0, x1, 4, z1, 'minecraft:air')
    for _ in range(25):
        x, y, z = r.randrange(1, W - 1), r.randrange(5, H - 1), r.randrange(1, L - 1)
        if b.get(x, y, z) == 'minecraft:air':
            b.set(x, y, z, 'minecraft:cobweb')
    return b


# ------------------------------------------------------------------------------------ data files
def wj(path, obj):
    p = os.path.join(BASE, path + '.json')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w') as f:
        json.dump(obj, f, indent=2)


def no_spawns():
    return {k: {'bounding_box': 'piece', 'spawns': []} for k in
            ('ambient', 'axolotls', 'creature', 'misc', 'monster', 'underground_water_creature', 'water_ambient', 'water_creature')}


def dungeon(name, builder, variants, biomes_tag, height, spacing, separation, salt):
    files = []
    for v in range(variants):
        b = builder(1000 * salt + v)
        path = os.path.join(BASE, 'structure', 'dungeon', name, f'variant_{v}.nbt')
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, 'wb') as f:
            f.write(nbt_bytes(b.nbt()))
        files.append(f'phaseworld:dungeon/{name}/variant_{v}')
    wj(f'worldgen/template_pool/dungeon/{name}/start', {
        'fallback': 'minecraft:empty',
        'elements': [{'weight': 1, 'element': {'element_type': 'minecraft:single_pool_element', 'location': loc,
                                               'processors': 'phaseworld:dungeon_decay', 'projection': 'rigid'}} for loc in files]})
    wj(f'worldgen/structure/dungeon/{name}', {
        'type': 'minecraft:jigsaw', 'biomes': biomes_tag, 'step': 'underground_structures', 'spawn_overrides': no_spawns(),
        'terrain_adaptation': 'encapsulate', 'start_pool': f'phaseworld:dungeon/{name}/start', 'size': 1,
        'start_height': {'type': 'minecraft:uniform', 'min_inclusive': {'absolute': height[0]}, 'max_inclusive': {'absolute': height[1]}},
        'max_distance_from_center': 80, 'use_expansion_hack': False})
    wj(f'worldgen/structure_set/dungeon/{name}', {
        'placement': {'type': 'minecraft:random_spread', 'spacing': spacing, 'separation': separation, 'salt': salt},
        'structures': [{'structure': f'phaseworld:dungeon/{name}', 'weight': 1}]})


def loot(name, pools):
    wj(f'loot_table/chests/{name}', {'type': 'minecraft:chest', 'pools': pools, 'random_sequence': f'phaseworld:chests/{name}'})


def item(name, weight, lo=1, hi=1, extra=None):
    e = {'type': 'minecraft:item', 'name': name, 'weight': weight}
    mods = []
    if hi > 1:
        mods.append({'type': 'minecraft:set_count', 'count': {'type': 'minecraft:uniform', 'min': lo, 'max': hi}})
    if extra:
        mods.append(extra)
    if mods:
        e['modifier'] = mods if len(mods) > 1 else mods[0]
    return e


def rolls(lo, hi):
    return {'type': 'minecraft:uniform', 'min': lo, 'max': hi}


ENCH = {'type': 'minecraft:enchant_randomly', 'options': '#minecraft:on_random_loot'}


def main():
    wj('worldgen/processor_list/dungeon_decay', {'processors': [{'processor_type': 'minecraft:rule', 'rules': [
        {'input_predicate': {'predicate_type': 'minecraft:random_block_match', 'block': 'phaseworld:shrine_bricks', 'probability': 0.15},
         'location_predicate': {'predicate_type': 'minecraft:always_true'}, 'output_state': 'phaseworld:mossy_shrine_bricks'},
        {'input_predicate': {'predicate_type': 'minecraft:random_block_match', 'block': 'phaseworld:shrine_bricks', 'probability': 0.1},
         'location_predicate': {'predicate_type': 'minecraft:always_true'}, 'output_state': 'phaseworld:cracked_shrine_bricks'},
        {'input_predicate': {'predicate_type': 'minecraft:random_block_match', 'block': 'minecraft:polished_blackstone_bricks', 'probability': 0.2},
         'location_predicate': {'predicate_type': 'minecraft:always_true'}, 'output_state': 'minecraft:cracked_polished_blackstone_bricks'}]}]})

    dungeon('forgotten_shrine', shrine, 3, '#phaseworld:has_dungeon/forgotten_shrine', (-40, 40), 30, 12, 71503211)
    dungeon('volcano_forge', forge, 3, '#phaseworld:has_dungeon/volcano_forge', (-30, 120), 16, 6, 71503212)
    wj('tags/worldgen/biome/has_dungeon/forgotten_shrine', {'values': ['#phaseworld:caves']})
    wj('tags/worldgen/biome/has_dungeon/volcano_forge', {'values': ['phaseworld:magma_tubes']})

    loot('shrine', [
        {'rolls': rolls(2, 5), 'entries': [item('phaseworld:onigiri', 20, 1, 4), item('phaseworld:dango', 15, 1, 3), item('phaseworld:rice', 20, 2, 8),
                                            item('phaseworld:tea_seeds', 12, 1, 4), item('phaseworld:nori', 12, 2, 6), item('minecraft:candle', 10, 1, 4),
                                            item('minecraft:paper', 10, 2, 6), item('phaseworld:wasabi_root', 8, 1, 2)]},
        {'rolls': rolls(1, 3), 'entries': [item('minecraft:iron_ingot', 20, 1, 5), item('minecraft:gold_ingot', 10, 1, 4), item('minecraft:emerald', 8, 1, 3),
                                            item('minecraft:book', 8, 1, 1, ENCH), item('minecraft:name_tag', 5), item('minecraft:golden_apple', 4)]}])
    loot('shrine_altar', [
        {'rolls': rolls(3, 5), 'entries': [item('phaseworld:sushi_set', 10, 1, 2), item('phaseworld:matcha_mochi', 12, 1, 3),
                                            item('phaseworld:eclipse_crystal_cluster', 10, 1, 3), item('minecraft:emerald', 14, 2, 6),
                                            item('minecraft:gold_ingot', 14, 2, 6), item('minecraft:diamond', 5, 1, 2),
                                            item('minecraft:book', 10, 1, 1, ENCH), item('minecraft:golden_apple', 6),
                                            item('minecraft:enchanted_golden_apple', 1), item('minecraft:experience_bottle', 8, 2, 6)]}])
    loot('forge', [
        {'rolls': rolls(3, 6), 'entries': [item('minecraft:iron_ingot', 25, 2, 8), item('minecraft:gold_ingot', 15, 1, 5),
                                            item('minecraft:raw_iron', 15, 2, 6), item('minecraft:coal', 15, 4, 12), item('minecraft:obsidian', 8, 1, 4),
                                            item('minecraft:flint_and_steel', 5), item('minecraft:iron_pickaxe', 4, 1, 1, ENCH),
                                            item('minecraft:iron_sword', 4, 1, 1, ENCH), item('phaseworld:scoria', 6, 4, 16)]}])
    loot('forge_hoard', [
        {'rolls': rolls(2, 4), 'entries': [item('minecraft:diamond', 10, 1, 3), item('minecraft:gold_block', 4), item('minecraft:iron_block', 6),
                                            item('minecraft:emerald', 10, 2, 5), item('minecraft:diamond_pickaxe', 2, 1, 1, ENCH),
                                            item('minecraft:book', 8, 1, 1, ENCH), item('minecraft:golden_apple', 6),
                                            item('phaseworld:eclipse_crystal_block', 6, 1, 3)]}])
    print('dungeons: 2 types x 3 variants')


if __name__ == '__main__':
    main()
