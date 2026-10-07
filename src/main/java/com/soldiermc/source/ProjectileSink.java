package com.soldiermc.source;

public interface ProjectileSink {

    int SINGLE = 0;
    int EMPTY = 1;
    int RELOAD = 2;

    void fireRocket(float curtime, MoveData mv, boolean ducked, boolean critical);

    void weaponSound(int sound);
}
