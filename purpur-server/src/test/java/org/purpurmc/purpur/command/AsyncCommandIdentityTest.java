package org.purpurmc.purpur.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent;
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

    @Test
    void sendAsyncKeepsRedirectIdentityForStructurallyEqualTargets() throws Exception {
        CommandNode<CommandSourceStack> firstTarget = LiteralArgumentBuilder.<CommandSourceStack>literal("target").build();
        CommandNode<CommandSourceStack> secondTarget = LiteralArgumentBuilder.<CommandSourceStack>literal("target").build();
        assertEquals(firstTarget, secondTarget);
        assertNotSame(firstTarget, secondTarget);

        RootCommandNode<CommandSourceStack> sourceRoot = new RootCommandNode<>();
        sourceRoot.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("first").then(firstTarget).build());
        sourceRoot.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("second").then(secondTarget).build());
        sourceRoot.addChild(LiteralArgumentBuilder.<CommandSourceStack>literal("alias").redirect(firstTarget).build());

        Commands commands = mock(Commands.class, CALLS_REAL_METHODS);
        Field dispatcher = Commands.class.getDeclaredField("dispatcher");
        dispatcher.setAccessible(true);
        dispatcher.set(commands, new CommandDispatcher<>(sourceRoot));
        ServerPlayer player = mock(ServerPlayer.class);
        IllegalStateException converted = new IllegalStateException("capture converted tree before event dispatch");
        var convertedRoots = new ArrayList<RootCommandNode<CommandSourceStack>>();

        try (var events = mockConstruction(AsyncPlayerSendCommandsEvent.class, (event, context) -> {
            @SuppressWarnings("unchecked")
            var convertedRoot = (RootCommandNode<CommandSourceStack>) context.arguments().get(1);
            convertedRoots.add(convertedRoot);
            doThrow(converted).when(event).callEvent();
        })) {
            Method sendAsync = Commands.class.getDeclaredMethod("sendAsync", ServerPlayer.class, Collection.class);
            sendAsync.setAccessible(true);
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> sendAsync.invoke(commands, player, new ArrayList<>(sourceRoot.getChildren())));
            assertSame(converted, thrown.getCause());
            assertEquals(1, events.constructed().size());
        }

        assertEquals(1, convertedRoots.size());
        var convertedRoot = convertedRoots.getFirst();
        var convertedFirst = convertedRoot.getChild("first").getChild("target");
        var convertedSecond = convertedRoot.getChild("second").getChild("target");
        assertNotSame(convertedFirst, convertedSecond);
        assertSame(convertedFirst, convertedRoot.getChild("alias").getRedirect());
    }
}
