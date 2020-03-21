package net.minecraft.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import javax.annotation.Nullable;

public abstract class LightEngineStorageArray<M extends LightEngineStorageArray<M>> {

    private final long[] b = new long[2];
    private final NibbleArray[] c = new NibbleArray[2];
    private boolean d;
    protected final com.tuinity.tuinity.chunk.QueuedChangesMapLong2Object<NibbleArray> data; // Tuinity - avoid copying light data
    protected final boolean isVisible; // Tuinity - avoid copying light data

    // Tuinity start - avoid copying light data
    protected LightEngineStorageArray(com.tuinity.tuinity.chunk.QueuedChangesMapLong2Object<NibbleArray> data, boolean isVisible) {
        if (isVisible) {
            data.performUpdatesLockMap();
        }
        this.data = data;
        this.isVisible = isVisible;
        // Tuinity end - avoid copying light data
        this.c();
        this.d = true;
    }

    public abstract M b();

    public void a(long i) {
        if (this.isVisible) { throw new IllegalStateException("writing to visible data"); } // Tuinity - avoid copying light data
        this.data.queueUpdate(i, ((NibbleArray) this.data.getUpdating(i)).b()); // Tuinity - avoid copying light data
        this.c();
    }

    public boolean b(long i) {
        return this.isVisible ? this.data.getVisibleAsync(i) != null : this.data.getUpdating(i) != null; // Tuinity - avoid copying light data
    }

    @Nullable
    public NibbleArray c(long i) {
        if (this.d) {
            for (int j = 0; j < 2; ++j) {
                if (i == this.b[j]) {
                    return this.c[j];
                }
            }
        }

        NibbleArray nibblearray = (NibbleArray) (this.isVisible ? this.data.getVisibleAsync(i) : this.data.getUpdating(i)); // Tuinity - avoid copying light data

        if (nibblearray == null) {
            return null;
        } else {
            if (this.d) {
                for (int k = 1; k > 0; --k) {
                    this.b[k] = this.b[k - 1];
                    this.c[k] = this.c[k - 1];
                }

                this.b[0] = i;
                this.c[0] = nibblearray;
            }

            return nibblearray;
        }
    }

    @Nullable
    public NibbleArray d(long i) {
        if (this.isVisible) { throw new IllegalStateException("writing to visible data"); } // Tuinity - avoid copying light data
        return (NibbleArray) this.data.queueRemove(i); // Tuinity - avoid copying light data
    }

    public void a(long i, NibbleArray nibblearray) {
        if (this.isVisible) { throw new IllegalStateException("writing to visible data"); } // Tuinity - avoid copying light data
        this.data.queueUpdate(i, nibblearray); // Tuinity - avoid copying light data
    }

    public void c() {
        for (int i = 0; i < 2; ++i) {
            this.b[i] = Long.MAX_VALUE;
            this.c[i] = null;
        }

    }

    public void d() {
        this.d = false;
    }
}
