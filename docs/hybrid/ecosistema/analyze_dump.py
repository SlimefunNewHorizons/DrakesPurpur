#!/usr/bin/env python3
"""Analiza el volcado de produccion de Slimefun (Slimefun-Knowledge-Drake/data/slimefun-dump.json.gz).

Uso (reproducible, solo lectura sobre los repos):
  python3 -I analyze_dump.py --dump /tmp/inv_repos/Slimefun-Knowledge-Drake/data/slimefun-dump.json.gz \
        --suites /tmp/inv_repos/Drakes-Suites --out <directorio de salida>

Genera:
  expected_by_addon.json     resumen por addon (conteos, grupos, investigaciones, tipos, prefijo, hash de ids)
  expected_ids_by_addon.json lista completa de ids por addon
  expected.properties        mismo formato que escribe hybrid-compat.yml (para diff exacto)
  anomalies.json             duplicados / sin addon / inconsistencias
  suites_crosswalk.json      cruce volcado <-> modulos de Drakes-Suites (si se pasa --suites)
"""
import argparse, collections as C, gzip, hashlib, json, os, re

ap = argparse.ArgumentParser()
ap.add_argument('--dump', required=True)
ap.add_argument('--suites')
ap.add_argument('--out', required=True)
a = ap.parse_args()
os.makedirs(a.out, exist_ok=True)

d = json.load(gzip.open(a.dump, 'rt', encoding='utf-8'))
items, groups, researches = d['items'], d['groups'], d['researches']
by_id = {}
dup_ids = C.defaultdict(list)
for i in items:
    if i['id'] in by_id:
        dup_ids[i['id']].append(i['addon'])
    by_id.setdefault(i['id'], i)


def short(cls):
    return cls.rsplit('.', 1)[-1]


def common_prefix_tokens(ids):
    """Prefijo '_'-token mas largo compartido por >=60% de los ids; mas tokens-prefijo frecuentes."""
    c = C.Counter()
    for x in ids:
        t = x.split('_')
        for n in range(1, min(len(t), 4)):
            c['_'.join(t[:n])] += 1
    top = [(p, n) for p, n in c.most_common(200) if n >= max(3, 0.1 * len(ids))]
    return top[:5]


# ---- por addon ----
A = {}
item_group_key = {g['key']: g for g in groups}
for i in items:
    ad = i.get('addon')
    e = A.setdefault(ad, dict(ids=[], classes=C.Counter(), recipe_types=C.Counter(), groups=C.Counter(),
                              researches=C.Counter(), energy_types=C.Counter(), n_energy=0, n_machine_recipes=0,
                              n_disabled=0, n_no_recipe=0, n_research=0))
    e['ids'].append(i['id'])
    e['classes'][short(i['class'])] += 1
    e['recipe_types'][i['recipe_type']] += 1
    e['groups'][i['group']] += 1
    if i.get('research'):
        e['researches'][i['research']] += 1
        e['n_research'] += 1
    if i.get('energy'):
        e['n_energy'] += 1
        e['energy_types'][i['energy'].get('type')] += 1
    if i.get('machine_recipes'):
        e['n_machine_recipes'] += 1
    if i.get('disabled'):
        e['n_disabled'] += 1
    if not any(r for r in i['recipe']):
        e['n_no_recipe'] += 1

g_by_addon = C.defaultdict(list)
for g in groups:
    g_by_addon[g['addon']].append(g['key'])
r_by_addon = C.defaultdict(set)  # investigacion -> addon via items afectados
r_items = {r['key']: r for r in researches}
for r in researches:
    for iid in r['items']:
        if iid in by_id:
            r_by_addon[by_id[iid]['addon']].add(r['key'])
# investigaciones con items de varios addons
r_multi = {k: sorted({by_id[x]['addon'] for x in r['items'] if x in by_id}) for k, r in r_items.items()}
r_multi = {k: v for k, v in r_multi.items() if len(v) > 1}
r_unknown_ids = {k: [x for x in r['items'] if x not in by_id] for k, r in r_items.items()}
r_unknown_ids = {k: v for k, v in r_unknown_ids.items() if v}
r_empty = [k for k, r in r_items.items() if not r['items']]

total = len(items)
out = {'source': {'schema': d['schema'], 'generated_at': d['generated_at'], 'server': d['server'],
                  'slimefun': d['slimefun'], 'item_errors': d['item_errors']},
       'totals': {'items': total, 'addons_with_items': len([k for k in A if k]),
                  'addons_in_groups': len({g['addon'] for g in groups if g['addon']}),
                  'groups': len(groups), 'researches': len(researches),
                  'unique_ids': len(by_id), 'items_with_research': sum(1 for i in items if i.get('research')),
                  'items_with_energy': sum(1 for i in items if i.get('energy')),
                  'items_with_machine_recipes': sum(1 for i in items if i.get('machine_recipes')),
                  'items_disabled': sum(1 for i in items if i.get('disabled'))},
       'addons': {}}
for ad, e in sorted(A.items(), key=lambda kv: -len(kv[1]['ids'])):
    ids = sorted(e['ids'])
    out['addons'][str(ad)] = {
        'name': ad, 'n_items': len(ids),
        'pct_of_total': round(100 * len(ids) / total, 3),
        'ids_sha256': hashlib.sha256('\n'.join(ids).encode()).hexdigest(),
        'groups_by_dump_group_addon': sorted(g_by_addon.get(ad, [])),
        'groups_used_by_items': dict(e['groups'].most_common()),
        'researches_of_items': sorted(r_by_addon.get(ad, [])),
        'n_researches_of_items': len(r_by_addon.get(ad, [])),
        'items_with_research': e['n_research'],
        'classes': dict(e['classes'].most_common()),
        'recipe_types': dict(e['recipe_types'].most_common()),
        'energy_components': e['n_energy'], 'energy_types': dict(e['energy_types']),
        'items_with_machine_recipes': e['n_machine_recipes'],
        'disabled': e['n_disabled'], 'items_without_recipe': e['n_no_recipe'],
        'id_prefixes': common_prefix_tokens(ids),
        'sample_ids': ids[:5],
    }
json.dump(out, open(os.path.join(a.out, 'expected_by_addon.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
json.dump({ad: sorted(e['ids']) for ad, e in A.items()}, open(os.path.join(a.out, 'expected_ids_by_addon.json'), 'w'),
          indent=0)

# expected.properties identico al de hybrid-compat.yml
cnt = C.Counter(i.get('addon') for i in items)
with open(os.path.join(a.out, 'expected.properties'), 'w') as f:
    f.write('__items=%d\n__groups=%d\n__researches=%d\n' % (len(items), len(groups), len(researches)))
    for k, v in cnt.items():
        f.write('%s=%d\n' % (str(k).replace(' ', '\\ ').replace(':', '\\:').replace('=', '\\='), v))

# ---- anomalias ----
group_ids_all = C.Counter()
group_addon_mismatch = []
items_not_in_any_group_list = []
group_refs_unknown = []
for g in groups:
    for iid in g['items']:
        group_ids_all[iid] += 1
        if iid not in by_id:
            group_refs_unknown.append((g['key'], iid))
        elif by_id[iid]['addon'] != g['addon']:
            group_addon_mismatch.append((g['key'], g['addon'], iid, by_id[iid]['addon']))
items_group_missing = [i['id'] for i in items if i['group'] not in item_group_key]
items_not_in_group = [i['id'] for i in items if group_ids_all[i['id']] == 0]
# colisiones tras normalizar (mayusculas, '-' -> '_')
norm = C.defaultdict(list)
for i in items:
    norm[re.sub(r'[^A-Z0-9]', '', i['id'].upper())].append(i['id'])
norm_coll = {k: v for k, v in norm.items() if len(v) > 1}
# mismo nombre de item visible en distintos addons
byname = C.defaultdict(set)
for i in items:
    byname[i['name'].strip().lower()].add(i['addon'])
name_multi = {k: sorted(v) for k, v in byname.items() if len(v) > 1 and k}
# grupos con addon sin items
addon_groups_without_items = sorted({g['addon'] for g in groups if g['addon']} - {k for k in A if k})
# id con sufijo numerico _a_b_c (ColoredEnderChests: tripleta de indices de color 0-15, NO version)
ver_suffix = C.Counter(i['addon'] for i in items if re.search(r'_\d+_\d+_\d+$', i['id']))
# prefijo de addon (primer token) compartido por addons distintos
first_tok = C.defaultdict(C.Counter)
for i in items:
    first_tok[i['id'].split('_')[0]][i['addon']] += 1
tok_multi = {t: dict(c) for t, c in first_tok.items() if len(c) > 1 and sum(c.values()) >= 20}
anom = {
    'duplicate_ids_within_dump': dict(dup_ids),
    'items_without_addon': [i['id'] for i in items if not i.get('addon')],
    'ids_case_or_punct_collisions': norm_coll,
    'item_group_key_missing_in_groups': items_group_missing,
    'groups_reference_unknown_item_ids': group_refs_unknown[:50],
    'n_groups_reference_unknown_item_ids': len(group_refs_unknown),
    'group_addon_differs_from_item_addon_count': len(group_addon_mismatch),
    'group_addon_differs_from_item_addon_sample': group_addon_mismatch[:30],
    'items_in_no_group_list': len(items_not_in_group), 'items_in_no_group_list_sample': items_not_in_group[:30],
    'items_in_several_group_lists': sum(1 for v in group_ids_all.values() if v > 1),
    'researches_with_unknown_item_ids': r_unknown_ids, 'researches_without_items': r_empty,
    'researches_spanning_several_addons': r_multi,
    'addons_with_groups_but_no_items': addon_groups_without_items,
    'items_with_numeric_triplet_suffix_by_addon': dict(ver_suffix),
    'first_id_token_shared_by_several_addons(>=20 items)': tok_multi,
    'same_display_name_in_several_addons_count': len(name_multi),
    'same_display_name_in_several_addons_sample': dict(list(name_multi.items())[:25]),
}
json.dump(anom, open(os.path.join(a.out, 'anomalies.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

# ---- cruce con Drakes-Suites ----
if a.suites:
    lit = re.compile(r'"([A-Z][A-Z0-9_]{2,})"')
    keyline = re.compile(r'^\s*([A-Z][A-Z0-9_]{2,}):', re.M)
    sup = re.compile(r'super\(plugin,\s*"([^"]+)",\s*"([^"]+)"\)')
    mod_lits = C.defaultdict(set)          # (suite, paquete) -> literales
    mods = {}
    ext_all = set()
    n_sfstack_literal = 0
    for r, _, fs in os.walk(a.suites):
        if '/.git' in r or '/target' in r:
            continue
        for f in fs:
            if not f.endswith(('.java', '.yml')):
                continue
            p = os.path.join(r, f)
            rel = os.path.relpath(p, a.suites).split(os.sep)
            suite = rel[0]
            t = open(p, errors='ignore').read()
            n_sfstack_literal += len(re.findall(r'new SlimefunItemStack\(\s*"', t))
            m = re.search(r'/java/com/[^/]+/(?:[^/]+/)*?(?:suites|multiversenets)/[^/]+/([^/]+)/', p)
            pkg = rel[rel.index('java') + 5] if 'java' in rel and len(rel) > rel.index('java') + 5 else ''
            lits = set(lit.findall(t)) | set(keyline.findall(t))
            mod_lits[(suite, pkg)] |= lits
            ext_all |= lits
            for k, disp in sup.findall(t):
                mods[(suite, pkg)] = (k, disp)
    # addon <-> modulo por solapamiento de ids
    cross = []
    for (suite, pkg), ls in sorted(mod_lits.items()):
        ov = C.Counter(by_id[x]['addon'] for x in ls if x in by_id)
        k, disp = mods.get((suite, pkg), (None, None))
        cross.append({'suite': suite, 'package': pkg, 'module_key': k, 'module_display': disp,
                      'literals': len(ls), 'dump_id_hits': sum(ov.values()), 'by_addon': dict(ov.most_common(6))})
    # cobertura de ids por addon (cota inferior: literal exacto en fuente/yml)
    cov = {}
    for ad, e in A.items():
        ids = set(e['ids'])
        cov[ad] = {'n_items': len(ids), 'literal_hits_in_suites': len(ids & ext_all)}
    # coincidencia por nombre normalizado addon-dump vs clave/visible de modulo
    def nz(s):
        return re.sub(r'[^a-z0-9]', '', (s or '').lower())
    name_match = {}
    for ad in A:
        n = nz(ad)
        cands = [{'suite': s, 'package': p, 'key': k, 'display': dsp}
                 for (s, p), (k, dsp) in mods.items() if n and (n in nz(k) or nz(k) in n or n in nz(dsp) or nz(p) == n)]
        name_match[str(ad)] = cands
    json.dump({'suites_commit_note': 'ver REPORT.md', 'n_SlimefunItemStack_string_literal_calls': n_sfstack_literal,
               'modules_declared_via_super(plugin,key,display)': {f'{s}/{p}': v for (s, p), v in sorted(mods.items())},
               'module_to_dump_addon_by_id_overlap': cross, 'literal_coverage_by_addon': cov,
               'name_matches': name_match, 'distinct_uppercase_literals_in_suites': len(ext_all),
               'literals_in_suites_that_are_dump_ids': len(ext_all & set(by_id))},
              open(os.path.join(a.out, 'suites_crosswalk.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

print('items', total, 'addons', len([k for k in A if k]), 'groups', len(groups), 'researches', len(researches))
