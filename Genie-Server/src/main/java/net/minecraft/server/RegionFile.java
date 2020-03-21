package net.minecraft.server;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.InflaterInputStream; // Paper

import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class RegionFile implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final ByteBuffer b = ByteBuffer.allocateDirect(1);
    private final FileChannel dataFile;
    private final java.nio.file.Path d; private final java.nio.file.Path getContainingDataFolder() { return this.d; } // Tuinity - OBFHELPER
    private final RegionFileCompression e; private final RegionFileCompression getRegionFileCompression() { return this.e; } // Tuinity - OBFHELPER
    private final ByteBuffer f;
    private final IntBuffer g; private final IntBuffer getOffsets() { return this.g; } // Tuinity - OBFHELPER
    private final IntBuffer h; private final IntBuffer getTimestamps() { return this.h; } // Tuinity - OBFHELPER
    private final RegionFileBitSet freeSectors;
    public final File file;

    // Tuinity start - try to recover from RegionFile header corruption
    private static long roundToSectors(long bytes) {
        long sectors = bytes >>> 12; // 4096 = 2^12
        long remainingBytes = bytes & 4095;
        long sign = -remainingBytes; // sign is 1 if nonzero
        return sectors + (sign >>> 63);
    }

    private static final NBTTagCompound OVERSIZED_COMPOUND = new NBTTagCompound();

    private NBTTagCompound attemptRead(long sector, int chunkDataLength, long fileLength) throws IOException {
        try {
            if (chunkDataLength < 0) {
                return null;
            }

            long offset = sector * 4096L + 4L; // offset for chunk data

            if ((offset + chunkDataLength) > fileLength) {
                return null;
            }

            ByteBuffer chunkData = ByteBuffer.allocate(chunkDataLength);
            if (chunkDataLength != this.dataFile.read(chunkData, offset)) {
                return null;
            }

            chunkData.flip();

            byte compressionType = chunkData.get();
            if (compressionType < 0) { // compressionType & 128 != 0
                // oversized chunk
                return OVERSIZED_COMPOUND;
            }

            RegionFileCompression compression = RegionFileCompression.getByType(compressionType);
            if (compression == null) {
                return null;
            }

            InputStream input = compression.wrap(new ByteArrayInputStream(chunkData.array(), chunkData.position(), chunkDataLength - chunkData.position()));

            return NBTCompressedStreamTools.readNBT(new DataInputStream(new BufferedInputStream(input)));
        } catch (Exception ex) {
            return null;
        }
    }

    private int getLength(long sector) throws IOException {
        ByteBuffer length = ByteBuffer.allocate(4);
        if (4 != this.dataFile.read(length, sector * 4096L)) {
            return -1;
        }

        return length.getInt(0);
    }

    private void backupRegionFile() {
        File backup = new File(this.file.getParent(), this.file.getName() + "." + new java.util.Random().nextLong() + ".backup");
        this.backupRegionFile(backup);
    }

    private void backupRegionFile(File to) {
        try {
            this.dataFile.force(true);
            MinecraftServer.LOGGER.warn("Backing up regionfile \"" + this.file.getAbsolutePath() + "\" to " + to.getAbsolutePath());
            java.nio.file.Files.copy(this.file.toPath(), to.toPath());
            MinecraftServer.LOGGER.warn("Backed up the regionfile to " + to.getAbsolutePath());
        } catch (IOException ex) {
            MinecraftServer.LOGGER.error("Failed to backup to " + to.getAbsolutePath(), ex);
        }
    }

    // note: only call for CHUNK regionfiles
    void recalculateHeader() throws IOException {
        if (!this.canRecalcHeader) {
            return;
        }
        synchronized (this) {
            MinecraftServer.LOGGER.warn("Corrupt regionfile header detected! Attempting to re-calculate header offsets for regionfile " + this.file.getAbsolutePath(), new Throwable());

            // try to backup file so maybe it could be sent to us for further investigation

            this.backupRegionFile();
            NBTTagCompound[] compounds = new NBTTagCompound[32 * 32]; // only in the regionfile (i.e exclude mojang/aikar oversized data)
            int[] rawLengths = new int[32 * 32]; // length of chunk data including 4 byte length field, bytes
            int[] sectorOffsets = new int[32 * 32]; // in sectors
            boolean[] hasAikarOversized = new boolean[32 * 32];

            long fileLength = this.dataFile.size();
            long totalSectors = roundToSectors(fileLength);

            // search the regionfile from start to finish for the most up-to-date chunk data

            for (long i = 2, maxSector = Math.min((long)(Integer.MAX_VALUE >>> 8), totalSectors); i < maxSector; ++i) { // first two sectors are header, skip
                int chunkDataLength = this.getLength(i);
                NBTTagCompound compound = this.attemptRead(i, chunkDataLength, fileLength);
                if (compound == null || compound == OVERSIZED_COMPOUND) {
                    continue;
                }

                ChunkCoordIntPair chunkPos = ChunkRegionLoader.getChunkCoordinate(compound);
                int location = (chunkPos.x & 31) | ((chunkPos.z & 31) << 5);

                NBTTagCompound otherCompound = compounds[location];

                if (otherCompound != null && ChunkRegionLoader.getLastWorldSaveTime(otherCompound) > ChunkRegionLoader.getLastWorldSaveTime(compound)) {
                    continue; // don't overwrite newer data.
                }

                // aikar oversized?
                File aikarOversizedFile = this.getOversizedFile(chunkPos.x, chunkPos.z);
                boolean isAikarOversized = false;
                if (aikarOversizedFile.exists()) {
                    try {
                        NBTTagCompound aikarOversizedCompound = this.getOversizedData(chunkPos.x, chunkPos.z);
                        if (ChunkRegionLoader.getLastWorldSaveTime(compound) == ChunkRegionLoader.getLastWorldSaveTime(aikarOversizedCompound)) {
                            // best we got for an id. hope it's good enough
                            isAikarOversized = true;
                        }
                    } catch (Exception ex) {
                        MinecraftServer.LOGGER.error("Failed to read aikar oversized data for absolute chunk (" + chunkPos.x + "," + chunkPos.z + ") in regionfile " + this.file.getAbsolutePath() + ", oversized data for this chunk will be lost", ex);
                        // fall through, if we can't read aikar oversized we can't risk corrupting chunk data
                    }
                }

                hasAikarOversized[location] = isAikarOversized;
                compounds[location] = compound;
                rawLengths[location] = chunkDataLength + 4;
                sectorOffsets[location] = (int)i;

                int chunkSectorLength = (int)roundToSectors(rawLengths[location]);
                i += chunkSectorLength;
                --i; // gets incremented next iteration
            }

            // forge style oversized data is already handled by the local search, and aikar data we just hope
            // we get it right as aikar data has no identifiers we could use to try and find its corresponding
            // local data compound

            java.nio.file.Path containingFolder = this.getContainingDataFolder();
            File[] regionFiles = containingFolder.toFile().listFiles();
            boolean[] oversized = new boolean[32 * 32];
            RegionFileCompression[] oversizedCompressionTypes = new RegionFileCompression[32 * 32];

            if (regionFiles != null) {
                ChunkCoordIntPair ourLowerLeftPosition = RegionFileCache.getRegionFileCoordinates(this.file);

                if (ourLowerLeftPosition == null) {
                    MinecraftServer.LOGGER.fatal("Unable to get chunk location of regionfile " + this.file.getAbsolutePath() + ", cannot recover oversized chunks");
                } else {
                    int lowerXBound = ourLowerLeftPosition.x; // inclusive
                    int lowerZBound = ourLowerLeftPosition.z; // inclusive
                    int upperXBound = lowerXBound + 32 - 1; // inclusive
                    int upperZBound = lowerZBound + 32 - 1; // inclusive

                    // read mojang oversized data
                    for (File regionFile : regionFiles) {
                        ChunkCoordIntPair oversizedCoords = getOversizedChunkPair(regionFile);
                        if (oversizedCoords == null) {
                            continue;
                        }

                        if ((oversizedCoords.x < lowerXBound || oversizedCoords.x > upperXBound) || (oversizedCoords.z < lowerZBound || oversizedCoords.z > upperZBound)) {
                            continue; // not in our regionfile
                        }

                        // ensure oversized data is valid & is newer than data in the regionfile

                        int location = (oversizedCoords.x & 31) | ((oversizedCoords.z & 31) << 5);

                        byte[] chunkData;
                        try {
                            chunkData = Files.readAllBytes(regionFile.toPath());
                        } catch (Exception ex) {
                            MinecraftServer.LOGGER.error("Failed to read oversized chunk data in file " + regionFile.getAbsolutePath(), ex);
                            continue;
                        }

                        NBTTagCompound compound = null;

                        // We do not know the compression type, as it's stored in the regionfile. So we need to try all of them
                        RegionFileCompression compression = null;
                        for (RegionFileCompression compressionType : RegionFileCompression.getCompressionTypes().values()) {
                            try {
                                DataInputStream in = new DataInputStream(new BufferedInputStream(compressionType.wrap(new ByteArrayInputStream(chunkData)))); // typical java
                                compound = NBTCompressedStreamTools.readNBT(in);
                                compression = compressionType;
                                break; // reaches here iff readNBT does not throw
                            } catch (Exception ex) {
                                continue;
                            }
                        }

                        if (compound == null) {
                            MinecraftServer.LOGGER.error("Failed to read oversized chunk data in file " + regionFile.getAbsolutePath() + ", it's corrupt. Its data will be lost");
                            continue;
                        }

                        if (compounds[location] == null || ChunkRegionLoader.getLastWorldSaveTime(compound) > ChunkRegionLoader.getLastWorldSaveTime(compounds[location])) {
                            oversized[location] = true;
                            oversizedCompressionTypes[location] = compression;
                        }
                    }
                }
            }

            // now we need to calculate a new offset header

            int[] calculatedOffsets = new int[32 * 32];
            RegionFileBitSet newSectorAllocations = new RegionFileBitSet();
            newSectorAllocations.allocate(0, 2); // make space for header

            // allocate sectors for normal chunks

            for (int chunkX = 0; chunkX < 32; ++chunkX) {
                for (int chunkZ = 0; chunkZ < 32; ++chunkZ) {
                    int location = chunkX | (chunkZ << 5);

                    if (oversized[location]) {
                        continue;
                    }

                    int rawLength = rawLengths[location]; // bytes
                    int sectorOffset = sectorOffsets[location]; // sectors
                    int sectorLength = (int)roundToSectors(rawLength);

                    if (newSectorAllocations.tryAllocate(sectorOffset, sectorLength)) {
                        calculatedOffsets[location] = sectorOffset << 8 | (sectorLength > 255 ? 255 : sectorLength); // support forge style oversized
                    } else {
                        MinecraftServer.LOGGER.error("Failed to allocate space for local chunk (overlapping data??) at (" + chunkX + "," + chunkZ + ") in regionfile " + this.file.getAbsolutePath() + ", chunk will be regenerated");
                    }
                }
            }

            // allocate sectors for oversized chunks

            for (int chunkX = 0; chunkX < 32; ++chunkX) {
                for (int chunkZ = 0; chunkZ < 32; ++chunkZ) {
                    int location = chunkX | (chunkZ << 5);

                    if (!oversized[location]) {
                        continue;
                    }

                    int sectorOffset = newSectorAllocations.allocateNewSpace(1);
                    int sectorLength = 1;

                    try {
                        this.dataFile.write(this.getOversizedChunkHolderData(oversizedCompressionTypes[location]), sectorOffset * 4096);
                        // only allocate in the new offsets if the write succeeds
                        calculatedOffsets[location] = sectorOffset << 8 | (sectorLength > 255 ? 255 : sectorLength); // support forge style oversized
                    } catch (IOException ex) {
                        newSectorAllocations.free(sectorOffset, sectorLength);
                        MinecraftServer.LOGGER.error("Failed to write new oversized chunk data holder, local chunk at (" + chunkX + "," + chunkZ + ") in regionfile " + this.file.getAbsolutePath() + " will be regenerated");
                    }
                }
            }

            // rewrite aikar oversized data

            this.oversizedCount = 0;
            for (int chunkX = 0; chunkX < 32; ++chunkX) {
                for (int chunkZ = 0; chunkZ < 32; ++chunkZ) {
                    int location = chunkX | (chunkZ << 5);
                    int isAikarOversized = hasAikarOversized[location] ? 1 : 0;

                    this.oversizedCount += isAikarOversized;
                    this.oversized[location] = (byte)isAikarOversized;
                }
            }

            if (this.oversizedCount > 0) {
                try {
                    this.writeOversizedMeta();
                } catch (Exception ex) {
                    MinecraftServer.LOGGER.error("Failed to write aikar oversized chunk meta, all aikar style oversized chunk data will be lost for regionfile " + this.file.getAbsolutePath(), ex);
                    this.getOversizedMetaFile().delete();
                }
            } else {
                this.getOversizedMetaFile().delete();
            }

            this.freeSectors.copyFrom(newSectorAllocations);

            // before we overwrite the old sectors, print a summary of the chunks that got changed.

            MinecraftServer.LOGGER.info("Starting summary of changes for regionfile " + this.file.getAbsolutePath());

            for (int chunkX = 0; chunkX < 32; ++chunkX) {
                for (int chunkZ = 0; chunkZ < 32; ++chunkZ) {
                    int location = chunkX | (chunkZ << 5);

                    int oldOffset = this.getOffsets().get(location);
                    int newOffset = calculatedOffsets[location];

                    if (oldOffset == newOffset) {
                        continue;
                    }

                    this.getOffsets().put(location, newOffset); // overwrite incorrect offset

                    if (oldOffset == 0) {
                        // found lost data
                        MinecraftServer.LOGGER.info("Found missing data for local chunk (" + chunkX + "," + chunkZ + ") in regionfile " + this.file.getAbsolutePath());
                    } else if (newOffset == 0) {
                        MinecraftServer.LOGGER.warn("Data for local chunk (" + chunkX + "," + chunkZ + ") could not be recovered in regionfile " + this.file.getAbsolutePath() + ", it will be regenerated");
                    } else {
                        MinecraftServer.LOGGER.info("Local chunk (" + chunkX + "," + chunkZ + ") changed to point to newer data or correct chunk in regionfile " + this.file.getAbsolutePath());
                    }
                }
            }

            MinecraftServer.LOGGER.info("End of change summary for regionfile " + this.file.getAbsolutePath());

            // simply destroy the timestamp header, it's not used

            for (int i = 0; i < 32 * 32; ++i) {
                this.getTimestamps().put(i, calculatedOffsets[i] != 0 ? (int)System.currentTimeMillis() : 0); // write a valid timestamp for valid chunks, I do not want to find out whatever dumb program actually checks this
            }

            // write new header
            try {
                this.flushHeader();
                this.dataFile.force(true); // try to ensure it goes through...
                MinecraftServer.LOGGER.info("Successfully wrote new header to disk for regionfile " + this.file.getAbsolutePath());
            } catch (IOException ex) {
                MinecraftServer.LOGGER.fatal("Failed to write new header to disk for regionfile " + this.file.getAbsolutePath(), ex);
            }
        }
    }

    final boolean canRecalcHeader; // final forces compile fail on new constructor
    // Tuinity end

    public final java.util.concurrent.locks.ReentrantLock fileLock = new java.util.concurrent.locks.ReentrantLock(true); // Paper

    // Paper start - Cache chunk status
    private final ChunkStatus[] statuses = new ChunkStatus[32 * 32];

    private boolean closed;

    // invoked on write/read
    public void setStatus(int x, int z, ChunkStatus status) {
        if (this.closed) {
            // We've used an invalid region file.
            throw new IllegalStateException("RegionFile is closed");
        }
        this.statuses[this.getChunkLocation(new ChunkCoordIntPair(x, z))] = status;
    }

    public ChunkStatus getStatusIfCached(int x, int z) {
        if (this.closed) {
            // We've used an invalid region file.
            throw new IllegalStateException("RegionFile is closed");
        }
        final int location = this.getChunkLocation(new ChunkCoordIntPair(x, z));
        return this.statuses[location];
    }
    // Paper end

    public RegionFile(File file, File file1) throws IOException {
        // Tuinity start - add header recalculation boolean
        this(file, file1, false);
    }
    public RegionFile(File file, File file1, boolean canRecalcHeader) throws IOException {
        this(file.toPath(), file1.toPath(), RegionFileCompression.b, canRecalcHeader);
        // Tuinity end
    }

    public RegionFile(java.nio.file.Path java_nio_file_path, java.nio.file.Path java_nio_file_path1, RegionFileCompression regionfilecompression) throws IOException {
        // Tuinity start - add header recalculation boolean
        this(java_nio_file_path, java_nio_file_path1, regionfilecompression, false);
    }
    public RegionFile(java.nio.file.Path java_nio_file_path, java.nio.file.Path java_nio_file_path1, RegionFileCompression regionfilecompression, boolean canRecalcHeader) throws IOException {
        this.canRecalcHeader = canRecalcHeader;
        // Tuinity end
        this.file = java_nio_file_path.toFile(); // Paper
        this.f = ByteBuffer.allocateDirect(8192);
        initOversizedState();
        this.freeSectors = new RegionFileBitSet();
        this.e = regionfilecompression;
        if (!Files.isDirectory(java_nio_file_path1, new LinkOption[0])) {
            throw new IllegalArgumentException("Expected directory, got " + java_nio_file_path1.toAbsolutePath());
        } else {
            this.d = java_nio_file_path1;
            this.g = this.f.asIntBuffer();
            ((java.nio.Buffer) this.g).limit(1024);
            ((java.nio.Buffer) this.f).position(4096);
            this.h = this.f.asIntBuffer();
            this.dataFile = FileChannel.open(java_nio_file_path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            this.freeSectors.a(0, 2);
            ((java.nio.Buffer) this.f).position(0);
            int i = this.dataFile.read(this.f, 0L);

            if (i != -1) {
                if (i != 8192) {
                    RegionFile.LOGGER.warn("Region file {} has truncated header: {}", java_nio_file_path, i);
                }

                boolean needsHeaderRecalc = false; // Tuinity - recalculate header on header corruption
                boolean hasBackedUp = false; // Tuinity - recalculate header on header corruption

                for (int j = 0; j < 1024; ++j) { // Tuinity - diff on change, we expect j to be the header location
                    int k = this.g.get(j);

                    if (k != 0) {
                        int l = b(k); // Tuinity - diff on change, we expect l to be offset in file
                        int i1 = a(k); // Tuinity - diff on change, we expect i1 to be sector length of region
                        // Spigot start
                        if (i1 == 255) {
                            // We're maxed out, so we need to read the proper length from the section
                            ByteBuffer realLen = ByteBuffer.allocate(4);
                            this.dataFile.read(realLen, l * 4096);
                            i1 = (realLen.getInt(0) + 4) / 4096 + 1;
                        }
                        // Spigot end

                        // Tuinity start - recalculate header on header corruption
                        if (l < 0 || i1 < 0 || (l + i1) < 0) {
                            if (canRecalcHeader) {
                                MinecraftServer.LOGGER.error("Detected invalid header for regionfile " + this.file.getAbsolutePath() + "! Recalculating header...");
                                needsHeaderRecalc = true;
                                break;
                            } else {
                                // location = chunkX | (chunkZ << 5);
                                MinecraftServer.LOGGER.fatal("Detected invalid header for regionfile " + this.file.getAbsolutePath() +
                                        "! Cannot recalculate, removing local chunk (" + (j & 31) + "," + (j >>> 5) + ") from header");
                                if (!hasBackedUp) {
                                    hasBackedUp = true;
                                    this.backupRegionFile();
                                }
                                this.getTimestamps().put(j, 0); // be consistent, delete the timestamp too
                                this.getOffsets().put(j, 0); // delete the entry from header
                                continue;
                            }
                        }
                        boolean failedToAllocate = !this.freeSectors.tryAllocate(l, i1);
                        if (failedToAllocate && !canRecalcHeader) {
                            // location = chunkX | (chunkZ << 5);
                            MinecraftServer.LOGGER.fatal("Detected invalid header for regionfile " + this.file.getAbsolutePath() +
                                    "! Cannot recalculate, removing local chunk (" + (j & 31) + "," + (j >>> 5) + ") from header");
                            if (!hasBackedUp) {
                                hasBackedUp = true;
                                this.backupRegionFile();
                            }
                            this.getTimestamps().put(j, 0); // be consistent, delete the timestamp too
                            this.getOffsets().put(j, 0); // delete the entry from header
                            continue;
                        }
                        needsHeaderRecalc |= failedToAllocate;
                        // Tuinity end - recalculate header on header corruption
                    }
                }

                // Tuinity start - recalculate header on header corruption
                // we move the recalc here so comparison to old header is correct when logging to console
                if (needsHeaderRecalc) { // true if header gave us overlapping allocations
                    MinecraftServer.LOGGER.error("Recalculating regionfile " + this.file.getAbsolutePath() + ", header gave conflicting offsets & locations");
                    this.recalculateHeader();
                }
                // Tuinity end
            }

        }
    }

    private final java.nio.file.Path getOversizedChunkPath(ChunkCoordIntPair chunkcoordintpair) { return this.e(chunkcoordintpair); } // Tuinity - OBFHELPER
    private java.nio.file.Path e(ChunkCoordIntPair chunkcoordintpair) {
        String s = "c." + chunkcoordintpair.x + "." + chunkcoordintpair.z + ".mcc"; // Tuinity - diff on change

        return this.d.resolve(s);
    }

    // Tuinity start
    private static ChunkCoordIntPair getOversizedChunkPair(File file) {
        String fileName = file.getName();

        if (!fileName.startsWith("c.") || !fileName.endsWith(".mcc")) {
            return null;
        }

        String[] split = fileName.split("\\.");

        if (split.length != 4) {
            return null;
        }

        try {
            int x = Integer.parseInt(split[1]);
            int z = Integer.parseInt(split[2]);

            return new ChunkCoordIntPair(x, z);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    // Tuinity end

    @Nullable public synchronized DataInputStream getReadStream(ChunkCoordIntPair chunkCoordIntPair) throws IOException { return a(chunkCoordIntPair);} // Paper - OBFHELPER
    @Nullable
    public synchronized DataInputStream a(ChunkCoordIntPair chunkcoordintpair) throws IOException {
        int i = this.getOffset(chunkcoordintpair);

        if (i == 0) {
            return null;
        } else {
            int j = b(i);
            int k = a(i);
            // Spigot start
            if (k == 255) {
                ByteBuffer realLen = ByteBuffer.allocate(4);
                this.dataFile.read(realLen, j * 4096);
                k = (realLen.getInt(0) + 4) / 4096 + 1;
            }
            // Spigot end
            int l = k * 4096;
            ByteBuffer bytebuffer = ByteBuffer.allocate(l);

            this.dataFile.read(bytebuffer, (long) (j * 4096));
            ((java.nio.Buffer) bytebuffer).flip();
            if (bytebuffer.remaining() < 5) {
                // Tuinity start - recalculate header on regionfile corruption
                if (this.canRecalcHeader) {
                    this.recalculateHeader();
                    return this.getReadStream(chunkcoordintpair);
                }
                // Tuinity end
                RegionFile.LOGGER.error("Chunk {} header is truncated: expected {} but read {}", chunkcoordintpair, l, bytebuffer.remaining());
                return null;
            } else {
                int i1 = bytebuffer.getInt();
                byte b0 = bytebuffer.get();

                if (i1 == 0) {
                    RegionFile.LOGGER.warn("Chunk {} is allocated, but stream is missing", chunkcoordintpair);
                    // Tuinity start - recalculate header on regionfile corruption
                    if (this.canRecalcHeader) {
                        this.recalculateHeader();
                        return this.getReadStream(chunkcoordintpair);
                    }
                    // Tuinity end
                    return null;
                } else {
                    int j1 = i1 - 1;

                    if (a(b0)) {
                        if (j1 != 0) {
                            RegionFile.LOGGER.warn("Chunk has both internal and external streams");
                        }

                        return this.a(chunkcoordintpair, b(b0));
                    } else if (j1 > bytebuffer.remaining()) {
                        RegionFile.LOGGER.error("Chunk {} stream is truncated: expected {} but read {}", chunkcoordintpair, j1, bytebuffer.remaining());
                        // Tuinity start - recalculate header on regionfile corruption
                        if (this.canRecalcHeader) {
                            this.recalculateHeader();
                            return this.getReadStream(chunkcoordintpair);
                        }
                        // Tuinity end
                        return null;
                    } else if (j1 < 0) {
                        RegionFile.LOGGER.error("Declared size {} of chunk {} is negative", i1, chunkcoordintpair);
                        // Tuinity start - recalculate header on regionfile corruption
                        if (this.canRecalcHeader) {
                            this.recalculateHeader();
                            return this.getReadStream(chunkcoordintpair);
                        }
                        // Tuinity end
                        return null;
                    } else {
                        return this.a(chunkcoordintpair, b0, a(bytebuffer, j1));
                    }
                }
            }
        }
    }

    private static boolean a(byte b0) {
        return (b0 & 128) != 0;
    }

    private static byte b(byte b0) {
        return (byte) (b0 & -129);
    }

    @Nullable
    private DataInputStream a(ChunkCoordIntPair chunkcoordintpair, byte b0, InputStream inputstream) throws IOException {
        RegionFileCompression regionfilecompression = RegionFileCompression.a(b0);

        if (regionfilecompression == null) {
            RegionFile.LOGGER.error("Chunk {} has invalid chunk stream version {}", chunkcoordintpair, b0);
            return null;
        } else {
            return new DataInputStream(new BufferedInputStream(regionfilecompression.a(inputstream)));
        }
    }

    @Nullable
    private DataInputStream a(ChunkCoordIntPair chunkcoordintpair, byte b0) throws IOException {
        java.nio.file.Path java_nio_file_path = this.e(chunkcoordintpair);

        if (!Files.isRegularFile(java_nio_file_path, new LinkOption[0])) {
            RegionFile.LOGGER.error("External chunk path {} is not file", java_nio_file_path);
            return null;
        } else {
            return this.a(chunkcoordintpair, b0, Files.newInputStream(java_nio_file_path));
        }
    }

    private static ByteArrayInputStream a(ByteBuffer bytebuffer, int i) {
        return new ByteArrayInputStream(bytebuffer.array(), bytebuffer.position(), i);
    }

    private int a(int i, int j) {
        return i << 8 | j;
    }

    private static int a(int i) {
        return i & 255;
    }

    private static int b(int i) {
        return i >> 8;
    }

    private static int c(int i) {
        return (i + 4096 - 1) / 4096;
    }

    public synchronized boolean b(ChunkCoordIntPair chunkcoordintpair) { // Paper - synchronized
        int i = this.getOffset(chunkcoordintpair);

        if (i == 0) {
            return false;
        } else {
            int j = b(i);
            int k = a(i);
            ByteBuffer bytebuffer = ByteBuffer.allocate(5);

            try {
                this.dataFile.read(bytebuffer, (long) (j * 4096));
                ((java.nio.Buffer) bytebuffer).flip();
                if (bytebuffer.remaining() != 5) {
                    return false;
                } else {
                    int l = bytebuffer.getInt();
                    byte b0 = bytebuffer.get();

                    if (a(b0)) {
                        if (!RegionFileCompression.b(b(b0))) {
                            return false;
                        }

                        if (!Files.isRegularFile(this.e(chunkcoordintpair), new LinkOption[0])) {
                            return false;
                        }
                    } else {
                        if (!RegionFileCompression.b(b0)) {
                            return false;
                        }

                        if (l == 0) {
                            return false;
                        }

                        int i1 = l - 1;

                        if (i1 < 0 || i1 > 4096 * k) {
                            return false;
                        }
                    }

                    return true;
                }
            } catch (IOException ioexception) {
                com.destroystokyo.paper.util.SneakyThrow.sneaky(ioexception); // Paper - we want the upper try/catch to retry this
                return false;
            }
        }
    }

    public DataOutputStream c(ChunkCoordIntPair chunkcoordintpair) throws IOException {
        return new DataOutputStream(new BufferedOutputStream(this.e.a((OutputStream) (new RegionFile.ChunkBuffer(chunkcoordintpair)))));
    }

    protected synchronized void a(ChunkCoordIntPair chunkcoordintpair, ByteBuffer bytebuffer) throws IOException {
        int i = g(chunkcoordintpair);
        int j = this.g.get(i);
        int k = b(j);
        int l = a(j);
        int i1 = bytebuffer.remaining();
        int j1 = c(i1);
        int k1;
        RegionFile.b regionfile_b;

        if (j1 >= 256) {
            java.nio.file.Path java_nio_file_path = this.e(chunkcoordintpair);

            RegionFile.LOGGER.warn("Saving oversized chunk {} ({} bytes} to external file {}", chunkcoordintpair, i1, java_nio_file_path);
            j1 = 1;
            k1 = this.freeSectors.a(j1);
            regionfile_b = this.a(java_nio_file_path, bytebuffer);
            ByteBuffer bytebuffer1 = this.a();

            this.dataFile.write(bytebuffer1, (long) (k1 * 4096));
        } else {
            k1 = this.freeSectors.a(j1);
            regionfile_b = () -> {
                Files.deleteIfExists(this.e(chunkcoordintpair));
            };
            this.dataFile.write(bytebuffer, (long) (k1 * 4096));
        }

        int l1 = (int) (SystemUtils.getTimeMillis() / 1000L);

        this.g.put(i, this.a(k1, j1));
        this.h.put(i, l1);
        this.b();
        regionfile_b.run();
        if (k != 0) {
            this.freeSectors.b(k, l);
        }

    }

    private ByteBuffer a() {
        // Tuinity start - add compressionType param
        return this.getOversizedChunkHolderData(this.getRegionFileCompression());
    }
    private ByteBuffer getOversizedChunkHolderData(RegionFileCompression compressionType) {
        // Tuinity end
        ByteBuffer bytebuffer = ByteBuffer.allocate(5);

        bytebuffer.putInt(1);
        bytebuffer.put((byte) (compressionType.a() | 128)); // Tuinity - replace with compressionType
        ((java.nio.Buffer) bytebuffer).flip();
        return bytebuffer;
    }

    private RegionFile.b a(java.nio.file.Path java_nio_file_path, ByteBuffer bytebuffer) throws IOException {
        java.nio.file.Path java_nio_file_path1 = Files.createTempFile(this.d, "tmp", (String) null);
        FileChannel filechannel = FileChannel.open(java_nio_file_path1, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        Throwable throwable = null;

        try {
            ((java.nio.Buffer) bytebuffer).position(5);
            filechannel.write(bytebuffer);
        } catch (Throwable throwable1) {
            throwable = throwable1;
            com.destroystokyo.paper.exception.ServerInternalException.reportInternalException(throwable); // Paper
            throw throwable1;
        } finally {
            if (filechannel != null) {
                if (throwable != null) {
                    try {
                        filechannel.close();
                    } catch (Throwable throwable2) {
                        throwable.addSuppressed(throwable2);
                    }
                } else {
                    filechannel.close();
                }
            }

        }

        return () -> {
            Files.move(java_nio_file_path1, java_nio_file_path, StandardCopyOption.REPLACE_EXISTING);
        };
    }

    private final void flushHeader() throws IOException { this.b(); } // Tuinity - OBFHELPER
    private void b() throws IOException {
        ((java.nio.Buffer) this.f).position(0);
        this.dataFile.write(this.f, 0L);
    }

    private int getOffset(ChunkCoordIntPair chunkcoordintpair) {
        return this.g.get(g(chunkcoordintpair));
    }

    public boolean chunkExists(ChunkCoordIntPair chunkcoordintpair) {
        return this.getOffset(chunkcoordintpair) != 0;
    }

    private final int getChunkLocation(ChunkCoordIntPair chunkcoordintpair) { return this.g(chunkcoordintpair); } // Paper - OBFHELPER
    private static int g(ChunkCoordIntPair chunkcoordintpair) {
        return chunkcoordintpair.j() + chunkcoordintpair.k() * 32;
    }

    public void close() throws IOException {
        // Paper start - Prevent regionfiles from being closed during use
        this.fileLock.lock();
        synchronized (this) {
        try {
        // Paper end
        this.closed = true; // Paper
        try {
            this.c();
        } finally {
            try {
                this.b();
            } finally {
                try {
                    this.dataFile.force(true);
                } finally {
                    this.dataFile.close();
                }
            }
        }
        } finally { // Paper start - Prevent regionfiles from being closed during use
            this.fileLock.unlock();
        }
        } // Paper end

    }

    private void c() throws IOException {
        int i = (int) this.dataFile.size();
        int j = c(i) * 4096;

        if (i != j) {
            ByteBuffer bytebuffer = RegionFile.b.duplicate();

            ((java.nio.Buffer) bytebuffer).position(0);
            this.dataFile.write(bytebuffer, (long) (j - 1));
        }

    }

    interface b {

        void run() throws IOException;
    }

    private final byte[] oversized = new byte[1024];
    private int oversizedCount = 0;

    private synchronized void initOversizedState() throws IOException {
        File metaFile = getOversizedMetaFile();
        if (metaFile.exists()) {
            final byte[] read = java.nio.file.Files.readAllBytes(metaFile.toPath());
            System.arraycopy(read, 0, oversized, 0, oversized.length);
            for (byte temp : oversized) {
                oversizedCount += temp;
            }
        }
    }

    private static int getChunkIndex(int x, int z) {
        return (x & 31) + (z & 31) * 32;
    }
    synchronized boolean isOversized(int x, int z) {
        return this.oversized[getChunkIndex(x, z)] == 1;
    }
    synchronized void setOversized(int x, int z, boolean oversized) throws IOException {
        final int offset = getChunkIndex(x, z);
        boolean previous = this.oversized[offset] == 1;
        this.oversized[offset] = (byte) (oversized ? 1 : 0);
        if (!previous && oversized) {
            oversizedCount++;
        } else if (!oversized && previous) {
            oversizedCount--;
        }
        if (previous && !oversized) {
            File oversizedFile = getOversizedFile(x, z);
            if (oversizedFile.exists()) {
                oversizedFile.delete();
            }
        }
        if (oversizedCount > 0) {
            if (previous != oversized) {
                writeOversizedMeta();
            }
        } else if (previous) {
            File oversizedMetaFile = getOversizedMetaFile();
            if (oversizedMetaFile.exists()) {
                oversizedMetaFile.delete();
            }
        }
    }

    private void writeOversizedMeta() throws IOException {
        java.nio.file.Files.write(getOversizedMetaFile().toPath(), oversized);
    }

    private File getOversizedMetaFile() {
        return new File(this.file.getParentFile(), this.file.getName().replaceAll("\\.mca$", "") + ".oversized.nbt");
    }

    private File getOversizedFile(int x, int z) {
        return new File(this.file.getParentFile(), this.file.getName().replaceAll("\\.mca$", "") + "_oversized_" + x + "_" + z + ".nbt");
    }

    synchronized NBTTagCompound getOversizedData(int x, int z) throws IOException {
        File file = getOversizedFile(x, z);
        try (DataInputStream out = new DataInputStream(new BufferedInputStream(new InflaterInputStream(new java.io.FileInputStream(file))))) {
            return NBTCompressedStreamTools.readNBT(out);
        }

    }
    // Paper end

    class ChunkBuffer extends ByteArrayOutputStream {

        private final ChunkCoordIntPair b;

        public ChunkBuffer(ChunkCoordIntPair chunkcoordintpair) {
            super(8096);
            super.write(0);
            super.write(0);
            super.write(0);
            super.write(0);
            super.write(RegionFile.this.e.a());
            this.b = chunkcoordintpair;
        }

        public void close() throws IOException {
            ByteBuffer bytebuffer = ByteBuffer.wrap(this.buf, 0, this.count);

            bytebuffer.putInt(0, this.count - 5 + 1);
            RegionFile.this.a(this.b, bytebuffer);
        }
    }
}
