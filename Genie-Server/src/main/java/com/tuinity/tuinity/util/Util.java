package com.tuinity.tuinity.util;

import it.unimi.dsi.fastutil.objects.ObjectRBTreeSet;
import net.minecraft.server.BlockPosition;
import net.minecraft.server.ChunkCoordIntPair;
import net.minecraft.server.Entity;
import org.bukkit.Bukkit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.SortedSet;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class Util {

    public static final long INVALID_CHUNK_KEY = getCoordinateKey(Integer.MAX_VALUE, Integer.MAX_VALUE);

    public static void ensureTickThread(final String reason) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(reason);
        }
    }

    public static long getCoordinateKey(final BlockPosition blockPos) {
        return getCoordinateKey(blockPos.getX() >> 4, blockPos.getZ() >> 4);
    }

    public static long getCoordinateKey(final Entity entity) {
        return getCoordinateKey(getChunkCoordinate(entity.locX()), getChunkCoordinate(entity.locZ()));
    }

    public static int fastFloor(double x) {
        int truncated = (int)x;
        return x < (double)truncated ? truncated - 1 : truncated;
    }

    public static int fastFloor(float x) {
        int truncated = (int)x;
        return x < (double)truncated ? truncated - 1 : truncated;
    }

    public static long getCoordinateKey(final ChunkCoordIntPair pair) {
        return getCoordinateKey(pair.x, pair.z);
    }

    public static long getCoordinateKey(final int x, final int z) {
        return ((long)z << 32) | (x & 0xFFFFFFFFL);
    }

    public static int getCoordinateX(final long key) {
        return (int)key;
    }

    public static int getCoordinateZ(final long key) {
        return (int)(key >>> 32);
    }

    public static int getChunkCoordinate(final double coordinate) {
        return Util.fastFloor(coordinate) >> 4;
    }

    public static int getBlockCoordinate(final double coordinate) {
        return Util.fastFloor(coordinate);
    }

    public static long getBlockKey(final int x, final int y, final int z) {
        return ((long)x & 0x7FFFFFF) | (((long)z & 0x7FFFFFF) << 27) | ((long)y << 54);
    }

    public static long getBlockKey(final BlockPosition pos) {
        return getBlockKey(pos.getX(), pos.getY(), pos.getZ());
    }

    public static long getBlockKey(final Entity entity) {
        return getBlockKey(getBlockCoordinate(entity.locX()), getBlockCoordinate(entity.locY()), getBlockCoordinate(entity.locZ()));
    }

    // assumes the sets have the same comparator, and if this comparator is null then assume T is Comparable
    public static <T> void mergeSortedSets(final Consumer<T> consumer, final Comparator<? super T> comparator, final SortedSet<T>...sets) {
        final ObjectRBTreeSet<T> all = new ObjectRBTreeSet<>(comparator);
        // note: this is done in log(n!) ~ nlogn time. It could be improved if it were to mimick what mergesort does.
        for (SortedSet<T> set : sets) {
            if (set != null) {
                all.addAll(set);
            }
        }
        all.forEach(consumer);
    }

    // Taken from
    // https://lemire.me/blog/2016/06/27/a-fast-alternative-to-the-modulo-reduction/
    // https://github.com/lemire/Code-used-on-Daniel-Lemire-s-blog/blob/master/2016/06/25/fastrange.c
    // Original license is public domain
    public static int fastRandomBounded(final long randomInteger, final long limit) {
        // randomInteger must be [0, pow(2, 32))
        // limit must be [0, pow(2, 32))
        return (int)((randomInteger * limit) >>> 32);
    }
}
