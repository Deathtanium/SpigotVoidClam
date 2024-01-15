package org.serbanstein.voidclam;

import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


//this plugin adds a special type of immobile entity to minecraft
public final class Main extends JavaPlugin {

    //dynamic list for keeping track of the entities
    public static List<Clam> clamList = new ArrayList<>();

    public static FileConfiguration config;
    /*
    * "spawn_naturally" : false,
      "trap_intruders": false,
      "kill_intruders": false,
      "trap_intruder_delay_sec": 2,
      "kill_intruder_delay_sec": 2
    *
    * */



    @Override
    public void onEnable() {
        saveDefaultConfig();
        config = getConfig();
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
