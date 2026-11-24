#!/usr/bin/env python3
"""CI helper: download the integration mods (26.3, Fabric) and their required dependencies from Modrinth
into run/mods, and dump what they contain (blocks, items, worldgen, tags, loot) for the integration work."""
import json
import os
import sys
import urllib.request
import zipfile

MODS = ['this-rocks', 'beautify-refabricated', 'more-armor-trims', 'satoyama', 'fabulous-birds', 'rustic-delight',
        'farmers-delight-refabricated', 'naturalist']
GV, LOADER = '26.3', 'fabric'
OUT_MODS, OUT_DUMP = sys.argv[1], sys.argv[2]
os.makedirs(OUT_MODS, exist_ok=True)
os.makedirs(OUT_DUMP, exist_ok=True)
SKIP = {'fabric-api', 'P7dR8mSH'}


def get(url):
    req = urllib.request.Request(url, headers={'User-Agent': 'PhaseSMP/phase-world-ci'})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


seen = set()
log = []


def fetch(project):
    if project in seen or project in SKIP:
        return
    seen.add(project)
    q = f'https://api.modrinth.com/v2/project/{project}/version?loaders=%5B%22{LOADER}%22%5D&game_versions=%5B%22{GV}%22%5D'
    try:
        versions = json.loads(get(q))
    except Exception as e:
        log.append(f'{project}: API error {e}')
        return
    if not versions:
        log.append(f'{project}: no {GV} {LOADER} version')
        return
    v = versions[0]
    f = next((f for f in v['files'] if f.get('primary')), v['files'][0])
    path = os.path.join(OUT_MODS, f['filename'])
    with open(path, 'wb') as out:
        out.write(get(f['url']))
    log.append(f"{project}: {v['version_number']} -> {f['filename']}")
    for d in v.get('dependencies', []):
        if d.get('dependency_type') == 'required' and d.get('project_id'):
            fetch(d['project_id'])


for m in MODS:
    fetch(m)

for name in sorted(os.listdir(OUT_MODS)):
    if not name.endswith('.jar'):
        continue
    z = zipfile.ZipFile(os.path.join(OUT_MODS, name))
    names = z.namelist()
    base = os.path.join(OUT_DUMP, name[:-4])
    os.makedirs(base, exist_ok=True)
    with open(base + '/files.txt', 'w') as fo:
        fo.write('\n'.join(n for n in names if not n.endswith('.class') and not n.endswith('.png') and not n.endswith('.ogg')))
    keep = ('fabric.mod.json',)
    for n in names:
        if n in keep or (n.startswith('data/') and n.endswith('.json') and any(k in n for k in (
                '/worldgen/', '/tags/', '/loot_table/', '/loot_modifiers/', 'loot_tables/', '/recipe/smithing', 'trim', 'spawn'))) \
                or (n.startswith('assets/') and n.endswith('lang/en_us.json')):
            dst = os.path.join(base, n)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            with open(dst, 'wb') as fo:
                fo.write(z.read(n))
    # nested jars (jar-in-jar libraries) are listed only
print('\n'.join(log))
open(os.path.join(OUT_DUMP, 'fetch.log'), 'w').write('\n'.join(log) + '\n')
