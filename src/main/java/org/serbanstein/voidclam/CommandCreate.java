package org.serbanstein.voidclam;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import org.bukkit.entity.Player;
import org.json.*;

import static org.serbanstein.voidclam.Main.voidclamFile;

public class CommandCreate implements CommandExecutor {
    // This method is called, when somebody uses our command
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player)) {
            return false;
        }
        //positional arguments are x y z
        int x,y,z;
        if (args.length == 3) {
            try {
                x = Integer.parseInt(args[0]);
                y = Integer.parseInt(args[1]);
                z = Integer.parseInt(args[2]);
                //create a new voidclam at the specified location
                Clam newClam = new Clam(x,y,z,"world",1);
                Main.clamList.add(newClam);
                //save the new clam to the file
                try {
                    JSONObject newClamJSON = new JSONObject();
                    newClamJSON.put("x",x);
                    newClamJSON.put("y",y);
                    newClamJSON.put("z",z);
                    newClamJSON.put("world", Objects.requireNonNull(((Player) sender).getLocation().getWorld()).getName());
                    newClamJSON.put("currentSize",3);
                    newClamJSON.put("isActive",true);
                    newClamJSON.put("energy",0);
                    newClamJSON.put("startTick",Bukkit.getWorlds().get(0).getFullTime());
                    Files.write(voidclamFile.toPath(),(newClamJSON.toString()+"\n").getBytes(), StandardOpenOption.APPEND);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                //build the voidclam
                BuildUtils.buildVoidclamScript(x,y,z,3, Bukkit.getWorld("world"));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        return false;
    }

}
