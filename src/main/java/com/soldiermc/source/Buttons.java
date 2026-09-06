package com.soldiermc.source;

/**
 * The {@code IN_*} button bits the Soldier's movement reads. Values are Source's own, from
 * {@code public/in_buttons.h}, so a condition like {@code (m_nOldButtons & IN_JUMP)} transcribes
 * as written.
 */
public final class Buttons {

    /** in_buttons.h:11 */
    public static final int IN_ATTACK = 1 << 0;
    /** in_buttons.h:12 */
    public static final int IN_JUMP = 1 << 1;
    /** in_buttons.h:13 */
    public static final int IN_DUCK = 1 << 2;
    /** in_buttons.h:14 */
    public static final int IN_FORWARD = 1 << 3;
    /** in_buttons.h:15 */
    public static final int IN_BACK = 1 << 4;
    /** in_buttons.h:22 */
    public static final int IN_MOVELEFT = 1 << 9;
    /** in_buttons.h:23 */
    public static final int IN_MOVERIGHT = 1 << 10;
    /** in_buttons.h:25 */
    public static final int IN_ATTACK2 = 1 << 11;
    /** in_buttons.h:27 */
    public static final int IN_RELOAD = 1 << 13;

    private Buttons() {
    }
}
