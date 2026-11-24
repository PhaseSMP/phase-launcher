# Optional mods: features of other mods, added to our biomes from Java (BiomeModifications) only when that mod
# is installed. Rows are (mod id, placed feature, generation step, biomes). A feature in our namespace is a copy
# with our own placement and a fabric:load_conditions guard, so the data pack still loads without the mod.
# Called from gen_worldgen.py (needs its helpers and the biome table B).
import json
import os
import zipfile

DUMP = os.path.join(os.path.dirname(__file__), '..', '..', 'ci-worldgen', 'mod-dump.zip')

VEG, ORES, UNDER = 9, 6, 7
CONIFER = ['sugi_forest', 'jukai_forest', 'pine_cliffs', 'fuji_subalpine']
MOUNTAIN = ['alpine_peaks', 'alpine_meadow', 'pine_cliffs', 'snowy_highlands', 'fuji_slopes', 'fuji_subalpine', 'momiji_highlands',
            'tsutsuji_hills', 'shirakaba_plateau', 'karst_spires', 'rocky_shore']
SANDY = ['beach', 'sand_dunes', 'black_sand_beach']
WARM = ['tropical_forest', 'karst_spires', 'mangrove_coast']


def rows(P):
    return [
        # This Rocks: pebbles, sticks, cones and shells where they belong
        ('rocks', 'rocks:rock', VEG, MOUNTAIN + ['river', 'tea_hills', 'sugi_forest', 'onsen_valley']),
        ('rocks', 'rocks:andesite_rock', VEG, ['fuji_slopes', 'fuji_subalpine', 'onsen_valley', 'jigoku_wastes', 'alpine_peaks']),
        ('rocks', 'rocks:granite_rock', VEG, ['pine_cliffs', 'rocky_shore', 'karst_spires']),
        ('rocks', 'rocks:diorite_rock', VEG, ['alpine_peaks', 'alpine_meadow', 'snowy_highlands']),
        ('rocks', 'rocks:gravel_rock', VEG, ['river', 'rocky_shore', 'black_sand_beach', 'reed_marsh']),
        ('rocks', 'rocks:sand_rock', VEG, ['beach', 'sand_dunes']),
        ('rocks', 'rocks:ice_rock', VEG, ['frozen_summits', 'snowy_highlands']),
        ('rocks', 'rocks:oak_stick', VEG, ['momiji_highlands', 'ginkgo_grove', 'wisteria_grove', 'hydrangea_hills', 'tsutsuji_hills', 'tea_hills']),
        ('rocks', 'rocks:cherry_stick', VEG, ['sakura_valley']),
        ('rocks', 'rocks:spruce_stick', VEG, CONIFER + ['snowy_highlands']),
        ('rocks', 'rocks:birch_stick', VEG, ['shirakaba_plateau']),
        ('rocks', 'rocks:dark_oak_stick', VEG, ['spirit_forest', 'jukai_forest']),
        ('rocks', 'rocks:jungle_stick', VEG, ['tropical_forest', 'yakushima_moss']),
        ('rocks', 'rocks:mangrove_stick', VEG, ['mangrove_coast']),
        ('rocks', 'rocks:bamboo_stick', VEG, ['bamboo_grove']),
        ('rocks', 'rocks:pinecone', VEG, CONIFER + ['snowy_highlands']),
        ('rocks', 'rocks:seashell', VEG, SANDY + ['rocky_shore']),
        ('rocks', 'rocks:starfish', VEG, ['beach', 'black_sand_beach']),
        ('rocks', 'rocks:underwater_seashell', VEG, ['coral_coast', 'ocean', 'inner_sea']),
        ('rocks', 'rocks:underwater_starfish', VEG, ['coral_coast']),
        ('rocks', 'rocks:snowy_geyser', VEG, ['frozen_summits']),
        ('rocks', P('compat/geysers'), VEG, ['onsen_valley', 'jigoku_wastes']),
        # Farmer's Delight: wild crops by climate (copies without FD's own is_overworld biome-tag filter)
        ('farmersdelight', P('compat/wild_cabbages'), VEG, SANDY),
        ('farmersdelight', P('compat/wild_beetroots'), VEG, SANDY),
        ('farmersdelight', P('compat/sandy_shrub'), VEG, SANDY),
        ('farmersdelight', P('compat/wild_carrots'), VEG, ['tea_hills', 'susuki_plains', 'hydrangea_hills', 'alpine_meadow', 'sakura_valley']),
        ('farmersdelight', P('compat/wild_onions'), VEG, ['tea_hills', 'higanbana_fields', 'tsutsuji_hills', 'susuki_plains']),
        ('farmersdelight', P('compat/wild_potatoes'), VEG, ['shirakaba_plateau', 'alpine_meadow', 'momiji_highlands', 'fuji_subalpine']),
        ('farmersdelight', P('compat/wild_tomatoes'), VEG, ['tropical_forest', 'karst_spires', 'onsen_valley']),
        ('farmersdelight', P('compat/wild_rice'), VEG, ['reed_marsh', 'lotus_marsh', 'rice_terraces', 'mangrove_coast', 'river']),
        ('farmersdelight', P('compat/brown_mushroom_colony'), UNDER, ['mushroom_caverns']),
        ('farmersdelight', P('compat/red_mushroom_colony'), UNDER, ['mushroom_caverns']),
        # Rustic Delight: peppers and coffee in the warm south, cotton on the dry grass plains
        ('rusticdelight', 'rusticdelight:wild_bell_peppers_placed', VEG, WARM),
        ('rusticdelight', 'rusticdelight:wild_coffee_placed', VEG, ['tropical_forest', 'karst_spires']),
        ('rusticdelight', 'rusticdelight:bell_pepper_block_patch_placed', VEG, ['tropical_forest']),
        ('rusticdelight', 'rusticdelight:wild_cotton_placed', VEG, ['susuki_plains', 'sand_dunes']),
        # Satoyama: the Japanese countryside. Fruit groves by the fields, sansai in the forests, reeds, igusa and
        # lotus in the marshes, iron sand on black beaches. No ores, no structures, nothing in the way.
        ('satoyama_uekiya', 'satoyama_uekiya:tea_grove', VEG, ['tea_hills']),
        ('satoyama_uekiya', 'satoyama_uekiya:ume_grove', VEG, ['sakura_valley', 'rice_terraces']),
        ('satoyama_uekiya', 'satoyama_uekiya:kaki_grove', VEG, ['rice_terraces', 'momiji_highlands']),
        ('satoyama_uekiya', 'satoyama_uekiya:chestnut_grove', VEG, ['momiji_highlands', 'tsutsuji_hills']),
        ('satoyama_uekiya', 'satoyama_uekiya:konara_grove', VEG, ['momiji_highlands', 'ginkgo_grove']),
        ('satoyama_uekiya', 'satoyama_uekiya:kunugi_grove', VEG, ['tsutsuji_hills', 'hydrangea_hills']),
        ('satoyama_uekiya', 'satoyama_uekiya:hinoki_grove', VEG, ['sugi_forest', 'fuji_subalpine']),
        ('satoyama_uekiya', 'satoyama_uekiya:matsu_grove', VEG, ['rocky_shore', 'pine_cliffs']),
        ('satoyama_uekiya', 'satoyama_uekiya:mikan_grove', VEG, ['onsen_valley', 'karst_spires']),
        ('satoyama_uekiya', 'satoyama_uekiya:yuzu_grove', VEG, ['onsen_valley']),
        ('satoyama_uekiya', 'satoyama_uekiya:shikuwasa_grove', VEG, ['tropical_forest', 'mangrove_coast']),
        ('satoyama_uekiya', 'satoyama_uekiya:hydrangea_patch', VEG, ['hydrangea_hills']),
        ('satoyama_uekiya', 'satoyama_uekiya:hydrangea_tree_blue', VEG, ['hydrangea_hills']),
        ('satoyama_uekiya', 'satoyama_uekiya:hydrangea_tree_purple', VEG, ['hydrangea_hills']),
        ('satoyama_yaoya', 'satoyama_yaoya:sasa_patch', VEG, ['sugi_forest', 'jukai_forest', 'shirakaba_plateau', 'bamboo_grove']),
        ('satoyama_yaoya', 'satoyama_yaoya:fuki_patch', VEG, ['river', 'sugi_forest', 'yakushima_moss']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_shiso', VEG, ['rice_terraces', 'tea_hills']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_myoga', VEG, ['bamboo_grove', 'spirit_forest']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_mitsuba', VEG, ['sugi_forest', 'hydrangea_hills']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_wisteria', VEG, ['wisteria_grove']),
        ('satoyama_yaoya', 'satoyama_yaoya:rapeseed_field', VEG, ['susuki_plains', 'higanbana_fields']),
        ('satoyama_yaoya', 'satoyama_yaoya:reed_thicket', VEG, ['reed_marsh', 'river']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_igusa', VEG, ['reed_marsh', 'lotus_marsh']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_hasu', VEG, ['lotus_marsh']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_strawberry', VEG, ['alpine_meadow', 'tsutsuji_hills']),
        ('satoyama_yaoya', 'satoyama_yaoya:wild_yama_budo', VEG, ['momiji_highlands', 'ginkgo_grove']),
        ('satoyama_yaoya', 'satoyama_yaoya:akebi_vine_north', VEG, ['momiji_highlands', 'sugi_forest']),
        ('satoyama_yaoya', 'satoyama_yaoya:akebi_vine_south', VEG, ['momiji_highlands', 'sugi_forest']),
        ('satoyama_yaoya', 'satoyama_yaoya:banana_stand', VEG, ['tropical_forest']),
        ('satoyama_sakanaya', 'satoyama_sakanaya:wild_nori', VEG, ['rocky_shore', 'coral_coast']),
        ('satoyama_kajiya', 'satoyama_kajiya:satetsu_sand', ORES, ['black_sand_beach', 'river']),
        ('satoyama_shikomiya', 'satoyama_shikomiya:brine_sand', ORES, ['beach']),
        ('satoyama_zaimokuya', 'satoyama_zaimokuya:diatomite_bed', ORES, ['lotus_marsh', 'reed_marsh']),
    ]


JAVA = """package gg.phase.world.compat;

/** Generated by tools/gen_compat.py: features of optional mods, per biome. Do not edit. */
final class CompatTable {
	private CompatTable() {
	}

	/** mod id, placed feature, generation step ordinal, comma-separated phaseworld biomes. */
	static final String[][] FEATURES = {
%s
	};
}
"""


def generate(g):
    """g: the gen_worldgen module namespace (helpers, NS, B, write)."""
    P, placed, rarity, count, IN_SQUARE, BIOME = g['P'], g['placed'], g['rarity'], g['count'], g['IN_SQUARE'], g['BIOME']
    heightmap, spread, SOLID_BELOW, cave_floor, write, NS, B = g['heightmap'], g['spread'], g['SOLID_BELOW'], g['cave_floor'], g['write'], g['NS'], g['B']

    def guarded(mod, obj):
        obj['fabric:load_conditions'] = [{'condition': 'fabric:all_mods_loaded', 'values': [mod]}]
        return obj

    fd = 'farmersdelight'
    sand_below = {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
        {'type': 'minecraft:matching_blocks', 'blocks': 'minecraft:air'},
        {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:sand', 'offset': [0, -1, 0]}]}}
    ground = heightmap('MOTION_BLOCKING_NO_LEAVES')

    def crop(name, chance):
        return placed(f'{fd}:{name}', [rarity(chance), IN_SQUARE, count(64), ground, BIOME])

    rice_spot = {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:all_of', 'predicates': [
        {'type': 'minecraft:matching_blocks', 'blocks': 'minecraft:air', 'offset': [0, 1, 0]},
        {'type': 'minecraft:matching_blocks', 'blocks': 'minecraft:water'},
        {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:substrate_overworld', 'offset': [0, -1, 0]}]}}
    pf = {
        'wild_cabbages': crop('wild_cabbages', 18), 'wild_beetroots': crop('wild_beetroots', 18),
        'wild_carrots': crop('wild_carrots', 40), 'wild_onions': crop('wild_onions', 40),
        'wild_potatoes': crop('wild_potatoes', 40), 'wild_tomatoes': crop('wild_tomatoes', 30),
        'sandy_shrub': placed(f'{fd}:sandy_shrub', [rarity(6), IN_SQUARE, ground, BIOME] + spread(3, 2, 24) + [sand_below]),
        'wild_rice': placed(f'{fd}:wild_rice', [rarity(10), IN_SQUARE, heightmap('OCEAN_FLOOR_WG'), BIOME] + spread(7, 3, 96) + [rice_spot]),
        'brown_mushroom_colony': placed(f'{fd}:brown_mushroom_colony', cave_floor(4)),
        'red_mushroom_colony': placed(f'{fd}:red_mushroom_colony', cave_floor(3)),
    }
    for k, v in pf.items():
        write(NS, 'worldgen/placed_feature/compat/' + k, guarded(fd, v))
    # This Rocks geysers by the hot springs (plain, not snowy), on solid ground
    write(NS, 'worldgen/placed_feature/compat/geysers', guarded('rocks', placed(
        {'type': 'minecraft:simple_block', 'to_place': {'type': 'minecraft:simple', 'state': {'id': 'rocks:geyser'}}},
        [rarity(3), IN_SQUARE, ground, BIOME, SOLID_BELOW,
         {'type': 'minecraft:block_predicate_filter', 'predicate': {'type': 'minecraft:matching_block_tag', 'tag': 'minecraft:air'}}])))

    # Satoyama spreads its plants with a "regional palette": a few kinds per 32x32-chunk region, rationed per
    # vanilla biome. Our biomes are not in its budgets, so the copies trade the palette for a plain rarity.
    dump = zipfile.ZipFile(DUMP)
    names = set(dump.namelist())

    def satoyama_copy(mod, feat):
        name = feat.split(':')[1]
        src = f'satoyama__runtime-26.3.jar__{mod}.jar/data/{mod}/worldgen/placed_feature/{name}.json'
        assert src in names, src
        d = json.loads(dump.read(src))
        pl = d['placement']
        if not any(m.get('type') == 'satoyama:regional_palette' for m in pl):
            return feat
        chance = 5 if name.endswith('_grove') or name.endswith('_stand') else 3
        d['placement'] = [rarity(chance) if m.get('type') == 'satoyama:regional_palette' else m for m in pl]
        write(NS, f'worldgen/placed_feature/compat/{mod}/{name}', guarded(mod, d))
        return P(f'compat/{mod}/{name}')

    out = []
    for mod, feat, step, bs in rows(P):
        if mod.startswith('satoyama_'):
            feat = satoyama_copy(mod, feat)
        missing = [b for b in bs if b not in B]
        assert not missing, (feat, missing)
        out.append('\t\t{"%s", "%s", "%d", "%s"},' % (mod, feat, step, ','.join(dict.fromkeys(bs))))
    path = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'java', 'gg', 'phase', 'world', 'compat', 'CompatTable.java')
    with open(path, 'w') as f:
        f.write(JAVA % '\n'.join(out))
    return len(out)
