package com.tuinity.tuinity.util.fastutil;

import it.unimi.dsi.fastutil.objects.ObjectAVLTreeSet;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;

import java.lang.reflect.Field;
import java.util.function.Predicate;

public class ExtendedObjectAVLTreeSet<K> extends ObjectAVLTreeSet<K> {

    private static final Field PREV_FIELD;
    private static final Field NEXT_FIELD;
    private static final Field CURR_FIELD;
    private static final Field INDEX_FIELD;

    private static final Integer ZERO = Integer.valueOf(0);

    static {
        try {
            final Class clazz = Class.forName(ObjectAVLTreeSet.class.getCanonicalName() + "$SetIterator");
            PREV_FIELD = clazz.getDeclaredField("prev");
            PREV_FIELD.setAccessible(true);

            NEXT_FIELD = clazz.getDeclaredField("next");
            NEXT_FIELD.setAccessible(true);

            CURR_FIELD = clazz.getDeclaredField("curr");
            CURR_FIELD.setAccessible(true);

            INDEX_FIELD = clazz.getDeclaredField("index");
            INDEX_FIELD.setAccessible(true);
        } catch (final Throwable thr) {
            throw new RuntimeException(thr);
        }
    }

    private ObjectBidirectionalIterator<K> cachedIterator = this.iterator();

    {
        this.nullIterator(this.cachedIterator);
    }

    @Override
    public boolean removeIf(Predicate<? super K> filter) {
        if (this.isEmpty()) {
            return false;
        }

        if (this.cachedIterator == null) {
            return super.removeIf(filter); // recursive...?
        }

        final ObjectBidirectionalIterator<K> iterator = this.cachedIterator;
        this.cachedIterator = null;
        this.startIterator(iterator);

        boolean ret = false;

        while (iterator.hasNext()) {
            if (filter.test(iterator.next())) {
                ret = true;
                iterator.remove();
            }
        }

        this.nullIterator(iterator);
        this.cachedIterator = iterator;
        return ret;
    }

    private void startIterator(final ObjectBidirectionalIterator<K> iterator) {
        // assume iterator is null'd
        try {
            NEXT_FIELD.set(iterator, this.firstEntry);
        } catch (final IllegalAccessException ex) {
            throw new RuntimeException(ex); // not going to occur
        }
    }

    private void nullIterator(final ObjectBidirectionalIterator<K> iterator) {
        try {
            PREV_FIELD.set(iterator, null);
            NEXT_FIELD.set(iterator, null);
            CURR_FIELD.set(iterator, null);
            INDEX_FIELD.set(iterator, ZERO);
        } catch (final IllegalAccessException ex) {
            throw new RuntimeException(ex); // not going to occur
        }
    }
}
