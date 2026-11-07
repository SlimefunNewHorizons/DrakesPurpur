# Catalogo real de Slimefun: de donde sale cada cifra

**Por que el smoke de CI registra 713 items y la produccion ~16.000.** El servidor de pruebas de CI carga `Slimefun4-Drake@port-26x` (Slimefun 11.0-Universal-26.x) y los 9 modulos de `Drakes-Suites@26.x`, **no** los ~64 addons de la instalacion real. Las cifras se distinguen asi:

| Cifra | Que es | Fuente |
|---|---|---|
| 713 | items registrados en el servidor de pruebas de CI = Slimefun core (555) + `Drakes Nanotechnology & Stark Engineering` (158) | auditor de catalogo (registro real tras cargar todo) |
| 15.986 | items registrados en la **instalacion de produccion** (Paper 1.21.11, Slimefun 11.0-Drake-1.21.11), 64 addons, 274 grupos, 440 investigaciones; todos con receta | volcado `data/slimefun-dump.json.gz` de `Slimefun-Knowledge-Drake` (generado 2026-10-14) |
| 94 | **addons/modulos documentados del monolito** (no son items) | `docs/README_MONOLITO.md` |

El volcado de produccion incluye contenido generado (p. ej. `ColoredEnderChests`: 8.192 variantes; `Cultivation` 1.401; `SlimeTinker` 1.376; `Slimefun` 554; `Supreme` 510): por eso el total de items no refleja "cuanto contenido hay" por si solo.

## Auditor reproducible (`tools/compat-smoke`, workflow `hybrid-compat.yml`)
1. Arranca el servidor con los plugins elegidos y, **tras cargar todos los addons**, lee `Slimefun.getRegistry()`.
2. Cuenta: items, items con receta, items deshabilitados, grupos, investigaciones, componentes de energia, multibloques, maquinas (heuristica por jerarquia de clases), addons con items, plugins habilitados.
3. Compara por addon con el volcado de `Slimefun-Knowledge-Drake` (convertido a `expected.properties`) y lista **addons ausentes / parciales / no esperados**.
4. Escribe `compat-catalog.json` y `compat-catalog.md` con procedencia (version del servidor, de Slimefun, fecha, metodo), y los publica como artefacto `hybrid-compat-catalog`.

## Reutilizacion del ecosistema
`Slimefun-Knowledge-Drake` ya tiene un plugin de volcado del registro (`dump-plugin`, `SlimefunDump`) y las herramientas `kb.py`/`diff_dumps.py`. El auditor de esta rama **no lo duplica**: usa su volcado como fuente de "lo esperado". Para una instalacion completa, el camino correcto es ejecutar su `dump-plugin` en el servidor real y comparar con `tools/diff_dumps.py`.

## Resultado en el servidor de pruebas de CI (run 9, commit `42a67a9`)
- Encontrados: Slimefun **555** items (produccion: 554) y `Drakes Nanotechnology & Stark Engineering` **158** (produccion: `DrakesNanotech` 158; el auditor lo marca como "no esperado" solo por diferencia de nombre).
- **63 de los 64 addons de produccion no estan presentes** en este servidor de pruebas (p. ej. FoxyMachines 452, DynaTech 123, FNAmplifications 193, Galaxyfun 100...). Es una limitacion del entorno de prueba (solo core + StarSuites), no un fallo de compatibilidad medido.
- Para cubrir todo el ecosistema falta: construir y cargar los addons independientes (repos `*-drake` de SlimefunNewHorizons) junto a los de las StarSuites.
