package com.soldiermc.bridge;

/** Integer 5 ms quanta, so substeps run 4, 3, 3 per tick without float drift. */
public final class SourcePump {

    private static final int QUANTA_PER_MC_TICK = 10;
    private static final int QUANTA_PER_SUBSTEP = 3;

    /** Unlike TF2, excess substeps are dropped so a packet cannot trip the speed check. */
    private static final int MAX_SUBSTEPS_PER_TICK = 8;

    private int quanta;
    private long substepIndex;
    private int lastSubstepCount;
    private int lastDropped;

    public int accrueTick() {
        quanta += QUANTA_PER_MC_TICK;

        int due = quanta / QUANTA_PER_SUBSTEP;
        lastDropped = 0;

        if (due > MAX_SUBSTEPS_PER_TICK) {
            lastDropped = due - MAX_SUBSTEPS_PER_TICK;
            quanta -= lastDropped * QUANTA_PER_SUBSTEP;
            due = MAX_SUBSTEPS_PER_TICK;
        }

        quanta -= due * QUANTA_PER_SUBSTEP;
        lastSubstepCount = due;
        return due;
    }

    public void commitTick(int substepsRun) {
        substepIndex += substepsRun;
    }

    public void reset() {
        quanta = 0;
        substepIndex = 0;
        lastSubstepCount = 0;
        lastDropped = 0;
    }

    /** Counted from substep 0: a float of nanoTime seconds would be far too coarse. */
    public float curtime(int i) {
        return (float) ((substepIndex + i) * 0.015);
    }

    public int lastSubstepCount() {
        return lastSubstepCount;
    }

    public int lastDropped() {
        return lastDropped;
    }
}
