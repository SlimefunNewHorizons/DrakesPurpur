#!/usr/bin/env bash
# Benchmark reproducible Java vs nativo (Panama). Uso: scripts/native-bench.sh [dir-con-.so]  (default: ./natives)
# Requiere JDK 25 (o 22+). Compila solo las clases de nativelib (sin Gradle). nice -n 10, 1 hilo de JIT extra.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
OUT=$(mktemp -d)
"$JAVA_HOME/bin/javac" -d "$OUT" purpur-server/src/main/java/org/purpurmc/purpur/nativelib/*.java tools/native-bench/NativeBench.java
nice -n 10 "$JAVA_HOME/bin/java" -Xms256m -Xmx256m -XX:+UseG1GC -XX:CICompilerCount=2 --enable-native-access=ALL-UNNAMED \
  -cp "$OUT" NativeBench "${1:-natives}"
