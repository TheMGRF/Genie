package com.tuinity.tuinity.util.fastutil;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.Arrays;
import java.util.List;

public class ExtendedDoubleArrayList extends DoubleArrayList {

    public ExtendedDoubleArrayList() {
        super();
    }

    public ExtendedDoubleArrayList(final int capacity) {
        super(capacity);
    }

    public ExtendedDoubleArrayList(final double[] array) {
        this(array, array.length, true);
    }

    public ExtendedDoubleArrayList(final double[] array, final int size, final boolean copy) {
        super(copy ? array.clone() : array, false);
        this.size = size;
    }

    public static ExtendedDoubleArrayList getList(final double[] list, final int requiredLength) {
        if (list.length == requiredLength) {
            return new ExtendedDoubleArrayList(list, requiredLength, false);
        } else {
            return new ExtendedDoubleArrayList(Arrays.copyOf(list, requiredLength), requiredLength, false);
        }
    }

    @Override
    public boolean equals(final Object other) {
        if (other == this) {
            return true;
        }
        if (!(other instanceof DoubleList)) {
            if (other instanceof List) {
                return super.equals(other);
            }
            return false;
        }

        final DoubleList otherList = (DoubleList)other;

        final int otherSize = otherList.size();
        final int thisSize = this.size();

        if (otherSize != thisSize) {
            return false;
        }

        for (int i = 0; i < thisSize; ++i) {
            if (this.getDouble(i) != otherList.getDouble(i)) {
                return false;
            }
        }

        return true;
    }
}
