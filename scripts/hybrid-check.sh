#!/usr/bin/env bash
# Compila la biblioteca Rust + puente Java y ejecuta tests de paridad (y benchmark con --bench). Requiere JDK 22+ y cargo.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${HYBRID_JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
(cd native/drakes-hybrid && cargo test --release -q && cargo build --release -q)
OUT=$(mktemp -d)
"$JAVA_HOME/bin/javac" -d "$OUT" purpur-server/src/main/java/org/purpurmc/purpur/nativelib/HybridNative.java tools/hybrid-bench/HybridCheck.java
nice -n 10 "$JAVA_HOME/bin/java" -Xmx256m --enable-native-access=ALL-UNNAMED -cp "$OUT" HybridCheck native/drakes-hybrid/target/release "${1:-}"
