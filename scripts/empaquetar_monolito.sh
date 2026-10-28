#!/usr/bin/env bash
# Monolito: copia las suites compiladas de Drakes-Suites a purpur-server/bundled/drakes-bundled/ para embeberlas en el jar.
# Uso: scripts/empaquetar_monolito.sh [ruta-a-Drakes-Suites]   y luego ./gradlew createPaperclipJar
set -euo pipefail
SUITES="${1:-$HOME/Documentos/Desarrollo/Repositorios/drakescraft/Drakes-Suites}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/purpur-server/bundled/drakes-bundled"
rm -rf "$OUT"; mkdir -p "$OUT"; : > "$OUT/index.txt"
for j in "$SUITES"/*/target/*.jar; do
  b=$(basename "$j"); case "$b" in original-*|*-sources.jar|*-javadoc.jar) continue;; esac
  cp "$j" "$OUT/$b"; echo "$b" >> "$OUT/index.txt"
done
echo "Compilar con: BUILD_NUMBER=${BUILD_NUMBER:-134} ./gradlew createPaperclipJar (sin BUILD_NUMBER la versión es 26.2.local-SNAPSHOT y rompe addons que parsean Bukkit.getBukkitVersion)"
echo "embebidos: $(wc -l < "$OUT/index.txt") jars ($(du -sh "$OUT" | cut -f1))"
