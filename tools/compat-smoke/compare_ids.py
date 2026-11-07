#!/usr/bin/env python3
"""Cobertura verificable por CONJUNTO DE IDS: registro de la instalacion de prueba vs volcado de produccion.

Uso: compare_ids.py --dump slimefun-dump.json.gz --registered registered-ids.tsv [--aliases aliases.json] --out DIR
Clasifica cada id esperado como: presente (mismo addon), presente_otro_addon (renombre/consolidacion), ausente.
Reporta tambien ids no esperados (nuevos en 26.x). No inventa nada: solo cuenta ids reales de ambos lados.
"""
import argparse, collections, gzip, json, os

ap = argparse.ArgumentParser()
ap.add_argument('--dump', required=True); ap.add_argument('--registered', required=True)
ap.add_argument('--aliases'); ap.add_argument('--out', required=True)
a = ap.parse_args()
os.makedirs(a.out, exist_ok=True)
alias = json.load(open(a.aliases)) if a.aliases and os.path.isfile(a.aliases) else {}
exp = {i['id']: i['addon'] for i in json.load(gzip.open(a.dump))['items']}
reg = {}
for n, line in enumerate(open(a.registered, encoding='utf-8')):
    if n == 0: continue
    p = line.rstrip('\n').split('\t')
    if p and p[0]: reg[p[0]] = p[1] if len(p) > 1 else ''
canon = lambda n: alias.get(n, n)
per = collections.defaultdict(lambda: {'expected': 0, 'present': 0, 'present_other_addon': 0, 'missing': 0, 'missing_sample': [], 'other_addon_names': collections.Counter()})
for i, ad in exp.items():
    r = per[ad]; r['expected'] += 1
    if i in reg:
        if canon(reg[i]) == canon(ad): r['present'] += 1
        else: r['present_other_addon'] += 1; r['other_addon_names'][reg[i]] += 1
    else:
        r['missing'] += 1
        if len(r['missing_sample']) < 5: r['missing_sample'].append(i)
extra = [i for i in reg if i not in exp]
tot = {'expected': len(exp), 'registered': len(reg), 'present': sum(r['present'] for r in per.values()),
       'present_other_addon': sum(r['present_other_addon'] for r in per.values()),
       'missing': sum(r['missing'] for r in per.values()), 'unexpected_new_ids': len(extra)}
tot['coverage_pct'] = round(100.0 * (tot['present'] + tot['present_other_addon']) / max(1, tot['expected']), 2)
no_cec = {k: v for k, v in per.items() if k != 'ColoredEnderChests'}
e2 = sum(r['expected'] for r in no_cec.values()); p2 = sum(r['present'] + r['present_other_addon'] for r in no_cec.values())
tot['coverage_pct_without_ColoredEnderChests'] = round(100.0 * p2 / max(1, e2), 2)
complete = sorted(k for k, r in per.items() if r['missing'] == 0)
res = {'totals': tot, 'addons_complete': complete, 'addons_complete_count': len(complete), 'addons_expected': len(per),
       'by_addon': {k: dict(v, other_addon_names=dict(v['other_addon_names'])) for k, v in sorted(per.items())},
       'unexpected_sample': sorted(extra)[:50]}
json.dump(res, open(os.path.join(a.out, 'coverage.json'), 'w'), indent=1, ensure_ascii=False)
md = ['# Cobertura por ids (instalacion de prueba vs volcado de produccion)\n', '| Medida | Valor |', '|---|---|']
md += [f'| {k} | {v} |' for k, v in tot.items()]
md += [f'| addons completos | {len(complete)} de {len(per)} |', '', '## Por addon (ordenado por ids ausentes)\n',
       '| addon | esperados | presentes | presentes bajo otro addon | ausentes |', '|---|---|---|---|---|']
for k, r in sorted(per.items(), key=lambda kv: -kv[1]['missing']):
    md.append(f"| {k} | {r['expected']} | {r['present']} | {r['present_other_addon']} | {r['missing']} |")
open(os.path.join(a.out, 'coverage.md'), 'w').write('\n'.join(md) + '\n')
print(json.dumps(tot))
print('addons completos: %d de %d' % (len(complete), len(per)))
for k, r in sorted(per.items(), key=lambda kv: -kv[1]['missing'])[:25]:
    print('  %-28s esperados=%-5d presentes=%-5d otro_addon=%-4d ausentes=%d' % (k, r['expected'], r['present'], r['present_other_addon'], r['missing']))
