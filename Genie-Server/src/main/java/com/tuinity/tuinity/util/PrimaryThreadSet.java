package com.tuinity.tuinity.util;

import org.spigotmc.AsyncCatcher;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class PrimaryThreadSet<E> implements Set<E> {

    protected final Set<E> wrapped;

    public PrimaryThreadSet(final Set<E> wrap) {
        if (wrap == null) {
            throw new NullPointerException("Wrapped list may not be null");
        }

        this.wrapped = wrap;
    }

    protected void check() {
        AsyncCatcher.catchOp("collection access");
    }

    @Override
    public boolean add(final E element) {
        this.check();
        return this.wrapped.add(element);
    }

    @Override
    public boolean addAll(final Collection<? extends E> collection) {
        this.check();
        return this.wrapped.addAll(collection);
    }

    @Override
    public boolean remove(final Object element) {
        this.check();
        return this.wrapped.remove(element);
    }

    @Override
    public boolean removeIf(final Predicate<? super E> filter) {
        this.check();
        return this.wrapped.removeIf(filter);
    }

    @Override
    public boolean removeAll(final Collection<?> collection) {
        this.check();
        return this.wrapped.removeAll(collection);
    }

    @Override
    public boolean retainAll(final Collection<?> collection) {
        this.check();
        return this.wrapped.retainAll(collection);
    }

    @Override
    public void clear() {
        this.check();
        this.wrapped.clear();
    }

    @Override
    public int size() {
        this.check();
        return this.wrapped.size();
    }

    @Override
    public boolean isEmpty() {
        this.check();
        return this.wrapped.isEmpty();
    }

    @Override
    public boolean containsAll(final Collection<?> collection) {
        this.check();
        return this.wrapped.containsAll(collection);
    }

    @Override
    public boolean contains(final Object object) {
        this.check();
        return this.wrapped.contains(object);
    }

    @Override
    public void forEach(final Consumer<? super E> action) {
        this.check();
        this.wrapped.forEach(action);
    }

    @Override
    public <T> T[] toArray(final T[] array) {
        this.check();
        return this.wrapped.toArray(array);
    }

    @Override
    public <T> T[] toArray(final IntFunction<T[]> generator) {
        this.check();
        return this.wrapped.toArray(generator);
    }

    @Override
    public Object[] toArray() {
        this.check();
        return this.wrapped.toArray();
    }

    @Override
    public Iterator<E> iterator() {
        this.check();
        return this.wrapped.iterator();
    }

    @Override
    public Stream<E> stream() {
        this.check();
        return this.wrapped.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        this.check();
        return this.wrapped.parallelStream();
    }

    @Override
    public Spliterator<E> spliterator() {
        this.check();
        return this.wrapped.spliterator();
    }

    @Override
    public String toString() {
        this.check();
        return this.wrapped.toString();
    }

    @Override
    public int hashCode() {
        this.check();
        return this.wrapped.hashCode();
    }

    @Override
    public boolean equals(final Object object) {
        this.check();
        return this.wrapped.equals(object);
    }

    public static class SortedPrimaryThreadSet<E> extends PrimaryThreadSet<E> implements SortedSet<E> {

        public SortedPrimaryThreadSet(final SortedSet<E> set) {
            super(set);
        }

        @Override
        public Comparator<? super E> comparator() {
            return ((SortedSet<E>)this.wrapped).comparator();
        }

        @Override
        public E first() {
            this.check();
            return ((SortedSet<E>)this.wrapped).first();
        }

        @Override
        public E last() {
            this.check();
            return ((SortedSet<E>)this.wrapped).last();
        }

        @Override
        public SortedSet<E> headSet(final E toElement) {
            this.check();
            return ((SortedSet<E>)this.wrapped).headSet(toElement);
        }

        @Override
        public SortedSet<E> subSet(final E fromElement, final E toElement) {
            this.check();
            return ((SortedSet<E>)this.wrapped).subSet(fromElement, toElement);
        }

        @Override
        public SortedSet<E> tailSet(final E fromElement) {
            this.check();
            return ((SortedSet<E>)this.wrapped).tailSet(fromElement);
        }
    }

    public static class NavigablePrimaryThreadSet<E> extends SortedPrimaryThreadSet<E> implements NavigableSet<E> {

        public NavigablePrimaryThreadSet(final NavigableSet<E> set) {
            super(set);
        }

        @Override
        public E ceiling(final E element) {
            this.check();
            return ((NavigableSet<E>)this.wrapped).ceiling(element);
        }

        @Override
        public E floor(final E element) {
            this.check();
            return ((NavigableSet<E>)this.wrapped).floor(element);
        }

        @Override
        public E higher(final E element) {
            this.check();
            return ((NavigableSet<E>)this.wrapped).higher(element);
        }

        @Override
        public E lower(final E element) {
            this.check();
            return ((NavigableSet<E>)this.wrapped).lower(element);
        }

        @Override
        public E pollFirst() {
            this.check();
            return ((NavigableSet<E>)this.wrapped).pollFirst();
        }

        @Override
        public E pollLast() {
            this.check();
            return ((NavigableSet<E>)this.wrapped).pollLast();
        }

        @Override
        public Iterator<E> descendingIterator() {
            this.check();
            return ((NavigableSet<E>)this.wrapped).descendingIterator();
        }

        @Override
        public final NavigableSet<E> descendingSet() {
            this.check();
            return new NavigablePrimaryThreadSet<>(((NavigableSet<E>)this.wrapped).descendingSet());
        }

        @Override
        public NavigableSet<E> headSet(final E toElement, final boolean inclusive) {
            this.check();
            return new NavigablePrimaryThreadSet<>(((NavigableSet<E>)this.wrapped).headSet(toElement, inclusive));
        }

        @Override
        public NavigableSet<E> subSet(final E fromElement, final boolean fromInclusive, final E toElement, final boolean toInclusive) {
            this.check();
            return new NavigablePrimaryThreadSet<>(((NavigableSet<E>)this.wrapped).subSet(fromElement, fromInclusive, toElement, toInclusive));
        }

        @Override
        public NavigableSet<E> tailSet(final E fromElement, final boolean inclusive) {
            this.check();
            return new NavigablePrimaryThreadSet<>(((NavigableSet<E>)this.wrapped).tailSet(fromElement, inclusive));
        }
    }

    public static <E> PrimaryThreadSet<E> of(final Set<E> set) {
        if (set instanceof NavigableSet) {
            return new NavigablePrimaryThreadSet<>((NavigableSet<E>)set);
        } else if (set instanceof SortedSet) {
            return new SortedPrimaryThreadSet<>((SortedSet<E>)set);
        }
        return new PrimaryThreadSet<>(set);
    }
}
