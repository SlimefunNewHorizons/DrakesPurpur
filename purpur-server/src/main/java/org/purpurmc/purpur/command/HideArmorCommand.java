package org.purpurmc.purpur.command;

import com.google.common.collect.Lists;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.datafixers.util.Pair;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class HideArmorCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hidearmor")
                .requires(listener -> listener.hasPermission(Permissions.COMMANDS_GAMEMASTER, "bukkit.command.hidearmor"))
                .executes(context -> execute(context.getSource(), Collections.singleton(context.getSource().getPlayerOrException())))
                .then(Commands.argument("targets", EntityArgument.players())
                        .requires(listener -> listener.hasPermission(Permissions.COMMANDS_GAMEMASTER, "bukkit.command.hidearmor.other"))
                        .executes(context -> execute(context.getSource(), EntityArgument.getPlayers(context, "targets")))
                )
        );
    }

    private static int execute(CommandSourceStack sender, Collection<ServerPlayer> targets) {
        for (ServerPlayer player : targets) {
            boolean newState = !player.hideArmor();
            player.hideArmor(newState);

            // Re-transmitir paquete de equipamiento a todos los jugadores que rastrean a este jugador y a sí mismo
            List<Pair<EquipmentSlot, ItemStack>> slots = Lists.newArrayList();
            for (EquipmentSlot slot : EquipmentSlot.VALUES) {
                if (slot.isArmor() && newState) {
                    slots.add(Pair.of(slot, ItemStack.EMPTY));
                } else {
                    slots.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
                }
            }

            ClientboundSetEquipmentPacket packet = new ClientboundSetEquipmentPacket(player.getId(), slots, true);
            ((ServerLevel) player.level()).getChunkSource().sendToTrackingPlayers(player, packet);
            player.connection.send(packet);

            Component msg = Component.text("[DrakesCraft] ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text("Armadura visible: ", NamedTextColor.WHITE))
                    .append(Component.text(newState ? "OCULTA (Skin visible)" : "VISIBLE", newState ? NamedTextColor.GOLD : NamedTextColor.GREEN))
                    .append(Component.text(newState ? " — Tus defensas y estadísticas siguen 100% activas." : ""));

            player.getBukkitEntity().sendMessage(msg);

            if (!sender.isPlayer() || sender.getPlayer() != player) {
                Component notify = Component.text("Armadura de " + player.getScoreboardName() + " ahora está " + (newState ? "oculta" : "visible") + ".", NamedTextColor.YELLOW);
                sender.sendSuccess(notify, false);
            }
        }
        return targets.size();
    }
}
