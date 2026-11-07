# Manifiesto de repositorios del ecosistema Slimefun / Drakes

Generado 2026-11-07 (solo lectura). Fuentes: GitHub `SlimefunNewHorizons` (182 repos via search API; 178 clonados, 4 privados sin acceso salvo Igdrassil), `JackStar6677-1` (35 repos; relevantes: Slimefun-Knowledge-Drake y Coronalis privado), y la config del monolito en `/home/user/DrakesPurpur`. Datos completos y campos por repo en `manifest.json`. Todo es analisis estatico: **ninguna compilacion se ejecuto** (`build.status = no-probado`, salvo las suites segun el CI hybrid-compat ajeno).

Regla de mantenimiento: activo = ultimo commit de la rama analizada < 90 dias antes de 2026-11-07; archivado si GitHub `archived`. Ojo: muchos commits recientes son barridos masivos ("chore: estandarizar organizacion a SlimefunNewHorizons", 2026-11-03), asi que 'activo' no implica desarrollo real.

## 1. Hallazgos clave

1. **El monolito solo embebe las 9 suites** (`DrakesCore, Tech, Bio, Magic, Generators, Utility, Combat, Server, Multiverse`), compiladas de `Drakes-Suites@26.x` por `scripts/empaquetar_monolito.sh` -> `purpur-server/bundled/drakes-bundled/*.jar` + `index.txt`; `DrakesBundledPlugins` las extrae a `plugins/` por SHA-256 y `drakes-data/suites.yml` (true/false/keep) elige cuales. `drakes-onechunk` se omite (addon BentoBox sin plugin.yml; va a `plugins/BentoBox/addons/`). **Slimefun4-Drake@port-26x NO se embebe**: el CI lo compila y lo copia aparte a `plugins/`. Terceros (LuckPerms, WorldEdit, EssentialsX, BentoBox...) son jars normales.
2. **"Consolidado" en las suites es mayormente nominal.** Del codigo de `Drakes-Suites@26.x`: 49 de los 94 modulos registrados son `GenericSuiteModule` (solo un `modules/<id>.yml` de configuracion, **sin codigo del addon**), 45 son modulos 'nativos' que implementan logica propia (engines/listeners) pero **no registran ningun SlimefunItem** (0 `SlimefunItemStack`/`extends SlimefunItem` en esas suites; la unica excepcion es `nanotech`/DrakesNanotech). Coincide con `docs/hybrid/COMPATIBILIDAD.md`: el smoke registra 713 items (consistente con solo el core; inferencia mia) y la doc dice que 'no cubre los 63 addons ausentes'. Conclusion: **para tener los items/maquinas de un addon hace falta su jar standalone** (marcado `standalone_jar=true`), salvo DrakesNanotech.
3. **Compatibilidad de API**: los imports `io.github.thebusybiscuit.slimefun4.*` y `me.mrCookieSlime.*` de los 113 repos con imports SF resuelven al 100% contra las fuentes de `Slimefun4-Drake@port-26x` (comprobacion estatica por declaraciones `package`; solo `drakes-slimefun-labs` legacy falla en 3). `me.mrCookieSlime.*` NO es obsoleto aqui: `BlockStorage/BlockMenu/AContainer` siguen en ese paquete en el core; lo realmente obsoleto seria `cscorelib2` y no aparece en ningun repo. Ojo: en `Slimefun4-Drake` el arbol de directorios (`io/github/thebusybiscuit/{api,implementation}`) no coincide con la declaracion `package` (`...slimefun4.*`); usar la declaracion.
4. **MC 26.2 declarado**: solo las suites (api-version '26.2' en 7 de 9 plugin.yml; DrakesCore/Multiverse usan `${plugin.api.version}`=1.21) y `Military-Arsenal` ('26.2') lo declaran en `plugin.yml`; los addons llevan 1.13-1.21.11. En unos ~40 repos el pom depende de `paper-api` 26.x.build (eso es lo que marca `declared=true`); el resto compila contra `1.21.11-R0.1-SNAPSHOT`/`${paper.version}`/sin version explicita, con `slimefun-core 11.0-Universal-26.x-SNAPSHOT`. La propia doc del monolito dice que el core Universal 26.x se compila contra Paper API 1.21.11. Ver campo `mc_26_2.evidence`; `declared=false` significa 'no declara 26.x', no que falle.
5. **Rama de port**: 102 repos tienen rama `port-26x` (o `26.x` en Drakes-Suites); los demas addons solo tienen `main` (p. ej. ChestTerminal-drake, EcoPower-drake, FN-FAL, Liquid-drake, SFCalc-drake, SoulJars-drake, Netheopoiesis, GlobalWarming, HardcoreSlimefun, Military-Arsenal, SlimefunNukes, SFPortalGuns-Extension, Obsidian...).
6. **Repos placeholder**: 20 repos solo con README/workflows (`ColoredEnderChests-drake`, `luckyblocks-sf-drake`, `CompressionCraft-drake`, ...); su codigo vive en el archivo legacy `drakes-slimefun-labs/sources` (migration/addons.json: 84 modulos, 44 migrados, 40 pendientes). `ColoredEnderChests` solo existe como modulo nativo de DrakesUtility.

## 2. Monolito: suites embebidas (Drakes-Suites@26.x, version 2.0.0-26.X-SNAPSHOT, Java 25)

| modulo | plugin id | embebido | depend | softdepend | api-version | .java | modulos registrados (nativo/stub) |
|---|---|---|---|---|---|---|---|
| drakes-bio | DrakesBio | si | DrakesCore,Slimefun | - | api-version=26.2 | 32 | 7 nativos / 3 stub |
| drakes-combat | DrakesCombat | si | DrakesCore,Slimefun | - | api-version=26.2 | 21 | 7 nativos / 7 stub |
| drakes-core | DrakesCore | si | - | - | api-version=1.21 (via propiedad pom plugin.api.version | 18 | 0 nativos / 0 stub |
| drakes-generators | DrakesGenerators | si | DrakesCore,Slimefun | - | api-version=26.2 | 20 | 7 nativos / 0 stub |
| drakes-magic | DrakesMagic | si | DrakesCore,Slimefun | - | api-version=26.2 | 22 | 4 nativos / 9 stub |
| drakes-multiverse | DrakesMultiverse | si | DrakesCore | DrakesCombat,DrakesTech,DrakesBosses,Slimefun | api-version=1.21 (via propiedad pom plugin.api.version | 42 | 0 nativos / 2 stub |
| drakes-server | DrakesServer | si | DrakesCore,Slimefun | - | api-version=26.2 | 8 | 1 nativos / 10 stub |
| drakes-tech | DrakesTech | si | DrakesCore,Slimefun | - | api-version=26.2 | 38 | 6 nativos / 12 stub |
| drakes-utility | DrakesUtility | si | DrakesCore,Slimefun | - | api-version=26.2 | 42 | 13 nativos / 6 stub |
| drakes-onechunk | DrakesOneChunk | NO (BentoBox addon) | AOneBlock | - | api-version=3.17.0 | 2 | 0 nativos / 0 stub |

Notas: `drakes-core` es base (si se desactiva, el resto no arranca). `drakes-multiverse` es de Chagui68 (nets = codigo propio `com.chagui68.multiversenets`; creatures = stub). Detalle de cada modulo y a que repo corresponde en las filas de addons (`consolidado_en`).

## 3. Core Slimefun y librerias

| repo | plugin id | version | rama | mantenimiento | consolidado_en | standalone | depend | 26.x declarado | items/maq/recetas* | build |
|---|---|---|---|---|---|---|---|---|---|---|
| Slimefun4-1.20.6-Port | Slimefun | 4.9-Drake-1.20.6 | main | archivado 2026-11-03 |  | si | - | no | 706/62/438 | maven j16 |
| Slimefun4-Drake | Slimefun | 11.0-Universal-26.x-SNAPSHOT | port-26x | activo 2026-11-04 |  | si | - | no | 706/62/438 | maven j? |
| DrakeInfinityLib | ? | 1.3.11-UNIVERSAL-26x-SNAPSHOT | port-26x | activo 2026-10-28 |  | si | - | si | 0/3/0 | maven j25 |
| EnderCore | EnderCore | 0.2.0 | main | activo 2026-11-01 |  | si | - | no | 0/0/0 | gradle j? |
| GuizhanLib | (gradle, plugin.yml generado en build; rootProject.name) guizhanlib | ? | main | activo 2026-10-13 |  | ? | - | ? | 1/1/0 | gradle j21 |
| InfinityLib-Drake | ? | 1.3.10-Drake-26.x | port-26x | activo 2026-10-11 |  | si | - | si | 0/2/0 | maven j25 |
| SefiLib | ? | 0.3.1-Universal-26.x-SNAPSHOT | port-26x | activo 2026-10-29 |  | si | - | si | 3/0/0 | maven j25 |
| commons-lang-drake-patched | ? | ? | main | activo 2026-10-05 |  | ? | - | ? | 0/0/0 | ? j? |
| dough-core | ? | 1.3.1-Universal-26.x-SNAPSHOT | port-26x | activo 2026-11-03 |  | si | - | no | 0/0/0 | maven j21 |

`Slimefun4-Drake@port-26x`: artifact `slimefun-core 11.0-Universal-26.x-SNAPSHOT` (parent drakes-slimefun-labs 11-SNAPSHOT), plugin `Slimefun`, `api-version 1.21`, 3158 imports SF propios, 848 archivos Java, sin depend. `Slimefun4-1.20.6-Port` esta archivado (linea 1.20.6).

## 4. Addons de Slimefun (repos con plugin.yml y codigo SF)

*items/maq/recetas = heuristica estatica de ocurrencias en fuente, no contenido real en runtime.

| repo | plugin id | version | rama | mantenimiento | consolidado_en | standalone | depend | 26.x declarado | items/maq/recetas* | build |
|---|---|---|---|---|---|---|---|---|---|---|
| AdvancedTech-drake | AdvancedTech | 1.0.0 | port-26x | activo 2026-10-08 | DrakesTech/advancedtech (stub-config) | si | - | si | 9/0/9 | maven j25 |
| AlchimiaVitae-drake | AlchimiaVitae | 1.1.1 | port-26x | activo 2026-10-26 | DrakesMagic/alchimia_vitae (logica-sin-items) | si | - | no | 42/2/9 | maven j21 |
| Automation | (gradle, plugin.yml generado en build; rootProject.name) Automation | UNOFFICIAL | main | activo 2026-11-03 |  | ? | - | no | 2/0/22 | gradle j21 |
| AxGraves-Drakes | AxGraves | 1.32.1-Drake.1 | port-26x | activo 2026-10-26 | DrakesServer/axgraves (stub-config) | si | - | no | 0/0/0 | maven j21 |
| BentoBox-Drake | BentoBox | ${project.version}${build.number} | port-26x | activo 2026-11-06 |  | si | - | no | 0/0/0 | gradle j? |
| Better-Nuclear-Generator-drake | BetterReactor | 1.3.0 | port-26x | activo 2026-10-13 | DrakesGenerators/better_reactors (logica-sin-items) | si | - | si | 14/5/13 | maven j25 |
| BreweryMenu | BreweryMenu | 1.0.0 | port-26x | activo 2026-10-07 | DrakesServer/brewery_menu (stub-config) | si | Slimefun,ExoticGarden | si | 24/0/1 | maven j25 |
| BreweryX-Drake | BreweryX | 3.7.0 | port-26x | activo 2026-10-26 | DrakesServer/breweryx (stub-config) | si | - | no | 0/0/0 | gradle j? |
| Bump | (gradle, plugin.yml generado en build; rootProject.name) Bump | UNOFFICIAL | main | activo 2026-10-13 | DrakesServer/bump (stub-config) | ? | - | no | 0/1/36 | gradle j16 |
| ChestTerminal-drake | ChestTerminal | 1.21-Drake-SNAPSHOT | main | activo 2026-11-03 | DrakesUtility/chest_terminal (logica-sin-items) | si | - | no | 9/0/9 | maven j21 |
| Coronalis | Coronalis | 1.0.0-SNAPSHOT | main | activo 2026-08-18 |  | si | Slimefun | no | 19/1/19 | maven j21 |
| Coronalis-drake | Coronalis | UNOFFICIAL | port-26x | activo 2026-11-05 | DrakesMagic/coronalis (stub-config) | si | Slimefun | si | 9/1/9 | maven j25 |
| Cringle-Bosses | CringleBosses | 1.0.0 | main | activo 2026-10-13 | DrakesCombat/cringle_bosses (stub-config) | si | - | si | 18/0/12 | maven j25 |
| CrystamaeHistoria-drake | CrystamaeHistoria | 1.0.1 | port-26x | activo 2026-11-03 | DrakesMagic/crystamae (logica-sin-items) | si | Slimefun,InfinityExpansion | si | 2/0/114 | maven j25 |
| Cultivation | Cultivation | 3.3 | port-26x | activo 2026-11-03 | DrakesBio/cultivation (logica-sin-items) | si | Slimefun | si | 1/7/20 | maven j21 |
| CustomItemGenerators-drake | (gradle, plugin.yml generado en build; rootProject.name) CustomItemGenerators | 1.21-Drake-v1 | main | activo 2026-10-28 |  | ? | - | no | 1/0/0 | gradle j? |
| DankTech2-Drake | DankTech2 | MODIFIED | port-26x | activo 2026-10-07 | DrakesTech/danktech (stub-config) | si | Slimefun | no | 1/0/11 | maven j21 |
| DemonicExpansion | DemonicExpansion | 1.0.0 | port-26x | activo 2026-10-07 | DrakesMagic/demonic_expansion (stub-config) | si | Slimefun | si | 0/0/9 | maven j25 |
| DiosesDrakes | DiosesDrakes | 0.6.0 | main | activo 2026-11-05 |  | si | - | no | 0/0/0 | maven j21 |
| DrakesBosses | DrakesBosses | 1.0.0 | port-26x | activo 2026-11-01 | DrakesCombat (README) (solo-README) | si | - | no | 0/0/0 | maven j21 |
| DrakesCrates | DrakesCrates | 1.0-SNAPSHOT | port-26x | activo 2026-11-01 |  | si | - | no | 0/0/0 | maven j21 |
| DrakesNanotech | DrakesNanotech | 0.4.0 | port-26x | activo 2026-11-03 | DrakesTech/nanotech (con-items) | no | Slimefun | no | 2/2/4 | maven j21 |
| DrakesSlimeMarket | DrakesSlimeMarket | 1.2.0 | port-26x | activo 2026-10-17 |  | si | Slimefun,Vault | no | 0/0/0 | maven j21 |
| DrakesTech | DrakesTech | 1.0-SNAPSHOT | main | activo 2026-10-13 |  | si | - | no | 0/9/0 | maven j21 |
| Drugfun | Drugfun | 1.0.0 | port-26x | activo 2026-10-07 | DrakesBio/drugfun (stub-config) | si | Slimefun | no | 2/0/4 | maven j25 |
| DyedBackpacks-drake | DyedBackpacks | 1.20.6-Drake-SNAPSHOT | port-26x | activo 2026-10-07 | DrakesUtility/backpacks (logica-sin-items) | si | Slimefun | si | 1/0/1 | maven j25 |
| DynaTech-drake | DynaTech | 1.21-Drake-v7 | port-26x | activo 2026-11-03 | DrakesTech/dynatech (logica-sin-items) | si | Slimefun | no | 97/23/86 | maven j21 |
| EcoPower-drake | EcoPower | 1.20.6-Universal-26.x-SNAPSHOT | main | activo 2026-10-14 | DrakesGenerators/ecopower (logica-sin-items) | si | Slimefun | si | 10/2/9 | maven j25 |
| ElectricSpawners-drake | ElectricSpawners | 1.21.1-Drake | port-26x | activo 2026-10-24 |  | si | Slimefun | no | 1/1/1 | maven j21 |
| Element-Manipulation-drake | ElementManipulation | 1.1.1-UNIVERSAL-26x | port-26x | activo 2026-10-08 | DrakesMagic/element_manipulation (stub-config) | si | Slimefun | si | 152/6/12 | maven j25 |
| EMCTech-drake | EMCTech | MODIFIED | port-26x | activo 2026-10-26 | DrakesTech/emctech (stub-config) | si | Slimefun | no | 1/3/50 | maven j21 |
| EquivalencyTech | EquivalencyTech | modified | port-26x | activo 2026-10-12 | DrakesTech/emctech (stub-config) | si | Slimefun | no | 8/0/8 | maven j25 |
| ExoticGarden-drake | ExoticGarden | 1.3-UNIVERSAL-26x-SNAPSHOT | port-26x | activo 2026-10-07 | DrakesBio/exotic_garden (logica-sin-items) | si | - | si | 124/1/30 | maven j25 |
| ExtraGear-drake | ExtraGear | 1.20.6-Drake-SNAPSHOT | port-26x | activo 2026-10-26 | DrakesCombat/extragear (logica-sin-items) | si | Slimefun | no | 5/0/5 | maven j21 |
| ExtraHeads-drake | ExtraHeads | 11.0-Drake-1.21.1-SNAPSHOT | port-26x | activo 2026-10-24 |  | si | Slimefun | no | 1/0/0 | maven j? |
| ExtraTools-drake | ExtraTools | 1.2 | port-26x | activo 2026-10-08 | DrakesUtility/extratools (logica-sin-items) | si | Slimefun | si | 8/6/7 | maven j25 |
| ExtraUtils-drake | ? | 1.20.6-Drake-UNIVERSAL-26x-SNAPSHOT | port-26x | activo 2026-10-08 | DrakesUtility/extrautils (stub-config) | ? | - | no | 0/0/0 | maven j21 |
| FastMachines-drake | (gradle, plugin.yml generado en build; rootProject.name) FastMachines | 11.0-Drake-1.21.1-SNAPSHOT | main | activo 2026-10-29 | DrakesTech (README: Pending Module Port) (solo-README) | ? | - | ? | 1/0/48 | gradle j21 |
| FlowerPower-drake | FlowerPower | 1.0.0 | port-26x | activo 2026-10-23 | DrakesBio/flowerpower (logica-sin-items) | si | - | si | 23/1/6 | maven j25 |
| FluffyMachines-drake | FluffyMachines | 1.0.0 | port-26x | activo 2026-10-08 | DrakesTech/fluffymachines (logica-sin-items) | si | Slimefun | no | 51/14/49 | maven j21 |
| FN-FAL-s-Amplifications-drake | FNAmplifications | Unoffical-4.2.4 | main | activo 2026-10-14 | DrakesCombat/fn_amplifications (stub-config) | si | Slimefun | no | 602/16/47 | maven j21 |
| FoxyMachines-drake | FoxyMachines | 1.21.11-Drake.1 | port-26x | activo 2026-10-07 | DrakesTech/foxy (logica-sin-items) | si | Slimefun | si | 106/4/70 | maven j25 |
| Galactifun2-drake | (gradle, plugin.yml generado en build; rootProject.name) Galactifun2 | ? | port-26x | activo 2026-10-26 |  | ? | - | ? | 3/0/20 | gradle j? |
| Galaxyfun-drake | (gradle, plugin.yml generado en build; rootProject.name) Galactifun | 11.0-Universal-26.x-Galaxyfun-SNAPSHOT | port-26x | activo 2026-10-18 | DrakesCombat/galaxyfun (stub-config) | ? | - | no | 107/12/60 | gradle j25 |
| Gastronomicon-drake | Gastronomicon | 1.21.11-Drake.1-UNIVERSAL-26x-SNAPSHOT | port-26x | activo 2026-10-24 | DrakesBio/gastronomicon (stub-config) | si | Slimefun | si | 0/4/250 | maven j25 |
| GeneticChickengineering-Reborn-drake | GeneticChickengineering | 1.1.DEV | port-26x | activo 2026-10-08 | DrakesBio/genetic_chickens (logica-sin-items) | si | Slimefun | no | 2/9/17 | maven j21 |
| Geyser-Slimefun-Heads-drake | GeyserHeads | 1.2 | port-26x | activo 2026-10-08 | DrakesUtility/geyser_heads (stub-config) | si | - | si | 0/0/0 | maven j25 |
| GlobalWarming | GlobalWarming | UNOFFICIAL | main | activo 2026-11-03 | DrakesBio/globalwarming (stub-config) | si | Slimefun | no | 10/3/9 | maven j21 |
| GlobiaMachines | GlobiaMachines | 1.0.0 | port-26x | activo 2026-10-08 | DrakesTech/globiamachines (stub-config) | si | Slimefun | si | 1/1/1 | maven j25 |
| HardcoreSlimefun | HardcoreSlimefun | UNOFFICIAL | main | activo 2026-10-27 | DrakesCombat/hardcore_combat (stub-config) | si | Slimefun | no | 0/0/0 | maven j? |
| HeadLimiter-drake | HeadLimiter | UNOFFICIAL | port-26x | activo 2026-10-24 |  | si | Slimefun | no | 0/0/0 | maven j21 |
| HotbarPets-drake | HotbarPets | 1.20.6-Drake-SNAPSHOT | port-26x | activo 2026-10-24 | DrakesBio (README) (solo-README) | si | Slimefun | no | 31/0/1 | maven j21 |
| IDreamOfEasy | IDreamOfEasy | DEV | port-26x | activo 2026-10-26 | DrakesTech/idreamofeasy (stub-config) | si | Slimefun | no | 52/10/49 | maven j21 |
| InfernalExpansion | InfernalExpansion | 1.0 | port-26x | activo 2026-10-07 | DrakesMagic/infernal_expansion (stub-config) | si | Slimefun | no | 7/1/6 | maven j21 |
| InfinityExpansion-drake | InfinityExpansion | 1.20.6-UNIVERSAL-26x-SNAPSHOT | port-26x | activo 2026-10-31 | DrakesTech/infinity (logica-sin-items) | si | Slimefun | si | 225/14/62 | maven j25 |
| Liquid-drake | Liquid | UNOFFICIAL | main | activo 2026-10-14 | DrakesGenerators/liquid (logica-sin-items) | si | Slimefun | no | 37/5/7 | maven j21 |
| LiteXpansion-drake | LiteXpansion | 1.21-Drake-v2 | port-26x | activo 2026-11-06 | DrakesGenerators/litexpansion (logica-sin-items) | si | Slimefun | no | 77/10/42 | maven j21 |
| Magic-8-Ball-drake | Magic8Ball | UNOFFICIAL | port-26x | activo 2026-10-24 |  | si | - | no | 2/0/2 | maven j21 |
| MagicXpansion | MagicXpansion | 0.1.0-UNIVERSAL-26x-SNAPSHOT | port-26x | activo 2026-11-05 | DrakesMagic/magic_xpansion (stub-config) | si | - | si | 34/3/29 | maven j25 |
| Military-Arsenal | WeaponsAddon | 1.1.3 | main | activo 2026-11-03 | DrakesCombat/military_arsenal (stub-config) | si | Slimefun | si | 65/6/50 | maven j21 |
| MiniBlocks-drake | MiniBlocks | UNOFFICIAL | port-26x | activo 2026-10-24 |  | si | - | no | 5/3/4 | maven j21 |
| MissileWarfare-drake | MissileWarfare | 1.11-SNAPSHOT.13 | port-26x | activo 2026-10-23 | DrakesCombat/missilewarfare (stub-config) | si | Slimefun | no | 53/0/54 | maven j21 |
| MobCapturer-drake | MobCapturer | 11.0-Drake-1.21.1-SNAPSHOT | port-26x | activo 2026-10-07 | DrakesBio/mob_capturer (logica-sin-items) | si | Slimefun | si | 4/0/2 | maven j25 |
| MoreResearches-drake | MoreResearches | UNOFFICIAL | port-26x | activo 2026-10-18 |  | si | - | no | 0/0/0 | maven j21 |
| MultiverseCreatures | MultiverseCreatures | 3.6-RELEASE | port-26x | activo 2026-10-13 | DrakesMultiverse/creatures (stub-config) | si | - | no | 0/1/0 | maven j21 |
| MultiverseNets | MultiverseNets | 6.2 | main | activo 2026-10-26 | DrakesMultiverse/nets (logica-sin-items) | si | - | no | 0/0/1 | maven j21 |
| Netheopoiesis | Netheopoiesis | MODIFIED | main | activo 2026-11-03 | DrakesMagic/netheopoiesis (stub-config) | si | Slimefun | no | 3/0/17 | maven j21 |
| Networks---Better-Compatibility-Unofficial- | Networks | 1.6 | main | archivado 2026-10-17 |  | si | Slimefun | no | 1/1/56 | maven j21 |
| NetworksV6-drake | NetworksV6-Drake | 11-SNAPSHOT | port-26x | activo 2026-11-03 | DrakesTech/networks (stub-config) | si | Slimefun | si | 2/2/141 | maven j25 |
| Nexcavate | Nexcavate | 1.0-SNAPSHOT | port-26x | activo 2026-10-26 | DrakesTech/nexcavate (stub-config) | si | Slimefun | si | 9/2/2 | maven j25 |
| NotEnoughAddons | NotEnoughAddons | UNOFFICIAL | port-26x | activo 2026-10-08 | DrakesUtility/notenoughaddons (logica-sin-items) | si | - | no | 12/3/12 | maven j25 |
| Obsidian-Armor | Obsidian-Armor | 1.0.0 | port-26x | activo 2026-10-08 | DrakesCombat/obsidian_expansion (logica-sin-items) | si | Slimefun | si | 5/0/5 | maven j25 |
| ObsidianExpansion | ObsidianExpansion | PRIVATE | port-26x | activo 2026-10-24 | DrakesCombat/obsidian_expansion (logica-sin-items) | si | Slimefun | no | 26/5/21 | maven j25 |
| Odysseia | Odysseia | 1.1.3 | port-26x | activo 2026-11-05 | DrakesServer/odysseia (stub-config) | si | - | no | 0/0/0 | maven j21 |
| Odysseia-Rust | Odysseia | 1.1.0-SNAPSHOT | main | activo 2026-11-03 |  | si | - | no | 0/0/0 | maven j21 |
| PotionExpansion-drake | PotionExpansion | 1.0.0 | port-26x | activo 2026-10-24 |  | si | - | no | 13/1/21 | maven j? |
| PrivateStorage | PrivateStorage | UNOFFICIAL | port-26x | activo 2026-10-08 | DrakesTech/privatestorage (stub-config) | si | - | si | 24/0/24 | maven j25 |
| Pylon-Drake | (gradle, plugin.yml generado en build; rootProject.name) pylon | ? | master | archivado 2026-10-13 |  | ? | - | no | 0/0/2 | gradle j21 |
| Quaptics-drake | Quaptics | 0 | port-26x | activo 2026-10-26 | DrakesTech/quaptics (stub-config) | si | Slimefun | no | 171/1/129 | maven j21 |
| RandomExpansion | RandomExpansion | 1.0.0 | port-26x | activo 2026-10-08 | DrakesServer/random_expansion (stub-config) | si | - | si | 9/0/9 | maven j25 |
| Rebar-Drake | (gradle, plugin.yml generado en build; rootProject.name) rebar-root | ? | master | archivado 2026-11-03 |  | ? | - | ? | 0/0/58 | gradle j? |
| RelicsOfCthonia-drake | RelicsOfCthonia | 1.21.11-Drake.1 | port-26x | activo 2026-10-07 | DrakesMagic/relics_cthonia (logica-sin-items) | si | Slimefun | no | 39/0/5 | maven j21 |
| RykenSlimeCustomizer-EN-drake | RykenSlimeCustomizer | 20 | port-26x | activo 2026-10-10 | DrakesTech/customizer (stub-config) | si | Slimefun | si | 18/18/5 | maven j25 |
| SaneCrafting-drake | SaneCrafting | 1.0 | port-26x | activo 2026-11-02 | DrakesTech/sanecrafting (stub-config) | si | Slimefun | si | 0/0/6 | maven j25 |
| SensibleToolbox-drake | SensibleToolbox | 1.22-Drake-SNAPSHOT | port-26x | activo 2026-11-02 | DrakesTech/sensibletoolbox (stub-config) | si | - | no | 1/24/7 | maven j21 |
| SfBetterChests-drake | BetterChests | 1.0 | port-26x | activo 2026-11-06 |  | si | Slimefun | no | 38/3/31 | maven j21 |
| SFCalc-drake | SFCalc | 1.21-Drake-v1 | main | activo 2026-10-14 | DrakesUtility/sfcalc (logica-sin-items) | si | Slimefun | no | 0/0/7 | maven j25 |
| SfChunkInfo-drake | SfChunkInfo | Unoffical-1.11-SNAPSHOT.1 | port-26x | activo 2026-10-24 |  | si | Slimefun | no | 0/0/0 | maven j21 |
| SFMobDrops-drake | SFMobDrops | 1.20.6-Drake-SNAPSHOT | port-26x | activo 2026-10-12 | DrakesCombat/sfmobdrops (logica-sin-items) | si | Slimefun | no | 0/0/0 | maven j21 |
| SFPortalGuns-Extension | SFPortalGunsExtension | 1.1.0-SNAPSHOT | main | activo 2026-11-03 | DrakesUtility/portalgun (logica-sin-items) | si | Slimefun | no | 3/0/3 | maven j21 |
| Simple-Storage-drake | SimpleStorage | MODIFIED | port-26x | activo 2026-10-24 |  | si | Slimefun,InfinityExpansion | no | 67/3/38 | maven j21 |
| SimpleUtils-drake | SimpleUtils | MODIFIED | port-26x | activo 2026-10-07 | DrakesUtility/simpleutils (logica-sin-items) | si | Slimefun | si | 4/1/3 | maven j25 |
| SlimeChem-drake | SlimeChem | 1.21-Drake-v7-UNIVERSAL-26x | port-26x | activo 2026-10-08 | DrakesMagic/slimechem (stub-config) | si | Slimefun | si | 20/2/10 | maven j25 |
| SlimeCustomizer | SlimeCustomizer | 1.0.0 | port-26x | activo 2026-10-18 | DrakesTech/customizer (stub-config) | si | Slimefun | no | 11/6/22 | maven j21 |
| SlimeFrame-drake | SlimeFrame | 1.7.0 | port-26x | activo 2026-10-31 | DrakesUtility/slimeframe (stub-config) | si | Slimefun | no | 32/22/16 | maven j21 |
| Slimefun-Disc-drake | Slimefun-Disc | 1.5.DEV | port-26x | activo 2026-10-08 | DrakesCombat/slimefundisc (logica-sin-items) | si | Slimefun | no | 2/0/4 | maven j21 |
| Slimefun-Knowledge-Drake | SlimefunKnowledgeDump | 0.1.0 | main | activo 2026-10-30 |  | si | Slimefun | no | 0/0/0 | ? j? |
| Slimefun-Rust | ? | ? | main | activo 2026-10-30 |  | ? | - | ? | 0/0/0 | ? j? |
| SlimefunAdvancements | SlimefunAdvancements | MODIFIED | port-26x | archivado 2026-11-06 | DrakesUtility/guidetools (stub-config) | si | Slimefun | no | 0/0/0 | maven j21 |
| SlimefunNukes | SlimefunNukes | 3.2.5 | main | activo 2026-11-03 | DrakesCombat/slimefun_nukes (logica-sin-items) | si | Slimefun | no | 1/0/1 | maven j? |
| SlimefunOreChunks | SlimefunOreChunks | UNOFFICIAL | port-26x | activo 2026-10-08 | DrakesGenerators/ore_chunks (logica-sin-items) | si | Slimefun | si | 1/0/4 | maven j25 |
| SlimefunTranslation-drake | (gradle, plugin.yml generado en build; rootProject.name) SlimefunTranslation | 11.0-Drake-1.21.1-SNAPSHOT | main | activo 2026-10-28 |  | ? | - | no | 0/0/0 | gradle j21 |
| SlimefunWarfare-Drake | SlimefunWarfare | 1.0.0-drake | port-26x | activo 2026-10-08 | DrakesCombat/warfare (logica-sin-items) | si | Slimefun | si | 100/8/53 | maven j25 |
| SlimeHUD-drake | SlimeHUD | 1.3.1-Universal-26.x-SNAPSHOT | port-26x | activo 2026-11-03 | DrakesUtility/slimehud (logica-sin-items) | si | - | no | 0/0/0 | maven j21 |
| SlimeTinker-drake | SlimeTinker | 1.2.1-Drake | port-26x | activo 2026-10-08 | DrakesCombat/slimetinker (logica-sin-items) | si | Slimefun | si | 13/0/3 | maven j25 |
| SlimyBees | SlimyBees | 1.0.0-UNOFFICIAL | port-26x | activo 2026-10-08 | DrakesBio/slimy_bees (logica-sin-items) | si | Slimefun | no | 29/4/16 | maven j21 |
| SlimyRepair-drake | SlimyRepair | 1.0.0 | port-26x | activo 2026-10-24 |  | si | Slimefun | no | 1/0/1 | maven j21 |
| SlimyTreeTaps-drake | SlimyTreeTaps | 11.0-Universal-26.x-SNAPSHOT | port-26x | activo 2026-10-26 | DrakesBio/slimytreetaps (logica-sin-items) | si | Slimefun | no | 16/2/8 | maven j21 |
| SmallSpace-drake | SmallSpace | MODED | port-26x | activo 2026-10-12 | DrakesUtility/smallspace (stub-config) | si | - | no | 10/6/7 | maven j21 |
| SMG-drake | SimpleMaterialGenerators | 1.0.0 | port-26x | activo 2026-10-12 | DrakesGenerators/smg (logica-sin-items) | si | - | no | 20/0/20 | maven j21 |
| SoulJars-drake | SoulJars | 1.21-Drake-v1 | main | activo 2026-10-14 | DrakesMagic/soul_jars (logica-sin-items) | si | - | si | 4/0/2 | maven j25 |
| SoundMuffler-drake | SoundMuffler | 11.0-Drake | port-26x | activo 2026-10-12 | DrakesUtility/soundmuffler (logica-sin-items) | si | Slimefun,ProtocolLib | no | 1/2/1 | maven j21 |
| SpiritsUnchained-drake | SpiritsUnchained | UNOFFICIAL | port-26x | activo 2026-10-26 | DrakesMagic/spiritsunchained (stub-config) | si | - | no | 29/3/25 | maven j21 |
| Supreme-Drake | Supreme | 1.21.11-Drake.1 | port-26x | activo 2026-10-24 | DrakesTech/supreme (logica-sin-items) | si | Slimefun | no | 219/28/64 | maven j21 |
| TranscEndence-drake | TranscEndence | DevTest-33 | port-26x | activo 2026-11-06 | DrakesMagic/transcendence (stub-config) | si | Slimefun | no | 34/6/19 | maven j21 |
| UltimateGenerators2-drake | UltimateGenerators2 | 1.1.DEV | main | activo 2026-10-28 | DrakesGenerators/ultimate_generators (logica-sin-items) | si | Slimefun | no | 0/0/58 | maven j? |
| VillagerTrade-drake | VillagerTrade | UNOFFICIAL | port-26x | activo 2026-10-26 |  | si | Slimefun | no | 0/0/0 | maven j21 |
| VillagerUtil-drake | VillagerUtil | MODIFIED | port-26x | activo 2026-10-24 |  | si | - | no | 10/0/6 | maven j21 |
| WorldEditSlimefun-drake | WorldEditSlimefun | UNOFFICIAL | port-26x | activo 2026-10-08 | DrakesUtility/worldedit_sf (stub-config) | si | Slimefun | si | 1/0/1 | maven j25 |
| YoutubeMusicMachine | YoutubeMusicMachine | 1.5.0 | master | activo 2026-11-03 |  | si | Slimefun | no | 1/0/1 | maven j21 |

## 5. Plugins de servidor / otros no-Slimefun con codigo

| repo | plugin id | version | rama | mantenimiento | consolidado_en | standalone | depend | 26.x declarado | items/maq/recetas* | build |
|---|---|---|---|---|---|---|---|---|---|---|
| ArcanaDrakes | DrakesArcana | 0.1.0 | main | activo 2026-10-13 |  | si | - | no | None/0/0 | maven j21 |
| drakes-labs-autoupdate | ? | 11-SNAPSHOT | main | activo 2026-11-03 |  | ? | - | no | None/0/0 | maven j21 |
| DrakesCore | DrakesCore | 1.0-SNAPSHOT | main | archivado 2026-10-17 |  | si | - | no | None/2/0 | maven j21 |
| DrakesMotd | DrakesMotd | 1.0-SNAPSHOT | main | activo 2026-10-13 |  | si | - | no | None/0/0 | maven j21 |
| DrakesRanks | DrakesRanks | 1.0-SNAPSHOT | main | activo 2026-10-30 |  | si | - | no | None/0/0 | maven j21 |
| DrakesRankup | DrakesRankup | 1.0.0 | port-26x | activo 2026-11-02 |  | si | - | si | None/0/0 | maven j25 |
| DrakesTab | DrakesTab | 1.0-SNAPSHOT | main | activo 2026-10-13 |  | si | - | no | None/0/0 | maven j21 |
| DrakesTranslate | DrakesTranslate | 1.0.0 | main | activo 2026-11-03 |  | si | - | no | None/0/0 | maven j21 |
| DrakesVIPPlusPlus | DrakesVIPPlusPlus | 1.0.0 | port-26x | activo 2026-10-05 |  | si | LuckPerms | no | None/0/0 | maven j21 |
| DrakesWorlds | DrakesWorlds | 1.0-SNAPSHOT | main | activo 2026-10-13 |  | si | - | no | None/1/0 | maven j21 |
| E4MC-Offline | (gradle, plugin.yml generado en build; rootProject.name) e4mc_minecraft_architectury | ? | main | activo 2026-10-29 |  | ? | - | ? | None/0/0 | gradle j? |
| EnderCarryOn | EnderCarryOn | development | main | activo 2026-10-13 | DrakesUtility/endercarryon (logica-sin-items) | si | EnderCore | no | None/0/0 | gradle j? |
| EssentialsX-Drake | EssentialsDiscordLink | 2.22.0 | fix/chat-processing-cache-race | activo 2026-10-13 |  | si | EssentialsDiscord | no | None/0/0 | gradle j? |
| ExcellentEnchants-Drake | ExcellentEnchants | 5.4.3 | port-26x | activo 2026-10-08 |  | si | nightcore | no | None/0/0 | maven j25 |
| ImageFrame | ImageFrame | 2026.1.4.1 | main | activo 2026-10-15 |  | si | - | no | None/0/0 | maven j? |
| InvSwitcher-Drake | BentoBox-InvSwitcher | 1.19.3-SNAPSHOT | port-26x | activo 2026-10-14 | DrakesServer/invswitcher (stub-config) | si | BentoBox | si | None/0/0 | maven j25 |
| IP-Detector | SingleIPPlugin | 2.0 | master | inactivo 2026-02-04 |  | si | - | no | None/0/0 | maven j17 |
| java-chat-ai | (gradle, plugin.yml generado en build; rootProject.name) java-chat-ai | 1.0.0 | master | inactivo 2026-05-30 |  | ? | - | ? | None/0/0 | gradle j17 |
| javaai-core | (gradle, plugin.yml generado en build; rootProject.name) javaai-core | 1.0.0 | master | inactivo 2026-05-30 |  | ? | - | ? | None/0/0 | gradle j17 |
| MultiverseGambling | MultiverseGambling | 1.0.7 | main | activo 2026-11-03 |  | si | - | no | None/0/1 | maven j21 |
| MultiverseProgramming | MultiverseProgramming | 1.2.2 | main | activo 2026-11-03 |  | si | - | no | None/0/0 | maven j21 |
| MultiverseTinker | MultiverseTinker | 1.2.0 | main | activo 2026-11-03 |  | si | - | no | None/0/0 | maven j21 |
| PlayerVaultZ-Drake | ? | 1.1.0-drake.1 | port-26x | archivado 2026-11-06 | DrakesServer/playervaultz (stub-config) | ? | - | si | None/0/0 | maven j25 |
| ProtectionStones-Drake | ProtectionStones | 2.10.6 | port-26x | activo 2026-10-07 |  | si | WorldGuard,WorldEdit | no | None/0/0 | maven j1.21 |
| S-PlayerWarps-Drake | S-PlayerWarps | 1.1 | main | activo 2026-11-04 |  | si | - | no | None/0/0 | gradle j? |
| sbank | sBank | 1.5.0 | port-26x | activo 2026-11-06 | DrakesServer (README: Core Integration; en codigo existe DrakesEconomyModule, equivalencia no verificada) (solo-README) | si | Vault | no | None/0/0 | maven j21 |
| SlimefunEssentials | ? | ? | 1.20.4 | activo 2026-10-13 |  | ? | - | ? | 2/0/4 | gradle j? |
| VanillaPatPat | VanillaPatPat | 1.0.1 | main | activo 2026-11-03 | DrakesUtility/vanillapatpat (logica-sin-items) | si | - | no | None/0/0 | gradle j21 |
| voice-java-ai | (gradle, plugin.yml generado en build; rootProject.name) voice-java-ai | 1.0.0 | master | inactivo 2026-05-30 |  | ? | - | ? | None/0/0 | gradle j17 |

## 6. Placeholders, archivo legacy, infraestructura

- **drakes-slimefun-labs** (archivo_legacy): rama main, activo 2026-11-03. desc GitHub: Slimefun 6 / Drake Framework migration lab for Paper 1.21.11, dough-core, and large-scale addon porting. Archivo legacy (README): no desplegar jars de aqui. migration/addons.json: 84 modulos fuente, 44 migrados, 40 pendientes.
- **Drakes-Suites** (suite_monorepo): rama 26.x, activo 2026-11-06. 
- .github: activo 2026-11-05 - Repo sin codigo fuente (solo README/workflows/assets).
- AllSlimeCustomAddons: activo 2026-10-05 - Repo sin codigo fuente (solo README/workflows/assets).; Placeholder: codigo original en drakes-slimefun-labs/sources (archivo legacy; migracion pendiente segun migration/addons.json).
- ColoredEnderChests-drake: activo 2026-10-05 - Modulo nativo en DrakesUtility/colored_enderchests: 1 .java en la suite vs 0 en el repo. En el codigo de la suite este modulo NO registra SlimefunItems (0 SlimefunItemStack/extends SlimefunItem en esa
- community: activo 2026-11-03 - Repo sin codigo fuente (solo README/workflows/assets).
- CompressionCraft-drake: activo 2026-10-05 - Repo sin codigo fuente (solo README/workflows/assets).; Placeholder: codigo original en drakes-slimefun-labs/sources (archivo legacy; migracion pendiente segun migration/addons.json).
- drakes-log-audit: activo 2026-11-03 - Repo sin codigo fuente (solo README/workflows/assets).
- drakescraft-datapack: activo 2026-10-17 - Repo sin codigo fuente (solo README/workflows/assets).
- drakescraft-texture-pack: activo 2026-11-05 - Repo sin codigo fuente (solo README/workflows/assets).
- drakescraft-web: activo 2026-11-06 - Repo sin codigo fuente (solo README/workflows/assets).
- DrakesLabPresence-drake: activo 2026-10-05 - Repo sin codigo fuente (solo README/workflows/assets).; Placeholder: codigo original en drakes-slimefun-labs/sources (archivo legacy; migracion pendiente segun migration/addons.json).
- DyeBench-drake: activo 2026-10-05 - Repo sin codigo fuente (solo README/workflows/assets).; Placeholder: codigo original en drakes-slimefun-labs/sources (archivo legacy; migracion pendiente segun migration/addons.json).
- Igdrassil: activo 2026-11-03 - Repo sin codigo fuente (solo README/workflows/assets).
- KinematicCore-drake: activo 2026-10-05 - Repo sin codigo fuente (solo README/workflows/assets).; Placeholder: codigo original en drakes-slimefun-labs/sources (archivo legacy; migracion pendiente segun migration/addons.json).
- luckyblocks-sf-drake: activo 2026-10-05 - La suite solo registra un GenericSuiteModule + modules/luckyblocks.yml (configuracion); el CODIGO del addon NO esta en la suite -> se necesita el jar standalone de este repo para tener sus items.; Rep
- MapJammers-drake: activo 2026-10-05 - Repo sin codigo fuente (solo README/workflows/assets).; Placeholder: codigo original en drakes-slimefun-labs/sources (archivo legacy; migracion pendiente segun migration/addons.json).
- maven-repo: activo 2026-11-03 - Repo sin codigo fuente (solo README/workflows/assets).
- MusicMP3-Downloader: inactivo 2026-05-20 - Repo sin codigo fuente (solo README/workflows/assets).
- slimefun-porting-tools: activo 2026-11-03 - Repo sin codigo fuente (solo README/workflows/assets).
- Slimefun-Texture-Drake: activo 2026-10-14 - Repo sin codigo fuente (solo README/workflows/assets).
- Wildernether-drake: activo 2026-10-05 - Repo sin codigo fuente (solo README/workflows/assets).; Placeholder: codigo original en drakes-slimefun-labs/sources (archivo legacy; migracion pendiente segun migration/addons.json).

## 7. Repos de JackStar6677-1
- Relevantes: `Slimefun-Knowledge-Drake` (base de conocimiento: dump de recetas `data/slimefun-dump.json.gz`, tooling; el CI hybrid-compat lo usa como volcado de produccion), `Coronalis` (privado, copia anterior de Coronalis-drake, ultimo commit 2026-08-18).
- No pertinentes (otras areas: escuela, IA, shaders, etc.): AstroControlSim, AureonVault, Aurexalis, AuroralisStar, BitacoraAPP, CampusCare-Monitoring, CastelRoomKeeper, JackStar6677-1, StellarDaybook, VeyonScripts, VoidWhisper, VotV-Points-Editor, ai-hub, astra-web, castel-credcam, castel-hq, cempudla, colegio-castelgandolfo-home, colegio-castelgandolfo-web, drakes-translate, edudocente-studio, jack-portal, jackos, omniast, omnisky-miner, saori, saori-3d, saori-discord, star-ambient-stream, star-server-backup, students-point, tangled-lantern, vault-stack (no analizados; varios privados).
