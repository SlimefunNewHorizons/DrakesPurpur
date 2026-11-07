# `drakes-onechunk` sin `plugin.yml`: causa y correccion

**Causa (verificada leyendo `Drakes-Suites@26.x`, `drakes-onechunk/`):** el modulo no es un plugin de Paper sino un **addon de BentoBox** (`src/main/resources/addon.yml`, clase `OneChunkAddon`, dependencia `world.bentobox:bentobox`, descripcion "OneChunk game mode for BentoBox AOneBlock"). BentoBox carga addons desde `plugins/BentoBox/addons/`; si el jar se deja en `plugins/`, Paper lo rechaza con `Directory 'plugins/drakes-onechunk-...jar' does not contain a paper-plugin.yml or plugin.yml`. No hay un bug en el modulo: hay un **despliegue incorrecto** (copiar `*/target/*.jar` a `plugins/`).

**Correccion aplicada en este repositorio (no en Drakes-Suites):**
- `scripts/empaquetar_monolito.sh` omite `*onechunk*` (no lo embebe como plugin).
- El workflow `hybrid-compat.yml` instala `drakes-onechunk` en `plugins/BentoBox/addons/` (solo tiene efecto si BentoBox esta instalado; en el servidor de pruebas minimo no lo esta, por lo que el addon no se ejecuta ahi).

**Parche sugerido para `Drakes-Suites` (NO aplicado; requiere autorizacion):** documentar en su README/`pom` que `drakes-onechunk` se despliega en `plugins/BentoBox/addons/`, y excluirlo de cualquier script/CI que copie todos los jars a `plugins/`. Si el monolito debe incluirlo, hace falta soporte explicito de `DrakesBundledPlugins` para extraer addons de BentoBox a su carpeta (no existe hoy).
