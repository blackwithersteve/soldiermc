package com.soldiermc.source;

/** IN_* bits with Source's own values, in_buttons.h. */
public final class Buttons {

    public static final int IN_ATTACK = 1 << 0;
    public static final int IN_JUMP = 1 << 1;
    public static final int IN_DUCK = 1 << 2;
    public static final int IN_ATTACK2 = 1 << 11;
    public static final int IN_RELOAD = 1 << 13;

    private Buttons() {
    }
}
