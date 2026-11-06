package org.purpurmc.purpur.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Produces one class overlay for a disposable runtime test, never edits a server jar. */
public final class StructureAnchorFixture implements Opcodes {
    public static final String OWNER = "net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate";
    private static final String TAG = "net/minecraft/nbt/CompoundTag";
    private static final String POS = "net/minecraft/core/BlockPos";
    private static final String HELPER = "drakes$relocateStructureAnchor";
    private static final String HELPER_DESC = "(L" + TAG + ";L" + POS + ";)V";

    private StructureAnchorFixture() {}

    public static byte[] patch(byte[] input) {
        ClassNode type = new ClassNode();
        new ClassReader(input).accept(type, 0);
        if (!OWNER.equals(type.name) || type.methods.stream().anyMatch(m -> m.name.equals(HELPER))) {
            throw new IllegalArgumentException("Wrong class or overlay already applied");
        }
        List<MethodNode> matches = type.methods.stream().filter(m -> m.name.equals("placeEntities")).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Expected one placeEntities method");
        MethodNode method = matches.getFirst();
        MethodInsnNode create = null;
        Integer tagSlot = null;
        Integer posSlot = null;
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.owner.equals(TAG) && call.name.equals("copy") && call.desc.equals("()L" + TAG + ";")) {
                if (tagSlot != null) throw new IllegalArgumentException("Ambiguous tag copy");
                tagSlot = storedReference(call);
            }
            if (call.owner.equals(POS) && call.name.equals("offset")
                    && call.desc.equals("(Lnet/minecraft/core/Vec3i;)L" + POS + ";")) {
                if (posSlot != null) throw new IllegalArgumentException("Ambiguous transformed anchor");
                posSlot = storedReference(call);
            }
            if (call.owner.equals(OWNER) && call.name.equals("createEntityIgnoreException")
                    && call.desc.equals("(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/world/level/ServerLevelAccessor;L" + TAG + ";)Ljava/util/Optional;")) {
                if (create != null || tagSlot == null || posSlot == null) {
                    throw new IllegalArgumentException("Unknown entity creation shape");
                }
                create = call;
            }
        }
        if (create == null) throw new IllegalArgumentException("Entity creation not found");
        // The original three call arguments remain on the stack; this call consumes only its own arguments.
        InsnList fix = new InsnList();
        fix.add(new VarInsnNode(ALOAD, tagSlot));
        fix.add(new VarInsnNode(ALOAD, posSlot));
        fix.add(new MethodInsnNode(INVOKESTATIC, OWNER, HELPER, HELPER_DESC, false));
        method.instructions.insertBefore(create, fix);
        type.methods.add(helper());
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        type.accept(writer);
        return writer.toByteArray();
    }

    private static int storedReference(AbstractInsnNode call) {
        AbstractInsnNode next = call.getNext();
        while (next != null && next.getOpcode() < 0) next = next.getNext();
        if (!(next instanceof VarInsnNode store) || store.getOpcode() != ASTORE) {
            throw new IllegalArgumentException("Expected reference store");
        }
        return store.var;
    }

    private static MethodNode helper() {
        MethodNode m = new MethodNode(ACC_PRIVATE | ACC_STATIC, HELPER, HELPER_DESC, null, null);
        LabelNode end = new LabelNode();
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new LdcInsnNode("block_pos"));
        m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, TAG, "contains", "(Ljava/lang/String;)Z", false));
        m.instructions.add(new JumpInsnNode(IFEQ, end));
        m.instructions.add(new VarInsnNode(ALOAD, 0));
        m.instructions.add(new LdcInsnNode("block_pos"));
        m.instructions.add(new FieldInsnNode(GETSTATIC, POS, "CODEC", "Lcom/mojang/serialization/Codec;"));
        m.instructions.add(new VarInsnNode(ALOAD, 1));
        m.instructions.add(new MethodInsnNode(INVOKEVIRTUAL, TAG, "store",
                "(Ljava/lang/String;Lcom/mojang/serialization/Codec;Ljava/lang/Object;)V", false));
        m.instructions.add(end);
        m.instructions.add(new FrameNode(F_SAME, 0, null, 0, null));
        m.instructions.add(new InsnNode(RETURN));
        return m;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("runtime.jar output.class (new file only)");
        byte[] result;
        try (ZipFile jar = new ZipFile(args[0])) {
            var entry = jar.getEntry(OWNER + ".class");
            if (entry == null) throw new IllegalArgumentException("Expected a mapped runtime jar, not paperclip");
            try (var stream = jar.getInputStream(entry)) { result = patch(stream.readAllBytes()); }
        }
        Files.write(Path.of(args[1]), result, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
