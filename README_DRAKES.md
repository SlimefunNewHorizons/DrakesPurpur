# 🏛️ DrakesPurpur 26.2 — El Motor Soberano de DrakesCraft
> **Kernel Fork de Purpur 26.2 (Paperweight + Java 25 LTS)**  
> **Arquitecto Jefe & Creador:** Jack (JackStar6677-1)  
> **Fecha de Creación:** Octubre 2026  
> **Propósito:** Proporcionar la base de ejecución más rápida, resistente y blindada para la Temporada 2 de DrakesCraft.

---

## 🌟 Pilares de Ingeniería de DrakesPurpur

1. **Kernel Crash Guard (Auto-Cicatrización):**
   - Intercepta excepciones no controladas en el bucle principal de juego (`ServerTick`).
   - Aísla la entidad o bloque problemático sin colapsar el proceso del servidor.
   - Registra el fallo atómicamente en SQLite WAL y notifica a SAORI.

2. **Pre-carga Nativa Rust FFM (`libslimefun_ffi.so`):**
   - Vincula las librerías dinámicas off-heap en el arranque del servidor antes de cargar mundos.
   - Reduce las pausas del Garbage Collector (G1GC) y acelera tickers de redes eléctricas en nanosegundos.

3. **`SaoriKernelBridge` Directo:**
   - Enlace nativo por socket/IPC entre el motor y el orquestador SAORI.
   - Erradica la lectura de logs en disco (`latest.log`) y elimina la necesidad de plugins intermediarios pesados.

4. **CI/CD Automatizado con GitHub Actions:**
   - Todo cambio en este repositorio dispara un build en la nube de GitHub con Java 25.
   - Genera el artefacto `DrakesPurpur-26.2-server.jar` listo para su despliegue seguro en staging o Dallas.
