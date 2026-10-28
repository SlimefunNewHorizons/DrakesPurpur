package org.purpurmc.purpur.saori;

import java.util.Collections;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/** /saori <pregunta> — abierto a todos; SAORI decide la respuesta. */
public final class SaoriCommand extends Command {
    public SaoriCommand(String name) {
        super(name);
        this.description = "Pregunta a SAORI";
        this.usageMessage = "/saori <pregunta> | /saori status";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        SaoriLink.command(sender, args);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return args.length == 1 && "status".startsWith(args[0].toLowerCase()) ? List.of("status") : Collections.emptyList();
    }
}
