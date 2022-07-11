package com.serbanstein.voidclam;

import org.bukkit.Location;
import java.util.HashSet;
import java.util.Set;

public class Module {
	int type; // 0 is stub, 1 is teen, 2 is broadcast, 3 is arming, 4 is Complex, -1 is Lightning rod
	int x;
	int y;
	int z;
	int currentSize;
	int status; 	//hibernation status: 1 is awake, 0 is asleep
	int energy;
	int age;  			//the age will be counted since the last call of /startpassive or since the last phase change
	Set<Location> lightsBlackList = new HashSet<>();
	short busyFlagPlaceEvent;
	short busyFlagMainCycle;
}
