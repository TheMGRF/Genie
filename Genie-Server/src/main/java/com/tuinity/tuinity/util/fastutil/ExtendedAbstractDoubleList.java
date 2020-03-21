package com.tuinity.tuinity.util.fastutil;

import it.unimi.dsi.fastutil.doubles.AbstractDoubleList;
import it.unimi.dsi.fastutil.doubles.DoubleList;

import java.util.List;

public abstract class ExtendedAbstractDoubleList extends AbstractDoubleList {

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