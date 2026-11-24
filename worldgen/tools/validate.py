#!/usr/bin/env python3
"""Checks that every namespaced id referenced in our JSON exists (vanilla 26.3 registries/data or our own files/code)."""
import json
import os
import re
import sys

REF = '/home/claude/ref'
ROOT = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'resources')
JAVA = os.path.join(os.path.dirname(__file__), '..', 'src', 'main', 'java')

known = set()
reg = json.load(open(f'{REF}/mcsummary/registries/data.json'))
for k, vals in reg.items():
    for v in vals:
        known.add('minecraft:' + v)
# vanilla data files and tags
for base, ns in ((f'{REF}/mcdata/data', None),):
    for dp, dn, fn in os.walk(base):
        for f in fn:
            if not f.endswith('.json'):
                continue
            rel = os.path.relpath(os.path.join(dp, f), base)[:-5].split(os.sep)
            namespace = rel[0]
            if rel[1] == 'tags':
                known.add('#%s:%s' % (namespace, '/'.join(rel[3:])))
                known.add('#%s:%s' % (namespace, '/'.join(rel[4:])))
            for cut in range(2, 5):
                known.add('%s:%s' % (namespace, '/'.join(rel[cut:])))
# vanilla models / textures
for dp, dn, fn in os.walk(f'{REF}/mcassets/assets/minecraft/models'):
    for f in fn:
        rel = os.path.relpath(os.path.join(dp, f), f'{REF}/mcassets/assets/minecraft/models')[:-5]
        known.add('minecraft:' + rel)
known.update({'minecraft:binomial_with_bonus_count', 'minecraft:ore_drops', 'minecraft:uniform_bonus_count'})
# vanilla sounds
sounds = json.load(open(f'{REF}/mcassets/assets/minecraft/sounds.json'))
known.update('minecraft:' + s for s in sounds)

ours = set()
for dp, dn, fn in os.walk(ROOT):
    for f in fn:
        rel = os.path.relpath(os.path.join(dp, f), ROOT).split(os.sep)
        if rel[0] == 'data' and f.endswith(('.json', '.nbt')):
            ns = rel[1]
            parts = rel[2:]
            parts[-1] = os.path.splitext(parts[-1])[0]
            if parts[0] == 'tags':
                ours.add('#%s:%s' % (ns, '/'.join(parts[2:])))
                ours.add('#%s:%s' % (ns, '/'.join(parts[3:])))
            for cut in range(1, 4):
                ours.add('%s:%s' % (ns, '/'.join(parts[cut:])))
        if rel[0] == 'assets' and f.endswith(('.json', '.png')):
            ns = rel[1]
            parts = rel[2:]
            parts[-1] = os.path.splitext(parts[-1])[0]
            for cut in range(1, 3):
                ours.add('%s:%s' % (ns, '/'.join(parts[cut:])))
# ids registered from code
for dp, dn, fn in os.walk(JAVA):
    for f in fn:
        src = open(os.path.join(dp, f)).read()
        for m in re.finditer(r'(?:reg|seed|food|plain|bowl)\("([a-z0-9_]+)"', src):
            ours.add('phaseworld:' + m.group(1))
        for m in re.finditer(r'id\("([a-z0-9_]+)"\)', src):
            ours.add('phaseworld:' + m.group(1))

ID = re.compile(r'^#?(minecraft|phaseworld):[a-z0-9_./-]+$')
bad = {}
skip_keys = {'type', 'predicate_type', 'sound', 'parent'}


def walk(node, path, key=None):
    if isinstance(node, dict):
        for k, v in node.items():
            walk(v, path, k)
    elif isinstance(node, list):
        for v in node:
            walk(v, path, key)
    elif isinstance(node, str) and ID.match(node):
        pool = known | ours
        if node not in pool and node.lstrip('#') not in pool:
            if key in ('type', 'predicate_type'):
                return
            bad.setdefault(node, set()).add(path)


for dp, dn, fn in os.walk(ROOT):
    for f in fn:
        if f.endswith('.json'):
            p = os.path.join(dp, f)
            try:
                walk(json.load(open(p)), os.path.relpath(p, ROOT))
            except json.JSONDecodeError as e:
                print('BAD JSON', p, e)
for k in sorted(bad):
    print('UNKNOWN', k, '<-', ', '.join(sorted(bad[k]))[:200])
print('unknown ids:', len(bad))
sys.exit(1 if bad else 0)
