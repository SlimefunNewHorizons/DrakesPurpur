# DrakesPurpur 26.2 - Install guide (engine jar)

DrakesPurpur is a Purpur/Paper fork for **Minecraft 26.2** with the Drakes kernel built in. There are two jars:

| | Modular (`ver/26.2`) | Monolith (`feat/slimefun-native-engine`) |
|---|---|---|
| Release asset | `drakespurpur-26.2-modular-<build>.jar` | `drakespurpur-26.2-monolith-<build>.jar` |
| Size | ~69 MB | ~82 MB |
| Contains | Purpur/Paper 26.2 + Drakes kernel | the modular jar + 9 Drakes suites embedded (see below) |
| Suites | you drop them into `plugins/` yourself | extracted automatically into `plugins/` on start |
| Choose it if | you already run your own plugins / want to pick versions | you want one file that starts the whole Drakes stack |

Both jars are paperclip launchers: the first start downloads/patches the vanilla server into `cache/` and `versions/`.

## 1. Requirements

* **Java 25** (OpenJDK / Temurin 25 LTS). Java 21 does NOT work with Minecraft 26.2.
* Linux x86_64 recommended (Windows and macOS work for testing). Run from a **local SSD**, not NFS/rclone mounts.
* RAM: modular 2 GB minimum (4-6 GB recommended); monolith with all suites 6 GB minimum (8-12 GB recommended for a populated server).
* Ports: game `25565` TCP (change with `server-port`); UDP is only needed if you add Geyser/Bedrock (default `19132/UDP`). Open them in the firewall. RCON is off unless you enable it.
* Disk: ~2 GB free for the first start (vanilla server, libraries, world).

## 2. Step by step

```bash
mkdir -p ~/drakespurpur && cd ~/drakespurpur
# 1) download the jar and SHA256SUMS.txt from the GitHub release, then verify
sha256sum -c SHA256SUMS.txt --ignore-missing
# 2) accept the Minecraft EULA (https://aka.ms/MinecraftEULA)
echo "eula=true" > eula.txt
# 3) start script
cat > start.sh <<'SH'
#!/usr/bin/env bash
cd "$(dirname "$0")"
exec /usr/lib/jvm/java-25-openjdk-amd64/bin/java \
  -Xms6G -Xmx6G -XX:+AlwaysPreTouch -XX:+UseG1GC -XX:MaxGCPauseMillis=100 \
  -XX:G1HeapRegionSize=8M -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=15 \
  -XX:InitiatingHeapOccupancyPercent=20 -XX:+ParallelRefProcEnabled -XX:+DisableExplicitGC \
  -XX:+PerfDisableSharedMem -Dio.netty.leakDetection.level=disabled \
  --enable-native-access=ALL-UNNAMED \
  -jar drakespurpur-26.2-monolith-138.jar --nogui
SH
chmod +x start.sh && ./start.sh
```

Adjust `-Xms/-Xmx` to your RAM (keep them equal; leave ~1-2 GB for the OS) and the jar name. `--nogui` avoids the Swing console.
Under a panel (Pterodactyl/Pelican) use the same flags in the startup command. Console colors work there; set `NO_COLOR=1` to disable.

### First start

* The console prints the DrakesPurpur banner (version, build, Minecraft, Java, mode, links, credits). Turn it off with `-Ddrakes.banner=false`.
* Standard files appear: `server.properties`, `bukkit.yml`, `spigot.yml`, `purpur.yml`, `config/paper-*.yml`, `logs/`, `world*/`.
* The kernel creates **`drakes-data/`**:
  * `kernel.db` (+ `-wal`/`-shm`): DrakesDb, the single shared SQLite database of the kernel (faults, staff, audit). Path can be changed with `-Ddrakes.db.path=...`. Never put it in Git.
  * `perf.yml` and `perf-status.json`: performance governor settings and live status.
  * `saori.yml`: SAORI link settings (disabled by default).
  * `suites.yml` (monolith only): which embedded suites are extracted.
* Stop with `stop` in the console, edit the files below, start again.

## 3. Configuration

* **`drakes-data/suites.yml`** (monolith): one `Name: true|false` per embedded suite. `false` = not extracted (and the identical embedded jar is removed from `plugins/`). `DrakesCore` is the base: if disabled the others will not start. Applied on restart. `-Ddrakes.bundled=false` turns the monolith into a modular server (no extraction at all).
* **`drakes-data/perf.yml`**: every performance feature is a switch with safe defaults (load governor, join throttling, chunk/entity tuning, ...). Change values only, keep the structure. Details and recommended profiles: [RENDIMIENTO_MOTOR.md](RENDIMIENTO_MOTOR.md). Live numbers: `drakes-data/perf-status.json`.
* **`drakes-data/saori.yml`**: SAORI integration (SaoriLink / SaoriAutomations / PrivacyGate). It ships **DISABLED** (`enabled: false`) and stays off until a token file exists. To use it: put your SAORI bearer token (one line) in `drakes-data/saori.token`, `chmod 600` it, set `enabled: true`, point `endpoint` to your SAORI instance (loopback only by default). The token is never written to logs. If you do not run SAORI, leave it alone.
* **`purpur.yml`**: normal Purpur settings plus the Drakes sections.
* Commands: `/drakes` (alias `/dpurpur`) kernel admin; `/saori`; `/drakessuites reload <suite> [module]`. See the README.

## 4. What is inside

* Minecraft **26.2**, **Java 25** required, Paper API 26.2.
* Kernel: **DrakesDb** (shared SQLite WAL), Crash Guard (isolates plugin faults), staff and modality engines, storage audit, 7z backup manager, Rust FFM bridge (optional `natives/libslimefun_ffi.so`; simulated mode when absent).
* **SAORI**: SaoriLink, SaoriAutomations and PrivacyGate (chat/log forwarding with a privacy filter). Disabled until a token exists.
* **Monolith only**: 9 suites embedded: DrakesCore, DrakesTech, DrakesBio, DrakesMagic, DrakesCombat, DrakesGenerators, DrakesServer, DrakesUtility, drakes-multiverse. Together they hold 92 addon modules + 2 more in the multiverse suite (94 modules).
* The modular jar has none of that: only the kernel.

## 5. Getting Slimefun and the suites (modular)

The suites run on top of the **Slimefun core** (Universal 26.x line, compiled against Paper API 1.21.11, Java 21 bytecode, loads fine on 26.2).

* Core: releases of [DrakesCraft-Labs/Slimefun4-Drake](https://github.com/DrakesCraft-Labs/Slimefun4-Drake/releases) or the Maven repo `https://maven.drakescraft.cl`.
* Suites: releases of [DrakesCraft-Labs/Drakes-Suites](https://github.com/DrakesCraft-Labs/Drakes-Suites/releases) (branch `26.x` is the active 26 line). Put `Slimefun4*.jar` and the `Drakes*.jar` files in `plugins/`. Install `DrakesCore` first; every other suite depends on it.
* Third-party infrastructure (LuckPerms, WorldEdit, ...) must be builds that support 26.x.

## 6. Migrating from Paper/Purpur 1.21.x

**Worlds and plugins are not binary compatible.** Read before touching a live server.

* Back up everything first. Test on a copy.
* Worlds: Minecraft upgrades worlds forward on load, but it is one-way. A world opened in 26.2 can no longer be opened in 1.21.x. Do not move your production world until you tested the copy.
* Plugins: jars built for 1.21.x may break on 26.2 (internals and NMS changed). Use 26.x builds only. Slimefun addons: the Drakes suites are the supported path.
* Config files from Paper/Purpur 1.21.x are migrated by the server on start, but review `purpur.yml` and `paper-*.yml` afterwards.
* Java must be 25; upgrade the panel's Java image/flags.

### Compatibility table

| | Production line | This engine |
|---|---|---|
| Minecraft | 1.21.11 (Paper) | **26.2** (DrakesPurpur) |
| Java | 21 | **25** |
| Plugin API | `1.21.11-R0.1` | Paper `26.2` |
| Plugins built for 1.21.11 | native | usually load (API compatible), NMS-based ones need a port |
| Worlds | 1.21.11 | upgrade is one-way |
| Versions below 1.21.11 | not supported | not supported |

## 7. Known issues

* Experimental test line (Season Two staging). Not recommended for a production world without your own testing.
* The first start is slow (downloads and patches vanilla).
* Some 1.21.x plugins fail on 26.2; check `logs/latest.log` for `NoSuchMethodError`/`ClassNotFoundException`.
* On NFS/rclone-backed disks the server may spam filesystem warnings; use local disk.
* `/version` reports DrakesPurpur; the update checker still looks at upstream Purpur and will say "unknown/behind". Ignore it.
* Ports in use / `Address already in use`: change `server-port`.

## 8. Reporting problems

Open an issue at <https://github.com/SlimefunNewHorizons/DrakesPurpur/issues> with: jar name and build number (the banner), Java version, `logs/latest.log` (no secrets), what you expected and steps to reproduce. Never paste `drakes-data/saori.token`.

## Credits and license

Based on Purpur (PurpurMC) and Paper (PaperMC); licensed per upstream (see `LICENSE` and `NOTICE`). Modifications by JackStar6677-1 (JackStar Systems), DrakesCraft Season Two.
