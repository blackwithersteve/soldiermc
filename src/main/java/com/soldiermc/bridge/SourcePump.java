package com.soldiermc.bridge;

/**
 * The integer 5 ms accumulator that schedules Source substeps inside Minecraft ticks, where a float
 * budget would accumulate rounding error. {@code gcd(50 ms, 15 ms) = 5 ms}: a tick contributes 10
 * quanta and a substep consumes 3, so the pattern is 4, 3, 3 — 10 substeps per 3 ticks, 66.67 Hz.
 */
public final class SourcePump {

    /** Quanta contributed by one Minecraft tick (50 ms / 5 ms). */
    private static final int QUANTA_PER_MC_TICK = 10;
    /** Quanta consumed by one Source substep (15 ms / 5 ms). */
    private static final int QUANTA_PER_SUBSTEP = 3;

    /**
     * Maximum substeps run for a single Minecraft tick. Diverges from TF2, which still simulates
     * every queued command after a stall; the remainder is dropped here so one packet's
     * displacement cannot trip the server's speed check.
     */
    private static final int MAX_SUBSTEPS_PER_TICK = 8;

    private int quanta;
    private long simTimeNanos;

    /** Substeps run since {@link #reset}, and the weapon clock's only source of truth. */
    private long substepIndex;

    /** Substeps actually run on the most recent tick — 4, 3, 3, 4, 3, 3 … in steady state. */
    private int lastSubstepCount;
    /** Substeps dropped to the catch-up cap. Nonzero means the sim lost time. */
    private int lastDropped;

    /** Advance by one Minecraft tick and return how many Source substeps are due. */
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

    /** Timestamp of substep {@code i} of the current tick, for querying the input ring. */
    public long substepTimeNanos(int i) {
        return simTimeNanos + (long) i * 15_000_000L;
    }

    /** Called after the tick's substeps have run. */
    public void commitTick(int substepsRun) {
        simTimeNanos += (long) substepsRun * 15_000_000L;
        substepIndex += substepsRun;
    }

    public void reset(long nowNanos) {
        quanta = 0;
        simTimeNanos = nowNanos;
        substepIndex = 0;
        lastSubstepCount = 0;
        lastDropped = 0;
    }

    /**
     * Source's {@code gpGlobals->curtime} for substep {@code i} of the tick being run:
     * {@code tickcount * interval_per_tick}, counted from the simulation's own start. Not derived
     * from {@link #simTimeNanos}, which is seeded from {@code System.nanoTime()} and would put
     * curtime near 10^6 seconds, where a float ULP is 0.0625 s — four times the substep.
     */
    public float curtime(int i) {
        return (float) ((substepIndex + i) * 0.015);
    }

    /** The substep index at the start of the current tick. */
    public long substepIndex() {
        return substepIndex;
    }

    public int lastSubstepCount() {
        return lastSubstepCount;
    }

    public int lastDropped() {
        return lastDropped;
    }

    public long simTimeNanos() {
        return simTimeNanos;
    }
}
