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
A separate regression captures the converted tree and verifies that an alias
still points to its exact target when two branches contain structurally equal
nodes. The event is intercepted, so the test does not dispatch Bukkit events.

Run the focused regression with Java 25:

```sh
./gradlew :purpur-server:test --tests org.purpurmc.purpur.command.AsyncCommandIdentityTest
```

Runtime staging validation and production deployment must be recorded separately
from this automated regression.

Validation on 2026-11-24 CLT: the Java 25 focused Gradle build compiled the server and
test sources and passed both regression tests (zero failures, errors or skips).
The patch has not yet been exercised with a connected player in staging.

The bundled server from source commit `9f0055c` also reached `Done` in 101.710 s
in an isolated Java 25 staging fixture (flat world, no plugins or players), then
saved and stopped with exit code 0. No exceptions were logged. The eight warning
lines were JOML's deprecated Unsafe access and the fixture's offline mode; the
fixture listened on loopback and was removed after shutdown.
Its extracted server SHA-256 matched the paperclip's target server hash.

Validation on 2026-11-25 CLT: the focused Java 25 build passed all three
regressions, with zero failures, errors or skips, including the alias identity
case. This run compiled test sources; the engine artifact remains the previously
built `9f0055c` paperclip (SHA-256
`cfa97e5fff51b9aa506d7e2752db33cc88158472688593ffa34dd895ee1d3150`).
The artifact has not been installed in production. A connected-player staging
check is still outstanding.
