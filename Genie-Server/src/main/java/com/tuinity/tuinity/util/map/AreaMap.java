package com.tuinity.tuinity.util.map;

import com.tuinity.tuinity.util.Util;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.minecraft.server.ChunkCoordIntPair;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.Iterator;

/** @author Spottedleaf */
public abstract class AreaMap<E> {

    /* Tested via https://gist.github.com/Spottedleaf/520419c6f41ef348fe9926ce674b7217 */

    private final Object2LongOpenHashMap<E> objectToLastCoordinate = new Object2LongOpenHashMap<>();
    private final Object2IntOpenHashMap<E> objectToViewDistance = new Object2IntOpenHashMap<>();

    {
        this.objectToViewDistance.defaultReturnValue(-1);
        this.objectToLastCoordinate.defaultReturnValue(Long.MIN_VALUE);
    }

    // we use linked for better iteration.
    // map of: coordinate to set of objects in coordinate
    private final Long2ObjectOpenHashMap<PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E>> areaMap = new Long2ObjectOpenHashMap<>(1024, 0.3f);
    private final PooledLinkedHashSets<E> pooledHashSets;

    private final ChangeCallback<E> addCallback;
    private final ChangeCallback<E> removeCallback;

    public AreaMap() {
        this(new PooledLinkedHashSets<>());
    }

    // let users define a "global" or "shared" pooled sets if they wish
    public AreaMap(final PooledLinkedHashSets<E> pooledHashSets) {
        this(pooledHashSets, null, null);
    }

    public AreaMap(final PooledLinkedHashSets<E> pooledHashSets, final ChangeCallback<E> addCallback, final ChangeCallback<E> removeCallback) {
        this.pooledHashSets = pooledHashSets;
        this.addCallback = addCallback;
        this.removeCallback = removeCallback;
    }

    @Nullable
    public PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> getObjectsInRange(final long key) {
        return this.areaMap.get(key);
    }

    @Nullable
    public PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> getObjectsInRange(final ChunkCoordIntPair chunkPos) {
        return this.getObjectsInRange(chunkPos.x, chunkPos.z);
    }

    @Nullable
    public PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> getObjectsInRange(final int chunkX, final int chunkZ) {
        return this.getObjectsInRange(Util.getCoordinateKey(chunkX, chunkZ));
    }

    // Long.MIN_VALUE indicates the object is not mapped
    public long getLastCoordinate(final E object) {
        return this.objectToLastCoordinate.getOrDefault(object, Long.MIN_VALUE);
    }

    // -1 indicates the object is not mapped
    public int getLastViewDistance(final E object) {
        return this.objectToViewDistance.getOrDefault(object, -1);
    }

    // returns the total number of mapped chunks
    public int size() {
        return this.areaMap.size();
    }

    public void update(final E object, final int chunkX, final int chunkZ, final int viewDistance) {
        final int oldDistance = this.objectToViewDistance.put(object, viewDistance);
        final long newPos = Util.getCoordinateKey(chunkX, chunkZ);
        if (oldDistance == -1) {
            this.objectToLastCoordinate.put(object, newPos);
            this.addObject(object, chunkX, chunkZ, Integer.MIN_VALUE, Integer.MIN_VALUE, viewDistance);
        } else {
            this.updateObject(object, this.objectToLastCoordinate.put(object, newPos), newPos, oldDistance, viewDistance);
        }
        //this.validate(object, viewDistance);
    }

    public boolean remove(final E object) {
        final long position = this.objectToLastCoordinate.removeLong(object);
        final int viewDistance = this.objectToViewDistance.removeInt(object);

        if (viewDistance == -1) {
            return false;
        }

        final int currentX = Util.getCoordinateX(position);
        final int currentZ = Util.getCoordinateZ(position);

        this.removeObject(object, currentX, currentZ, currentX, currentZ, viewDistance);
        //this.validate(object, -1);
        return true;
    }

    protected abstract PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> getEmptySetFor(final E object);

    // expensive op, only for debug
    private void validate(final E object, final int viewDistance) {
        int entiesGot = 0;
        int expectedEntries = (2 * viewDistance + 1);
        expectedEntries *= expectedEntries;
        if (viewDistance < 0) {
            expectedEntries = 0;
        }

        final long currPosition = this.objectToLastCoordinate.getLong(object);

        final int centerX = Util.getCoordinateX(currPosition);
        final int centerZ = Util.getCoordinateZ(currPosition);

        for (Iterator<Long2ObjectLinkedOpenHashMap.Entry<PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E>>> iterator = this.areaMap.long2ObjectEntrySet().fastIterator();
             iterator.hasNext();) {

            final Long2ObjectLinkedOpenHashMap.Entry<PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E>> entry = iterator.next();
            final long key = entry.getLongKey();
            final PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> map = entry.getValue();

            if (map.referenceCount == 0) {
                throw new IllegalStateException("Invalid map");
            }

            if (map.contains(object)) {
                ++entiesGot;

                final int chunkX = Util.getCoordinateX(key);
                final int chunkZ = Util.getCoordinateZ(key);

                final int dist = Math.max(Math.abs(chunkX - centerX), Math.abs(chunkZ - centerZ));

                if (dist > viewDistance) {
                    throw new IllegalStateException("Expected view distance " + viewDistance + ", got " + dist);
                }
            }
        }

        if (entiesGot != expectedEntries) {
            throw new IllegalStateException("Expected " + expectedEntries + ", got " + entiesGot);
        }
    }

    protected final void addObjectTo(final E object, final int chunkX, final int chunkZ, final int currChunkX,
                                     final int currChunkZ, final int prevChunkX, final int prevChunkZ) {
        final long key = Util.getCoordinateKey(chunkX, chunkZ);

        PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> empty = this.getEmptySetFor(object);
        PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> current = this.areaMap.putIfAbsent(key, empty);

        if (current != null) {
            PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> next = this.pooledHashSets.findMapWith(current, object);
            if (next == current) {
                throw new IllegalStateException("Expected different map: got " + next.toString());
            }
            this.areaMap.put(key, next);

            current = next;
            // fall through to callback
        } else {
            current = empty;
        }

        if (this.addCallback != null) {
            try {
                this.addCallback.accept(object, chunkX, chunkZ, currChunkX, currChunkZ, prevChunkX, prevChunkZ, current);
            } catch (final Throwable ex) {
                if (ex instanceof ThreadDeath) {
                    throw (ThreadDeath)ex;
                }
                MinecraftServer.LOGGER.error("Add callback for map threw exception ", ex);
            }
        }
    }

    protected final void removeObjectFrom(final E object, final int chunkX, final int chunkZ, final int currChunkX,
                                          final int currChunkZ, final int prevChunkX, final int prevChunkZ) {
        final long key = Util.getCoordinateKey(chunkX, chunkZ);

        PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> current = this.areaMap.get(key);

        if (current == null) {
            throw new IllegalStateException("Current map may not be null for " + object + ", (" + chunkX + "," + chunkZ + ")");
        }

        PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> next = this.pooledHashSets.findMapWithout(current, object);

        if (next == current) {
            throw new IllegalStateException("Current map [" + next.toString() + "] should have contained " + object + ", (" + chunkX + "," + chunkZ + ")");
        }

        if (next != null) {
            this.areaMap.put(key, next);
        } else {
            this.areaMap.remove(key);
        }

        if (this.removeCallback != null) {
            try {
                this.removeCallback.accept(object, chunkX, chunkZ, currChunkX, currChunkZ, prevChunkX, prevChunkZ, next);
            } catch (final Throwable ex) {
                if (ex instanceof ThreadDeath) {
                    throw (ThreadDeath)ex;
                }
                MinecraftServer.LOGGER.error("Remove callback for map threw exception ", ex);
            }
        }
    }

    private void addObject(final E object, final int chunkX, final int chunkZ, final int prevChunkX, final int prevChunkZ, final int viewDistance) {
        final int maxX = chunkX + viewDistance;
        final int maxZ = chunkZ + viewDistance;
        for (int x = chunkX - viewDistance; x <= maxX; ++x) {
            for (int z = chunkZ - viewDistance; z <= maxZ; ++z) {
                this.addObjectTo(object, x, z, chunkX, chunkZ, prevChunkX, prevChunkZ);
            }
        }
    }

    private void removeObject(final E object, final int chunkX, final int chunkZ, final int currentChunkX, final int currentChunkZ, final int viewDistance) {
        final int maxX = chunkX + viewDistance;
        final int maxZ = chunkZ + viewDistance;
        for (int x = chunkX - viewDistance; x <= maxX; ++x) {
            for (int z = chunkZ - viewDistance; z <= maxZ; ++z) {
                this.removeObjectFrom(object, x, z, currentChunkX, currentChunkZ, chunkX, chunkZ);
            }
        }
    }

    /* math sign function except 0 returns 1 */
    protected static int sign(int val) {
        return 1 | (val >> (Integer.SIZE - 1));
    }

    protected final void updateObject(final E object, final long oldPosition, final long newPosition, final int oldViewDistance, final int newViewDistance) {
        final int toX = Util.getCoordinateX(newPosition);
        final int toZ = Util.getCoordinateZ(newPosition);
        final int fromX = Util.getCoordinateX(oldPosition);
        final int fromZ = Util.getCoordinateZ(oldPosition);

        final int dx = toX - fromX;
        final int dz = toZ - fromZ;

        final int totalX = Math.abs(fromX - toX);
        final int totalZ = Math.abs(fromZ - toZ);

        if (Math.max(totalX, totalZ) > (2 * Math.max(newViewDistance, oldViewDistance))) {
            // teleported?
            this.removeObject(object, fromX, fromZ, fromX, fromZ, oldViewDistance);
            this.addObject(object, toX, toZ, fromX, fromZ, newViewDistance);
            return;
        }

        if (oldViewDistance != newViewDistance) {
            // remove loop

            final int oldMaxX = fromX + oldViewDistance;
            final int oldMaxZ = fromZ + oldViewDistance;
            for (int currX = fromX - oldViewDistance; currX <= oldMaxX; ++currX) {
                for (int currZ = fromZ - oldViewDistance; currZ <= oldMaxZ; ++currZ) {

                    // only remove if we're outside the new view distance...
                    if (Math.max(Math.abs(currX - toX), Math.abs(currZ - toZ)) > newViewDistance) {
                        this.removeObjectFrom(object, currX, currZ, toX, toZ, fromX, fromZ);
                    }
                }
            }

            // add loop

            final int newMaxX = toX + newViewDistance;
            final int newMaxZ = toZ + newViewDistance;
            for (int currX = toX - newViewDistance; currX <= newMaxX; ++currX) {
                for (int currZ = toZ - newViewDistance; currZ <= newMaxZ; ++currZ) {

                    // only add if we're outside the old view distance...
                    if (Math.max(Math.abs(currX - fromX), Math.abs(currZ - fromZ)) > oldViewDistance) {
                        this.addObjectTo(object, currX, currZ, toX, toZ, fromX, fromZ);
                    }
                }
            }

            return;
        }

        // x axis is width
        // z axis is height
        // right refers to the x axis of where we moved
        // top refers to the z axis of where we moved

        // same view distance

        // used for relative positioning
        final int up = sign(dz); // 1 if dz >= 0, -1 otherwise
        final int right = sign(dx); // 1 if dx >= 0, -1 otherwise

        // The area excluded by overlapping the two view distance squares creates four rectangles:
        // Two on the left, and two on the right. The ones on the left we consider the "removed" section
        // and on the right the "added" section.
        // https://i.imgur.com/MrnOBgI.png is a reference image. Note that the outside border is not actually
        // exclusive to the regions they surround.

        // 4 points of the rectangle
        int maxX; // exclusive
        int minX; // inclusive
        int maxZ; // exclusive
        int minZ; // inclusive

        if (dx != 0) {
            // handle right addition

            maxX = toX + (oldViewDistance * right) + right; // exclusive
            minX = fromX + (oldViewDistance * right) + right; // inclusive
            maxZ = fromZ + (oldViewDistance * up) + up; // exclusive
            minZ = toZ - (oldViewDistance * up); // inclusive

            for (int currX = minX; currX != maxX; currX += right) {
                for (int currZ = minZ; currZ != maxZ; currZ += up) {
                    this.addObjectTo(object, currX, currZ, toX, toZ, fromX, fromZ);
                }
            }
        }

        if (dz != 0) {
            // handle up addition

            maxX = toX + (oldViewDistance * right) + right; // exclusive
            minX = toX - (oldViewDistance * right); // inclusive
            maxZ = toZ + (oldViewDistance * up) + up; // exclusive
            minZ = fromZ + (oldViewDistance * up) + up; // inclusive

            for (int currX = minX; currX != maxX; currX += right) {
                for (int currZ = minZ; currZ != maxZ; currZ += up) {
                    this.addObjectTo(object, currX, currZ, toX, toZ, fromX, fromZ);
                }
            }
        }

        if (dx != 0) {
            // handle left removal

            maxX = toX - (oldViewDistance * right); // exclusive
            minX = fromX - (oldViewDistance * right); // inclusive
            maxZ = fromZ + (oldViewDistance * up) + up; // exclusive
            minZ = toZ - (oldViewDistance * up); // inclusive

            for (int currX = minX; currX != maxX; currX += right) {
                for (int currZ = minZ; currZ != maxZ; currZ += up) {
                    this.removeObjectFrom(object, currX, currZ, toX, toZ, fromX, fromZ);
                }
            }
        }

        if (dz != 0) {
            // handle down removal

            maxX = fromX + (oldViewDistance * right) + right; // exclusive
            minX = fromX - (oldViewDistance * right); // inclusive
            maxZ = toZ - (oldViewDistance * up); // exclusive
            minZ = fromZ - (oldViewDistance * up); // inclusive

            for (int currX = minX; currX != maxX; currX += right) {
                for (int currZ = minZ; currZ != maxZ; currZ += up) {
                    this.removeObjectFrom(object, currX, currZ, toX, toZ, fromX, fromZ);
                }
            }
        }
    }

    @FunctionalInterface
    public static interface ChangeCallback<E> {

        // if there is no previous position, then prevPos = Integer.MIN_VALUE
        void accept(final E object, final int rangeX, final int rangeZ, final int currPosX, final int currPosZ, final int prevPosX, final int prevPosZ,
                    final PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<E> newState);

    }
}