#!/usr/bin/env python3
"""
Generates Phase World worldgen data (biomes, surface rules, features, ores, noise settings).
Vanilla 26.3 JSON (misode/mcmeta tag 26.3-data) is used as the template so formats always match.
Usage: python3 tools/gen_worldgen.py <mcmeta-26.3-data-dir>
"""
import copy
import json
import os
import sys

VAN = sys.argv[1] if len(sys.argv) > 1 else '/home/claude/ref/mcdata'
ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources', 'data')
NS = 'phaseworld'
SEA = 63
WORLD_MIN, WORLD_HEIGHT = -64, 1360   # top block y = 1295


def van(path):
    with open(os.path.join(VAN, 'data', 'minecraft', path + '.json')) as f:
        return json.load(f)


def write(ns, path, obj):
    p = os.path.join(ROOT, ns, path + '.json')
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, 'w') as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)


def P(name):
    return f'{NS}:{name}'


# --------------------------------------------------------------------------------------------- dimension
def dimension():
    dt = van('dimension_type/overworld')
    dt['height'] = WORLD_HEIGHT
    dt['logical_height'] = WORLD_HEIGHT
    dt['min_y'] = WORLD_MIN
    # clouds wrap the ring mountains; Fuji stands far above them
    dt['attributes']['minecraft:visual/cloud_height'] = 330.33
    write('minecraft', 'dimension_type/overworld', dt)

    biomes = {b: P(b) for b in BIOMES}
    gen = {
        'type': 'minecraft:noise',
        'biome_source': {'type': P('phase'), 'biomes': biomes},
        'settings': P('overworld'),
    }
    preset = van('worldgen/world_preset/normal')
    preset['dimensions']['minecraft:overworld']['generator'] = gen
    # the Phase map replaces the default world type (that is what a server creates)
    write('minecraft', 'worldgen/world_preset/normal', preset)


# --------------------------------------------------------------------------------------------- noise router
def layout(kind):
    return {'type': P('layout'), 'kind': kind}


def replace_refs(node, mapping):
    if isinstance(node, str):
        return mapping.get(node, node)
    if isinstance(node, list):
        return [replace_refs(x, mapping) for x in node]
    if isinstance(node, dict):
        return {k: replace_refs(v, mapping) for k, v in node.items()}
    return node


def noise_router():
    for k in ['surface', 'biome', 'spawn', 'continents', 'depth']:
        write(NS, f'worldgen/density_function/{k}', layout(k))
    write(NS, 'worldgen/density_function/terrain', {'type': 'minecraft:cache', 'input': layout('terrain')})
    write(NS, 'worldgen/density_function/caverns', layout('caverns'))

    fd = van('worldgen/density_function/overworld/final_density')
    s = json.dumps(fd)
    s = s.replace('"minecraft:overworld/sloped_cheese"', f'"{P("terrain")}"')
    fd = json.loads(s)

    # walk: move the top "air lid" from 240..256 up to the new build limit, enlarge cheese caves,
    # and merge the giant caverns into the deep branch
    def walk(n):
        if isinstance(n, dict):
            if n.get('type') == 'minecraft:gradient' and n.get('from_coordinate') == 240:
                n['from_coordinate'] = 1272
                n['to_coordinate'] = 1290
            if n.get('type') == 'minecraft:noise' and n.get('noise') == 'minecraft:cave_cheese':
                n['xz_scale'] = 0.72
                n['y_scale'] = 0.5
            if n.get('type') == 'minecraft:range_choice' and n.get('input') == P('terrain'):
                deep = n['when_out_of_range']
                n['when_out_of_range'] = {'type': 'minecraft:min', 'left': deep, 'right': P('caverns')}
            for v in n.values():
                walk(v)
        elif isinstance(n, list):
            for v in n:
                walk(v)
    walk(fd)
    write(NS, 'worldgen/density_function/final_density', fd)

    ns = van('worldgen/noise_settings/overworld')
    ns['noise'] = {'min_y': WORLD_MIN, 'height': WORLD_HEIGHT}
    ns['material_rule'] = P('overworld')
    ns['noise_router'] = {
        'chunk_surface_level': {'type': 'minecraft:interpolated', 'cell_size_xz': 16, 'cell_size_y': 1, 'input': P('surface')},
        'continents': P('continents'),
        'depth': P('depth'),
        'erosion': 0.0,
        'final_density': P('final_density'),
        'ridges': 0.0,
        'temperature': P('biome'),
        'vegetation': 0.0,
    }
    ns['aquifers']['exclusion'] = layout('aquifer_exclusion')
    ns['aquifers']['surface_level'] = P('surface')
    ns['spawn_target'] = [{P('spawn'): [0.97, 1.0]}]
    ns['debug_functions'] = [
        {'function': P('final_density'), 'label': 'N'},
        {'function': P('surface'), 'label': 'H'},
        {'function': P('biome'), 'label': 'B'},
        {'function': P('continents'), 'label': 'C'},
    ]
    write(NS, 'worldgen/noise_settings/overworld', ns)


# --------------------------------------------------------------------------------------------- surface rules
def block(b, props=None):
    st = {'Name': b}
    if props:
        st['Properties'] = props
    return {'type': 'minecraft:block', 'result_state': b if not props else st}


def cond(c, then):
    return {'type': 'minecraft:condition', 'if_true': c, 'then_run': then}


def seq(*rules):
    return {'type': 'minecraft:sequence', 'sequence': list(rules)}


def biome_is(*names):
    return {'type': 'minecraft:biome', 'biome_is': [P(n) for n in names]}


def noise_band(lo, hi, noise='minecraft:surface'):
    return {'type': 'minecraft:noise_threshold', 'noise': noise, 'min_threshold': lo, 'max_threshold': hi}


def y_above(y, mult=0, add_stone=False):
    return {'type': 'minecraft:y_above', 'anchor': {'absolute': y}, 'surface_depth_multiplier': mult, 'add_stone_depth': add_stone}


def NOT(c):
    return {'type': 'minecraft:not', 'invert': c}


STEEP = {'type': 'minecraft:steep'}
UNDERWATER_FLOOR = seq(cond(noise_band(-0.25, 0.1), block('minecraft:clay')),
                       cond(noise_band(0.1, 1e9), block('minecraft:gravel')),
                       block('minecraft:sand'))

# top block / under block per biome (on dry land). Each is a material rule.
GRASS, DIRT = block('minecraft:grass_block'), block('minecraft:dirt')


def mix(*pairs):
    """pairs of (lo, hi, rule) on the surface noise, last plain rule is the default."""
    out = []
    for p in pairs:
        if isinstance(p, tuple):
            out.append(cond(noise_band(p[0], p[1]), p[2]))
        else:
            out.append(p)
    return seq(*out)


SURF = {
    'beach': (block('minecraft:sand'), block('minecraft:sand')),
    'black_sand_beach': (block(P('black_sand')), block(P('black_sand'))),
    'rocky_shore': (mix((-0.3, 0.25, block('minecraft:gravel')), block('minecraft:stone')), block('minecraft:stone')),
    'river': (GRASS, DIRT),
    'sakura_valley': (mix((0.55, 1e9, block('minecraft:coarse_dirt')), GRASS), DIRT),
    'momiji_highlands': (seq(cond(STEEP, mix((-1e9, 0, block('minecraft:andesite')), block('minecraft:stone'))),
                             mix((0.45, 1e9, block('minecraft:podzol')), GRASS)), DIRT),
    'sugi_forest': (seq(cond(STEEP, block('minecraft:mossy_cobblestone')),
                        mix((-0.95, -0.55, block('minecraft:moss_block')), (0.3, 1e9, block('minecraft:podzol')), GRASS)), DIRT),
    'bamboo_grove': (mix((0.2, 1e9, block('minecraft:podzol')), GRASS), DIRT),
    'rice_terraces': (GRASS, mix((0.4, 1e9, block('minecraft:mud')), DIRT)),
    'tea_hills': (mix((0.65, 1e9, block('minecraft:coarse_dirt')), GRASS), DIRT),
    'alpine_peaks': (seq(cond(STEEP, block('minecraft:stone')),
                         cond(y_above(SEA + 330, 2), block('minecraft:snow_block')),
                         cond(y_above(SEA + 210, 2), mix((-0.4, 0.3, block('minecraft:gravel')), block('minecraft:stone'))),
                         GRASS), seq(cond(y_above(SEA + 210, 2), block('minecraft:stone')), DIRT)),
    'snowy_highlands': (seq(cond(STEEP, block('minecraft:stone')),
                            cond(y_above(SEA + 140, 2), block('minecraft:snow_block')),
                            GRASS), DIRT),
    'onsen_valley': (seq(cond(STEEP, block('minecraft:tuff')),
                         mix((-1e9, -0.6, block('minecraft:tuff')), (-0.12, 0.0, block('minecraft:sulfur')),
                             (0.55, 1e9, block('minecraft:coarse_dirt')), GRASS)), mix((-0.2, 0.3, block('minecraft:tuff')), DIRT)),
    'pine_cliffs': (seq(cond(STEEP, mix((-1e9, 0.1, block('minecraft:stone')), block('minecraft:andesite'))), GRASS), DIRT),
    'reed_marsh': (mix((-0.4, 0.35, block('minecraft:mud')), GRASS), block('minecraft:mud')),
    'sand_dunes': (block('minecraft:sand'), block('minecraft:sand')),
    'frozen_summits': (seq(cond(STEEP, block('minecraft:packed_ice')), mix((0.35, 0.6, block('minecraft:powder_snow')), block('minecraft:snow_block'))),
                       block('minecraft:snow_block')),
    'jukai_forest': (mix((-1e9, -0.35, block('minecraft:moss_block')), (0.25, 0.55, block('minecraft:podzol')),
                         (0.75, 1e9, block(P('scoria'))), GRASS), mix((0.1, 1e9, block('minecraft:basalt')), DIRT)),
    'fuji_subalpine': (seq(cond(STEEP, block(P('scoria'))),
                           mix((-0.3, 0.05, block(P('volcanic_ash'))), (0.5, 1e9, block('minecraft:podzol')), GRASS)), DIRT),
    'fuji_slopes': (seq(cond(STEEP, mix((-1e9, 0.0, block(P('scoria'))), block('minecraft:basalt'))),
                        mix((-1e9, -0.45, block(P('black_sand'))), (-0.45, 0.2, block(P('scoria'))), (0.6, 1e9, block(P('pumice'))),
                            block(P('volcanic_ash')))),
                    mix((-0.1, 0.3, block(P('volcanic_ash'))), block(P('scoria')))),
    'fuji_summit': (seq(cond(STEEP, block(P('scoria'))), mix((0.45, 0.65, block('minecraft:powder_snow')), block('minecraft:snow_block'))),
                    block(P('scoria'))),
    'ocean': (block('minecraft:sand'), block('minecraft:sand')),
    'deep_ocean': (block('minecraft:gravel'), block('minecraft:gravel')),
    'inner_sea': (block('minecraft:sand'), block('minecraft:sand')),
}
SANDY_DEEP = ['beach', 'sand_dunes', 'ocean', 'inner_sea']


def material_rules():
    on_floor = []
    under_floor = []
    for name, (top, under) in SURF.items():
        on_floor.append(cond(biome_is(name), top))
        under_floor.append(cond(biome_is(name), under))
    surface = seq(
        cond('minecraft:on_floor', seq(
            cond('minecraft:not_underwater', seq(*on_floor, GRASS)),
            cond(biome_is('ocean', 'inner_sea', 'deep_ocean', 'beach', 'black_sand_beach', 'rocky_shore', 'sand_dunes'),
                 seq(*[c for c in on_floor if c['if_true']['biome_is'][0] in
                       [P(x) for x in ('ocean', 'inner_sea', 'deep_ocean', 'beach', 'black_sand_beach', 'rocky_shore', 'sand_dunes')]])),
            UNDERWATER_FLOOR)),
        cond('minecraft:not_under_deep_water', seq(
            cond('minecraft:under_floor', seq(*under_floor, DIRT)),
            cond(biome_is(*SANDY_DEEP), cond('minecraft:deep_under_floor', block('minecraft:sandstone'))))),
        cond('minecraft:on_floor', 'minecraft:overworld/gravel_or_stone_if_ceiling'),
    )
    write(NS, 'worldgen/material_rule/surface', surface)

    underground = seq(
        cond(biome_is('magma_tubes'), 'minecraft:overworld/sulfur_cave_bands'),
        cond(biome_is('magma_tubes'), cond(noise_band(-0.3, 0.3, 'minecraft:calcite'), block('minecraft:basalt'))),
        cond(biome_is('ice_caves'), cond(noise_band(0.35, 1e9, 'minecraft:packed_ice'), block('minecraft:packed_ice'))),
        'minecraft:overworld/underground',
    )
    write(NS, 'worldgen/material_rule/underground', underground)

    root = seq(
        'minecraft:bedrock_floor',
        'minecraft:overworld/copper_ore_vein',
        'minecraft:overworld/iron_ore_vein',
        cond({'type': 'minecraft:above_preliminary_surface'}, P('surface')),
        P('underground'),
    )
    write(NS, 'worldgen/material_rule/overworld', root)


# --------------------------------------------------------------------------------------------- features
def simple_block(state, props=None):
    tp = {'id': state}
    if props:
        tp['properties'] = props
    return {'type': 'minecraft:simple_block', 'to_place': tp}


def placed(feature, placement):
    return {'feature': feature, 'placement': placement}


def count(n):
    return {'type': 'minecraft:count', 'count': n}


def rarity(n):
    return {'type': 'minecraft:rarity_filter', 'chance': n}


IN_SQUARE = {'type': 'minecraft:in_square'}
BIOME = {'type': 'minecraft:biome'}


def heightmap(h='WORLD_SURFACE_WG'):
    return {'type': 'minecraft:heightmap', 'heightmap': h}


def height_uniform(lo, hi):
    return {'type': 'minecraft:height_range', 'height': {'type': 'minecraft:uniform', 'min_inclusive': lo, 'max_inclusive': hi}}


def height_trap(lo, hi):
    return {'type': 'minecraft:height_range', 'height': {'type': 'minecraft:trapezoid', 'min_inclusive': lo, 'max_inclusive': hi}}


def A(y):
    return {'absolute': y}


def spread(xz=7, y=3, n=32):
    return [count(n), {'type': 'minecraft:offset',
                       'x': {'type': 'minecraft:trapezoid', 'min': -xz, 'max': xz, 'plateau': 0},
                       'y': {'type': 'minecraft:trapezoid', 'min': -y, 'max': y, 'plateau': 0},
                       'z': {'type': 'minecraft:trapezoid', 'min': -xz, 'max': xz, 'plateau': 0}}]


def air_and_survive(state):
    return {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
        {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:air'},
        {'type': 'minecraft:would_survive', 'state': state}]}}


def near_water():
    preds = []
    for off in ([1, -1, 0], [-1, -1, 0], [0, -1, 1], [0, -1, -1]):
        preds.append({'type': 'minecraft:matching_fluids', 'fluids': ['minecraft:water', 'minecraft:flowing_water'], 'offset': off})
    return {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:any_of', 'predicates': preds}}


def patch(feature, chance, n, xz=6, water=False, state=None):
    pl = [rarity(chance), IN_SQUARE, heightmap(), BIOME] + spread(xz, 2, n) + [air_and_survive(state)]
    if water:
        pl.append(near_water())
    return placed(feature, pl)


def tree_placement(sapling, n, extra=None):
    cnt = n if extra is None else {'type': 'minecraft:weighted_list', 'distribution': [{'data': n, 'weight': 9}, {'data': n + extra, 'weight': 1}]}
    return [count(cnt), IN_SQUARE, {'type': 'minecraft:surface_water_depth_filter', 'max_water_depth': 0},
            heightmap('OCEAN_FLOOR'), BIOME,
            {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:would_survive', 'state': sapling}}]


def leaves(b):
    return {'id': b, 'properties': {'distance': '7', 'persistent': 'false', 'waterlogged': 'false'}}


def log(b):
    return {'id': b, 'properties': {'axis': 'y'}}


def features():
    F = {}   # configured features: name -> json
    PF = {}  # placed features: name -> json

    F['water_fill'] = {'type': P('water_fill')}
    PF['water_fill'] = placed(P('water_fill'), [])
    F['paddy'] = {'type': P('paddy')}
    PF['paddy'] = placed(P('paddy'), [])
    F['onsen'] = {'type': P('onsen')}
    PF['onsen'] = placed(P('onsen'), [rarity(5), IN_SQUARE, heightmap(), BIOME])

    # ---- trees
    momiji = van('worldgen/feature/fancy_oak')
    momiji['foliage_provider'] = leaves(P('momiji_leaves'))
    momiji['trunk_provider'] = log('minecraft:dark_oak_log')
    momiji['trunk_placer']['height_rand_a'] = 6
    F['momiji'] = momiji
    small = van('worldgen/feature/oak')
    small['foliage_provider'] = leaves(P('momiji_leaves'))
    small['trunk_provider'] = log('minecraft:dark_oak_log')
    F['momiji_small'] = small
    ginkgo = van('worldgen/feature/fancy_oak')
    ginkgo['foliage_provider'] = leaves(P('ginkgo_leaves'))
    ginkgo['trunk_provider'] = log('minecraft:birch_log')
    ginkgo['trunk_placer']['base_height'] = 6
    F['ginkgo'] = ginkgo
    matsu = van('worldgen/feature/acacia')
    matsu['foliage_provider'] = leaves('minecraft:spruce_leaves')
    matsu['trunk_provider'] = log('minecraft:spruce_log')
    F['matsu'] = matsu
    PF['trees_momiji'] = placed(P('momiji'), tree_placement('minecraft:oak_sapling', 4, 2))
    PF['trees_momiji_small'] = placed(P('momiji_small'), tree_placement('minecraft:oak_sapling', 3, 2))
    PF['trees_ginkgo'] = placed(P('ginkgo'), [rarity(3)] + tree_placement('minecraft:oak_sapling', 1))
    PF['trees_matsu'] = placed(P('matsu'), tree_placement('minecraft:oak_sapling', 2, 2))
    PF['trees_matsu_sparse'] = placed(P('matsu'), [rarity(3)] + tree_placement('minecraft:oak_sapling', 1))
    PF['trees_sugi'] = placed('minecraft:mega_spruce', tree_placement('minecraft:spruce_sapling', 5, 2))
    PF['trees_sugi_spruce'] = placed('minecraft:spruce', tree_placement('minecraft:spruce_sapling', 3, 1))
    PF['trees_jukai'] = placed('minecraft:mega_spruce', tree_placement('minecraft:spruce_sapling', 3, 1))
    PF['trees_jukai_oak'] = placed('minecraft:dark_oak', tree_placement('minecraft:dark_oak_sapling', 4, 2))
    PF['trees_subalpine'] = placed('minecraft:spruce', tree_placement('minecraft:spruce_sapling', 2, 2))
    PF['trees_subalpine_birch'] = placed('minecraft:birch', [rarity(2)] + tree_placement('minecraft:birch_sapling', 1))
    PF['trees_snowy'] = placed('minecraft:pine', tree_placement('minecraft:spruce_sapling', 2, 2))
    PF['trees_alpine'] = placed('minecraft:spruce', [rarity(2)] + tree_placement('minecraft:spruce_sapling', 1, 1))
    PF['trees_cherry_sparse'] = placed('minecraft:cherry', [rarity(2)] + tree_placement('minecraft:cherry_sapling', 1))
    PF['trees_cherry'] = placed('minecraft:cherry_bees_005', tree_placement('minecraft:cherry_sapling', 6, 2))
    PF['trees_marsh'] = placed('minecraft:swamp_oak', [rarity(2)] + tree_placement('minecraft:oak_sapling', 1))

    # ---- wild plants
    for name in ['wild_rice', 'wild_soybean', 'wild_daikon', 'wild_wasabi', 'wild_tea', 'shiitake']:
        F[name] = simple_block(P(name))
    PF['patch_wild_rice'] = patch(P('wild_rice'), 3, 24, 5, True, P('wild_rice'))
    PF['patch_wild_rice_common'] = patch(P('wild_rice'), 1, 32, 6, True, P('wild_rice'))
    PF['patch_wild_soybean'] = patch(P('wild_soybean'), 9, 14, 4, False, P('wild_soybean'))
    PF['patch_wild_daikon'] = patch(P('wild_daikon'), 10, 10, 4, False, P('wild_daikon'))
    PF['patch_wild_wasabi'] = patch(P('wild_wasabi'), 3, 20, 4, True, P('wild_wasabi'))
    PF['patch_wild_tea'] = patch(P('wild_tea'), 10, 18, 5, False, P('wild_tea'))
    PF['patch_wild_tea_fields'] = patch(P('wild_tea'), 1, 48, 8, False, P('wild_tea'))
    PF['patch_shiitake'] = patch(P('shiitake'), 6, 10, 4, False, P('shiitake'))

    # ---- caves
    geode = van('worldgen/feature/amethyst_geode')
    geode['blocks']['inner_layer_provider'] = {'id': P('eclipse_crystal_block')}
    geode['blocks']['alternate_inner_layer_provider'] = {'id': P('eclipse_crystal_block')}
    geode['blocks']['inner_placements'] = [P('eclipse_crystal_cluster')]
    geode['blocks']['middle_layer_provider'] = {'id': 'minecraft:obsidian'}
    geode['blocks']['outer_layer_provider'] = {'id': 'minecraft:basalt'}
    F['eclipse_geode'] = geode
    PF['eclipse_geode'] = placed(P('eclipse_geode'), [rarity(3), IN_SQUARE, height_uniform({'above_bottom': 8}, A(0)), BIOME])
    PF['eclipse_geode_rare'] = placed(P('eclipse_geode'), [rarity(70), IN_SQUARE, height_uniform({'above_bottom': 8}, A(-10)), BIOME])
    F['eclipse_cluster'] = simple_block(P('eclipse_crystal_cluster'), {'facing': 'up', 'waterlogged': 'false'})
    PF['eclipse_clusters'] = placed(P('eclipse_cluster'), [count(40), IN_SQUARE, height_uniform({'above_bottom': 4}, A(0)),
        {'type': 'minecraft:environment_scan', 'direction_of_search': 'down', 'max_steps': 12,
         'allowed_search_condition': {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:air'},
         'target_condition': {'type': 'minecraft:solid'}},
        {'type': 'minecraft:offset', 'x': 0, 'y': 1, 'z': 0}, BIOME])
    hik = van('worldgen/feature/moss_patch')
    hik['ground_state'] = {'id': P('hikari_moss')}
    hik['vegetation_feature'] = {'feature': P('hikari_vegetation'), 'placement': []}
    hik['vegetation_chance'] = 0.5
    F['hikari_patch'] = hik
    F['hikari_vegetation'] = {'type': 'minecraft:simple_block', 'to_place': {'type': 'minecraft:weighted', 'entries': [
        {'data': P('hikari_moss_carpet'), 'weight': 20}, {'data': 'minecraft:short_grass', 'weight': 6},
        {'data': 'minecraft:fern', 'weight': 4}, {'data': P('shiitake'), 'weight': 2}]}}
    lush = van('worldgen/placed_feature/lush_caves_vegetation')
    lush['feature'] = P('hikari_patch')
    lush['placement'][0] = count(70)
    PF['hikari_patches'] = lush

    def ore(name, state, size, targets='minecraft:base_stone_overworld', air=0.0):
        F[name] = {'type': 'minecraft:ore', 'discard_chance_on_air_exposure': air, 'size': size,
                   'targets': [{'state': state, 'target': {'predicate_type': 'minecraft:tag_match', 'tag': targets}}]}
    ore('blob_scoria', P('scoria'), 48)
    ore('blob_pumice', P('pumice'), 32)
    ore('blob_basalt', 'minecraft:basalt', 48)
    ore('blob_blackstone', 'minecraft:blackstone', 40)
    ore('blob_packed_ice', 'minecraft:packed_ice', 40)
    ore('blob_ice', 'minecraft:ice', 24)
    ore('blob_tuff', 'minecraft:tuff', 56)
    PF['blob_scoria'] = placed(P('blob_scoria'), [count(4), IN_SQUARE, height_uniform(A(-30), A(900)), BIOME])
    PF['blob_pumice'] = placed(P('blob_pumice'), [count(2), IN_SQUARE, height_uniform(A(0), A(900)), BIOME])
    PF['blob_basalt'] = placed(P('blob_basalt'), [count(5), IN_SQUARE, height_uniform(A(-40), A(400)), BIOME])
    PF['blob_blackstone'] = placed(P('blob_blackstone'), [count(3), IN_SQUARE, height_uniform(A(-60), A(80)), BIOME])
    PF['blob_packed_ice'] = placed(P('blob_packed_ice'), [count(6), IN_SQUARE, height_uniform(A(0), A(500)), BIOME])
    PF['blob_ice'] = placed(P('blob_ice'), [count(4), IN_SQUARE, height_uniform(A(0), A(500)), BIOME])
    PF['blob_tuff'] = placed(P('blob_tuff'), [count(3), IN_SQUARE, height_uniform(A(0), A(260)), BIOME])
    PF['lava_lakes_deep'] = placed('minecraft:lake_lava', [rarity(4), IN_SQUARE, height_uniform(A(-50), A(40)), BIOME])

    # ---- ores, tuned for a finite 5000x5000 map and ~50 players for one season.
    # Coal and iron also reach into the tall ring mountains; diamonds are ~35% rarer than vanilla.
    tuning = {
        'ore_coal_upper': (30, ('uniform', A(136), A(640))),
        'ore_coal_lower': (20, None),
        'ore_iron_upper': (90, ('trapezoid', A(80), A(720))),
        'ore_iron_middle': (10, None),
        'ore_iron_small': (10, None),
        'ore_gold': (4, None),
        'ore_gold_lower': (None, None),
        'ore_redstone': (4, None),
        'ore_redstone_lower': (6, None),
        'ore_diamond': (5, None),
        'ore_diamond_medium': (1, None),
        'ore_diamond_large': (None, None),
        'ore_diamond_buried': (3, None),
        'ore_lapis': (2, None),
        'ore_lapis_buried': (3, None),
        'ore_copper': (16, None),
        'ore_emerald': (70, ('uniform', A(-16), A(700))),
    }
    for name, (n, h) in tuning.items():
        pf = van('worldgen/placed_feature/' + name)
        for m in pf['placement']:
            if m['type'] == 'minecraft:count' and n is not None:
                m['count'] = n
            if m['type'] == 'minecraft:rarity_filter' and name == 'ore_diamond_large':
                m['chance'] = 14
            if m['type'] == 'minecraft:height_range' and h is not None:
                m['height'] = {'type': 'minecraft:' + h[0], 'min_inclusive': h[1], 'max_inclusive': h[2]}
        PF[name] = pf

    for k, v in F.items():
        write(NS, 'worldgen/feature/' + k, v)
    for k, v in PF.items():
        write(NS, 'worldgen/placed_feature/' + k, v)
    return PF


# --------------------------------------------------------------------------------------------- biomes
ORES = ['ore_coal_upper', 'ore_coal_lower', 'ore_iron_upper', 'ore_iron_middle', 'ore_iron_small', 'ore_gold', 'ore_gold_lower',
        'ore_redstone', 'ore_redstone_lower', 'ore_diamond', 'ore_diamond_medium', 'ore_diamond_large', 'ore_diamond_buried',
        'ore_lapis', 'ore_lapis_buried', 'ore_copper']
STONES = ['minecraft:ore_dirt', 'minecraft:ore_gravel', 'minecraft:ore_granite_upper', 'minecraft:ore_granite_lower',
          'minecraft:ore_diorite_upper', 'minecraft:ore_diorite_lower', 'minecraft:ore_andesite_upper', 'minecraft:ore_andesite_lower',
          'minecraft:ore_tuff']

# Master order per generation step. Every biome uses a subsequence, so the feature order is consistent
# across biomes (vanilla refuses to load cyclic orders).
MASTER = [
    [],                                                                                  # 0 raw_generation
    [P('water_fill'), P('paddy'), 'minecraft:lake_lava_underground', P('lava_lakes_deep')],  # 1 lakes
    ['minecraft:amethyst_geode', P('eclipse_geode'), P('eclipse_geode_rare'), P('onsen')],   # 2 local_modifications
    [],                                                                                  # 3 underground_structures
    [],                                                                                  # 4 surface_structures
    [],                                                                                  # 5 strongholds
    STONES + [P(o) for o in ORES] + [P('ore_emerald'), P('blob_scoria'), P('blob_pumice'), P('blob_basalt'),
              P('blob_blackstone'), P('blob_packed_ice'), P('blob_ice'), P('blob_tuff'), 'minecraft:ore_magma',
              'minecraft:disk_sand', 'minecraft:disk_clay', 'minecraft:disk_gravel'],   # 6 underground_ores
    ['minecraft:fossil_upper', 'minecraft:fossil_lower', 'minecraft:dripstone_cluster', 'minecraft:large_dripstone',
     'minecraft:pointed_dripstone', P('eclipse_clusters'), P('hikari_patches'), 'minecraft:lush_caves_ceiling_vegetation',
     'minecraft:cave_vines', 'minecraft:lush_caves_clay', 'minecraft:spore_blossom'],    # 7 underground_decoration
    ['minecraft:spring_water', 'minecraft:spring_lava'],                                 # 8 fluid_springs
    ['minecraft:glow_lichen',
     P('trees_cherry'), P('trees_cherry_sparse'), P('trees_momiji'), P('trees_momiji_small'), P('trees_ginkgo'),
     P('trees_sugi'), P('trees_sugi_spruce'), P('trees_jukai'), P('trees_jukai_oak'), P('trees_subalpine'),
     P('trees_subalpine_birch'), P('trees_snowy'), P('trees_alpine'), P('trees_matsu'), P('trees_matsu_sparse'),
     P('trees_marsh'), 'minecraft:bamboo', 'minecraft:bamboo_light',
     'minecraft:flower_cherry', 'minecraft:flower_default', 'minecraft:patch_tall_grass_2', 'minecraft:patch_grass_plain',
     'minecraft:patch_grass_forest', 'minecraft:patch_grass_taiga', 'minecraft:patch_large_fern', 'minecraft:patch_dead_bush',
     'minecraft:patch_dry_grass_desert', 'minecraft:patch_leaf_litter', 'minecraft:brown_mushroom_normal', 'minecraft:red_mushroom_normal',
     'minecraft:patch_sugar_cane', 'minecraft:patch_sugar_cane_swamp', 'minecraft:patch_waterlily', 'minecraft:patch_berry_rare',
     'minecraft:forest_rock',
     P('patch_wild_rice_common'), P('patch_wild_rice'), P('patch_wild_soybean'), P('patch_wild_daikon'), P('patch_wild_wasabi'),
     P('patch_wild_tea_fields'), P('patch_wild_tea'), P('patch_shiitake'),
     'minecraft:seagrass_warm', 'minecraft:seagrass_normal', 'minecraft:seagrass_deep', 'minecraft:seagrass_river',
     'minecraft:kelp_cold', 'minecraft:kelp_warm', 'minecraft:sea_pickle'],              # 9 vegetal_decoration
    ['minecraft:freeze_top_layer'],                                                      # 10 top_layer_modification
]

COMMON_UNDER = {1: [P('water_fill'), 'minecraft:lake_lava_underground'],
                2: ['minecraft:amethyst_geode', P('eclipse_geode_rare')],
                6: STONES + [P(o) for o in ORES] + ['minecraft:disk_sand', 'minecraft:disk_clay', 'minecraft:disk_gravel'],
                7: ['minecraft:fossil_upper', 'minecraft:fossil_lower', 'minecraft:dripstone_cluster', 'minecraft:large_dripstone',
                    'minecraft:pointed_dripstone', P('eclipse_clusters'), P('hikari_patches'), 'minecraft:lush_caves_ceiling_vegetation',
                    'minecraft:cave_vines', 'minecraft:lush_caves_clay', 'minecraft:spore_blossom'],
                8: ['minecraft:spring_water', 'minecraft:spring_lava'],
                9: ['minecraft:glow_lichen'],
                10: ['minecraft:freeze_top_layer']}

# biome: vanilla template, temperature, downfall, precipitation, colors (grass, foliage, water, sky/fog via template), extra features
B = {}


def biome(name, template, temp, down, precip=True, grass=None, foliage=None, water=None, extra=None, music=None, **kw):
    B[name] = dict(template=template, temp=temp, down=down, precip=precip, grass=grass, foliage=foliage, water=water,
                   extra=extra or {}, music=music, **kw)


W_JAPAN = '#3f87c9'
biome('deep_ocean', 'deep_ocean', 0.5, 0.5, water='#2e5d9e', extra={9: ['minecraft:seagrass_deep', 'minecraft:kelp_cold']})
biome('ocean', 'ocean', 0.6, 0.5, water='#3a7fc4', extra={9: ['minecraft:seagrass_normal', 'minecraft:kelp_warm', 'minecraft:sea_pickle']})
biome('inner_sea', 'lukewarm_ocean', 0.7, 0.5, water='#3fa3c9', extra={9: ['minecraft:seagrass_warm', 'minecraft:sea_pickle']})
biome('beach', 'beach', 0.8, 0.4, water=W_JAPAN)
biome('black_sand_beach', 'stony_shore', 0.7, 0.4, water='#3b7fae')
biome('rocky_shore', 'stony_shore', 0.6, 0.4, water='#3b7fae')
biome('river', 'river', 0.6, 0.6, water='#3f9bd1', extra={9: ['minecraft:patch_sugar_cane', P('patch_wild_wasabi'), 'minecraft:seagrass_river']})
biome('sakura_valley', 'cherry_grove', 0.6, 0.7, grass='#a8d36b', foliage='#a8d36b', water='#5db7ef',
      extra={9: [P('trees_cherry'), 'minecraft:flower_cherry', 'minecraft:patch_tall_grass_2', 'minecraft:patch_grass_plain',
                 P('patch_wild_soybean'), P('patch_wild_tea')]})
biome('momiji_highlands', 'dark_forest', 0.55, 0.7, grass='#b5a24a', foliage='#c0602a', water=W_JAPAN,
      extra={6: [P('ore_emerald')], 9: [P('trees_momiji'), P('trees_momiji_small'), P('trees_ginkgo'), 'minecraft:patch_grass_forest',
                 'minecraft:patch_leaf_litter', 'minecraft:patch_large_fern', P('patch_shiitake'), P('patch_wild_daikon'), 'minecraft:forest_rock']})
biome('sugi_forest', 'old_growth_spruce_taiga', 0.35, 0.85, grass='#5f9a4e', foliage='#3f7a3a', water='#3b6bc0',
      extra={6: [P('ore_emerald')], 8: [],
             9: [P('trees_sugi'), P('trees_sugi_spruce'), 'minecraft:patch_large_fern', 'minecraft:patch_grass_taiga',
                 'minecraft:brown_mushroom_normal', P('patch_shiitake'), P('patch_wild_wasabi'), 'minecraft:patch_berry_rare']})
biome('bamboo_grove', 'bamboo_jungle', 0.75, 0.85, grass='#7ec850', foliage='#6fbf45', water=W_JAPAN,
      extra={9: ['minecraft:bamboo', 'minecraft:patch_grass_forest', P('patch_wild_tea'), P('patch_shiitake')]})
biome('rice_terraces', 'plains', 0.7, 0.8, grass='#8fd05a', foliage='#78b84a', water='#4db0d8',
      extra={1: [P('paddy')], 9: [P('trees_cherry_sparse'), 'minecraft:patch_grass_plain', P('patch_wild_rice_common'),
                                   P('patch_wild_soybean'), P('patch_wild_daikon')]})
biome('tea_hills', 'plains', 0.7, 0.75, grass='#5fb84a', foliage='#4a9d3c', water=W_JAPAN,
      extra={9: [P('trees_cherry_sparse'), P('trees_matsu_sparse'), 'minecraft:patch_grass_plain', P('patch_wild_tea_fields'), P('patch_wild_soybean')]})
biome('alpine_peaks', 'jagged_peaks', 0.1, 0.4, grass='#7ba05b', foliage='#5e8a4b', water='#3d57d6',
      extra={6: [P('ore_emerald')], 9: [P('trees_alpine'), 'minecraft:patch_grass_taiga']}, music='minecraft:music.overworld.jagged_peaks')
biome('snowy_highlands', 'snowy_taiga', -0.4, 0.5, grass='#80b497', foliage='#60a17b', water='#3d57d6',
      extra={6: [P('ore_emerald')], 9: [P('trees_snowy'), 'minecraft:patch_grass_taiga', 'minecraft:patch_large_fern']})
biome('onsen_valley', 'windswept_hills', 0.65, 0.5, grass='#a6b366', foliage='#8a9a4f', water='#5ac9c0',
      extra={2: [P('onsen')], 6: [P('blob_tuff'), P('blob_scoria')], 9: [P('trees_matsu_sparse'), 'minecraft:patch_grass_plain',
                                                                          'minecraft:patch_dead_bush', 'minecraft:forest_rock']})
biome('pine_cliffs', 'windswept_forest', 0.6, 0.6, grass='#79b45a', foliage='#4f8a3f', water='#2f7fbf',
      extra={9: [P('trees_matsu'), 'minecraft:patch_grass_plain', 'minecraft:forest_rock']})
biome('reed_marsh', 'swamp', 0.7, 0.9, grass='#8aa04a', foliage='#6f8a3a', water='#4c7d6a',
      extra={9: [P('trees_marsh'), 'minecraft:patch_sugar_cane_swamp', 'minecraft:patch_waterlily', 'minecraft:patch_grass_plain',
                 P('patch_wild_rice_common'), P('patch_wild_wasabi')]})
biome('sand_dunes', 'desert', 1.2, 0.1, precip=False, water=W_JAPAN, extra={9: ['minecraft:patch_dead_bush', 'minecraft:patch_dry_grass_desert']})
biome('frozen_summits', 'frozen_peaks', -0.7, 0.9, water='#3938c9', music='minecraft:music.overworld.frozen_peaks')
biome('jukai_forest', 'dark_forest', 0.5, 0.95, grass='#4f8a3c', foliage='#386b2c', water='#3b6bc0',
      extra={6: [P('blob_basalt')], 9: [P('trees_jukai'), P('trees_jukai_oak'), P('trees_sugi_spruce'), 'minecraft:patch_large_fern',
                                         'minecraft:patch_grass_taiga', 'minecraft:brown_mushroom_normal', 'minecraft:red_mushroom_normal',
                                         P('patch_shiitake'), P('patch_wild_wasabi')]})
biome('fuji_subalpine', 'taiga', 0.25, 0.6, grass='#86a35e', foliage='#6a8a50', water='#3b6bc0',
      extra={6: [P('blob_scoria'), P('blob_pumice')], 9: [P('trees_subalpine'), P('trees_subalpine_birch'), 'minecraft:patch_grass_taiga']})
biome('fuji_slopes', 'windswept_gravelly_hills', 0.05, 0.3, water='#3d57d6',
      extra={6: [P('blob_scoria'), P('blob_pumice'), P('blob_basalt')]}, music='minecraft:music.overworld.stony_peaks')
biome('fuji_summit', 'frozen_peaks', -0.8, 0.8, water='#3938c9', extra={6: [P('blob_scoria'), P('blob_basalt')]},
      music='minecraft:music.overworld.frozen_peaks')
# caves
biome('deep_caverns', 'dripstone_caves', 0.6, 0.4, water=W_JAPAN, cave=True)
biome('hikari_grotto', 'lush_caves', 0.6, 0.6, water='#43d5ee', cave=True,
      extra={7: [P('hikari_patches'), 'minecraft:lush_caves_ceiling_vegetation', 'minecraft:cave_vines', 'minecraft:spore_blossom']})
biome('eclipse_hollows', 'dripstone_caves', 0.5, 0.4, water='#3f3fae', cave=True,
      extra={2: [P('eclipse_geode')], 6: [P('blob_blackstone')], 7: [P('eclipse_clusters')]}, music='minecraft:music.overworld.deep_dark')
biome('magma_tubes', 'sulfur_caves', 1.0, 0.0, precip=False, water='#5a8a6a', cave=True,
      extra={1: [P('lava_lakes_deep')], 6: [P('blob_basalt'), P('blob_blackstone'), P('blob_scoria'), 'minecraft:ore_magma']})
biome('flooded_caves', 'lush_caves', 0.6, 0.8, water='#3f76e4', cave=True, extra={7: ['minecraft:lush_caves_clay']})
biome('ice_caves', 'dripstone_caves', -0.5, 0.4, water='#3938c9', cave=True, extra={6: [P('blob_packed_ice'), P('blob_ice')]})

BIOMES = list(B.keys())
SURFACE_DEFAULT_CAVE_DECOR = {7: ['minecraft:fossil_upper', 'minecraft:fossil_lower', 'minecraft:dripstone_cluster',
                                  'minecraft:large_dripstone', 'minecraft:pointed_dripstone']}


def biomes():
    for name, spec in B.items():
        tpl = van('worldgen/biome/' + spec['template'])
        out = copy.deepcopy(tpl)
        out['temperature'] = spec['temp']
        out['downfall'] = spec['down']
        out['has_precipitation'] = spec['precip']
        eff = out.setdefault('effects', {})
        for k in ('grass', 'foliage', 'water'):
            if spec[k]:
                eff[k + '_color'] = spec[k]
        if spec['foliage'] and 'dry_foliage_color' in eff:
            del eff['dry_foliage_color']
        if spec['music']:
            out.setdefault('attributes', {})['minecraft:audio/background_music'] = {
                'default': {'max_delay': 24000, 'min_delay': 12000, 'sound': spec['music']}}
        # features: common underground + biome extras, ordered by the master lists
        want = {i: set() for i in range(11)}
        for i, lst in COMMON_UNDER.items():
            want[i].update(lst)
        if spec.get('cave'):
            want[1].discard(P('water_fill'))
        for i, lst in spec['extra'].items():
            want[i].update(lst)
        feats = []
        for i in range(11):
            master = MASTER[i]
            missing = want[i] - set(master)
            assert not missing, (name, i, missing)
            feats.append([f for f in master if f in want[i]])
        out['features'] = feats
        out['carvers'] = ['minecraft:cave', 'minecraft:cave_extra_underground', 'minecraft:canyon'] if not spec.get('cave') else []
        write(NS, 'worldgen/biome/' + name, out)


def tags():
    land = [P(b) for b, s in B.items() if not s.get('cave') and b not in ('deep_ocean', 'ocean', 'inner_sea')]
    write(NS, 'tags/worldgen/biome/surface', {'values': land})
    write(NS, 'tags/worldgen/biome/all', {'values': [P(b) for b in B]})
    write(NS, 'tags/worldgen/biome/caves', {'values': [P(b) for b, s in B.items() if s.get('cave')]})
    # Only harmless vanilla hooks: the group tags (is_ocean, is_forest, is_overworld...) also switch on
    # vanilla structures (strongholds, mineshafts, monuments...), which this map leaves out for now.
    write('minecraft', 'tags/worldgen/biome/water_on_map_outlines', {'replace': False, 'values': [P('deep_ocean'), P('ocean'), P('inner_sea'), P('river')]})
    write('minecraft', 'tags/worldgen/biome/more_frequent_drowned_spawns', {'replace': False, 'values': [P('river')]})


if __name__ == '__main__':
    dimension()
    noise_router()
    material_rules()
    features()
    biomes()
    tags()
    print('worldgen: %d biomes' % len(B))
