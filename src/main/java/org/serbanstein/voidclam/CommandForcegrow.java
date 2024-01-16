package org.serbanstein.voidclam;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class CommandForcegrow implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] strings) {
        if (strings.length < 1) {
            return false;
        }
        int clamID = Integer.parseInt(strings[0]);
        if (clamID < 0 || clamID > Main.clamList.size()) {
            return false;
        }
        Clam clam = Main.clamList.get(clamID);
        clam.energy=0;
        clam.currentSize++;
        ClamBehaviorUtils.saveClams();
        BuildUtils.buildVoidclamScript(clam.x, clam.y, clam.z, clam.currentSize, Main.getPlugin(Main.class).getServer().getWorld(clam.worldName));
        return true;
    }
}
