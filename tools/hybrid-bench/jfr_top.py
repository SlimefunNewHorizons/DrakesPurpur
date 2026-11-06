#!/usr/bin/env python3
"""Agrega un .jfr (jdk.ExecutionSample): metodos con mas tiempo propio y por familia (worldgen, nbt, red...).
Uso: jfr_top.py <archivo.jfr> [N]. Requiere el binario `jfr` del JDK en PATH."""
import collections, re, subprocess, sys
f = sys.argv[1]; n = int(sys.argv[2]) if len(sys.argv) > 2 else 25
txt = subprocess.run(["jfr", "print", "--events", "jdk.ExecutionSample", "--stack-depth", "64", f],
                     capture_output=True, text=True, check=True).stdout
samples = [b for b in txt.split("jdk.ExecutionSample") if "stackTrace" in b]
self_t = collections.Counter(); fam = collections.Counter(); total = 0
FAMS = [("worldgen:noise/density", r"NoiseChunk|DensityFunction|ImprovedNoise|PerlinNoise|NormalNoise|BlendedNoise|Climate|Aquifer|NoiseRouter"),
        ("worldgen:surface/carvers", r"SurfaceSystem|SurfaceRules|WorldCarver|CarvingContext"),
        ("worldgen:features/structures", r"PlacedFeature|ConfiguredFeature|StructureStart|StructurePlacement|ChunkGeneratorStructureState|BiomeSource"),
        ("lighting", r"LightEngine|ThreadedLevelLightEngine|SkyLight|BlockLight"),
        ("nbt/codec/palette", r"PalettedContainer|SimpleBitStorage|NbtOps|CompoundTag|Codec"),
        ("chunk system/scheduling", r"ChunkHolder|ChunkMap|ChunkTaskScheduler|ChunkStatus|GenerationChunkHolder")]
for b in samples:
    frames = re.findall(r"^\s+([\w.$<>]+)\.([\w$<>]+)\(", b.split("stackTrace = [")[1], re.M)
    if not frames: continue
    total += 1
    cls, m = frames[0]
    self_t[f"{cls}.{m}"] += 1
    joined = "\n".join(c for c, _ in frames)
    for name, rx in FAMS:
        if re.search(rx, joined): fam[name] += 1
print(f"muestras={total}")
print("\n== top metodos por tiempo propio ==")
for k, v in self_t.most_common(n): print(f"{100*v/total:5.1f}%  {k}")
print("\n== familias (inclusivo: la muestra tiene esa familia en su pila) ==")
for k, v in fam.most_common(): print(f"{100*v/total:5.1f}%  {k}")
