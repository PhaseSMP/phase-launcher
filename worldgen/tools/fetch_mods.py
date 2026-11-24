#!/usr/bin/env python3
"""CI helper: download the integrated mods (Fabric, 26.3) with their required dependencies into run/mods,
then write an index of their ids (blocks, items, entities, worldgen, loot, tags) for the integration code."""
import io
import json
import os
import sys
import urllib.parse
import urllib.request
import zipfile

SLUGS = ['this-rocks', 'beautify-refabricated', 'more-armor-trims', 'satoyama', 'fabulous-birds', 'rustic-delight',
         'farmers-delight-refabricated', 'naturalist']
GAME = '26.3'
API = 'https://api.modrinth.com/v2'
HDR = {'User-Agent': 'PhaseSMP/phase-launcher worldgen-ci (github actions)'}


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HDR), timeout=60) as r:
        return r.read()


def versions(pid):
    q = urllib.parse.urlencode({'loaders': json.dumps(['fabric']), 'game_versions': json.dumps([GAME])})
    return json.loads(get(f'{API}/project/{pid}/version?{q}'))


out_dir = sys.argv[1]
os.makedirs(out_dir, exist_ok=True)
seen, queue, index = set(), list(SLUGS), []
DUMP = os.path.join(os.path.dirname(out_dir.rstrip('/')) or '.', 'mod-dump.zip')
dump = zipfile.ZipFile(DUMP, 'w', zipfile.ZIP_DEFLATED)


# classes whose bytecode we need to read (spawn rules, variants, worldgen hooks): keep them raw for javap
CLASS_HINTS = ('fabulousbirds/entity/Songbird', 'fabulousbirds/Birds', 'fabulousbirds/BirdProfile', 'fabulousbirds/FabulousBirds',
               'fabulousbirds/mixin/NaturalSpawner', 'fabulousbirds/registry/ModEntities', 'FabulousBirdsConfig', 'BirdHooks',
               'worldgen', 'WorldGen', 'Worldgen', 'Feature', 'Biome', 'Spawn')


def dump_jar(z, tag, depth=0):
    """Keep every data/ JSON, the mod metadata, lang and blockstate names, so the integration can be written offline."""
    names = z.namelist()
    dump.writestr(f'{tag}/_files.txt', '\n'.join(names))
    for n in names:
        keep = (n.startswith('data/') and n.endswith('.json')) or n == 'fabric.mod.json' or n.endswith('en_us.json') \
            or n.endswith('.mixins.json') or (n.startswith('META-INF/') and n.endswith('.json') and '/data/' in n) \
            or (n.endswith('.class') and any(h in n for h in CLASS_HINTS)) \
            or (n.endswith('.png') and 'textures/entity/songbird' in n)
        if keep:
            try:
                dump.writestr(f'{tag}/{n}', z.read(n))
            except Exception:
                pass
        if depth < 3 and n.startswith('META-INF/jars/') and n.endswith('.jar') and 'fabric-api' not in n and depth > 0:
            try:
                dump_jar(zipfile.ZipFile(io.BytesIO(z.read(n))), tag + '__' + os.path.basename(n), depth + 1)
            except Exception:
                pass
while queue:
    pid = queue.pop(0)
    if pid in seen:
        continue
    seen.add(pid)
    try:
        vs = versions(pid)
    except Exception as e:
        index.append(f'!! {pid}: {e}')
        continue
    if not vs:
        index.append(f'!! {pid}: no fabric {GAME} version')
        continue
    v = vs[0]
    f = next((f for f in v['files'] if f.get('primary')), v['files'][0])
    data = get(f['url'])
    if pid not in ('P7dR8mSH', 'fabric-api'):   # the dev run already has Fabric API on its classpath
        open(os.path.join(out_dir, f['filename']), 'wb').write(data)
    index.append(f'== {pid} {v["version_number"]} {f["filename"]}')
    for d in v.get('dependencies', []):
        if d.get('dependency_type') == 'required' and d.get('project_id'):
            queue.append(d['project_id'])
            index.append(f'   requires {d["project_id"]}')
    z = zipfile.ZipFile(io.BytesIO(data))
    # nested jars (jar-in-jar): dump them too
    for n in list(z.namelist()):
        if n.startswith('META-INF/jars/') and n.endswith('.jar') and 'fabric-api' not in n:
            try:
                dump_jar(zipfile.ZipFile(io.BytesIO(z.read(n))), pid + '__' + os.path.basename(n), 1)
            except Exception as e:
                index.append(f'   nested {n}: {e}')
    dump_jar(z, pid)
    names = z.namelist()
    try:
        fmj = json.loads(z.read('fabric.mod.json'))
        index.append(f'   modid {fmj.get("id")} depends {json.dumps(fmj.get("depends", {}))}')
    except Exception:
        pass
    groups = {}
    for n in names:
        parts = n.split('/')
        if len(parts) >= 4 and parts[0] == 'assets' and parts[2] == 'blockstates':
            groups.setdefault('blocks', []).append(parts[1] + ':' + parts[3][:-5])
        elif len(parts) >= 4 and parts[0] == 'assets' and parts[2] == 'items':
            groups.setdefault('items', []).append(parts[1] + ':' + parts[3][:-5])
        elif len(parts) >= 4 and parts[0] == 'data' and parts[2] in ('worldgen', 'loot_table', 'tags', 'recipe', 'trim_pattern', 'loot_modifiers'):
            groups.setdefault(parts[2], []).append(parts[1] + ':' + '/'.join(parts[3:]))
        elif n.endswith('en_us.json') and '/lang/' in n:
            try:
                lang = json.loads(z.read(n))
                groups.setdefault('entities', []).extend(k for k in lang if k.startswith('entity.'))
            except Exception:
                pass
    for g, vals in sorted(groups.items()):
        index.append(f'   [{g}] ' + ' '.join(sorted(set(vals))))
    for n in names:
        if n.startswith('data/') and ('worldgen/biome_modifier' in n or 'fabric' in n and n.endswith('.json')):
            index.append(f'   file {n}')
dump.close()
open(os.path.join(os.path.dirname(out_dir.rstrip('/')) or '.', 'mod-index.txt'), 'w').write('\n'.join(index) + '\n')
print('\n'.join(index)[:3000])
