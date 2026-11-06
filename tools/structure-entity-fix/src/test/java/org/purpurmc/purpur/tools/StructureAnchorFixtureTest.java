package org.purpurmc.purpur.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class StructureAnchorFixtureTest implements Opcodes {
    static final String TAG = "net/minecraft/nbt/CompoundTag";
    static final String POS = "net/minecraft/core/BlockPos";
    static final String OWNER = StructureAnchorFixture.OWNER;
    static final String CREATE = "(Lnet/minecraft/util/ProblemReporter;Lnet/minecraft/world/level/ServerLevelAccessor;L" + TAG + ";)Ljava/util/Optional;";

    static byte[] fixture(int tagSlot, int posSlot, boolean copy, boolean offset, int calls) {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        w.visit(V25, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor untouched = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "untouched", "()I", null, null);
        untouched.visitCode(); untouched.visitIntInsn(BIPUSH, 42); untouched.visitInsn(IRETURN);
        untouched.visitMaxs(0, 0); untouched.visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC | ACC_STATIC, "placeEntities", "()V", null, null);
        m.visitCode();
        if (copy) {
            m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKEVIRTUAL, TAG, "copy", "()L" + TAG + ";", false);
            m.visitVarInsn(ASTORE, tagSlot);
        }
        if (offset) {
            m.visitInsn(ACONST_NULL); m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKEVIRTUAL, POS, "offset", "(Lnet/minecraft/core/Vec3i;)L" + POS + ";", false);
            m.visitVarInsn(ASTORE, posSlot);
        }
        for (int i = 0; i < calls; i++) {
            m.visitInsn(ACONST_NULL); m.visitInsn(ACONST_NULL); m.visitInsn(ACONST_NULL);
            m.visitMethodInsn(INVOKESTATIC, OWNER, "createEntityIgnoreException", CREATE, false);
            m.visitInsn(POP);
        }
        m.visitInsn(RETURN); m.visitMaxs(0, 0); m.visitEnd(); w.visitEnd();
        return w.toByteArray();
    }

    static ClassNode read(byte[] bytes) {
        ClassNode n = new ClassNode(); new ClassReader(bytes).accept(n, 0); return n;
    }

    @Test void insertsBeforeLoadingUsingDiscoveredSlotsAndNoDebugTable() {
        ClassNode n = read(StructureAnchorFixture.patch(fixture(17, 23, true, true, 1)));
        MethodNode place = n.methods.stream().filter(m -> m.name.equals("placeEntities")).findFirst().orElseThrow();
        MethodInsnNode call = null;
        for (var i : place.instructions) {
            if (i instanceof MethodInsnNode m && m.name.equals("createEntityIgnoreException")) call = m;
        }
        assertNotNull(call);
        MethodInsnNode helper = (MethodInsnNode) call.getPrevious();
        assertEquals("drakes$relocateStructureAnchor", helper.name);
        assertEquals(23, ((VarInsnNode) helper.getPrevious()).var);
        assertEquals(17, ((VarInsnNode) helper.getPrevious().getPrevious()).var);
        MethodNode untouched = n.methods.stream().filter(m -> m.name.equals("untouched")).findFirst().orElseThrow();
        assertEquals(42, ((IntInsnNode) untouched.instructions.getFirst()).operand);
        assertEquals(IRETURN, untouched.instructions.getLast().getOpcode());
        assertEquals(3, n.methods.size());
    }

    @Test void helperGuardsExistingAnchorAndUsesCodecWithoutSuppressingValidation() {
        ClassNode n = read(StructureAnchorFixture.patch(fixture(12, 11, true, true, 1)));
        MethodNode helper = n.methods.stream().filter(m -> m.name.startsWith("drakes$")).findFirst().orElseThrow();
        int contains = 0, stores = 0;
        for (var i : helper.instructions) {
            if (i instanceof MethodInsnNode m) {
                if (m.name.equals("contains")) contains++;
                if (m.name.equals("store")) stores++;
                assertEquals(TAG, m.owner);
            }
        }
        assertEquals(1, contains); assertEquals(1, stores);
        assertTrue((helper.access & ACC_PRIVATE) != 0);
    }

    @Test void refusesAlreadyPatchedClass() {
        byte[] once = StructureAnchorFixture.patch(fixture(12, 11, true, true, 1));
        assertThrows(IllegalArgumentException.class, () -> StructureAnchorFixture.patch(once));
    }

    @Test void refusesMissingOrAmbiguousCreation() {
        for (int calls : new int[]{0, 2}) assertThrows(IllegalArgumentException.class,
                () -> StructureAnchorFixture.patch(fixture(12, 11, true, true, calls)));
    }

    @Test void refusesMissingCopyOrTransformedAnchor() {
        assertThrows(IllegalArgumentException.class, () -> StructureAnchorFixture.patch(fixture(12, 11, false, true, 1)));
        assertThrows(IllegalArgumentException.class, () -> StructureAnchorFixture.patch(fixture(12, 11, true, false, 1)));
    }

    @Test void refusesWrongClass() {
        ClassNode n = read(fixture(12, 11, true, true, 1)); n.name = "Other";
        ClassWriter w = new ClassWriter(0); n.accept(w);
        assertThrows(IllegalArgumentException.class, () -> StructureAnchorFixture.patch(w.toByteArray()));
    }
}
