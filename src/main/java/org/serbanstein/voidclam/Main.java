package org.serbanstein.voidclam;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import static org.serbanstein.voidclam.ClamBehaviorUtils.loadClams;
import static org.serbanstein.voidclam.ClamBehaviorUtils.populateLightList;


//this plugin adds a special type of immobile entity to minecraft
public final class Main extends JavaPlugin {
    public static BukkitTask heartbeatSoundTask,seekLightTask,checkRepairGrowTask,buildPathExecutorTask;
    //dynamic list for keeping track of the entities
    public static List<Clam> clamList = new ArrayList<>();
    public static FileConfiguration config;
    public static File voidclamFile;
    public void registerTasks(){
        checkRepairGrowTask = ClamBehaviorUtils.registerCheckRepairGrowTask();
        seekLightTask = ClamBehaviorUtils.registerLightSeekTask();
        heartbeatSoundTask = ClamBehaviorUtils.registerHeartbeatSoundTask();
        buildPathExecutorTask = ClamBehaviorUtils.registerBuildTaskExecutor();
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        voidclamFile = new File(getDataFolder(), "voidclams.jsonl");
        if(!voidclamFile.exists()){
            try {
                Files.createFile(voidclamFile.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        saveDefaultConfig();
        config = getConfig();
        populateLightList();
        registerTasks();
        //open voidclams.jsonl and load the clams into the list
        clamList = loadClams();
    }


    public void stopTasks(){
        checkRepairGrowTask.cancel();
        seekLightTask.cancel();
        heartbeatSoundTask.cancel();
        buildPathExecutorTask.cancel();
        ClamBehaviorUtils.asyncTasks.forEach(BukkitTask::cancel);
    }
    @Override
    public void onDisable() {
        // Plugin shutdown logic
        stopTasks();
    }

    //register commands
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            // Here we need to give items to our player
        }

        // If the player (or console) uses our command correct, we can return true
        return true;
    }
}