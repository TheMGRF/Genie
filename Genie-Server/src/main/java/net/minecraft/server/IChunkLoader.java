package net.minecraft.server;

import com.mojang.datafixers.DataFixer;
import java.io.File;
import java.io.IOException;
// Paper start
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
// Paper end
import java.util.function.Supplier;
import javax.annotation.Nullable;

public class IChunkLoader extends RegionFileCache implements AutoCloseable {

//    private final IOWorker a; public IOWorker getIOWorker() { return a; } // Paper - OBFHELPER - nuke IOWorker
    protected final DataFixer b;
    @Nullable
    private volatile PersistentStructureLegacy c; // Paper - async chunk loading

    private final Object persistentDataLock = new Object(); // Paper

    public IChunkLoader(File file, DataFixer datafixer) {
        super(file);
        this.b = datafixer;
//        this.a = new IOWorker(new RegionFileCache(file), "chunk"); // Paper - nuke IOWorker
    }

    // CraftBukkit start
    private boolean check(ChunkProviderServer cps, int x, int z) throws IOException {
        ChunkCoordIntPair pos = new ChunkCoordIntPair(x, z);
        if (cps != null) {
            //com.google.common.base.Preconditions.checkState(org.bukkit.Bukkit.isPrimaryThread(), "primary thread"); // Paper - this function is now MT-Safe
            if (cps.getChunkAtIfCachedImmediately(x, z) != null) { // Paper - isLoaded is a ticket level check, not a chunk loaded check!
                return true;
            }
        }


            // Paper start - prioritize
            NBTTagCompound nbt = cps == null ? read(pos) :
                com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.loadChunkData((WorldServer)cps.getWorld(), x, z,
                    com.destroystokyo.paper.io.PrioritizedTaskQueue.HIGHER_PRIORITY, false, true).chunkData;
            // Paper end
            if (nbt != null) {
                NBTTagCompound level = nbt.getCompound("Level");
                if (level.getBoolean("TerrainPopulated")) {
                    return true;
                }

            ChunkStatus status = ChunkStatus.a(level.getString("Status"));
            if (status != null && status.b(ChunkStatus.FEATURES)) {
                return true;
            }
        }

        return false;
    }
    // CraftBukkit end

    public NBTTagCompound getChunkData(DimensionManager dimensionmanager, Supplier<WorldPersistentData> supplier, NBTTagCompound nbttagcompound, ChunkCoordIntPair pos, @Nullable GeneratorAccess generatoraccess) throws IOException {
        int i = a(nbttagcompound);
        boolean flag = true;

        // CraftBukkit start
        if (i < 1466) {
            NBTTagCompound level = nbttagcompound.getCompound("Level");
            if (level.getBoolean("TerrainPopulated") && !level.getBoolean("LightPopulated")) {
                ChunkProviderServer cps = (generatoraccess == null) ? null : ((WorldServer) generatoraccess).getChunkProvider();
                if (check(cps, pos.x - 1, pos.z) && check(cps, pos.x - 1, pos.z - 1) && check(cps, pos.x, pos.z - 1)) {
                    level.setBoolean("LightPopulated", true);
                }
            }
        }
        // CraftBukkit end

        if (i < 1493) {
            nbttagcompound = GameProfileSerializer.a(this.b, DataFixTypes.CHUNK, nbttagcompound, i, 1493);
            if (nbttagcompound.getCompound("Level").getBoolean("hasLegacyStructureData")) {
                synchronized (this.persistentDataLock) { // Paper - Async chunk loading
                if (this.c == null) {
                    this.c = PersistentStructureLegacy.a(dimensionmanager.getType(), (WorldPersistentData) supplier.get()); // CraftBukkit - getType
                }

                nbttagcompound = this.c.a(nbttagcompound);
                } // Paper - Async chunk loading
            }
        }

        nbttagcompound = GameProfileSerializer.a(this.b, DataFixTypes.CHUNK, nbttagcompound, Math.max(1493, i));
        if (i < SharedConstants.getGameVersion().getWorldVersion()) {
            nbttagcompound.setInt("DataVersion", SharedConstants.getGameVersion().getWorldVersion());
        }

        return nbttagcompound;
    }

    public static int a(NBTTagCompound nbttagcompound) {
        return nbttagcompound.hasKeyOfType("DataVersion", 99) ? nbttagcompound.getInt("DataVersion") : -1;
    }

//    Paper start - nuke IOWorker
//    @Nullable
//    public NBTTagCompound read(ChunkCoordIntPair chunkcoordintpair) throws IOException {
//        return this.a.a(chunkcoordintpair);
//    }
//
    public void a(ChunkCoordIntPair chunkcoordintpair, NBTTagCompound nbttagcompound) throws IOException { write(chunkcoordintpair, nbttagcompound); } // Paper OBFHELPER
    public void write(ChunkCoordIntPair chunkcoordintpair, NBTTagCompound nbttagcompound) throws IOException { // Paper - OBFHELPER - (Switched around for safety)
        // Paper start
        if (!chunkcoordintpair.equals(ChunkRegionLoader.getChunkCoordinate(nbttagcompound))) {
            String world = (this instanceof PlayerChunkMap) ? ((PlayerChunkMap)this).world.getWorld().getName() : null;
            throw new IllegalArgumentException("Chunk coordinate and serialized data do not have matching coordinates, trying to serialize coordinate " + chunkcoordintpair.toString()
                + " but compound says coordinate is " + ChunkRegionLoader.getChunkCoordinate(nbttagcompound).toString() + (world == null ? " for an unknown world" : (" for world: " + world)));
        }
        // Paper end
        super.write(chunkcoordintpair, nbttagcompound);
        if (this.c != null) {
            synchronized (this.persistentDataLock) { // Paper - Async chunk loading
            this.c.a(chunkcoordintpair.pair()); } // Paper - Async chunk loading}
        }

    }
//
//    public void i() {
//        this.a.a().join();
//    }
//
//    public void close() throws IOException {
//        this.a.close();
//    }
//    Paper end
}
