package org.purpurmc.purpur.command;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AsyncCommandIdentityTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void structuralHashReproducesConcurrentModification() {
        RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();
        root.addChild(new LiteralCommandNode<>("first", null, source -> true, null, null, false) {
            @Override
            public int hashCode() {
                root.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("added").build());
                return super.hashCode();
            }
        });
        root.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("second").build());

        assertThrows(ConcurrentModificationException.class, () -> new HashMap<>().put(root, new RootCommandNode<>()));
    }

    @Test
    void sendAsyncConvertsNodesAndRedirectsWithoutStructuralHashing() throws Exception {
        RootCommandNode<CommandSourceStack> root = new RootCommandNode<>() {
            @Override
            public int hashCode() {
                throw new ConcurrentModificationException("root children changed during structural hash");
            }
        };
        CommandNode<CommandSourceStack> command = new LiteralCommandNode<>("example", null, source -> true, null, null, false) {
            @Override
            public int hashCode() {
                throw new ConcurrentModificationException("command children changed during structural hash");
            }
        };
        root.addChild(command);
        root.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("alias").redirect(command).build());

        Commands commands = mock(Commands.class, CALLS_REAL_METHODS);
        Field dispatcher = Commands.class.getDeclaredField("dispatcher");
        dispatcher.setAccessible(true);
        dispatcher.set(commands, new CommandDispatcher<>(root));
        ServerPlayer player = mock(ServerPlayer.class);
        IllegalStateException converted = new IllegalStateException("conversion complete; stop before Bukkit event dispatch");
        doThrow(converted).when(player).getBukkitEntity();

        Method sendAsync = Commands.class.getDeclaredMethod("sendAsync", ServerPlayer.class, Collection.class);
        sendAsync.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
            () -> sendAsync.invoke(commands, player, new ArrayList<>(root.getChildren())));

        assertSame(converted, thrown.getCause());
        verify(player).createCommandSourceStack();
        verify(player).getBukkitEntity();
    }
}
