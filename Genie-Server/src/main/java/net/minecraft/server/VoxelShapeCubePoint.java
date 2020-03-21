package net.minecraft.server;

import it.unimi.dsi.fastutil.doubles.AbstractDoubleList;

public class VoxelShapeCubePoint extends com.tuinity.tuinity.util.fastutil.ExtendedAbstractDoubleList { // Tuinity - remove iterator allocation

    private final int a;

    VoxelShapeCubePoint(int i) {
        this.a = i;
    }

    public double getDouble(int i) {
        return (double) i / (double) this.a;
    }

    public int size() {
        return this.a + 1;
    }
}
