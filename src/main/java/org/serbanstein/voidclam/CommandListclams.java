package org.serbanstein.voidclam;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class CommandListclams implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        commandSender.sendMessage("List of clams:");
        for(Clam clam : Main.clamList){
            commandSender.sendMessage("Clam at " + clam.x + " " + clam.y + " " + clam.z);
        }
        return true;
    }
}
