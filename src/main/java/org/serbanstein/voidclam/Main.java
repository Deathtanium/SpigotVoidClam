package org.serbanstein.voidclam;

import org.bukkit.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


//this plugin adds a special type of immobile entity to minecraft
public final class Main extends JavaPlugin {

    //dynamic list for keeping track of the entities
    public static List<Clam> clamList = new ArrayList<>();



    @Override
    public void onEnable() {
        /*
        * load voidclam.jsonl from resources and populate clamList
        *
        *
        *
        * */
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
