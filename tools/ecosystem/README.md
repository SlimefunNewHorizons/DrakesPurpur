# Ecosistema completo: build de addons + integracion

Workflow: `.github/workflows/hybrid-ecosystem.yml` (jobs `core` -> `addons` x8 -> `integrate`).
Entrada: `docs/hybrid/ecosistema/manifest.json` (clave `entries`, `category == "addon_slimefun"`, 112 addons).

## build_addons.py

    build_addons.py --manifest M --shard I/N --slimefun-jar JAR --out DIR --workdir DIR [--timeout 900] [--dry-run] [--only id,id]
    build_addons.py report      --results-dir D --manifest M [--load-json J] [--excluded ids] --out DIR
    build_addons.py load-report --log LOG --plugins DIR --out J [--accumulate ACC --iteration N] [--remove-list F]

Por addon: clona (`--depth 1 -b <branch>`, https anonimo), detecta Maven (pom raiz, o el primero encontrado en
subdirectorios) o Gradle, resuelve la dependencia de Slimefun contra NUESTRO jar y compila sin tests.

- Maven: busca dependencias con artifactId `Slimefun|Slimefun4|slimefun-core|slimefun4-drake` (cualquier groupId) en todos
  los poms (hasta 3 niveles), resuelve `${props}` (propiedades del pom y de padres locales) y `dependencyManagement`, y hace
  `mvn install:install-file` con esas coordenadas en un `-Dmaven.repo.local` compartido por shard.
- Gradle: detecta la dependencia en scripts/`*.toml` y anade un `--init-script` que sustituye cualquier dependencia
  Slimefun por `drake.local:slimefun:local` (repo `file:` generado), sea cual sea la version/variable.
- JAR elegido: `target/*.jar` o `build/libs/*.jar` (sin `-sources/-javadoc/-tests/-plain/original-`), prefiere el que
  tiene `plugin.yml`, luego shaded/all, luego el mayor.
- Estados: `construido | fallo | inaccesible | sin-build | timeout` (`dry-run` con `--dry-run`).
  `inaccesible` = fallo de `git clone` (repo privado/inexistente), con el texto exacto de git en `reason`.
- Salidas: `out/results/<id>.json`, `out/jars/<id>.jar`, `out/logs/<id>.log`, `out/results_shard_I.json`.
  Los ids del manifest con prefijo `(gradle, ...)` o nulos se normalizan (`ident()`).

## Job integrate

1. Pool: Slimefun + 9 suites + jars de addons (se excluye `ECO_EXCLUDE`, hoy `DrakesNanotech`, que ya trae su suite).
2. Arranque base solo con Slimefun: **falla el job** si el servidor o Slimefun no arrancan.
3. Carga: hasta 3 iteraciones; tras cada una `load-report` clasifica cada jar (`Could not load`, `Unsupported API version`,
   `Missing dependency`, `NoClassDefFoundError`, `onEnable`, `UnsupportedClassVersionError`) y retira los que no cargan.
4. `ecosystem-build-report.{json,md}` (build + carga por addon).
5. Smoke fase 1+2 con `tools/compat-smoke` y `compare_ids.py` en orden OFF,ON,ON,OFF sobre la misma instalacion
   (`coverage/<corrida>/`, `coverage/hybrid-on-vs-off.md`).

Artefactos: `ecosystem-build-report`, `coverage` (+ `results`), `ecosystem-logs`, `addons-<shard>`, `core-*`.

## Limites conocidos
Sin red a JitPack/papermc en el sandbox: la compilacion real de los addons y el arranque de servidor solo se verifican en CI.
