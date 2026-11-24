# Как добавить свой данж

Данжи Phase — обычные jigsaw-структуры Minecraft. Java не нужна: достаточно NBT-постройки и трёх JSON.
За основу берите встроенные `forgotten_shrine` и `volcano_forge`.

## 1. Постройте и сохраните

1. В творческом мире постройте данж. Сундуки оставьте пустыми, лут подставится сам.
2. Поставьте блок структуры (`/give @s structure_block`) в режиме **Save**, задайте имя
   `phaseworld:dungeon/<имя>/variant_0` и размер (до 48×48×48), нажмите **Save**.
3. Файл появится в `saves/<мир>/generated/phaseworld/structure/dungeon/<имя>/variant_0.nbt`.
   Скопируйте его в `src/main/resources/data/phaseworld/structure/dungeon/<имя>/`.
   Чем больше вариантов (`variant_1`, `variant_2` …), тем разнообразнее данж.

Лут в сундук назначается командой, пока вы строите (смотрите на сундук):
`/data merge block ~ ~ ~ {LootTable:"phaseworld:chests/shrine"}`.
Спавнер: `/data merge block ~ ~ ~ {SpawnData:{entity:{id:"minecraft:skeleton"}}}`.

## 2. Три JSON (замените `<имя>`)

`data/phaseworld/worldgen/template_pool/dungeon/<имя>/start.json` — какие варианты бывают:

```json
{
  "fallback": "minecraft:empty",
  "elements": [
    {"weight": 1, "element": {"element_type": "minecraft:single_pool_element",
      "location": "phaseworld:dungeon/<имя>/variant_0",
      "processors": "phaseworld:dungeon_decay", "projection": "rigid"}}
  ]
}
```

`data/phaseworld/worldgen/structure/dungeon/<имя>.json` — где и на какой высоте:

```json
{
  "type": "minecraft:jigsaw",
  "biomes": "#phaseworld:has_dungeon/<имя>",
  "step": "underground_structures",
  "spawn_overrides": {},
  "terrain_adaptation": "encapsulate",
  "start_pool": "phaseworld:dungeon/<имя>/start",
  "size": 1,
  "start_height": {"type": "minecraft:uniform", "min_inclusive": {"absolute": -40}, "max_inclusive": {"absolute": 40}},
  "max_distance_from_center": 80,
  "use_expansion_hack": false
}
```

Для данжа на поверхности укажите `"step": "surface_structures"`, `"terrain_adaptation": "beard_thin"`,
`"start_height": {"absolute": 0}` и добавьте `"project_start_to_heightmap": "WORLD_SURFACE_WG"`.

`data/phaseworld/worldgen/structure_set/dungeon/<имя>.json` — как часто:

```json
{
  "placement": {"type": "minecraft:random_spread", "spacing": 30, "separation": 12, "salt": 71503299},
  "structures": [{"structure": "phaseworld:dungeon/<имя>", "weight": 1}]
}
```

`spacing` — размер сетки в чанках: в каждой клетке не больше одного данжа. 30 чанков — это примерно
один данж на 480×480 блоков. `salt` — любое уникальное число.

## 3. Биомы

`data/phaseworld/tags/worldgen/biome/has_dungeon/<имя>.json`:

```json
{"values": ["#phaseworld:caves"]}
```

Готовые группы: `#phaseworld:caves` (все пещерные биомы), `#phaseworld:surface` (вся суша),
`#phaseworld:all`. Можно перечислить и отдельные биомы, например `"phaseworld:magma_tubes"`,
`"phaseworld:sakura_valley"`. Полный список — в `src/main/resources/data/phaseworld/worldgen/biome/`.

Если данж в пещерном биоме, его старт должен попасть под землю: высота `start_height` должна быть
хотя бы на 15 блоков ниже поверхности.

## 4. Лут

Сундуки берут таблицы из `data/phaseworld/loot_table/chests/`. Скопируйте `shrine.json`,
переименуйте и правьте предметы и веса.

## 5. Проверка

Найти данж в игре: `/locate structure phaseworld:dungeon/<имя>`.
Поставить прямо сейчас: `/place structure phaseworld:dungeon/<имя>`.

## Ванильные структуры

Сейчас они выключены: биомы Phase не входят в ванильные теги вроде `#minecraft:is_overworld` и
`#minecraft:has_structure/...`. Чтобы включить, например, крепости и порталы в Энд, добавьте файл
`data/minecraft/tags/worldgen/biome/has_structure/stronghold.json`:

```json
{"replace": false, "values": ["#phaseworld:surface"]}
```
