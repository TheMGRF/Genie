package com.tuinity.tuinity.util;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.server.Chunk;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

// list with O(1) remove & contains
public final class ChunkList implements Iterable<Chunk> {

    protected final Long2IntOpenHashMap chunkToIndex = new Long2IntOpenHashMap();
    {
        this.chunkToIndex.defaultReturnValue(Integer.MIN_VALUE);
    }

    protected Chunk[] chunks = new Chunk[16];
    protected int count;

    public int size() {
        return this.count;
    }

    public boolean contains(final Chunk chunk) {
        return this.chunkToIndex.containsKey(Util.getCoordinateKey(chunk.getPos()));
    }

    public boolean remove(final Chunk chunk) {
        final int index = this.chunkToIndex.remove(Util.getCoordinateKey(chunk.getPos()));
        if (index == Integer.MIN_VALUE) {
            return false;
        }

        // move the entity at the end to this index
        final int endIndex = --this.count;
        final Chunk end = this.chunks[endIndex];
        if (index != endIndex) {
            // not empty after this call
            this.chunkToIndex.put(Util.getCoordinateKey(end.getPos()), index); // update index
        }
        this.chunks[index] = end;
        this.chunks[endIndex] = null;

        return true;
    }

    public boolean add(final Chunk chunk) {
        final int count = this.count;
        final int currIndex = this.chunkToIndex.putIfAbsent(Util.getCoordinateKey(chunk.getPos()), count);

        if (currIndex != Integer.MIN_VALUE) {
            return false; // already in this list
        }

        Chunk[] list = this.chunks;

        if (list.length == count) {
            // resize required
            list = this.chunks = Arrays.copyOf(list, count * 2); // overflow results in negative
        }

        list[count] = chunk;
        this.count = count + 1;

        return true;
    }

    public Chunk getChecked(final int index) {
        if (index < 0 || index >= this.count) {
            throw new IndexOutOfBoundsException("Index: " + index + " is out of bounds, size: " + this.count);
        }
        return this.chunks[index];
    }

    public Chunk getUnchecked(final int index) {
        return this.chunks[index];
    }

    public Chunk[] getRawData() {
        return this.chunks;
    }

    @Override
    public Iterator<Chunk> iterator() {
        return new Iterator<>() {

            Chunk lastRet;
            int current;

            @Override
            public boolean hasNext() {
                return this.current < ChunkList.this.count;
            }

            @Override
            public Chunk next() {
                if (this.current >= ChunkList.this.count) {
                    throw new NoSuchElementException();
                }
                return this.lastRet = ChunkList.this.chunks[this.current++];
            }

            @Override
            public void remove() {
                final Chunk lastRet = this.lastRet;

                if (lastRet == null) {
                    throw new IllegalStateException();
                }
                this.lastRet = null;

                ChunkList.this.remove(lastRet);
                --this.current;
            }
        };
    }

}
