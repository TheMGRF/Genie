package net.minecraft.server;

import java.util.Comparator;

public class NextTickListEntry<T> {

    private static final java.util.concurrent.atomic.AtomicLong COUNTER = new java.util.concurrent.atomic.AtomicLong(); // Paper - async chunk loading
    private final T e; public final T getData() { return this.e; } // Tuinity - OBFHELPER
    public final BlockPosition a; public final BlockPosition getPosition() { return this.a; } // Tuinity - OBFHELPER
    public final long b; public final long getTargetTick() { return this.b; } // Tuinity - OBFHELPER
    public final TickListPriority c; public final TickListPriority getPriority() { return this.c; } // Tuinity - OBFHELPER
    private final long f; public final long getId() { return this.f; } // Tuinity - OBFHELPER
    private final int hash; // Tuinity
    public int tickState; // Tuinity

    public NextTickListEntry(BlockPosition blockposition, T t0) {
        this(blockposition, t0, 0L, TickListPriority.NORMAL);
    }

    public NextTickListEntry(BlockPosition blockposition, T t0, long i, TickListPriority ticklistpriority) {
        this.f = (long) (NextTickListEntry.COUNTER.getAndIncrement()); // Paper - async chunk loading
        this.a = blockposition.immutableCopy();
        this.e = t0;
        this.b = i;
        this.c = ticklistpriority;
        this.hash = this.computeHash(); // Tuinity
    }

    public boolean equals(Object object) {
        if (!(object instanceof NextTickListEntry)) {
            return false;
        } else {
            NextTickListEntry<?> nextticklistentry = (NextTickListEntry) object;

            return this.a.equals(nextticklistentry.a) && this.e == nextticklistentry.e;
        }
    }

    // Tuinity start - optimize hashcode
    @Override
    public int hashCode() {
        return this.hash;
    }
    public final int computeHash() {
        // Tuinity end - optimize hashcode
        return this.a.hashCode();
    }

    // Tuinity start - let's not use more functional code for no reason.
    public static <T> Comparator<Object> comparator() { return NextTickListEntry.a(); } // Tuinity - OBFHELPER
    public static <T> Comparator<Object> a() {
        return (Comparator)(Comparator<NextTickListEntry>)(NextTickListEntry nextticklistentry, NextTickListEntry nextticklistentry1) -> {
            int i = Long.compare(nextticklistentry.getTargetTick(), nextticklistentry1.getTargetTick());

            if (i != 0) {
                return i;
            } else {
                i = nextticklistentry.getPriority().compareTo(nextticklistentry1.getPriority());
                return i != 0 ? i : Long.compare(nextticklistentry.getId(), nextticklistentry1.getId());
            }
        };
    }
    // Tuinity end - let's not use more functional code for no reason.

    public String toString() {
        return this.e + ": " + this.a + ", " + this.b + ", " + this.c + ", " + this.f;
    }

    public T b() {
        return this.e;
    }
}
