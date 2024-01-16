package org.serbanstein.voidclam;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class CommandTickrepairgrow implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        ClamBehaviorUtils.f_repairgrowtask();
        return true;
    }
}
