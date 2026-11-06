"""Disposable differential MC-252934 test. Manual, no installation or service changes.

Run inside systemd-run --user --scope --unit=saori-ensayo609-UNIQUE
-p CPUQuota=200% -p MemoryMax=4G. Only copies structure templates from the pack.
"""
import argparse
import hashlib
import json
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import zipfile

SHAPES = ['stronghold/sculk_lab', 'stronghold/portal_room_alt_2', 'village/butcher_big']
CASES = [(0, rotation, 'none') for rotation in
         ('none', 'clockwise_90', '180', 'counterclockwise_90')]
CASES += [(0, 'none', mirror) for mirror in ('left_right', 'front_back')]
CASES += [(1, 'none', 'none'), (2, 'none', 'none')]


def runtime_libraries(bootstrap, libraries):
    """Use the launcher's exact dependency versions; plugin caches may contain incompatible ones."""
    result = []
    with zipfile.ZipFile(bootstrap) as jar:
        for line in jar.read('META-INF/libraries.list').decode().splitlines():
            digest, _, relative = line.split('\t')
            path = libraries / relative
            if Path(relative).is_absolute() or '..' in Path(relative).parts:
                raise ValueError('Unsafe bootstrap library path')
            if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise ValueError('Bootstrap library checksum mismatch: ' + relative)
            result.append(path)
    if not result:
        raise ValueError('Empty bootstrap library list')
    return result


def run_case(args, variant):
    root = args.output / variant
    root.mkdir()
    (root / 'eula.txt').write_text('eula=true\n')
    (root / 'server.properties').write_text('\n'.join([
        'server-ip=127.0.0.1', 'server-port=0', 'online-mode=true', 'white-list=true',
        'enable-rcon=false', 'enable-query=false', 'management-server-enabled=false',
        'pause-when-empty-seconds=-1', 'level-seed=609', 'level-type=minecraft:flat',
        'generator-settings=' + json.dumps({'biome': 'minecraft:plains', 'layers': [
            {'block': 'minecraft:bedrock', 'height': 1}]}),
        'generate-structures=false', 'view-distance=2', 'simulation-distance=2',
        'allow-nether=false', 'max-tick-time=60000',
        'initial-enabled-packs=vanilla,file/609.zip', '']))
    (root / 'bukkit.yml').write_text('settings:\n  allow-end: false\n')
    packs = root / 'world/datapacks'
    packs.mkdir(parents=True)
    with zipfile.ZipFile(args.pack) as source, zipfile.ZipFile(packs / '609.zip', 'w') as fixture:
        fixture.writestr('pack.mcmeta', json.dumps({'pack': {'min_format': 94,
            'max_format': 999, 'description': 'Disposable MC-252934 structure fixture'}}))
        for index, shape in enumerate(SHAPES):
            fixture.writestr(f'data/saori609/structure/shape{index}.nbt',
                source.read('data/stellarity/structure/' + shape + '.nbt'))
    jars = [args.runtime] + args.verified_libraries
    cp = ([args.overlay] if variant == 'candidate' else []) + jars
    lines, events = [], queue.Queue()
    with (root / 'console.log').open('w') as log:
        proc = subprocess.Popen([
            '/usr/lib/jvm/java-25-openjdk-amd64/bin/java', '-Xms512M', '-Xmx2G',
            '-XX:ActiveProcessorCount=2', '-Dterminal.jline=false', '-Dterminal.ansi=false',
            '-cp', ':'.join(map(str, cp)), 'org.bukkit.craftbukkit.Main', '--nogui'],
            cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)

        def reader():
            for line in proc.stdout:
                log.write(line); log.flush(); lines.append(line.rstrip()); events.put(line)

        thread = threading.Thread(target=reader, daemon=True)
        thread.start()

        def send(command):
            proc.stdin.write(command + '\n'); proc.stdin.flush()

        def wait_for(pattern, timeout=90):
            until = time.monotonic() + timeout
            while time.monotonic() < until:
                try:
                    line = events.get(timeout=.5)
                    if re.search(pattern, line):
                        return line
                except queue.Empty:
                    if proc.poll() is not None:
                        raise RuntimeError('Runtime exited before expected marker')
            raise TimeoutError('Expected marker absent: ' + pattern)

        result = {'variant': variant, 'cases': []}
        try:
            wait_for(r'Done \(', 180)
            print(variant + ': Done', flush=True)
            for index, (shape, rotation, mirror) in enumerate(CASES):
                x, z = 4096 + index * 192, 4096
                send(f'forceload add {x-64} {z-64} {x+63} {z+63}')
                pending = {(a, b) for a in range(x-64, x+64, 16) for b in range(z-64, z+64, 16)}
                until = time.monotonic() + 90
                while pending and time.monotonic() < until:
                    for a, b in sorted(pending):
                        send(f'execute if loaded {a} 80 {b} run say READY609_{a}_{b}')
                    send('say ROUND609')
                    while True:
                        line = wait_for(r'\[Server\] (?:READY609_-?\d+_-?\d+|ROUND609)\s*$')
                        if re.search(r'\[Server\] ROUND609\s*$', line): break
                        match = re.search(r'READY609_(-?\d+)_(-?\d+)\s*$', line)
                        pending.discard(tuple(map(int, match.groups())))
                    if pending: time.sleep(.5)
                if pending: raise RuntimeError('Chunks not loaded')
                start = len(lines)
                send(f'place template saori609:shape{shape} {x} 20 {z} {rotation} {mirror}')
                send('say END609_' + str(index))
                wait_for(r'\[Server\] END609_' + str(index) + r'\s*$')
                segment = lines[start:]
                case = {'shape': SHAPES[shape], 'rotation': rotation, 'mirror': mirror,
                        'placed': sum('Loaded template "saori609:shape' in line for line in segment),
                        'anchor_errors': sum('Block-attached entity at invalid position' in line for line in segment)}
                result['cases'].append(case)
                # Query actual entities before unloading, without copying their UUIDs or item contents into the report.
                for entity in ('item_frame', 'glow_item_frame', 'painting'):
                    send(f'execute if entity @e[type=minecraft:{entity}]')
                send('say COUNT609_' + str(index))
                wait_for(r'\[Server\] COUNT609_' + str(index) + r'\s*$')
                count_lines = lines[start:]
                case['entity_matches'] = [int(m.group(1)) for line in count_lines
                    if (m := re.search(r'Test passed\. Count: (\d+)', line))]
                send('forceload remove all')
                print(json.dumps(case), flush=True)
            send('save-all flush'); wait_for(r'Saved the game')
        except Exception as exc:
            result['failure'] = str(exc)
        finally:
            if proc.poll() is None:
                send('stop')
                try: proc.wait(timeout=120)
                except subprocess.TimeoutExpired: result['shutdown_timeout'] = True
            thread.join(timeout=5)
            result['exit'] = proc.poll()
            result['saved'] = any('All dimensions are saved' in line for line in lines)
            result['errors'] = sum(bool(re.search(r'\bERROR\]', line)) for line in lines)
            result['anchor_errors'] = sum('Block-attached entity at invalid position' in line for line in lines)
            result['watchdog'] = any('DO NOT REPORT THIS TO PAPER' in line for line in lines)
            (root / 'summary.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('bootstrap', 'runtime', 'libraries', 'overlay', 'pack', 'output'):
        p.add_argument('--' + name, required=True, type=Path)
    args = p.parse_args()
    if 'saori-ensayo609-' not in Path('/proc/self/cgroup').read_text():
        raise RuntimeError('Use the documented resource-limited scope')
    if subprocess.run(['pgrep', '-x', 'java'], capture_output=True).returncode == 0:
        raise RuntimeError('Another JVM is running')
    if float(Path('/proc/loadavg').read_text().split()[0]) >= 5:
        raise RuntimeError('Host load >= 5; defer runtime test')
    args.verified_libraries = runtime_libraries(args.bootstrap, args.libraries)
    args.output.mkdir()  # Never reuse a world or overwrite an earlier test.
    hashes = {key: hashlib.sha256(getattr(args, key).read_bytes()).hexdigest()
              for key in ('bootstrap', 'runtime', 'pack')}
    results = []
    for variant in ('base', 'candidate'):
        result = run_case(args, variant); results.append(result)
        if result.get('failure') or result['exit'] != 0 or not result['saved'] or result['watchdog']:
            break
    passed = (len(results) == 2 and all(len(r['cases']) == len(CASES)
              and not r.get('failure') and r['exit'] == 0 and r['saved']
              and not r['watchdog'] and all(c['placed'] == 1 for c in r['cases']) for r in results)
              and results[0]['anchor_errors'] > 0
              and results[0]['errors'] == results[0]['anchor_errors'] and results[1]['errors'] == 0
              and all(a['entity_matches'] == b['entity_matches'] and a['entity_matches']
                      for a, b in zip(results[0]['cases'], results[1]['cases'])))
    report = {'passed': bool(passed), 'inputs': hashes, 'results': results}
    (args.output / 'summary.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
