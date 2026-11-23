#!/usr/bin/env bash
# Monolito: copia las suites compiladas de Drakes-Suites a purpur-server/bundled/drakes-bundled/ para embeberlas en el jar.
# Uso: scripts/empaquetar_monolito.sh [ruta-a-Drakes-Suites] [jar-extra...]   y luego ./gradlew createPaperclipJar
# Los jars extra (p. ej. el núcleo Slimefun 26.x) se embeben también; sin él DrakesTech no habilita.
# Valida cada jar antes de embeberlo: plugin.yml parseable, recursos de src/main/resources presentes
# (un target/ viejo dejó b140 con plugin.yml inválido y DrakesMultiverse sin config.yml) y dependencias resueltas.
set -euo pipefail
SUITES="${1:-$HOME/Documentos/Desarrollo/Repositorios/drakescraft/Drakes-Suites}"
shift || true
OUT="$(cd "$(dirname "$0")/.." && pwd)/purpur-server/bundled/drakes-bundled"
rm -rf "$OUT"; mkdir -p "$OUT"; : > "$OUT/index.txt"
SRC=$(mktemp); trap 'rm -f "$SRC"' EXIT
for j in "$SUITES"/*/target/*.jar "$@"; do
  b=$(basename "$j"); case "$b" in original-*|*-sources.jar|*-javadoc.jar) continue;; esac
  # drakes-onechunk es un ADDON de BentoBox (addon.yml, sin plugin.yml): Paper lo rechaza en plugins/.
  # Se instala en plugins/BentoBox/addons/, no se embebe como plugin.
  case "$b" in *onechunk*) echo "omitido (addon de BentoBox, no plugin): $b"; continue;; esac
  cp "$j" "$OUT/$b"; echo "$b" >> "$OUT/index.txt"
  printf '%s\t%s\n' "$b" "$(dirname "$(dirname "$j")")" >> "$SRC"
done
python3 - "$OUT" "$SRC" <<'EOF'
import os, sys, zipfile, yaml
out = sys.argv[1]
src = dict(l.rstrip("\n").split("\t", 1) for l in open(sys.argv[2]))
errs, names, deps = [], set(), {}
for b in src:
    with zipfile.ZipFile(os.path.join(out, b)) as z:
        files = set(z.namelist())
        desc = next((f for f in ("paper-plugin.yml", "plugin.yml") if f in files), None)
        if not desc:
            errs.append(f"{b}: sin plugin.yml"); continue
        try:
            y = yaml.safe_load(z.read(desc))
        except yaml.YAMLError as e:
            errs.append(f"{b}: {desc} inválido: {str(e).splitlines()[0]}"); continue
        names.add(y["name"])
        deps[b] = y.get("depend") or []
        res = os.path.join(src[b], "src", "main", "resources")
        if os.path.isdir(res):
            for root, _, fs in os.walk(res):
                for f in fs:
                    rel = os.path.relpath(os.path.join(root, f), res).replace(os.sep, "/")
                    if rel not in files:
                        errs.append(f"{b}: falta {rel} (target/ desactualizado, recompilar)")
for b, d in deps.items():
    for n in d:
        if n not in names:
            errs.append(f"{b}: depend '{n}' no embebido (pásalo como jar extra)")
if errs:
    sys.exit("monolito inválido:\n  " + "\n  ".join(errs))
EOF
echo "Compilar con: BUILD_NUMBER=${BUILD_NUMBER:-134} ./gradlew createPaperclipJar (sin BUILD_NUMBER la versión es 26.2.local-SNAPSHOT y rompe addons que parsean Bukkit.getBukkitVersion)"
echo "embebidos: $(wc -l < "$OUT/index.txt") jars ($(du -sh "$OUT" | cut -f1))"
