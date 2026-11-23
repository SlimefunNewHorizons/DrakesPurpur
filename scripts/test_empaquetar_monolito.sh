#!/usr/bin/env bash
# Pruebas del validador de scripts/empaquetar_monolito.sh con jars sintéticos (sin gradle).
# Uso: scripts/test_empaquetar_monolito.sh
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T=$(mktemp -d); trap 'rm -rf "$T"; rm -rf "$ROOT/purpur-server/bundled/drakes-bundled"' EXIT
fail=0

# suite <raíz> <módulo> <plugin.yml> [recurso...]: target/<módulo>.jar con los recursos de src/main/resources
suite() {
  local d="$T/$1/$2" n="$2" yml="$3"; shift 3
  mkdir -p "$d/target" "$d/src/main/resources" "$d/jar"
  printf '%b' "$yml" > "$d/jar/plugin.yml"
  for r in "$@"; do echo x > "$d/src/main/resources/$r"; echo x > "$d/jar/$r"; done
  (cd "$d/jar" && python3 -c 'import sys,zipfile,os; z=zipfile.ZipFile(sys.argv[1],"w"); [z.write(f) for f in os.listdir(".")]' "$d/target/$n.jar")
}
caso() {  # caso <nombre> <exit esperado> <patrón esperado> <suites> [extra...]
  local n="$1" want="$2" pat="$3"; shift 3
  out=$("$ROOT/scripts/empaquetar_monolito.sh" "$@" 2>&1); got=$?
  if { [ "$want" = 0 ] && [ $got = 0 ]; } || { [ "$want" != 0 ] && [ $got != 0 ]; }; then
    grep -q -- "$pat" <<<"$out" && { echo "ok   $n"; return; }
  fi
  echo "FAIL $n (exit $got)"; sed 's/^/     /' <<<"$out"; fail=1
}

suite ok tech 'name: DrakesTech\ndepend: [Slimefun]\n' config.yml
mkdir -p "$T/core"; (cd "$T/core" && printf 'name: Slimefun\n' > plugin.yml && python3 -c 'import zipfile; zipfile.ZipFile("slimefun-core.jar","w").write("plugin.yml")')
caso "con núcleo Slimefun" 0 "embebidos: 2 jars" "$T/ok" "$T/core/slimefun-core.jar"
caso "sin núcleo Slimefun" 1 "depend 'Slimefun' no embebido" "$T/ok"

suite yml server 'name: DrakesServer\ndescription: Motor: DrakesServer\n'
caso "plugin.yml con ':' sin comillas" 1 "plugin.yml inválido" "$T/yml"

suite res multiverse 'name: DrakesMultiverse\n'
echo x > "$T/res/multiverse/src/main/resources/config.yml"
caso "config.yml ausente en el jar" 1 "falta config.yml" "$T/res"

suite bento onechunk 'name: X\n'
caso "addon BentoBox omitido" 0 "omitido (addon de BentoBox" "$T/bento"

exit $fail
