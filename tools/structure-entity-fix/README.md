# Structure entity anchor regression (MC-252934)

The 26.2 source patch updates the copied `block_pos` to the transformed structure
anchor before `Entity.load` validates it. The original template, existing world
entities, item payloads and validation rules remain intact. It addresses the
[upstream issue fixed in Minecraft 26.3](https://feedback.minecraft.net/hc/en-us/articles/48913133328013-Minecraft-Java-Edition-26-3).

This standalone Maven module is a **test tool**, not a plugin or server release.
`StructureAnchorFixture` builds a single class overlay with the same guarded
assignment, so a disposable 26.2 runtime can exercise the fix before a full engine
build. It discovers local variable slots from instruction patterns, preserves
other methods, rejects unfamiliar bytecode and duplicate application, and writes
only a new output file. Its helper is private and does not suppress errors.

Java 25 build on Star:

```bash
~/ai-hub/scripts/build_seguro.sh 25 "$PWD/tools/structure-entity-fix" mvn -B -o clean package
```

Measured on 2026-11-06: six JUnit tests passed; compilation and packaging took
12.102 seconds; five offline Python regressions also passed. In disposable
Paper 26.2-133, seven actual placements reduced anchor errors from 52 to zero,
with identical entity counts, complete saves and exit code zero in both cases.
The complete runtime test remains **failed**: an eighth placement used the
invalid rotation `clockwise_180`. This was corrected offline to `180`; it has
not been rerun (two-attempt stop). The first attempt exited before startup due
to a mixed dependency classpath, now replaced by the verified bootstrap manifest.
This does **not** certify a full DrakesPurpur build, the monolith, Dallas, Java 21
or Minecraft 1.21.11. No production artifact has been built or deployed.

To produce the overlay, include this module's `target/classes`, `asm` and
`asm-tree` 9.9.1 on the Java classpath, then invoke:

```text
org.purpurmc.purpur.tools.StructureAnchorFixture runtime.jar NEW_OUTPUT.class
```

Place that new class under the overlay directory at
`net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.class`.
Do not replace any class in an installed server. Run the differential test with
only disposable worlds and no other JVM active:

```bash
systemd-run --user --scope --unit=saori-ensayo609-UNIQUE \
  -p CPUQuota=200% -p MemoryMax=4G \
  python3 tools/structure-entity-fix/ensayo.py \
    --bootstrap PAPERCLIP_JAR --runtime RUNTIME_JAR \
    --libraries LIBRARIES_DIR --overlay OVERLAY_DIR \
    --pack STELLARITY_ZIP --output NEW_TEST_DIRECTORY
```

The test verifies SHA-256 of each dependency in the bootstrap's exact library
manifest, avoiding incompatible versions from unrelated plugin caches.
It requires host load below 5, uses local ephemeral listeners, and runs the
baseline and overlay sequentially. Eight placements cover four rotations, both
mirrors, paintings, frames and glowing frames using unchanged structure NBT.
It requires loaded chunks, actual placement acknowledgements, equal nonempty
entity counts, baseline reproduction, zero candidate errors, complete saving and
clean process exits. Logs and worlds stay outside Git. If shutdown stalls, it
leaves the scope for investigation instead of killing a saving server.

Rollback: revert the source patch commit before rebuilding. The overlay and its
disposable test directory can be removed after successful shutdown. There is no
installation or production mutation in this workflow.
