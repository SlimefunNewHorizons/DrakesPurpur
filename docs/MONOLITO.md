# Monolito vs modular

Rama `ver/26.2` = modular (solo el kernel; las suites son jars externos en `plugins/`).
Rama `feat/slimefun-native-engine` = monolito = modular + las suites embebidas en el ejecutable.
El monolito contiene todo el modular (se fusiona `origin/ver/26.2` en el, con merge).

## Construir

```
# modular (por defecto: no se embebe nada)
BUILD_NUMBER=137 ./gradlew createPaperclipJar

# monolito
scripts/empaquetar_monolito.sh [ruta/a/Drakes-Suites]   # llena purpur-server/bundled/drakes-bundled/ (9 jars + index.txt)
DRAKES_BUNDLE=1 BUILD_NUMBER=137 ./gradlew createPaperclipJar   # o -PdrakesBundle
```

Un solo mecanismo: `purpur-server/build.gradle.kts.patch` anade `srcDir("bundled")` solo si `DRAKES_BUNDLE=1`
(o `-PdrakesBundle`). `bundled/` esta en `.gitignore`; nunca se suben los jars.
Comprobar: `unzip -l purpur-server/build/libs/purpur-server-*.jar | grep drakes-bundled`.

## Elegir suites sin recompilar: `drakes-data/suites.yml`

Al primer arranque el monolito crea el archivo con un `true` por jar embebido:

```yaml
suites:
  DrakesCore: true       # base; si se desactiva, las demas no arrancan
  DrakesTech: true
  drakes-multiverse: true
```

- `true`: se extrae a `plugins/` (solo si cambia el SHA-256) y Paper lo carga.
- `false`: no se extrae y se borra el jar de `plugins/` si es identico al embebido.
- Un jar ausente del archivo cuenta como activo. Se aplica al reiniciar.
- `-Ddrakes.bundled=false` desactiva toda la extraccion (jar monolito usado como modular).

Dentro de cada suite, los addons (94 en total: Bio 10, Combat 14, Generators 7, Magic 13, Server 11,
Tech 18, Utility 19, Multiverse 2) se activan/ajustan en `plugins/Drakes<Suite>/modules/<addon>.yml`
y se recargan con `/drakessuites reload <suite> [modulo]`.

## Tests

`./gradlew :purpur-server:test` ejecuta ademas `SaoriRulesTest` y `PrivacyGateTest` (15 tests,
`include("org/purpurmc/purpur/**/*Test.class")` en el patch). La primera vez necesita red para poblar la cache de Gradle.

## Reemplazar un addon problematico (y dejarlo trackeado)

Las suites se re-extraen a `plugins/` en CADA arranque si su SHA-256 difiere del embebido: un jar de suite
reemplazado a mano con el mismo nombre se SOBRESCRIBE al reiniciar (el log lo avisa). Opciones:

1. **Definitivo**: recompilar la suite (Drakes-Suites), `scripts/empaquetar_monolito.sh` y publicar un build nuevo del monolito.
2. **Hotfix sin recompilar el motor**: en `drakes-data/suites.yml` pon `NombreSuite: keep`, copia tu jar a `plugins/`
   con el nombre exacto de la suite y reinicia. El motor no lo toca.
3. Addons de terceros que no son parte de las suites son jars normales de `plugins/`: reemplazalos sin mas.

Cada arranque escribe `drakes-data/bundled-state.yml` (por suite: modo, SHA-256 embebido y, en `keep`, el desplegado).
Un `keep` queda registrado ahi y como WARNING en el log. Al publicar el build definitivo, vuelve a `true`.
