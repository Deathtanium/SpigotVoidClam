package org.serbanstein.voidclam;

import org.bukkit.Location;
import java.util.HashSet;
import java.util.Set;

public class Clam {
    //position
    int x,y,z;
    //world name
    String world;
    //radius
    int currentSize;
    //this flag toggles growth and tendril activity
    boolean isActive;
    //energy level, used to determine growth and maybe tendril activity
    float energy;
    //server tick when the clam was created or resumed activity from isActive
    int startTick;

    //Set<Location> lightsBlackList = new HashSet<>();
    //short busyFlagPlaceEvent;
    //short busyFlagMainCycle;
    //int type; // 0 is stub, 1 is teen, 2 is broadcast, 3 is arming, 4 is Complex, -1 is Lightning rod
}