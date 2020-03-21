package com.tuinity.tuinity.util;

import ca.spottedleaf.concurrentutil.misc.SynchronizationPoint;

public final class TickSynchronizationPoint {

    private final SynchronizationPoint lock;

    public TickSynchronizationPoint(final TickThread[] threads) {
        this.lock = new SynchronizationPoint(threads);
    }

    private static int getIdForCurrentThread() {
        return ((TickThread)Thread.currentThread()).id;
    }

    public void start() {
        this.lock.start(getIdForCurrentThread());
    }

    public void end() {
        this.lock.end(getIdForCurrentThread());
    }

    public void enter() {
        this.lock.enter(getIdForCurrentThread());
    }

    public void weakEnter() {
        this.lock.weakEnter(getIdForCurrentThread());
    }

    public void enterAlone() {
        this.lock.enterAlone(getIdForCurrentThread());
    }

    public void endAloneExecution() {
        this.lock.endAloneExecution(getIdForCurrentThread());
    }
}