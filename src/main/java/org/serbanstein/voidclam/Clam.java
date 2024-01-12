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

    //location list that clears whenever a clam grows or repairs itself. It's populated with the light source blocks that it could not reach
    Set<Location> lightsBlackList = new HashSet<>();

    //flag that is true while the voidclam is responding to a block place event
    boolean busyFlagPlaceEvent;

    //flag that is true while the voidclam is active as a result of its regular lightSeek cycle
    boolean busyFlagMainCycle;

}