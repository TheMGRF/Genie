package net.minecraft.server;

import java.util.BitSet;

public class RegionFileBitSet {

    private final BitSet a = new BitSet(); private final BitSet getBitset() { return this.a; } // Tuinity - OBFHELPER

    public RegionFileBitSet() {}

    public final void allocate(int from, int length) { this.a(from, length); } // Tuinity - OBFHELPER
    public void a(int i, int j) {
        this.a.set(i, i + j);
    }

    public final void free(int from, int length) { this.b(from, length); } // Tuinity - OBFHELPER
    public void b(int i, int j) {
        this.a.clear(i, i + j);
    }

    // Tuinity start
    public final void copyFrom(RegionFileBitSet other) {
        BitSet thisBitset = this.getBitset();
        BitSet otherBitset = other.getBitset();

        for (int i = 0; i < Math.max(thisBitset.size(), otherBitset.size()); ++i) {
            thisBitset.set(i, otherBitset.get(i));
        }
    }

    public final boolean tryAllocate(int from, int length) {
        BitSet bitset = this.getBitset();
        int firstSet = bitset.nextSetBit(from);
        if (firstSet > 0 && firstSet < (from + length)) {
            return false;
        }
        bitset.set(from, from + length);
        return true;
    }
    // Tuinity end

    public final int allocateNewSpace(final int requiredLength) { return this.a(requiredLength); } // Tuinity - OBFHELPER
    public int a(int i) {
        int j = 0;

        while (true) {
            int k = this.a.nextClearBit(j);
            int l = this.a.nextSetBit(k);

            if (l == -1 || l - k >= i) {
                this.a(k, i);
                return k;
            }

            j = l;
        }
    }
}
