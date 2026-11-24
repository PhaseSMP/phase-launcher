#!/usr/bin/env python3
"""Summarise `jfr print --events jdk.ExecutionSample` output: hottest frames on worldgen threads."""
import collections
import re
import sys

self_c = collections.Counter()
incl = collections.Counter()
total = 0
stack, thread, in_stack = [], '', False
frame = re.compile(r'^\s+([\w$.<>]+)\(')
for line in open(sys.argv[1], errors='replace'):
    if line.startswith('jdk.ExecutionSample'):
        stack, thread, in_stack = [], '', False
    elif 'sampledThread' in line:
        thread = line
    elif 'stackTrace = [' in line:
        in_stack = True
    elif in_stack and line.strip().startswith(']'):
        in_stack = False
        if 'Worker' in thread or 'Server thread' in thread or 'worldgen' in thread.lower():
            total += 1
            if stack:
                self_c[stack[0]] += 1
            for f in set(stack):
                incl[f] += 1
    elif in_stack:
        m = frame.match(line)
        if m:
            stack.append(m.group(1))
print(f'samples on worldgen/server threads: {total}')
print('\n== self time (top 35)')
for f, n in self_c.most_common(35):
    print(f'{100.0 * n / max(1, total):5.1f}%  {f}')
print('\n== inclusive, our code and worldgen (top 45)')
for f, n in incl.most_common(400):
    if f.startswith('gg.phase') or 'levelgen' in f or 'NoiseChunk' in f or 'densityfunction' in f.lower():
        print(f'{100.0 * n / max(1, total):5.1f}%  {f}')
