package com.serbanstein.voidclam;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.util.*;

import static org.bukkit.Bukkit.*;

public class Main extends JavaPlugin {
	static java.util.logging.Logger log = Bukkit.getLogger();
	public static List<BukkitTask> syncTasks = new ArrayList<>();		//arrays for tasks
	public static List<BukkitTask> aSyncTasks = new ArrayList<>();
	static Module[] modules = new Module[1001];
	static int moduleNumber = 0;
	public static List<Node> targets = new ArrayList<>();
	public static List<Material> lights = new ArrayList<>();	//list of blocks that SIVA's consider food
	public static Set<Material> fluids = new HashSet<>();
	public static List<Material> baseCost = new ArrayList<>();
	public static int serverPort;
	static String worldName;

	public static void getWorldName() {
		Scanner propFile = null;
		try {
			propFile = new Scanner(new File("server.properties"));
		} catch (FileNotFoundException e) {/**/}
		String worldLine;
        do {
			assert propFile != null;
			worldLine = propFile.nextLine();
        }while(!Objects.requireNonNull(worldLine).contains("level-name"));
        worldName = worldLine.substring(worldLine.indexOf('=')+1);

		Scanner propFile2 = null;
		try {
			propFile2 = new Scanner(new File("server.properties"));
		} catch (FileNotFoundException e) {/**/}
		String portLine;
		do {
			assert propFile2 != null;
			portLine = propFile2.nextLine();
		}while(!Objects.requireNonNull(portLine).contains("server-port"));
		serverPort = Integer.parseInt(portLine.substring(portLine.indexOf('=')+1));

    }

	//for filling List lights; list of blocks that SIVA's consider food
	public void makeLists() {
		lights.add(Material.BEACON);
		lights.add(Material.GLOWSTONE);
		lights.add(Material.JACK_O_LANTERN);
		lights.add(Material.SEA_LANTERN);
		lights.add(Material.LANTERN);
		lights.add(Material.END_ROD);
		lights.add(Material.TORCH);
		lights.add(Material.SEA_PICKLE);
		lights.add(Material.WALL_TORCH);
		lights.add(Material.SHROOMLIGHT);
		Pathfinder.xc.add(new Cursor( 1, 0, 0));
		Pathfinder.xc.add(new Cursor(-1, 0, 0));
		Pathfinder.xc.add(new Cursor( 0, 1, 0));
		Pathfinder.xc.add(new Cursor( 0,-1, 0));
		Pathfinder.xc.add(new Cursor( 0, 0, 1));
		Pathfinder.xc.add(new Cursor( 0, 0,-1));
		Pathfinder.yc.add(new Cursor( 1, 0, 0));
		Pathfinder.yc.add(new Cursor(-1, 0, 0));
		Pathfinder.yc.add(new Cursor( 0, 1, 0));
		Pathfinder.yc.add(new Cursor( 0,-1, 0));
		Pathfinder.yc.add(new Cursor( 0, 0, 1));
		Pathfinder.yc.add(new Cursor( 0, 0,-1));
		fluids.add(Material.AIR);
		fluids.add(Material.WATER);
		fluids.add(Material.LAVA);
		lights.add(Material.LAVA);
		baseCost.add(Material.AIR);
		baseCost.add(Material.WATER);
		baseCost.add(Material.LAVA);
		baseCost.add(Material.SNOW);
	}

	public static void saveToFile() {
		try {
			File f = new File(worldName+"/modules.siva");
			File old = new File(worldName+"/modules.siva.old");
			//noinspection ResultOfMethodCallIgnored
			old.delete();
			//noinspection ResultOfMethodCallIgnored
			f.renameTo(old);
			FileWriter writer = new FileWriter(worldName+"/modules.siva");
			BufferedWriter write = new BufferedWriter(writer);
			PrintWriter out = new PrintWriter(write);
			try {
				for (int i = 1; i <= moduleNumber; i++) {

					if (modules[moduleNumber] != null) {
						String toWrite;
						toWrite = modules[i].type + "," +
								modules[i].x + "," +
								modules[i].y + "," +
								modules[i].z + "," +
								modules[i].currentSize + "," +
								modules[i].status + ","+
								modules[i].energy + "," +
								modules[i].age;
						out.println(toWrite);
					}
				}
			} catch (NullPointerException e) {
				//Bukkit.getServer().broadcastMessage("null again");
			}
			out.close();
		} catch (Exception exception) {
			//
		}
	}


	//REMEMBER TO MODIFY THIS WHEN ADDING A NEW FIELD TO THE MODULES STRUCT
	public void onLoad() {
		makeLists();
		getWorldName();
		modules = new Module[1001];
		moduleNumber = 0;
		try {
			Scanner s = new Scanner(new File(worldName+"/modules.siva"));
			while (s.hasNextLine()) {
				String curr = s.nextLine();
				String[] lineData = curr.split(",", 0);
				moduleNumber++;
				modules[moduleNumber] = new Module();
				List<Integer> parsedLineData = new ArrayList<>();
				byte b; int i;
				String[] arrayOfString1;
				for (i = (arrayOfString1 = lineData).length, b = 0; b < i;) {
					String a = arrayOfString1[b];
					parsedLineData.add(Integer.parseInt(a));
					b++;
				}
				modules[moduleNumber].type = parsedLineData.get(0);
				modules[moduleNumber].x = parsedLineData.get(1);
				modules[moduleNumber].y = parsedLineData.get(2);
				modules[moduleNumber].z = parsedLineData.get(3);
				modules[moduleNumber].currentSize = parsedLineData.get(4);
				modules[moduleNumber].status = parsedLineData.get(5);
				modules[moduleNumber].energy = parsedLineData.get(6);
				modules[moduleNumber].age = parsedLineData.get(7);
			}
			s.close();
		} catch (FileNotFoundException fileNotFoundException) {/**/}
	}

	public void onEnable() {

		//register the EventListener class in EventListener.java
		getServer().getPluginManager().registerEvents(new VoidclamEventListener(), this);

		//autosearch lights
		syncTasks.add(new BukkitRunnable() {
			public void run() {
				for (int i = 1; i <= moduleNumber; i++) {
					if(true || Objects.requireNonNull(getWorld(worldName)).isChunkLoaded(modules[i].x/16,modules[i].z/16)) CommandToolbox.clamReach(i);
				}
			}}.runTaskTimer(Main.getPlugin(Main.class), 0, 1*20));

		//autorepair and grow
		syncTasks.add(new BukkitRunnable() {
			public void run() {
				int i,hasRoom,ix,iy,iz,x,y,z,csize;
				double cst;
				for (i = 1;i <= moduleNumber; i++){
					x = modules[i].x;
					y = modules[i].y;
					z = modules[i].z;
					if(false || !Objects.requireNonNull(getWorld(worldName)).isChunkLoaded(modules[i].x/16,modules[i].z/16)){
						continue;
					}
					csize = modules[i].currentSize;
					if (modules[i].energy > 4 * modules[i].currentSize && modules[i].currentSize < 15) {
						hasRoom = 1;
						cst = 0;
						for (ix = x - csize + 2; ix <= x + csize - 2; ix++) {
							for (iz = z - csize + 2; iz <= z + csize - 2; iz++) {
								for (iy = y - 2; iy <= y + csize / 2 + 2; iy++) {
									Block b = Objects.requireNonNull(getWorld(worldName)).getBlockAt(ix, iy, iz);
									Material m = b.getType();
									if (m != Material.AIR && m != Material.WATER && m != Material.LAVA && m != Material.OBSIDIAN && m != Material.NETHER_WART_BLOCK && m != Material.WARPED_WART_BLOCK) {
										if (m.getBlastResistance() == -1) hasRoom = 0;
										else cst += m.getBlastResistance();
									}
								}
							}
						}
						if (cst > 10 * csize) hasRoom = 0;
						if (hasRoom == 1) {
							modules[i].energy = 0;
							CommandToolbox.clamReSize(i, modules[i].currentSize + 2);
							modules[i].currentSize+=2;
						}
					}
					else CommandToolbox.clamReSize(i, modules[i].currentSize);
					modules[i].lightsBlackList.clear();
				}
				saveToFile();
			}
		}.runTaskTimer(Main.getPlugin(Main.class), 0, 5*60*20));

		//heartbeat sound
		syncTasks.add(new BukkitRunnable(){
			public void run() {
				for (int i = 1;i <= moduleNumber; i++) {
					if(Objects.requireNonNull(getWorld(worldName)).isChunkLoaded(modules[i].x/16,modules[i].z/16)) {
						int x = modules[i].x;
						int y = modules[i].y;
						int z = modules[i].z;
						float volume = (float) modules[i].currentSize / 4;
						float pitch = 0.7f;
						Objects.requireNonNull(getWorld(Main.worldName)).playSound(new Location(getWorld(Main.worldName), x, y, z),
								Sound.valueOf("BLOCK_CONDUIT_AMBIENT"), volume, pitch);
		}}}}.runTaskTimer(Main.getPlugin(Main.class),0,4*20));

		//Listener for Paths that finished calculating and were pushed in the global targets list
		syncTasks.add(new BukkitRunnable() {
			public void run() {
				if (!targets.isEmpty()) {
					targets.forEach(Pathfinder::buildpath);
					targets.clear();
				}
		}}.runTaskTimer(Main.getPlugin(Main.class), 0, 20));

		syncTasks.add(new BukkitRunnable(){
			public void run(){
				for(int i=1;i<=moduleNumber;i++) {
					Material x = Objects.requireNonNull(getWorld(worldName)).getBlockAt(modules[i].x,modules[i].y,modules[i].z).getType();
					if(x != Material.NETHER_WART_BLOCK && x != Material.OBSIDIAN){
						CommandToolbox.clamKill(i);
		}}}}.runTaskTimer(getPlugin(Main.class),0,20));

	}

	public void onDisable() {
		syncTasks.forEach(BukkitTask::cancel);
		aSyncTasks.forEach(BukkitTask::cancel);
		saveToFile();
	}

	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		//debug command
		if (command.getName().equalsIgnoreCase("voidclamping") && (sender.hasPermission("debugVoidClam"))) {
			sender.sendMessage(worldName+" "+moduleNumber);
			return true;
		}

		//debug command
		if (command.getName().equalsIgnoreCase("voidclamtestfile") && (sender.hasPermission("debugVoidClam"))) {
			try {BufferedReader reader = new BufferedReader(new FileReader(worldName+"/modules.siva"));
				String show;
				try {//noinspection StatementWithEmptyBody
					for (; (show = reader.readLine()) != null; log.info(show));} catch (IOException e) {e.printStackTrace();}
			} catch (FileNotFoundException e) {e.printStackTrace();}
		}

		//debug command
		if (command.getName().equalsIgnoreCase("voidclaminfo") && (sender.hasPermission("debugVoidClam"))) {
			if (args.length == 0) {
				if(sender instanceof Player) {
					int i,tno = -1;
					Location closest = new Location(getWorld(worldName),0,-1,0),loc;
					for(i=1;i<=moduleNumber;i++) {
						loc = new Location(getWorld(worldName),modules[i].x,modules[i].y,modules[i].z);
						if((closest.getY() == -1 || ((Player) sender).getLocation().distance(loc) < ((Player) sender).getLocation().distance(closest)))
						{closest = loc; tno = i;}
					}
					if(closest.getY()!=-1) {
						sender.sendMessage("Index: "+tno);
						sender.sendMessage("x: "+(modules[tno]).x + " y: " + (modules[tno]).y + " z: " + (modules[tno]).z+" Size: "+(modules[tno]).currentSize+" Power: "+(modules[tno]).energy);
					}
				}
				return true;
			}
			int tno;
			try{ tno = Integer.parseInt(args[0]); }catch(NumberFormatException e){return false;}
			if (tno > moduleNumber || modules[tno] == null || moduleNumber == 0) {
				sender.sendMessage("Bad number");
				return true;
			}
			sender.sendMessage("x: "+(modules[tno]).x + " y: " + (modules[tno]).y + " z: " + (modules[tno]).z+" Size: "+(modules[tno]).currentSize+" Power: "+(modules[tno]).energy);
			return true;
		}

		if (command.getName().equalsIgnoreCase("voidclammake") && (sender.hasPermission("debugVoidClam"))) {
			if (args.length!=3) return false;
			int x,y,z;
			try{
				x = Integer.parseInt(args[0]);
				y = Integer.parseInt(args[1]);
				z = Integer.parseInt(args[2]);
			}catch(NumberFormatException e){return false;}
			CommandToolbox.makestub(x,y,z);
			return true;
		}

		if (command.getName().equalsIgnoreCase("voidclamresize") && (sender.hasPermission("debugVoidClam"))) {
			if (args.length!=2) return false;
			int tno,tsize;
			try{tno = Integer.parseInt(args[0]); tsize = Integer.parseInt(args[1]);}catch(NumberFormatException e){return false;}
			int csize = (modules[tno]).currentSize;
			if (csize > tsize) {
				sender.sendMessage("target size cannot be be smaller than current size");
				return false;
			}
			CommandToolbox.clamReSize(tno,tsize);
			return true;
		}

		if (command.getName().equalsIgnoreCase("voidclamrepair") && (sender.hasPermission("debugVoidClam"))) {
			if (args.length!=1) return false;
			int tno;
			try{tno = Integer.parseInt(args[0]);}catch(NumberFormatException e){return false;}
			if(tno>moduleNumber || tno<0) {
				sender.sendMessage("Bad number");
				return false;
			}
			CommandToolbox.clamReSize(tno,modules[tno].currentSize);
			return true;
		}

		if (command.getName().equalsIgnoreCase("voidclamreach") && (sender.hasPermission("debugVoidClam"))) {
			if (args.length!=1) return false;
			int tno;
			try{tno = Integer.parseInt(args[0]);}catch(NumberFormatException e){return false;}
			if (tno > moduleNumber || modules[tno] == null) {
				sender.sendMessage("Bad number");
				return false;
			}
			CommandToolbox.clamReach(tno);
			return true;
		}

		if (command.getName().equalsIgnoreCase("voidclamkill") && (sender.hasPermission("debugVoidClam"))) {
			if (args.length!=1) return false;
			int tno;
			try{tno = Integer.parseInt(args[0]);}catch(NumberFormatException e){return false;}
			if(tno>moduleNumber) {
				sender.sendMessage("Bad number");
				return true;
			}
			if (modules[tno] == null || moduleNumber == 0) {
				sender.sendMessage("Bad number");
				return true;
			}
			CommandToolbox.clamKill(tno);
			return true;
		}

		if (command.getName().equalsIgnoreCase("voidclamgrow") && (sender.hasPermission("debugVoidClam"))) {
			if(args.length!=1) return false;
			int tno;
			try{tno = Integer.parseInt(args[0]);}catch(Exception e){e.printStackTrace(); return false;}
			if(tno > moduleNumber) {
				sender.sendMessage("Bad number");
				return true;
			}
			if (modules[tno] == null || moduleNumber == 0) {
				sender.sendMessage("Bad number");
				return true;
			}
			int cSize = modules[tno].currentSize;
			cSize += 2;
			CommandToolbox.clamReSize(tno,cSize);
			return true;
		}

		if(command.getName().equalsIgnoreCase("voidclamsave") && (sender.hasPermission("debugVoidClam"))) {
			saveToFile();
			return true;
		}
		return false;
	}
}