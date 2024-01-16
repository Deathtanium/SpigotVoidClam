package org.serbanstein.voidclam;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class CommandTickrepairgrow implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        if(strings.length < 1){
            return false;
        }
        ClamBehaviorUtils.f_repairgrowtask();
        return true;
    }
}
