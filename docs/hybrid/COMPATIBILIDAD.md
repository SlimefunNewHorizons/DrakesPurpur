# Matriz de compatibilidad

Leyenda: OK = verificado en esta sesion · NV = no verificado (requiere build/servidor/cliente) · N/A

| Area | Estado | Nota |
|---|---|---|
| Motor hibrido OFF (defecto) | OK (logica) / NV (servidor) | `HybridNative.init` con `enabled=false` no carga nada; probado en `HybridCheck` |
| Fallback a Java si falta .so | OK (logica) | `find()` nulo -> warning + Java |
| Bukkit/Paper/Purpur API, eventos, scheduler | OK por construccion / NV | No se toca ninguna API ni hilo; la rama solo anade una clase y 15 lineas de config |
| Slimefun y addons, StarSuites (9), Geyser Companion, SAORI, DrakesProfiler, RyzenWorkerPool | NV | No modificados. Sin servidor construido no hay prueba funcional; **no se afirma** que funcionen mas alla de no haber sido tocados |
| Mundos / activar-desactivar sin corrupcion | OK por diseno | Rust no escribe archivos ni estado; sin integracion en el pipeline no hay efecto en disco |
| Java vanilla 26.2 | NV | Sin cambios de protocolo |
| Horizon Client (26.3) -> servidor 26.2 | NV | El cliente trae adaptador `older-servers` con 26.2 (leido en codigo). Probar: cliente Horizon en `mc.drakescraft.cl` de pruebas |
| Slimefun en Horizon Client | NV / probable fallo parcial | Depende de resource pack y paquetes custom; un cliente no-vanilla puede no renderizar modelos custom igual |
| Bedrock via Geyser | NV | Sin cambios |
| Mods Forge/Fabric/NeoForge | N/A | No se promete; mods de cliente != plugins de servidor |
| FPS / render | N/A | Pertenece al cliente Horizon, no al servidor |
