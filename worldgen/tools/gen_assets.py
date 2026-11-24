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


import textures as T  # noqa: E402


# ------------------------------------------------------------------------------------------ definitions
CUBES = ['volcanic_ash', 'black_sand', 'scoria', 'pumice', 'eclipse_crystal_block', 'shrine_bricks', 'mossy_shrine_bricks',
         'cracked_shrine_bricks', 'vermilion_planks', 'tatami', 'glowcap_block', 'hydrangea_blue', 'hydrangea_purple', 'hydrangea_pink']
FALLING = {'volcanic_ash', 'black_sand'}
LEAVES = ['momiji_leaves', 'ginkgo_leaves', 'wisteria_leaves']
CROSS = ['glowcap', 'lumen_fern', 'higanbana', 'spirit_vine', 'wisteria_vine']
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
    'glowcap': ('Светогриб', 'Glowcap'), 'glowcap_block': ('Шляпка светогриба', 'Glowcap Block'), 'glowcap_stem': ('Ножка светогриба', 'Glowcap Stem'),
    'lumen_fern': ('Светящийся папоротник', 'Lumen Fern'), 'spirit_vine': ('Лоза духов', 'Spirit Vine'),
    'wisteria_leaves': ('Листва глицинии', 'Wisteria Leaves'), 'wisteria_vine': ('Кисти глицинии', 'Wisteria Blossoms'),
    'hydrangea_blue': ('Синяя гортензия', 'Blue Hydrangea'), 'hydrangea_purple': ('Лиловая гортензия', 'Purple Hydrangea'),
    'hydrangea_pink': ('Розовая гортензия', 'Pink Hydrangea'), 'higanbana': ('Хиганбана', 'Red Spider Lily'),
    'susuki': ('Сусуки', 'Silver Grass'), 'lotus': ('Лотос', 'Lotus'),
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
    # ---------------- textures (see textures.py)
    for name, fn in T.BLOCK_FN.items():
        save(fn(), 'block', name)
        if name in T.EMISSIVE:
            save(T.specular(fn(), T.EMISSIVE[name]), 'block', name + '_s')
    for name in ITEMS:
        save(T.ITEM_FN[name](), 'item', name)

    # ---------------- block models, blockstates, item definitions
    def simple_state(name, model=None):
        wj(f'{A}/blockstates/{name}.json', {'variants': {'': {'model': model or P('block/' + name)}}})

    def item_def(name, model):
        wj(f'{A}/items/{name}.json', {'model': {'type': 'minecraft:model', 'model': model}})

    def flat_item(name, tex):
        wj(f'{A}/models/item/{name}.json', {'parent': 'minecraft:item/generated', 'textures': {'layer0': tex}})
        item_def(name, P('item/' + name))

    # random rotations so natural blocks never show a repeating pattern
    ISOTROPIC = {'volcanic_ash', 'black_sand', 'scoria', 'pumice', 'eclipse_crystal_block', 'glowcap_block'}
    YROT = {'hydrangea_blue', 'hydrangea_purple', 'hydrangea_pink'} | set(LEAVES)

    def rotated_state(name, full):
        xs = (0, 90, 180, 270) if full else (0,)
        wj(f'{A}/blockstates/{name}.json', {'variants': {'': [dict({'model': P('block/' + name)}, **({'x': x} if x else {}), **({'y': y} if y else {}))
                                                              for x in xs for y in (0, 90, 180, 270)]}})

    for name in CUBES + LEAVES:
        parent = 'minecraft:block/leaves' if name in LEAVES else 'minecraft:block/cube_all'
        wj(f'{A}/models/block/{name}.json', {'parent': parent, 'textures': {'all': P('block/' + name)}})
        if name in ISOTROPIC or name in YROT:
            rotated_state(name, name in ISOTROPIC or name in LEAVES)
        else:
            simple_state(name)
        item_def(name, P('block/' + name))
    # hikari moss: three textures, random rotations, so a patch never looks stamped
    for v in ('hikari_moss', 'hikari_moss_1', 'hikari_moss_2'):
        wj(f'{A}/models/block/{v}.json', {'parent': 'minecraft:block/cube_all', 'textures': {'all': P('block/' + v)}})
    wj(f'{A}/blockstates/hikari_moss.json', {'variants': {'': [{'model': P('block/' + v), 'y': r}
                                                               for v in ('hikari_moss', 'hikari_moss_1', 'hikari_moss_2') for r in (0, 90, 180, 270)]}})
    item_def('hikari_moss', P('block/hikari_moss'))
    wj(f'{A}/models/block/hikari_moss_carpet.json', {'parent': 'minecraft:block/carpet', 'textures': {'wool': P('block/hikari_moss_1')}})
    simple_state('hikari_moss_carpet')
    item_def('hikari_moss_carpet', P('block/hikari_moss_carpet'))
    # glowcap stem: a pillar
    wj(f'{A}/models/block/glowcap_stem.json', {'parent': 'minecraft:block/cube_column',
                                               'textures': {'end': P('block/glowcap_stem_top'), 'side': P('block/glowcap_stem')}})
    wj(f'{A}/models/block/glowcap_stem_horizontal.json', {'parent': 'minecraft:block/cube_column_horizontal',
                                                          'textures': {'end': P('block/glowcap_stem_top'), 'side': P('block/glowcap_stem')}})
    wj(f'{A}/blockstates/glowcap_stem.json', {'variants': {
        'axis=y': {'model': P('block/glowcap_stem')},
        'axis=x': {'model': P('block/glowcap_stem_horizontal'), 'x': 90, 'y': 90},
        'axis=z': {'model': P('block/glowcap_stem_horizontal'), 'x': 90}}})
    item_def('glowcap_stem', P('block/glowcap_stem'))
    wj(f'{A}/models/block/eclipse_crystal_cluster.json', {'parent': 'minecraft:block/cross', 'textures': {'cross': P('block/eclipse_crystal_cluster')}})
    rot = {'down': {'x': 180}, 'east': {'x': 90, 'y': 90}, 'north': {'x': 90}, 'south': {'x': 90, 'y': 180}, 'up': {}, 'west': {'x': 90, 'y': 270}}
    wj(f'{A}/blockstates/eclipse_crystal_cluster.json', {'variants': {f'facing={k}': dict(model=P('block/eclipse_crystal_cluster'), **v) for k, v in rot.items()}})
    flat_item('eclipse_crystal_cluster', P('block/eclipse_crystal_cluster'))
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
        for st in range(4):
            wj(f'{A}/models/block/{c}_stage{st}.json', {'parent': 'minecraft:block/crop', 'textures': {'crop': P(f'block/{c}_stage{st}')}})
        stage_of = [0, 0, 1, 1, 2, 2, 2, 3]
        wj(f'{A}/blockstates/{c}_crop.json', {'variants': {f'age={a}': {'model': P(f'block/{c}_stage{stage_of[a]}')} for a in range(8)}})
    for w in WILD + CROSS:
        wj(f'{A}/models/block/{w}.json', {'parent': 'minecraft:block/cross', 'textures': {'cross': P('block/' + w)}})
        wj(f'{A}/blockstates/{w}.json', {'variants': {'': [dict({'model': P('block/' + w)}, **({'y': y} if y else {})) for y in (0, 90, 180, 270)]}})
        flat_item(w, P('block/' + w))
    # susuki: a two-block grass
    for half in ('bottom', 'top'):
        wj(f'{A}/models/block/susuki_{half}.json', {'parent': 'minecraft:block/cross', 'textures': {'cross': P('block/susuki_' + half)}})
    wj(f'{A}/blockstates/susuki.json', {'variants': {'half=lower': {'model': P('block/susuki_bottom')}, 'half=upper': {'model': P('block/susuki_top')}}})
    flat_item('susuki', P('block/susuki_top'))
    # lotus: a flat pad like a lily pad, untinted
    wj(f'{A}/models/block/lotus.json', {'ambientocclusion': False, 'textures': {'particle': P('block/lotus'), 'texture': P('block/lotus')},
                                        'elements': [{'from': [0, 0.25, 0], 'to': [16, 0.25, 16], 'faces': {
                                            'down': {'uv': [0, 16, 16, 0], 'texture': '#texture'}, 'up': {'uv': [0, 0, 16, 16], 'texture': '#texture'}}}]})
    wj(f'{A}/blockstates/lotus.json', {'variants': {'': [{'model': P('block/lotus'), 'y': r} for r in (0, 90, 180, 270)]}})
    flat_item('lotus', P('block/lotus'))
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
    bnames.update({
        'wisteria_grove': ('Сад глицинии', 'Wisteria Grove'), 'spirit_forest': ('Лес духов', 'Spirit Forest'),
        'ginkgo_grove': ('Золотая роща гинкго', 'Ginkgo Grove'), 'hydrangea_hills': ('Холмы гортензий', 'Hydrangea Hills'),
        'higanbana_fields': ('Поля хиганбаны', 'Higanbana Fields'), 'lotus_marsh': ('Лотосовые топи', 'Lotus Marsh'),
        'karst_spires': ('Парящие скалы', 'Karst Spires'), 'coral_coast': ('Коралловый берег', 'Coral Coast'),
        'jigoku_wastes': ('Долина Дзигоку', 'Jigoku Wastes'), 'shirakaba_plateau': ('Берёзовое плато сиракаба', 'Shirakaba Plateau'),
        'alpine_meadow': ('Альпийские луга', 'Alpine Meadow'), 'luminous_depths': ('Светящиеся глубины', 'Luminous Depths'),
        'root_halls': ('Залы корней', 'Root Halls'), 'abyss': ('Бездна', 'The Abyss'),
        'tsutsuji_hills': ('Азалиевые холмы цуцудзи', 'Tsutsuji Hills'), 'yakushima_moss': ('Моховой лес Якусима', 'Yakushima Moss Forest'),
        'susuki_plains': ('Равнины сусуки', 'Susuki Plains'), 'mangrove_coast': ('Мангровое побережье', 'Mangrove Coast'),
        'tropical_forest': ('Тропический лес Окинавы', 'Okinawa Rainforest')})
    for b in B:
        ru[f'biome.{NS}.{b}'], en[f'biome.{NS}.{b}'] = bnames[b]
    wj(f'{A}/lang/ru_ru.json', ru)
    wj(f'{A}/lang/en_us.json', en)

    # ---------------- loot tables
    for name in CUBES + ['hikari_moss', 'glowcap_stem', 'glowcap', 'lumen_fern', 'higanbana', 'lotus']:
        wj(f'{D}/loot_table/blocks/{name}.json', block_loot(name))
    shear = {'type': 'minecraft:any_of', 'terms': ['minecraft:tool/can_shear', 'minecraft:tool/can_silk_touch']}
    for name in ('spirit_vine', 'wisteria_vine'):
        wj(f'{D}/loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'condition': shear,
            'entries': [{'type': 'minecraft:item', 'name': P(name)}]}], 'random_sequence': f'{NS}:blocks/{name}'})
    wj(f'{D}/loot_table/blocks/susuki.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'condition': {
        'type': 'minecraft:match_block', 'blocks': P('susuki'), 'state': {'half': 'lower'}},
        'entries': [{'type': 'minecraft:item', 'name': P('susuki')}]}], 'random_sequence': f'{NS}:blocks/susuki'})
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
    R['blue_dye_from_hydrangea'] = shapeless([P('hydrangea_blue')], 'minecraft:blue_dye', 2)
    R['purple_dye_from_hydrangea'] = shapeless([P('hydrangea_purple')], 'minecraft:purple_dye', 2)
    R['pink_dye_from_hydrangea'] = shapeless([P('hydrangea_pink')], 'minecraft:pink_dye', 2)
    R['red_dye_from_higanbana'] = shapeless([P('higanbana')], 'minecraft:red_dye', 1)
    R['pink_dye_from_lotus'] = shapeless([P('lotus')], 'minecraft:pink_dye', 1)
    R['purple_dye_from_wisteria'] = shapeless([P('wisteria_vine')], 'minecraft:purple_dye', 1)
    R['glowcap_block'] = shaped(['##', '##'], {'#': P('glowcap')}, P('glowcap_block'))
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
    tag('block', 'mineable/hoe', [P(n) for n in ['hikari_moss', 'hikari_moss_carpet', 'momiji_leaves', 'ginkgo_leaves', 'wisteria_leaves', 'tatami',
                                                   'glowcap_block', 'hydrangea_blue', 'hydrangea_purple', 'hydrangea_pink']])
    tag('block', 'mineable/axe', [P('vermilion_planks'), P('glowcap_stem')])
    tag('block', 'leaves', [P(n) for n in LEAVES])
    tag('item', 'leaves', [P(n) for n in LEAVES])
    # shader packs (Photon, Complementary, BSL...) read these through Iris tag support: our plants sway in the wind
    tag('block', 'small_flowers', [P(n) for n in ['higanbana', 'lumen_fern', 'wild_rice', 'wild_soybean', 'wild_daikon', 'wild_wasabi', 'wild_tea']])
    tag('item', 'small_flowers', [P(n) for n in ['higanbana', 'lumen_fern']])
    tag('block', 'climbable', [P('spirit_vine'), P('wisteria_vine')])
    tag('block', 'sword_efficient', [P(n) for n in ['higanbana', 'lumen_fern', 'glowcap', 'susuki', 'spirit_vine', 'wisteria_vine'] + WILD])
    tag('block', 'enderman_holdable', [P('higanbana'), P('glowcap')])
    tag('block', 'crops', [P(c + '_crop') for c in CROPS])
    tag('block', 'maintains_farmland', [P(c + '_crop') for c in CROPS])
    tag('block', 'replaceable_by_trees', [P(w) for w in WILD] + [P(n) for n in ['higanbana', 'lumen_fern', 'glowcap', 'susuki']])
    tag('item', 'villager_plantable_seeds', [P('rice'), P('soybeans'), P('daikon_seeds')])
    tag('block', 'base_stone_overworld', [P('scoria'), P('pumice')])
    tag('block', 'stone_ore_replaceables', [P('scoria'), P('pumice')])
    tag('block', 'dripstone_replaceable_blocks', [P('scoria'), P('pumice')])
    tag('block', 'moss_replaceable', [P('scoria'), P('pumice'), P('volcanic_ash')])
    tag('block', 'substrate_overworld', [P('volcanic_ash'), P('black_sand')])
    print('assets: %d items, %d recipes' % (len(ITEMS), len(R)))


if __name__ == '__main__':
    main()
