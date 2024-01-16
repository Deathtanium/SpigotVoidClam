package org.serbanstein.voidclam;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class CommandListclams implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        commandSender.sendMessage("List of clams:");
        int i=0;
        for(Clam clam : Main.clamList){
            commandSender.sendMessage("["+i+"] at " + clam.x + " " + clam.y + " " + clam.z);
            i++;
        }
        return true;
    }
}
