package com.tuinity.tuinity.chunk;

import com.tuinity.tuinity.util.TickSynchronizationPoint;
import com.tuinity.tuinity.util.TickThread;
import com.tuinity.tuinity.util.Util;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.server.World;

public final class ChunkRegionManager {

    private static final int REGION_MERGE_RADIUS = 1;

    public final Long2ObjectOpenHashMap<ChunkRegionHolder> regions = new Long2ObjectOpenHashMap<>(8192, 0.25f);
    public final World world;
    public final TickSynchronizationPoint synchronizationPoint;

    private final TickThread[] threads;

    public ChunkRegionManager(final World world, final TickSynchronizationPoint synchronizationPoint, final TickThread[] threads) {
        this.world = world;
        this.synchronizationPoint = synchronizationPoint;
        this.threads = threads;
    }

    public void addToRegion(final long coordinate) {
        this.addToRegion(Util.getCoordinateX(coordinate), Util.getCoordinateZ(coordinate), coordinate);
    }

    public void addToRegion(final int chunkX, final int chunkZ) {
        this.addToRegion(chunkX, chunkZ, Util.getCoordinateKey(chunkX, chunkZ));
    }

    // note: for MT, when we want to merge regions we actually need to tell the owning thread (if any) to merge it
    // themselves
    public void addToRegion(final int chunkX, final int chunkZ, final long coordinate) {
        // find the ideal region to merge into

        ChunkRegionHolder regionHolder = null;
        ChunkRegion region = null;
        int regionHolderChunks = 0;

        for (int dx = -REGION_MERGE_RADIUS; dx <= REGION_MERGE_RADIUS; ++dx) {
            for (int dz = -REGION_MERGE_RADIUS; dz <= REGION_MERGE_RADIUS; ++dz) {
                final int checkX = dx + chunkX;
                final int checkZ = dz + chunkZ;
                final long k = Util.getCoordinateKey(checkX, checkZ);

                ChunkRegionHolder currentRegion = this.regions.get(k);

                if (currentRegion != null) {
                    final int currentSize = currentRegion.region.coordinates.size();
                    if (currentSize > regionHolderChunks) {
                        regionHolderChunks = currentSize;
                        regionHolder = currentRegion;
                        region = currentRegion.region;
                    }
                }
            }
        }

        if (regionHolder == null) {
            regionHolder = new ChunkRegionHolder(region = new ChunkRegion());
        }

        // now merge regions in radius

        region.addChunk(chunkX, chunkZ, coordinate);

        for (int dx = -REGION_MERGE_RADIUS; dx <= REGION_MERGE_RADIUS; ++dx) {
            for (int dz = -REGION_MERGE_RADIUS; dz <= REGION_MERGE_RADIUS; ++dz) {
                final int checkX = dx + chunkX;
                final int checkZ = dz + chunkZ;
                final long k = Util.getCoordinateKey(checkX, checkZ);

                ChunkRegionHolder currentRegion = this.regions.putIfAbsent(k, regionHolder);

                if (currentRegion != null && currentRegion.region != region) {
                    currentRegion.region.mergeInto(region);
                }
            }
        }
    }

    static final class ChunkRegionHolder {

        public ChunkRegion region;

        public ChunkRegionHolder(final ChunkRegion region) {
            this.region = region;
            this.region.addRegionHolder(this);
        }
    }

    static final class ChunkRegion {

        private final LongOpenHashSet coordinates = new LongOpenHashSet();
        private boolean dead;

        private int lowerX;
        private int lowerZ;

        private int upperX;
        private int upperZ;

        private final ObjectOpenHashSet<ChunkRegionHolder> regionHolders = new ObjectOpenHashSet<>();

        void addRegionHolder(final ChunkRegionHolder regionHolder) {
            this.regionHolders.add(regionHolder);
        }

        public void mergeInto(final ChunkRegion region) {
            if (region.dead) {
                throw new IllegalStateException("Attempting to merge into a dead region");
            } else if (this.dead) {
                throw new IllegalStateException("Attempting to merge from a dead region");
            }

            for (LongIterator iterator = this.coordinates.iterator(); iterator.hasNext();) {
                region.addChunk(iterator.nextLong());
            }

            // forward our old region holders
            for (final ChunkRegionHolder regionHolder : this.regionHolders) {
                regionHolder.region = region;
            }

            this.dead = true;
        }

        void addChunk(final long coordinate) {
            this.addChunk(Util.getCoordinateX(coordinate), Util.getCoordinateZ(coordinate), coordinate);
        }

        void addChunk(final int chunkX, final int chunkZ) {
            this.addChunk(chunkX, chunkZ, Util.getCoordinateKey(chunkX, chunkZ));
        }

        boolean addChunk(final int chunkX, final int chunkZ, final long coordinate) {
            if (!this.coordinates.add(coordinate)) {
                return false;
            }

            if (this.coordinates.size() == 1) {
                this.lowerX = this.upperX = chunkX;
                this.lowerZ = this.upperZ = chunkZ;
            } else {
                if (chunkX < this.lowerX) {
                    this.lowerX = chunkX;
                } else if (chunkX > this.upperX) {
                    this.upperX = chunkX;
                }
                if (chunkZ < this.lowerZ) {
                    this.lowerZ = chunkZ;
                } else if (chunkZ > this.upperZ) {
                    this.upperZ = chunkZ;
                }
            }

            return true;
        }
    }
}