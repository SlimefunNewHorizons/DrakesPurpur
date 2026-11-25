# Async command node conversion

`Commands.sendAsync` maps original Brigadier nodes to their client copies.
The conversion map now uses object identity, including redirect lookups.

Brigadier's [`CommandNode.hashCode()`](https://github.com/Mojang/brigadier/blob/master/src/main/java/com/mojang/brigadier/tree/CommandNode.java)
computes a structural hash of its mutable children. A `HashMap.put` on the root
therefore traverses the command tree on the asynchronous command builder.
Concurrent command registration can invalidate that traversal and throw
`ConcurrentModificationException` before conversion starts. Structural equality
also makes a mutable node unsuitable as a persistent hash key during conversion.

An `IdentityHashMap` tracks the specific source node without calling its
structural `hashCode` or `equals`. The existing conversion, permission checks,
redirect handling and both command-send event phases remain in place.
This patch addresses structural hashing; it does not make arbitrary concurrent
tree edits safe throughout every traversal.

`AsyncCommandIdentityTest` contains a deterministic reproduction with the former
map and exercises the actual private `sendAsync` method with nodes that reject
structural hashing, including an alias redirect. It stops after conversion,
before dispatching a Bukkit event, without connecting a player or server.

Run the focused regression with Java 25:

```sh
./gradlew :purpur-server:test --tests org.purpurmc.purpur.command.AsyncCommandIdentityTest
```

Runtime staging validation and production deployment must be recorded separately
from this automated regression.

Validation on 2026-11-25: the Java 25 focused Gradle build compiled the server and
test sources and passed both regression tests (zero failures, errors or skips).
The patch has not yet been exercised with a connected player in staging.
