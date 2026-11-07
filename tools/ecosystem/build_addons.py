#!/usr/bin/env python3
"""Construye los addons Slimefun del manifest del ecosistema (un shard por invocacion).

Para cada addon (category == 'addon_slimefun'): clona (https anonimo, --depth 1 -b <branch>), detecta
maven/gradle, resuelve la dependencia de Slimefun CONTRA NUESTRO JAR (--slimefun-jar) en vez de JitPack,
compila sin tests y recoge el JAR. Un fallo de un addon nunca aborta el resto: todo queda en
out/results/<id>.json. Solo libreria estandar.
"""
import argparse, glob, hashlib, json, os, re, shutil, signal, subprocess, sys, time, zipfile
import xml.etree.ElementTree as ET

SF_ARTIFACTS = {'slimefun', 'slimefun4', 'slimefun-core', 'slimefun4-drake'}
BAD_JAR = re.compile(r'(-sources|-javadoc|-tests?|-plain)\.jar$|(^|/)original-', re.I)
ERR_RE = re.compile(r'ERROR|FAILED|Could not resolve|Could not find|Could not transfer|What went wrong|Cannot access')


def log(*a):
    print(*a, file=sys.stderr, flush=True)


def run(cmd, cwd=None, timeout=900, env=None):
    """Ejecuta con grupo de procesos propio; devuelve (rc|None si timeout, salida)."""
    p = subprocess.Popen(cmd, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         stdin=subprocess.DEVNULL, text=True, errors='replace', start_new_session=True)
    try:
        out, _ = p.communicate(timeout=timeout)
        return p.returncode, out
    except subprocess.TimeoutExpired:
        try:
            os.killpg(p.pid, signal.SIGKILL)
        except Exception:
            pass
        out, _ = p.communicate()
        return None, out or ''
    except FileNotFoundError as e:
        return 127, str(e)


# ---------------------------------------------------------------- maven
def _local(tag):
    return tag.rsplit('}', 1)[-1]


def _child(el, name):
    for c in el:
        if _local(c.tag) == name:
            return c
    return None


def _text(el, name):
    c = _child(el, name)
    return (c.text or '').strip() if c is not None and c.text else None


def parse_pom(path):
    try:
        return ET.parse(path).getroot()
    except Exception:
        return None


def pom_chain(path, limit=4):
    """pom + padres alcanzables dentro del mismo repo (relativePath, por defecto ../pom.xml)."""
    chain, seen = [], set()
    cur = os.path.abspath(path)
    for _ in range(limit):
        if cur in seen or not os.path.isfile(cur):
            break
        seen.add(cur)
        root = parse_pom(cur)
        if root is None:
            break
        chain.append(root)
        par = _child(root, 'parent')
        if par is None:
            break
        rp = _text(par, 'relativePath')
        nxt = os.path.normpath(os.path.join(os.path.dirname(cur), rp if rp else '../pom.xml'))
        if os.path.isdir(nxt):
            nxt = os.path.join(nxt, 'pom.xml')
        cur = nxt
    return chain


def collect_props(chain):
    props = {}
    for root in reversed(chain):  # padre primero, el hijo sobreescribe
        for prof in [root] + [p for pr in root.iter() if _local(pr.tag) == 'profile' for p in [pr]]:
            pe = _child(prof, 'properties')
            if pe is not None:
                for c in pe:
                    if c.text is not None:
                        props[_local(c.tag)] = c.text.strip()
        ver = _text(root, 'version')
        par = _child(root, 'parent')
        gid = _text(root, 'groupId')
        if par is not None:
            ver = ver or _text(par, 'version')
            gid = gid or _text(par, 'groupId')
            props['project.parent.version'] = _text(par, 'version') or ''
        if ver:
            props['project.version'] = props['pom.version'] = props['version'] = ver
        if gid:
            props['project.groupId'] = gid
    return props


def subst(s, props):
    for _ in range(10):
        n = re.sub(r'\$\{([^}]+)\}', lambda m: props.get(m.group(1), m.group(0)), s or '')
        if n == s:
            break
        s = n
    return s


def maven_slimefun_deps(pom_files):
    """Devuelve (lista de {groupId,artifactId,version}, notas). Recorre todos los poms dados."""
    found, notes = {}, []
    for pf in pom_files:
        chain = pom_chain(pf)
        if not chain:
            continue
        props = collect_props(chain)
        managed = {}
        for root in chain:
            for dm in root.iter():
                if _local(dm.tag) == 'dependencyManagement':
                    for d in dm.iter():
                        if _local(d.tag) == 'dependency':
                            managed.setdefault((_text(d, 'groupId'), _text(d, 'artifactId')), _text(d, 'version'))
        for d in chain[0].iter():
            if _local(d.tag) != 'dependency':
                continue
            g, a, v = _text(d, 'groupId'), _text(d, 'artifactId'), _text(d, 'version')
            if not a:
                continue
            a_res = subst(a, props)
            if a_res.lower() not in SF_ARTIFACTS:
                continue
            g_res = subst(g or '', props)
            v = v or managed.get((g, a))
            v_res = subst(v or '', props)
            if not g_res or not v_res or '${' in g_res + v_res:
                notes.append('dependencia Slimefun %s:%s:%s con groupId/version sin resolver en %s'
                             % (g, a, v, os.path.basename(os.path.dirname(pf)) or '.'))
                continue
            found[(g_res, a_res, v_res)] = True
    return [dict(groupId=g, artifactId=a, version=v) for (g, a, v) in found], notes


def norm_java(v):
    if not v:
        return None
    v = v.strip()
    m = re.match(r'^1\.(\d+)$', v) or re.match(r'^(\d+)', v)
    return m.group(1) if m else (v if '${' not in v else None)


def maven_java(chain, props):
    for k in ('maven.compiler.release', 'maven.compiler.source', 'maven.compiler.target', 'java.version', 'jdk.version', 'java.release'):
        if props.get(k):
            return norm_java(subst(props[k], props))
    for root in chain:
        for pl in root.iter():
            if _local(pl.tag) == 'plugin' and _text(pl, 'artifactId') == 'maven-compiler-plugin':
                for c in pl.iter():
                    if _local(c.tag) in ('release', 'source', 'target') and c.text:
                        return norm_java(subst(c.text.strip(), props))
    return None


def find_poms(root, depth=3):
    out = []
    base = root.rstrip('/').count('/')
    for dp, dn, fn in os.walk(root):
        if dp.count('/') - base >= depth or '/.git' in dp or '/node_modules' in dp:
            dn[:] = []
            continue
        if 'pom.xml' in fn:
            out.append(os.path.join(dp, 'pom.xml'))
    out.sort(key=lambda p: p.count('/'))
    return out


# ---------------------------------------------------------------- gradle
GRADLE_FILES = ('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts')


def gradle_scan(root):
    files = []
    for pat in ('*.gradle', '*.gradle.kts', '*.toml', '*/*.gradle', '*/*.gradle.kts', 'gradle/*.toml'):
        files += glob.glob(os.path.join(root, pat))
    deps, java = set(), None
    for f in sorted(set(files)):
        try:
            t = open(f, encoding='utf-8', errors='replace').read()
        except Exception:
            continue
        for m in re.finditer(r'([\w.\-]+):(slimefun[\w\-]*)(?::([^\'"\s)]+))?', t, re.I):
            if m.group(2).lower() in SF_ARTIFACTS:
                deps.add((m.group(1), m.group(2), m.group(3) or ''))
        for m in re.finditer(r'module\s*=\s*["\']([\w.\-]+):(slimefun[\w\-]*)["\']', t, re.I):  # version catalog
            if m.group(2).lower() in SF_ARTIFACTS:
                deps.add((m.group(1), m.group(2), ''))
        if not java:
            m = (re.search(r'JavaLanguageVersion\.of\(\s*(\d+)', t) or re.search(r'release(?:\.set\(|\s*=)\s*(\d+)', t)
                 or re.search(r'sourceCompatibility\s*=?\s*[\'"]?(?:JavaVersion\.VERSION_)?([\d._]+)', t)
                 or re.search(r'jvmToolchain\(\s*(\d+)', t))
            if m:
                java = norm_java(m.group(1).replace('_', '.'))
    return sorted(deps), java


def write_local_repo(repo_dir, group, artifact, version, jar):
    d = os.path.join(repo_dir, group.replace('.', '/'), artifact, version)
    os.makedirs(d, exist_ok=True)
    shutil.copyfile(jar, os.path.join(d, '%s-%s.jar' % (artifact, version)))
    with open(os.path.join(d, '%s-%s.pom' % (artifact, version)), 'w') as f:
        f.write('<project><modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId>'
                '<version>%s</version></project>' % (group, artifact, version))


def gradle_init_script(path, repo_dir):
    names = ', '.join('"%s"' % n for n in sorted(SF_ARTIFACTS))
    open(path, 'w').write('''
def sfNames = [%s]
gradle.beforeProject { p ->
  p.repositories { maven { url = uri("%s"); content { includeGroup("drake.local") } } }
  p.configurations.all {
    resolutionStrategy.eachDependency { d ->
      if (d.requested.name.toLowerCase() in sfNames && d.requested.group != "drake.local") {
        d.useTarget("drake.local:slimefun:local")
      }
    }
  }
}
''' % (names, repo_dir.replace('\\', '/')))


# ---------------------------------------------------------------- jars
def has_plugin_yml(jar):
    try:
        with zipfile.ZipFile(jar) as z:
            n = set(z.namelist())
            return 'plugin.yml' in n or 'paper-plugin.yml' in n
    except Exception:
        return False


def pick_jar(root, system):
    pats = ['target/*.jar', '*/target/*.jar', '*/*/target/*.jar'] if system == 'maven' else \
           ['build/libs/*.jar', '*/build/libs/*.jar', '*/*/build/libs/*.jar']
    for pat in pats:  # primero el nivel mas superficial que tenga candidatos
        c = [j for j in glob.glob(os.path.join(root, pat)) if not BAD_JAR.search(j)]
        if c:
            c.sort(key=lambda j: (has_plugin_yml(j), bool(re.search(r'shaded|all|full', os.path.basename(j), re.I)),
                                  os.path.getsize(j)), reverse=True)
            return c[0]
    return None


def reason_from(out, limit=800):
    lines = []
    for l in out.splitlines():
        l = l.rstrip()
        if ERR_RE.search(l) and '[WARNING]' not in l and l not in lines:
            lines.append(l)
    return '\n'.join(lines[-12:])[:limit]


def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    return h.hexdigest()


# ---------------------------------------------------------------- addon
def ident(e):
    """id estable y seguro como nombre de fichero (el manifest trae ids con prefijo '(gradle, ...)' o None)."""
    raw = e.get('id') or (e.get('repo') or 'desconocido').split('/')[-1]
    raw = re.sub(r'^\([^)]*\)\s*', '', raw)
    return re.sub(r'[^\w.\-]', '_', raw) or 'desconocido'


def process(e, a, dirs):
    t0 = time.time()
    res = dict(id=ident(e), repo=e.get('repo'), branch=e.get('branch'), system=None, status='fallo', reason='',
               jar=None, sha256=None, bytes=None, seconds=0, slimefun_dep_resolved=None, java_required=None,
               manifest_system=(e.get('build') or {}).get('system'), depends=e.get('depends', []),
               softdepends=e.get('softdepends', []), standalone_jar=e.get('standalone_jar'), notes=[])
    src = os.path.join(a.workdir, 'src', ident(e))
    logf = os.path.join(dirs['logs'], ident(e) + '.log')
    transcript = []
    try:
        shutil.rmtree(src, ignore_errors=True)
        env = dict(os.environ, GIT_TERMINAL_PROMPT='0', GIT_ASKPASS='/bin/true')
        url = e['repo'] if e['repo'].startswith(('/', 'file:')) else 'https://github.com/%s' % e['repo']  # ruta local solo para pruebas
        branch = e.get('branch')
        rc, out = run(['git', 'clone', '--depth', '1'] + (['-b', branch] if branch else []) + [url, src], timeout=300, env=env)
        transcript.append(out)
        if rc not in (0, None) and re.search(r'Remote branch .* not found|not found in upstream', out) and e.get('default_branch') and e['default_branch'] != branch:
            res['notes'].append('rama %s no existe; se uso la rama por defecto %s' % (branch, e['default_branch']))
            shutil.rmtree(src, ignore_errors=True)
            res['branch'] = e['default_branch']
            rc, out = run(['git', 'clone', '--depth', '1', '-b', e['default_branch'], url, src], timeout=300, env=env)
            transcript.append(out)
        if rc is None:
            res.update(status='timeout', reason='git clone supero 300 s')
            return res
        if rc != 0:
            fatal = [l for l in out.splitlines() if re.search(r'fatal|error|remote:', l, re.I)]
            res.update(status='inaccesible', reason=' | '.join(fatal or out.splitlines())[-800:] or 'git clone rc=%s' % rc)
            return res

        has_pom = os.path.isfile(os.path.join(src, 'pom.xml'))
        gfiles = [f for f in GRADLE_FILES + ('gradlew',) if os.path.isfile(os.path.join(src, f))]
        system = 'maven' if has_pom else ('gradle' if gfiles else None)
        res['system'] = system
        if not system:
            # multi-modulo con pom solo en subdirectorio
            sub = find_poms(src)
            if sub:
                system, has_pom = 'maven', True
                res['system'] = 'maven'
                res['notes'].append('pom raiz ausente; se usa %s' % os.path.relpath(sub[0], src))
        if not system:
            res.update(status='sin-build', reason='sin pom.xml, build.gradle[.kts] ni gradlew en la raiz del repo')
            return res

        cmd, cwd = None, src
        if system == 'maven':
            poms = find_poms(src)
            root_pom = os.path.join(src, 'pom.xml') if os.path.isfile(os.path.join(src, 'pom.xml')) else poms[0]
            cwd = os.path.dirname(root_pom)
            chain = pom_chain(root_pom)
            res['java_required'] = maven_java(chain, collect_props(chain))
            deps, notes = maven_slimefun_deps(poms)
            res['notes'] += notes
            m2 = os.path.join(a.workdir, 'm2')
            installed = []
            for d in deps:
                coord = '%(groupId)s:%(artifactId)s:%(version)s' % d
                if a.dry_run:
                    res['notes'].append('dry-run: instalaria %s -> %s' % (coord, a.slimefun_jar))
                    installed.append(coord)
                    continue
                irc, iout = run(['mvn', '-B', '-ntp', '-q', '-Dmaven.repo.local=' + m2, 'install:install-file',
                                 '-Dfile=' + os.path.abspath(a.slimefun_jar), '-DgroupId=' + d['groupId'],
                                 '-DartifactId=' + d['artifactId'], '-Dversion=' + d['version'],
                                 '-Dpackaging=jar', '-DgeneratePom=true'], timeout=300)
                transcript.append(iout)
                if irc == 0:
                    installed.append(coord)
                else:
                    res['notes'].append('install-file %s fallo: %s' % (coord, reason_from(iout, 300)))
            res['slimefun_dep_resolved'] = installed or None
            if not deps:
                res['notes'].append('sin dependencia a Slimefun detectada en los poms')
            cmd = ['mvn', '-B', '-ntp', '-nsu', '-Dmaven.repo.local=' + m2, '-DskipTests', '-Dmaven.test.skip=true',
                   '-Dmaven.javadoc.skip=true', '-Dgpg.skip=true', 'package']
        else:
            deps, java = gradle_scan(src)
            res['java_required'] = java
            wrapper = os.path.join(src, 'gradlew')
            if os.path.isfile(wrapper):
                os.chmod(wrapper, 0o755)
            gcmd = [wrapper] if os.path.isfile(wrapper) else ['gradle']
            cmd = gcmd + ['build', '-x', 'test', '--no-daemon', '--console=plain']
            if deps:
                if os.path.isfile(a.slimefun_jar):
                    repo_dir = os.path.join(a.workdir, 'gradle-local-repo')
                    init = os.path.join(a.workdir, 'sf-init.gradle')
                    if not a.dry_run:
                        write_local_repo(repo_dir, 'drake.local', 'slimefun', 'local', a.slimefun_jar)
                        gradle_init_script(init, repo_dir)
                    cmd += ['--init-script', init]
                    res['slimefun_dep_resolved'] = ['drake.local:slimefun:local (sustituye %s)' %
                                                    ', '.join('%s:%s:%s' % d for d in deps)]
                else:
                    res['notes'].append('--slimefun-jar no existe; no se pudo sustituir la dependencia')
            else:
                res['notes'].append('sin dependencia a Slimefun detectada en los scripts gradle')
        res['command'] = ' '.join(cmd)
        if a.dry_run:
            res.update(status='dry-run', reason='')
            return res

        rc, out = run(cmd, cwd=cwd, timeout=a.timeout)
        transcript.append(out)
        if rc is None:
            res.update(status='timeout', reason='build supero %s s. %s' % (a.timeout, reason_from(out, 600)))
            return res
        jar = pick_jar(src, system)
        if rc == 0 and jar:
            dst = os.path.join(dirs['jars'], ident(e) + '.jar')
            shutil.copyfile(jar, dst)
            res.update(status='construido', reason='', jar=os.path.basename(jar), sha256=sha256(dst), bytes=os.path.getsize(dst))
        elif rc == 0:
            res.update(status='fallo', reason='build OK pero no se encontro ningun JAR utilizable (target/*.jar, build/libs/*.jar)')
        else:
            res.update(status='fallo', reason=reason_from(out) or 'rc=%s: %s' % (rc, ' '.join(out.split())[-600:]))
        return res
    except Exception as ex:  # nunca abortar el shard
        res.update(status='fallo', reason='excepcion del orquestador: %r' % ex)
        return res
    finally:
        res['seconds'] = round(time.time() - t0, 1)
        try:
            open(logf, 'w', errors='replace').write('\n'.join(transcript)[-200000:])
        except Exception:
            pass
        if not a.keep_clones:
            shutil.rmtree(src, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--shard', default='0/1', help='I/N (reparto por indice: posicion % N == I)')
    ap.add_argument('--slimefun-jar', default='')
    ap.add_argument('--out', required=True)
    ap.add_argument('--workdir', required=True)
    ap.add_argument('--timeout', type=int, default=900)
    ap.add_argument('--dry-run', action='store_true', help='clona y analiza, no compila')
    ap.add_argument('--only', help='ids separados por coma (depuracion)')
    ap.add_argument('--keep-clones', action='store_true')
    a = ap.parse_args()
    si, sn = (int(x) for x in a.shard.split('/'))
    a.out, a.workdir = os.path.abspath(a.out), os.path.abspath(a.workdir)
    if a.slimefun_jar:
        a.slimefun_jar = os.path.abspath(a.slimefun_jar)
    dirs = {k: os.path.join(a.out, k) for k in ('results', 'jars', 'logs')}
    for d in list(dirs.values()) + [a.workdir]:
        os.makedirs(d, exist_ok=True)
    entries = [x for x in json.load(open(a.manifest))['entries'] if x.get('category') == 'addon_slimefun']
    mine = [x for i, x in enumerate(entries) if i % sn == si]
    if a.only:
        want = set(a.only.split(','))
        mine = [x for x in mine if ident(x) in want]
    log('shard %d/%d: %d de %d addons%s' % (si, sn, len(mine), len(entries), ' (dry-run)' if a.dry_run else ''))
    results = []
    for n, e in enumerate(mine, 1):
        r = process(e, a, dirs)
        log('[%d/%d] %-28s %-12s %6.1fs %s' % (n, len(mine), r['id'], r['status'], r['seconds'], (r['reason'] or '').split('\n')[0][:100]))
        json.dump(r, open(os.path.join(dirs['results'], r['id'] + '.json'), 'w'), indent=1, ensure_ascii=False)
        results.append(r)
    summ = {}
    for r in results:
        summ[r['status']] = summ.get(r['status'], 0) + 1
    json.dump(dict(shard=si, shards=sn, summary=summ, results=results),
              open(os.path.join(a.out, 'results_shard_%d.json' % si), 'w'), indent=1, ensure_ascii=False)
    print('RESUMEN shard %d/%d: %s' % (si, sn, ', '.join('%s=%d' % kv for kv in sorted(summ.items())) or 'vacio'))


# ---------------------------------------------------------------- subcomandos de integracion
TS = re.compile(r'^\[\d\d:\d\d:\d\d')
FAIL_KINDS = [  # (etiqueta, regex) en orden de prioridad
    ('Unsupported API version', re.compile(r'Unsupported API version')),
    ('Missing dependency', re.compile(r'Unknown/missing dependency|Missing dependency|missing dependency', re.I)),
    ('UnsupportedClassVersionError', re.compile(r'UnsupportedClassVersionError|class file version')),
    ('NoClassDefFoundError', re.compile(r'NoClassDefFoundError|ClassNotFoundException')),
    ('onEnable', re.compile(r'Error occurred while enabling')),
    ('Could not load', re.compile(r"Could not load '")),
]


def jar_plugin_name(jar):
    try:
        with zipfile.ZipFile(jar) as z:
            for n in ('paper-plugin.yml', 'plugin.yml'):
                if n in z.namelist():
                    m = re.search(r'^name:\s*[\'"]?([^\'"\r\n#]+?)[\'"]?\s*$', z.read(n).decode('utf-8', 'replace'), re.M)
                    if m:
                        return m.group(1).strip()
    except Exception:
        pass
    return None


def load_report_main(argv):
    ap = argparse.ArgumentParser(prog='build_addons.py load-report')
    ap.add_argument('--log', required=True)
    ap.add_argument('--plugins', required=True, help='directorio con los jars (plugins/ y plugins/BentoBox/addons)')
    ap.add_argument('--out', required=True, help='JSON con el estado de carga por jar')
    ap.add_argument('--remove-list', help='fichero donde escribir los nombres de jar que no cargaron')
    ap.add_argument('--accumulate', help='JSON acumulado entre iteraciones: {"final": {jar: estado}, "iterations": [...]}')
    ap.add_argument('--iteration', type=int, default=1)
    ap.add_argument('--protect', default='slimefun', help='subcadenas (minusculas) de jars que no se retiran nunca')
    a = ap.parse_args(argv)
    jars = sorted(glob.glob(os.path.join(a.plugins, '**', '*.jar'), recursive=True))
    byname = {}
    info = {}
    for j in jars:
        n = jar_plugin_name(j)
        info[j] = dict(jar=os.path.relpath(j, a.plugins), plugin=n, status='sin-datos', kind=None, reason='', runtime_errors=0)
        if n:
            byname[n] = j
    lines = open(a.log, encoding='utf-8', errors='replace').read().splitlines()

    def tail_reason(i):
        out = []
        for l in lines[i:i + 14]:
            if l is not lines[i] and TS.match(l):
                break
            if re.search(r'Exception|Error|Caused by|Unsupported|issing|class file version', l):
                out.append(l.strip())
            if len(out) >= 4:
                break
        return ' | '.join(out or [lines[i].strip()])[:400]

    def mark(j, kind, i):
        d = info[j]
        if d['status'] != 'fallo':
            d.update(status='fallo', kind=kind, reason=tail_reason(i))
        # una causa mas especifica sustituye a una generica
        for lab, rx in FAIL_KINDS[:4]:
            if d['kind'] in ('Could not load', 'onEnable') and rx.search(d['reason']):
                d['kind'] = lab
                break

    for i, l in enumerate(lines):
        m = re.search(r"Could not load '([^']+\.jar)'", l)
        if m:
            fn = os.path.basename(m.group(1))
            for j in jars:
                if os.path.basename(j) == fn:
                    mark(j, 'Could not load', i)
            continue
        m = re.search(r'Error occurred while enabling (.+?) v', l)
        if m and m.group(1) in byname:
            mark(byname[m.group(1)], 'onEnable', i)
            continue
        m = re.search(r'\] Enabling (\S+) v', l)
        if m and m.group(1) in byname and info[byname[m.group(1)]]['status'] == 'sin-datos':
            info[byname[m.group(1)]]['status'] = 'cargo'
        m = re.match(r'^\[[\d:]+ (?:ERROR|WARN)[^\]]*\]: \[([^\]]+)\]', l)
        if m and m.group(1) in byname and re.search(r'Exception|Error', l):
            info[byname[m.group(1)]]['runtime_errors'] += 1
    for d in info.values():
        if d['status'] == 'fallo' and d['kind'] == 'onEnable':
            for lab, rx in FAIL_KINDS[:4]:
                if rx.search(d['reason']):
                    d['kind'] = lab
                    break
    done = any(re.search(r'Done \(', l) for l in lines)
    rem = [d['jar'] for d in info.values() if d['status'] == 'fallo' and not any(p in d['jar'].lower() for p in a.protect.split(','))]
    res = dict(server_done=done, total=len(info), cargaron=sum(d['status'] == 'cargo' for d in info.values()),
               fallaron=sum(d['status'] == 'fallo' for d in info.values()), retirar=rem, jars=list(info.values()))
    json.dump(res, open(a.out, 'w'), indent=1, ensure_ascii=False)
    if a.accumulate:
        acc = json.load(open(a.accumulate)) if os.path.isfile(a.accumulate) else dict(final={}, iterations=[])
        for d in info.values():
            key = os.path.basename(d['jar'])
            if acc['final'].get(key, {}).get('status') == 'fallo':
                continue  # ya retirado en una iteracion anterior; conserva su motivo
            acc['final'][key] = dict(status=d['status'], kind=d['kind'], reason=d['reason'], plugin=d['plugin'],
                                     runtime_errors=d['runtime_errors'], iteration=a.iteration)
        acc['iterations'].append(dict(iteration=a.iteration, server_done=done, total=res['total'], cargaron=res['cargaron'],
                                      fallaron=res['fallaron'], retirados=rem))
        json.dump(acc, open(a.accumulate, 'w'), indent=1, ensure_ascii=False)
    if a.remove_list:
        open(a.remove_list, 'w').write('\n'.join(rem) + ('\n' if rem else ''))
    print('carga: %d jars, %d cargaron, %d fallaron, a retirar %d, Done=%s' % (res['total'], res['cargaron'], res['fallaron'], len(rem), done))


def report_main(argv):
    ap = argparse.ArgumentParser(prog='build_addons.py report')
    ap.add_argument('--results-dir', required=True, help='busca recursivamente */results/*.json')
    ap.add_argument('--manifest', required=True)
    ap.add_argument('--load-json', help='JSON final de carga (iteraciones) generado por el job integrate')
    ap.add_argument('--excluded', default='', help='ids no integrados por estar ya en las suites')
    ap.add_argument('--out', required=True)
    a = ap.parse_args(argv)
    os.makedirs(a.out, exist_ok=True)
    got = {}
    for f in glob.glob(os.path.join(a.results_dir, '**', 'results', '*.json'), recursive=True):
        try:
            r = json.load(open(f))
            got[r['id']] = r
        except Exception:
            pass
    ents = [x for x in json.load(open(a.manifest))['entries'] if x.get('category') == 'addon_slimefun']
    for e in ents:
        if ident(e) not in got:
            got[ident(e)] = dict(id=ident(e), repo=e.get('repo'), branch=e.get('branch'), system=(e.get('build') or {}).get('system'),
                                 status='sin-resultado', reason='el shard no produjo resultado (job cancelado o caido)',
                                 slimefun_dep_resolved=None, java_required=None, seconds=0)
    load = {}
    if a.load_json and os.path.isfile(a.load_json):
        load = json.load(open(a.load_json)).get('final', {})
    excluded = {x for x in a.excluded.split(',') if x}
    rows = []
    for k in sorted(got, key=str.lower):
        r = got[k]
        lj = load.get(k + '.jar')
        r['integrado'] = ('excluido (ya en suites)' if k in excluded else (lj or {}).get('status', 'no-instalado' if r['status'] != 'construido' else 'sin-datos'))
        r['carga_motivo'] = (lj or {}).get('reason', '') if lj and lj.get('status') == 'fallo' else ''
        rows.append(r)
    tot = {}
    for r in rows:
        tot[r['status']] = tot.get(r['status'], 0) + 1
    ltot = {}
    for r in rows:
        ltot[r['integrado']] = ltot.get(r['integrado'], 0) + 1
    json.dump(dict(totales_build=tot, totales_carga=ltot, addons=rows), open(os.path.join(a.out, 'ecosystem-build-report.json'), 'w'), indent=1, ensure_ascii=False)
    esc = lambda t: (t or '').replace('|', '/').replace('\n', ' ')[:140]
    md = ['# Informe de build del ecosistema', '', '**Build:** ' + ', '.join('%s=%d' % kv for kv in sorted(tot.items())),
          '', '**Carga en servidor:** ' + (', '.join('%s=%d' % kv for kv in sorted(ltot.items()))), '',
          '| addon | build | sistema | java | slimefun resuelto | integracion | motivo |', '|---|---|---|---|---|---|---|']
    for r in rows:
        sf = ', '.join(r.get('slimefun_dep_resolved') or []) or '-'
        md.append('| %s | %s | %s | %s | %s | %s | %s |' % (r['id'], r['status'], r.get('system') or '-', r.get('java_required') or '-',
                                                            esc(sf)[:60], r['integrado'], esc(r.get('reason') or r.get('carga_motivo'))))
    open(os.path.join(a.out, 'ecosystem-build-report.md'), 'w').write('\n'.join(md) + '\n')
    print('\n'.join(md[:6]))


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'report':
        report_main(sys.argv[2:])
    elif len(sys.argv) > 1 and sys.argv[1] == 'load-report':
        load_report_main(sys.argv[2:])
    else:
        main()
