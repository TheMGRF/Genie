package com.tuinity.tuinity.util;

import org.spigotmc.AsyncCatcher;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public class PrimaryThreadList<E> implements List<E> {

    protected final List<E> wrapped;

    public PrimaryThreadList(final List<E> wrap) {
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
    public void add(final int index, final E element) {
        this.check();
        this.wrapped.add(index, element);
    }

    @Override
    public boolean addAll(final Collection<? extends E> collection) {
        this.check();
        return this.wrapped.addAll(collection);
    }

    @Override
    public boolean addAll(final int index, final Collection<? extends E> collection) {
        this.check();
        return this.wrapped.addAll(index, collection);
    }

    @Override
    public E set(final int index, final E element) {
        this.check();
        return this.wrapped.set(index, element);
    }

    @Override
    public boolean remove(final Object element) {
        this.check();
        return this.wrapped.remove(element);
    }

    @Override
    public E remove(final int index) {
        this.check();
        return this.wrapped.remove(index);
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
    public void replaceAll(final UnaryOperator<E> operator) {
        this.check();
        this.wrapped.replaceAll(operator);
    }

    @Override
    public void sort(final Comparator<? super E> comparator) {
        this.check();
        this.wrapped.sort(comparator);
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
    public E get(final int index) {
        this.check();
        return this.wrapped.get(index);
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
    public int indexOf(final Object object) {
        this.check();
        return this.wrapped.indexOf(object);
    }

    @Override
    public int lastIndexOf(final Object object) {
        this.check();
        return this.wrapped.lastIndexOf(object);
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
    public ListIterator<E> listIterator() {
        this.check();
        return this.wrapped.listIterator();
    }

    @Override
    public ListIterator<E> listIterator(final int index) {
        this.check();
        return this.wrapped.listIterator(index);
    }

    @Override
    public List<E> subList(final int fromIndex, final int toIndex) {
        this.check();
        return new PrimaryThreadList<>(this.wrapped.subList(fromIndex, toIndex));
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

    public static class RandomAccessPrimaryThreadList<E> extends PrimaryThreadList<E> implements RandomAccess {
        public RandomAccessPrimaryThreadList(final List<E> wrap) {
            super(wrap);
        }
    }

    public static <E> PrimaryThreadList<E> of(final List<E> list) {
        return list instanceof RandomAccess ? new RandomAccessPrimaryThreadList<>(list) : new PrimaryThreadList<>(list);
    }
}