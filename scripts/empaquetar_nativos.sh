#!/usr/bin/env bash
# Compila la biblioteca nativa del Hybrid Engine y la deja en purpur-server/bundled/drakes-natives/<plataforma>/ junto a su SHA-256,
# para que el JAR (construido con DRAKES_BUNDLE=1) la lleve embebida. Solo se declara soporte para plataformas compiladas Y probadas.
set -euo pipefail
cd "$(dirname "$0")/.."
( cd native/drakes-hybrid && cargo build --release )
os=$(uname -s | tr 'A-Z' 'a-z'); case "$os" in linux) ext=so; pre=lib;; darwin) os=macos; ext=dylib; pre=lib;; *) ext=dll; pre=; os=windows;; esac
arch=$(uname -m); case "$arch" in x86_64|amd64) arch=x86_64;; aarch64|arm64) arch=aarch64;; esac
OUT="purpur-server/bundled/drakes-natives/${os}-${arch}"; mkdir -p "$OUT"
f="${pre}drakes_hybrid_ffi.${ext}"
cp "native/drakes-hybrid/target/release/$f" "$OUT/$f"
( cd "$OUT" && sha256sum "$f" | awk '{print $1}' > "$f.sha256" )
echo "embebida: $OUT/$f  sha256=$(cat "$OUT/$f.sha256")"
