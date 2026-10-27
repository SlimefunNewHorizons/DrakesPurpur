# DrakesPurpur 26.2 - El Motor Soberano de DrakesCraft
> **Kernel Fork de Purpur 26.2 (Paperweight + Java 25 LTS)**  
> **Arquitecto Jefe & Creador:** Jack (JackStar6677-1)  
> **Fecha de Actualizacion:** Octubre 2026  
> **Proposito:** Proporcionar la base de ejecucion mas rapida, resistente y blindada para la Temporada 2 de DrakesCraft y redes tecnicas de alto rendimiento.

---

## Infraestructura Oficial y Comunidad

* **IP de Conexion**: `mc.drakescraft.cl` (Java 26.2 y Bedrock Cross-play en puerto 25565)
* **Discord Oficial**: [discord.gg/drakescraft](https://discord.gg/rv3vtXZTk7)
* **Portal Web y Documentacion**: [web.drakescraft.cl](https://web.drakescraft.cl)
* **Tienda Oficial**: [web.drakescraft.cl/store](https://web.drakescraft.cl/store.html)

DrakesPurpur es el nucleo soberano que impulsa la Temporada 2 de DrakesCraft, gestionando mas de 80 expansiones de Slimefun, economias dinamicas, aislamiento multi-modal, herramientas de moderacion integradas en el motor, auditoria de almacenamiento en SQLite WAL y 20.0 TPS continuos.

---

## Mision y Filosofia de Ingenieria

Los servidores de Minecraft de alta concurrencia que ejecutan addons tecnicos masivos (como Slimefun, logistica digital automatizada y maquinaria multibloque) colapsan historicamente debido a las pausas del Garbage Collector de la JVM y a la sobrecarga de millones de eventos Bukkit disparandose cada segundo.

**DrakesPurpur** es un fork soberano de [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur) disenado con un objetivo prioritario:  
**Transformar Minecraft en un motor tecnico de latencia ultra-baja y auto-cicatrizacion, blindado contra excepciones no controladas, sincronizado nativamente con orquestadores autonomos SRE (SAORI) y optimizado para jugadores moviles de Bedrock (Geyser).**

---

## Pilares de Ingenieria de DrakesPurpur

### 1. Kernel Crash Guard (Auto-Cicatrizacion y Cero Caidas)
* Intercepta excepciones no controladas (`NullPointerException`, `IndexOutOfBoundsException`, `ConcurrentModificationException`) directamente en el bucle principal de juego (`ServerTick` y `BlockEntityTick`).
* **Aislamiento Dinamico de Fallos:** Si una maquina de un addon o una entidad lanza un error no controlado, el motor deshabilita unicamente el componente danado y registra el incidente en una base de datos atomica SQLite WAL (`drakes_faults.db`). El servidor nunca se detiene y la comunidad mantiene 20.0 TPS estables.

### 2. Pre-carga Nativa Rust Off-Heap (Java 25 Panama FFM)
* **El Cuello de Botella Real de Minecraft:** En servidores tecnicos masivos, el limite rara vez es el procesamiento de CPU; es la creacion de millones de objetos transitorios Java por tick que saturan el Garbage Collector, provocando pausas criticas de Stop-The-World (STW).
* **Diseno Hibrido Empresarial:** Reescribir cientos de plugins Bukkit a Rust o C desde cero es inviable. La arquitectura soberana es hibrida: Java procesa la logica de juego, eventos y plugins, mientras que Rust administra la memoria pura off-heap mediante la API moderna Panama FFM (Foreign Function & Memory API) de Java 25, sin el overhead del JNI clasico.
* **Pre-carga en Arranque:** El servidor arranca enlazando la libreria nativa `libslimefun_ffi.so`. Redes electricas, transferencias cuanticas y el ticking de maquinas se calculan fuera del Heap de Java con vectorizacion SIMD (AVX-512 VNNI), reduciendo las pausas de G1GC por debajo de 15 milisegundos.

### 3. Motor Soberano de Modalidades (6 Modalidades Canonicas)
El kernel incorpora un motor interno de enrutamiento (`DrakesModalityEngine`) que divide el servidor en 6 modalidades canonicas dentro de la misma instancia, eliminando la latencia de red y la sobrecarga de RAM de clusters BungeeCord externos:
* **`survival_sf`**: Modalidad principal de supervivencia tecnica con Slimefun y todas las suites de maquinaria activas.
* **`survival_clasico`**: Modalidad Survival Vanilla Puro. **Esta es la unica modalidad que opera sin Slimefun.**
* **`oneblock`**: Modalidad de progresion sobre un bloque infinito regenerativo, integrada con BentoBox y tecnologia.
* **`onechunk`**: Desafio de ingenieria y supervivencia confinado a los limites de un unico chunk.
* **`skyblock`**: Progresion tecnica en islas flotantes en el vacio.
* **`lab`**: Entorno de pruebas tecnicas y laboratorio con reinicio periodico de mapa.

**Garantias de Aislamiento Hermetico:**
* Snapshots independientes de inventario, armadura y segunda mano por modalidad.
* Cofres de Ender completamente independientes.
* Niveles de experiencia, efectos de pocion, hambre y vida totalmente separados.
* Modos de juego y perfiles de permisos dedicados por modalidad.
* Migracion asincrona en memoria sin congelamientos de disco en el hilo principal.

### 4. Herramientas de Staff Integradas en el Motor (Sin Dependencia de Plugins)
Las herramientas de administracion critica residen directamente en el kernel de Purpur:
* **`/vani` / `/vanish`**: Estado espectral soberano. Intercepta paquetes de entidades en la capa de red, silencia sonidos, detiene particulas, bloquea la recoleccion accidental de items y oculta la presencia en la lista de jugadores (tablist) de forma invisible.
* **`/ultragod`**: Invulnerabilidad a nivel de kernel superior al comando clasico `/god`. Anula dano por vacio, caidas fuera del limite del mundo, comandos `/kill`, hambre, explosiones y cualquier vector de destruccion ambiental.
* **`/freeze <jugador>`**: Bloqueo interactivo inmediato de movimiento e interacciones a nivel de paquetes de red para sospechosos de exploits.
* **`/drakes inspect <jugador>`**: Telemetria administrativa integral:
  - UUID de la cuenta, ping, cliente utilizado y modalidad activa.
  - Direccion IP resuelta, geolocalizacion (pais) y proveedor de red (ASN/ISP).
  - Deteccion de multicuentas (Alts): rastreo cruzado en tiempo real y en SQLite de conexiones compartidas bajo la misma firma de red.
  - Visualizacion automatica de prefijos informativos sobre la cabeza del jugador para miembros del staff (`settings.drakes.staff.nametag-alt-display`).

### 5. Subsistema de Auditoria de Almacenamiento y Acciones (DrakesStorageAudit)
Motor transaccional embebido basado en SQLite WAL (`drakes_storage_audit.db`), disenado como una alternativa de cero sobrecarga frente a plugins de rollback externos:
* Registra colocacion, destruccion y eventos fisicos de bloques.
* Monitorea transacciones en contenedores (cofres, tolvas, shulkers, barriles) guardando cantidad, datos NBT y el jugador responsable.
* Registra recoleccion y lanzamiento de items en el suelo, asi como comandos ejecutados.
* Escritura asincrona mediante el pool multi-nucleo `RyzenWorkerPool`, eliminando cualquier congelamiento del hilo principal del servidor.

### 6. Motor de Respaldos Diarios Automatizados 7z (DrakesBackupScheduler)
Servicio interno de copias de seguridad sin degradacion de rendimiento:
* Utiliza el algoritmo de compresion LZMA2 en formato 7z para conseguir la maxima tasa de reduccion de tamano en mapas y configuraciones.
* Se ejecuta en horarios de baja concurrencia mediante hilos en segundo plano.
* Aplica una politica de rotacion con ventana de retencion estricta de 7 dias (`retention-days: 7`), purgando automaticamente archivos antiguos para proteger los discos NVMe.

### 7. Geyser y Bedrock Companion Engine (Optimizacion para Movil y Consolas)
* **Compensacion de Paquetes Redundantes:** Regula el envio de actualizaciones de entidades lejanas para ahorrar ancho de banda movil.
* **Recuperacion Preventiva del Vacio:** Previene muertes invisibles en el vacio del End y Skyblock reposicionando al jugador antes de la desincronizacion (`teleport-if-outside-border`).
* **Keepalive Desacoplado:** Handshakes alternativos asincronos que previenen desconexiones por falso timeout en redes celulares de alta latencia.

### 8. Enlace Nativo con SAORI (Socket IPC Directo)
* Conexion nativa por Socket de Dominio Unix entre el motor y el orquestador autonomo **SAORI** (`saori-ai-daemon` en puerto 8089).
* Erradica la lectura pesada de archivos de log en disco (`latest.log`) y elimina la necesidad de puentes de Discord externos.
* Permite sincronizacion de chat bidireccional en submilisegundos, alertas de caida en tiempo real y resolucion automatica de incidentes por agentes de IA.

### 9. DrakesProfiler y Despachador Multi-Nucleo Ryzen
* **Telemetria Directa:** Lectura de consumo de CPU, memoria JVM y uso de hilos directamente desde `ManagementFactory.getThreadMXBean()` y `OperatingSystemMXBean` con overhead cero.
* **Diagnostico Anti-Lag:** Desglose instantaneo de chunks cargados, recuento de entidades vivas por mundo y deteccion de hilos saturados.
* **Informes Locales en Markdown:** Generacion de auditorias en `drakes_reports/` con tablas comparativas y hardware sin exponer datos del servidor a visores web externos.
* **Planificador Ryzen:** `RyzenWorkerPool` (ForkJoinPool) para distribuir tareas asincronas y escrituras en SQLite entre todos los nucleos del procesador.

### 10. DrakesGrimGuard y DrakesChatGuard (Seguridad Nativa)
* **Anti-Cheat en Kernel (`DrakesGrimGuard`):** Validacion a nivel de paquetes de Reach geometrico (<3.20m), velocidad horizontal (<4.20 b/t), vuelo y clicks por segundo (<22 CPS) antes de entregar los datos a la capa de Bukkit.
* **Seguridad de Chat (`DrakesChatGuard`):** Cadencia minima entre mensajes (600ms), censura bilingue en tiempo real (`[censurado]`), proteccion contra paquetes gigantes (>256 caracteres) y bloqueo de inyeccion de comandos (`/op`, `/calc`, `/execute`).
* **Pruebas Sinteticas en Consola:** `/drakes test all` ejecuta pruebas integrales de seguridad, chat y motor Rust directamente desde la terminal sin necesidad de jugadores conectados.

### 11. Arquitectura Monolitica Slimefun Nativo (`feat/slimefun-native-engine`)
Rama paralela de investigacion y desarrollo que explora la integracion total de Slimefun como primitivas directas de Minecraft:
* Los items de Slimefun se evaluan como clases nativas `Block` e `Item`, eliminando metadatos PDC y envoltorios de Bukkit.
* Ejecucion en memoria en el mismo ciclo que los hornos y tolvas vanilla en `ServerLevel.tickBlockEntities()`.
* Genera el artefacto autonomo `DrakesPurpur-Server.jar` con soporte para mas de 80 expansiones preinstaladas de fabrica.

---

## Configuracion Centralizada (`purpur.yml`)

Todas las caracteristicas del kernel se gestionan centralizadamente en `purpur.yml` bajo la seccion `settings.drakes.*`:

```yaml
settings:
  drakes:
    crash-guard:
      enabled: true
      max-errors-per-minute: 60
      log-to-sqlite: true
    modalities:
      enabled: true
      worlds:
        survival_sf:
          - "world"
          - "world_nether"
          - "world_the_end"
        survival_clasico:
          - "clasico"
          - "clasico_nether"
          - "clasico_the_end"
        oneblock:
          - "oneblock_world"
        onechunk:
          - "onechunk_world"
        skyblock:
          - "bskyblock_world"
        lab:
          - "lab_world"
    staff:
      nametag-alt-display: true
      telemetry-database: "drakes_staff_telemetry.db"
    audit:
      storage-audit-enabled: true
      database: "drakes_storage_audit.db"
    backups:
      daily-7z-enabled: true
      retention-days: 7
      backup-hour-utc: 4
    anti-xray:
      engine-enhanced: true
    rust-engine:
      preload-native: true
      library-path: "natives/libslimefun_ffi.so"
    saori-bridge:
      enabled: true
      socket-path: "/var/run/saori.sock"
    geyser:
      packet-throttling: true
      void-teleport-fix: true
    motd:
      custom-gradient: true
    tab:
      smart-alignment: true
```

---

## Pipeline CI/CD y Distribucion Maven Soberana

El repositorio se encuentra automatizado con **GitHub Actions** (`.github/workflows/drakes_ci_cd.yml`):
1. **Compilacion en la Nube:** Cada commit en ramas `ver/**` compila el binario Paperclip del servidor en runners de GitHub con **Java 25** y Paperweight.
2. **Despliegue Automatico de API:** Compila y despliega `DrakesPurpur-API` al repositorio oficial en **`https://maven.drakescraft.cl`**.
3. **Distribucion por Webhook:** Envia el artefacto final a staging sin consumo de CPU ni memoria en estaciones de trabajo locales.

---

## Instrucciones de Compilacion Local

Para compilar DrakesPurpur localmente en sistemas Linux (Nova / Workstations):

```bash
# Requiere Java 25 LTS configurado en JAVA_HOME
./gradlew applyAllPatches
./gradlew createPaperclipJar -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

El binario de produccion generado estara disponible en:  
`purpur-server/build/libs/purpur-paperclip-26.2.local-SNAPSHOT.jar`.

---

## Reconocimientos y Atribucion Upstream

DrakesPurpur es un fork soberano disenado y mantenido por [**JackStar6677-1**](https://github.com/JackStar6677-1) bajo **DrakesCraft Labs** y **Star Systems**.

Agradecemos profundamente a los proyectos upstream y co-creadores soberanos:
* **PurpurMC** ([github.com/PurpurMC/Purpur](https://github.com/PurpurMC/Purpur)): Por la base de servidor de Minecraft mas configurable y flexible.
* **PaperMC** ([github.com/PaperMC/Paper](https://github.com/PaperMC/Paper)): Por liderar la innovacion en rendimiento de servidores, Paperweight y el ecosistema Bukkit.
* **Chagui68 (Chagui)**: Autor soberano de la **Suite Multiverse** (`MultiverseNets`, `MultiverseCreatures`, `MultiverseTinker`, `MultiverseProgramming` y `MultiverseGambling`) e innovador en **Pumpkin-launcher** (aislamiento de ClassLoader, sanitizacion de plugins y capa PatchBukkit). Reconocido en el kernel mediante `/drakes credits`.
* **GeyserMC** ([geysermc.org](https://geysermc.org/)): Por unificar las comunidades de Bedrock y Java.
* **TheBusyBiscuit** ([github.com/TheBusyBiscuit](https://github.com/TheBusyBiscuit)): Por crear Slimefun4, la piedra angular de la supervivencia tecnica moderna.
* **SpigotMC** y **Mojang Studios**: Por el universo de Minecraft.

---

<div align="center">

**DrakesCraft Labs · Star Systems** · Mantenido por [**JackStar6677-1**](https://github.com/JackStar6677-1)  
*Arquitectura Soberana de Software para la Temporada Dos*

</div>
