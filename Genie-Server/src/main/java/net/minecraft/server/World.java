package net.minecraft.server;

import co.aikar.timings.Timing;
import co.aikar.timings.Timings;
import com.destroystokyo.paper.antixray.ChunkPacketBlockController; // Paper - Anti-Xray
import com.destroystokyo.paper.antixray.ChunkPacketBlockControllerAntiXray; // Paper - Anti-Xray
import com.destroystokyo.paper.event.server.ServerExceptionEvent;
import com.destroystokyo.paper.exception.ServerInternalException;
import com.google.common.base.MoreObjects;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.util.Supplier;

// CraftBukkit start
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.block.BlockState;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CapturedBlockState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.event.block.BlockPhysicsEvent;
// CraftBukkit end

public abstract class World implements GeneratorAccess, AutoCloseable {

    protected static final Logger LOGGER = LogManager.getLogger();
    private static final EnumDirection[] a = EnumDirection.values();
    //public final List<TileEntity> tileEntityList = Lists.newArrayList(); // Paper - remove unused list
    public final List<TileEntity> tileEntityListTick = Lists.newArrayList();
    protected final List<TileEntity> tileEntityListPending = Lists.newArrayList();
    protected final java.util.Set<TileEntity> tileEntityListUnload = com.google.common.collect.Sets.newHashSet();
    public final Thread serverThread;
    private int c;
    protected int i = (new Random()).nextInt();
    protected final int j = 1013904223;
    protected float lastRainLevel;
    protected float rainLevel;
    protected float lastThunderLevel;
    protected float thunderLevel;
    public final Random random = new Random();
    public WorldProvider worldProvider;
    protected final IChunkProvider chunkProvider;
    public final WorldData worldData;
    private final GameProfilerFiller methodProfiler;
    public final boolean isClientSide;
    protected boolean tickingTileEntities;
    private final WorldBorder worldBorder;
    private final BiomeManager biomeManager;

    // CraftBukkit start Added the following
    private final CraftWorld world;
    public boolean pvpMode;
    public boolean keepSpawnInMemory = true;
    public org.bukkit.generator.ChunkGenerator generator;
    public static final boolean DEBUG_ENTITIES = Boolean.getBoolean("debug.entities"); // Paper

    public boolean captureBlockStates = false;
    public boolean captureTreeGeneration = false;
    public Map<BlockPosition, org.bukkit.craftbukkit.block.CraftBlockState> capturedBlockStates = new HashMap<>(); // Paper
    public Map<BlockPosition, TileEntity> capturedTileEntities = new HashMap<>();
    public List<EntityItem> captureDrops;
    public long ticksPerAnimalSpawns;
    public long ticksPerMonsterSpawns;
    public long ticksPerWaterSpawns;
    public long ticksPerAmbientSpawns;
    public boolean populating;
    public final org.spigotmc.SpigotWorldConfig spigotConfig; // Spigot

    public final com.destroystokyo.paper.PaperWorldConfig paperConfig; // Paper
    public final ChunkPacketBlockController chunkPacketBlockController; // Paper - Anti-Xray

    public final com.tuinity.tuinity.config.TuinityConfig.WorldConnfig tuinityConfig; // Tuinity - Server Config

    public final co.aikar.timings.WorldTimingsHandler timings; // Paper
    public static BlockPosition lastPhysicsProblem; // Spigot
    private org.spigotmc.TickLimiter entityLimiter;
    private org.spigotmc.TickLimiter tileLimiter;
    private int tileTickPosition;
    public final Map<Explosion.CacheKey, Float> explosionDensityCache = new HashMap<>(); // Paper - Optimize explosions
    public java.util.ArrayDeque<BlockRedstoneTorch.RedstoneUpdateInfo> redstoneUpdateInfos; // Paper - Move from Map in BlockRedstoneTorch to here
    // Paper start - yes this is hacky as shit
    RegionLimitedWorldAccess regionLimited;
    World originalWorld;
    public World regionLimited(RegionLimitedWorldAccess limitedWorldAccess) {
        try {
            World clone = (World) super.clone();
            clone.regionLimited = limitedWorldAccess;
            clone.originalWorld = this;
            return clone;
        } catch (CloneNotSupportedException e1) {
        }
        return null;
    }
    ChunkCoordIntPair[] strongholdCoords;
    List<StructureStart> strongholdStuctures = Lists.newArrayList();
    final java.lang.Object stuctureLock = new Object();
    // Paper end

    public CraftWorld getWorld() {
        return this.world;
    }

    public CraftServer getServer() {
        return (CraftServer) Bukkit.getServer();
    }

    public Chunk getChunkIfLoaded(int x, int z) {
        return ((ChunkProviderServer) this.chunkProvider).getChunkAtIfLoadedImmediately(x, z); // Paper
    }
    // Paper start
    @Override
    public boolean isChunkLoaded(int x, int z) {
        return getChunkIfLoaded(x, z) != null;
    }


    // Paper end

    protected World(WorldData worlddata, DimensionManager dimensionmanager, BiFunction<World, WorldProvider, IChunkProvider> bifunction, GameProfilerFiller gameprofilerfiller, boolean flag, org.bukkit.generator.ChunkGenerator gen, org.bukkit.World.Environment env) {
        this.spigotConfig = new org.spigotmc.SpigotWorldConfig( worlddata.getName() ); // Spigot
        this.paperConfig = new com.destroystokyo.paper.PaperWorldConfig(worlddata.getName(), this.spigotConfig); // Paper
        this.chunkPacketBlockController = this.paperConfig.antiXray ? new ChunkPacketBlockControllerAntiXray(this.paperConfig) : ChunkPacketBlockController.NO_OPERATION_INSTANCE; // Paper - Anti-Xray
        this.tuinityConfig = new com.tuinity.tuinity.config.TuinityConfig.WorldConnfig(worlddata.getName()); // Tuinity - Server Config
        this.generator = gen;
        this.world = new CraftWorld((WorldServer) this, gen, env);
        this.ticksPerAnimalSpawns = this.getServer().getTicksPerAnimalSpawns(); // CraftBukkit
        this.ticksPerMonsterSpawns = this.getServer().getTicksPerMonsterSpawns(); // CraftBukkit
        this.ticksPerWaterSpawns = this.getServer().getTicksPerWaterSpawns(); // CraftBukkit
        this.ticksPerAmbientSpawns = this.getServer().getTicksPerAmbientSpawns(); // CraftBukkit
        // CraftBukkit end
        this.methodProfiler = gameprofilerfiller;
        this.worldData = worlddata;
        this.worldProvider = dimensionmanager.getWorldProvider(this);
        this.chunkProvider = (IChunkProvider) bifunction.apply(this, this.worldProvider);
        this.isClientSide = flag;
        this.worldBorder = this.worldProvider.getWorldBorder();
        this.serverThread = Thread.currentThread();
        this.biomeManager = new BiomeManager(this, flag ? worlddata.getSeed() : WorldData.c(worlddata.getSeed()), dimensionmanager.getGenLayerZoomer());
        // CraftBukkit start
        getWorldBorder().world = (WorldServer) this;
        // From PlayerList.setPlayerFileData
        getWorldBorder().a(new IWorldBorderListener() {
            public void a(WorldBorder worldborder, double d0) {
                getServer().getHandle().sendAll(new PacketPlayOutWorldBorder(worldborder, PacketPlayOutWorldBorder.EnumWorldBorderAction.SET_SIZE), worldborder.world);
            }

            public void a(WorldBorder worldborder, double d0, double d1, long i) {
                getServer().getHandle().sendAll(new PacketPlayOutWorldBorder(worldborder, PacketPlayOutWorldBorder.EnumWorldBorderAction.LERP_SIZE), worldborder.world);
            }

            public void a(WorldBorder worldborder, double d0, double d1) {
                getServer().getHandle().sendAll(new PacketPlayOutWorldBorder(worldborder, PacketPlayOutWorldBorder.EnumWorldBorderAction.SET_CENTER), worldborder.world);
            }

            public void a(WorldBorder worldborder, int i) {
                getServer().getHandle().sendAll(new PacketPlayOutWorldBorder(worldborder, PacketPlayOutWorldBorder.EnumWorldBorderAction.SET_WARNING_TIME), worldborder.world);
            }

            public void b(WorldBorder worldborder, int i) {
                getServer().getHandle().sendAll(new PacketPlayOutWorldBorder(worldborder, PacketPlayOutWorldBorder.EnumWorldBorderAction.SET_WARNING_BLOCKS), worldborder.world);
            }

            public void b(WorldBorder worldborder, double d0) {}

            public void c(WorldBorder worldborder, double d0) {}
        });
        // CraftBukkit end
        timings = new co.aikar.timings.WorldTimingsHandler(this); // Paper - code below can generate new world and access timings
        this.keepSpawnInMemory = this.paperConfig.keepSpawnInMemory; // Paper
        this.entityLimiter = new org.spigotmc.TickLimiter(spigotConfig.entityMaxTickTime);
        this.tileLimiter = new org.spigotmc.TickLimiter(spigotConfig.tileMaxTickTime);
    }

    // Paper start
    // ret true if no collision
    public final boolean checkEntityCollision(IBlockData data, Entity source, VoxelShapeCollision voxelshapedcollision,
                                              BlockPosition position, boolean checkCanSee) {
        // Copied from IWorldReader#a(IBlockData, BlockPosition, VoxelShapeCollision) & EntityAccess#a(Entity, VoxelShape)
        VoxelShape voxelshape = data.getCollisionShape(this, position, voxelshapedcollision);
        if (voxelshape.isEmpty()) {
            return true;
        }

        voxelshape = voxelshape.offset((double) position.getX(), (double) position.getY(), (double) position.getZ());
        if (voxelshape.isEmpty()) {
            return true;
        }

        List<Entity> entities = this.getEntities(null, voxelshape.getBoundingBox());
        for (int i = 0, len = entities.size(); i < len; ++i) {
            Entity entity = entities.get(i);

            if (checkCanSee && source instanceof EntityPlayer && entity instanceof EntityPlayer
                && !((EntityPlayer) source).getBukkitEntity().canSee(((EntityPlayer) entity).getBukkitEntity())) {
                continue;
            }

            // !entity1.dead && entity1.i && (entity == null || !entity1.x(entity));
            // elide the last check since vanilla calls with entity = null
            // only we care about the source for the canSee check
            if (entity.dead || !entity.blocksEntitySpawning()) {
                continue;
            }

            if (VoxelShapes.applyOperation(voxelshape, VoxelShapes.of(entity.getBoundingBox()), OperatorBoolean.AND)) {
                return false;
            }
        }

        return true;
    }
    // Paper end

    @Override
    public boolean p_() {
        return this.isClientSide;
    }

    @Nullable
    public MinecraftServer getMinecraftServer() {
        return null;
    }

    public IBlockData i(BlockPosition blockposition) {
        BlockPosition blockposition1;

        for (blockposition1 = new BlockPosition(blockposition.getX(), this.getSeaLevel(), blockposition.getZ()); !this.isEmpty(blockposition1.up()); blockposition1 = blockposition1.up()) {
            ;
        }

        return this.getType(blockposition1);
    }

    public static boolean isValidLocation(BlockPosition blockposition) {
        return blockposition.isValidLocation();
    }

    public static boolean isOutsideWorld(BlockPosition blockposition) {
        return blockposition.isInvalidYLocation();
    }

    public static boolean b(int i) {
        return i < 0 || i >= 256;
    }

    public Chunk getChunkAtWorldCoords(BlockPosition blockposition) {
        return this.getChunkAt(blockposition.getX() >> 4, blockposition.getZ() >> 4);
    }

    @Override
    public Chunk getChunkAt(int i, int j) {
        // Paper start - optimise this for loaded chunks
        if (Thread.currentThread() == this.serverThread) {
            Chunk ifLoaded = ((WorldServer) this).getChunkProvider().getChunkAtIfLoadedMainThread(i, j);
            if (ifLoaded != null) {
                return ifLoaded;
            }
        }
        // Paper end
        return (Chunk) this.getChunkAt(i, j, ChunkStatus.FULL);
    }

    // Paper start - if loaded
    @Nullable
    @Override
    public IChunkAccess getChunkIfLoadedImmediately(int x, int z) {
        return ((ChunkProviderServer)this.chunkProvider).getChunkAtIfLoadedImmediately(x, z);
    }

    @Override
    public IBlockData getTypeIfLoaded(BlockPosition blockposition) {
        // CraftBukkit start - tree generation
        if (captureTreeGeneration) {
            CraftBlockState previous = capturedBlockStates.get(blockposition);
            if (previous != null) {
                return previous.getHandle();
            }
        }
        // CraftBukkit end
        if (!isValidLocation(blockposition)) {
            return Blocks.AIR.getBlockData();
        }
        IChunkAccess chunk = this.getChunkIfLoadedImmediately(blockposition.getX() >> 4, blockposition.getZ() >> 4);

        return chunk == null ? null : chunk.getType(blockposition);
    }

    @Override
    public Fluid getFluidIfLoaded(BlockPosition blockposition) {
        IChunkAccess chunk = this.getChunkIfLoadedImmediately(blockposition.getX() >> 4, blockposition.getZ() >> 4);

        return chunk == null ? null : chunk.getFluid(blockposition);
    }

    public boolean isLoaded(BlockPosition blockposition) {
        return getChunkIfLoaded(blockposition.getX() >> 4, blockposition.getZ() >> 4) != null; // Paper
    }


    public boolean isLoadedAndInBounds(BlockPosition blockposition) {
        return getWorldBorder().isInBounds(blockposition) && getChunkIfLoadedImmediately(blockposition.getX() >> 4, blockposition.getZ() >> 4) != null;
    }
    public Chunk getChunkIfLoaded(BlockPosition blockposition) {
        return getChunkIfLoaded(blockposition.getX() >> 4, blockposition.getZ() >> 4);
    }

    //  reduces need to do isLoaded before getType
    public IBlockData getTypeIfLoadedAndInBounds(BlockPosition blockposition) {
        return getWorldBorder().isInBounds(blockposition) ? getTypeIfLoaded(blockposition) : null;
    }
    // Paper end

    @Override
    public IChunkAccess getChunkAt(int i, int j, ChunkStatus chunkstatus, boolean flag) {
        IChunkAccess ichunkaccess = this.chunkProvider.getChunkAt(i, j, chunkstatus, flag);

        if (ichunkaccess == null && flag) {
            throw new IllegalStateException("Should always be able to create a chunk!");
        } else {
            return ichunkaccess;
        }
    }

    @Override
    public boolean setTypeAndData(BlockPosition blockposition, IBlockData iblockdata, int i) {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async set type call"); // Tuinity
        // CraftBukkit start - tree generation
        if (this.captureTreeGeneration) {
            CraftBlockState blockstate = capturedBlockStates.get(blockposition);
            if (blockstate == null) {
                blockstate = CapturedBlockState.getTreeBlockState(this, blockposition, i);
                this.capturedBlockStates.put(blockposition.immutableCopy(), blockstate);
            }
            blockstate.setData(iblockdata);
            return true;
        }
        // CraftBukkit end
        if (isOutsideWorld(blockposition)) {
            return false;
        } else if (!this.isClientSide && this.worldData.getType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            return false;
        } else {
            Chunk chunk = this.getChunkAtWorldCoords(blockposition);
            Block block = iblockdata.getBlock();

            // CraftBukkit start - capture blockstates
            boolean capturedBlockState = false; //Paper - fix blockstate capture undoing
            if (this.captureBlockStates && !this.capturedBlockStates.containsKey(blockposition)) {
                capturedBlockState = true; //Paper - fix blockstate capture undoing
                CraftBlockState blockstate = (CraftBlockState) world.getBlockAt(blockposition.getX(), blockposition.getY(), blockposition.getZ()).getState(); // Paper - use CB getState to get a suitable snapshot
                this.capturedBlockStates.put(blockposition.immutableCopy(), blockstate);
            }
            // CraftBukkit end

            IBlockData iblockdata1 = chunk.setType(blockposition, iblockdata, (i & 64) != 0, (i & 1024) == 0); // CraftBukkit custom NO_PLACE flag
            this.chunkPacketBlockController.onBlockChange(this, blockposition, iblockdata, iblockdata1, i); // Paper - Anti-Xray

            if (iblockdata1 == null) {
                // CraftBukkit start - remove blockstate if failed
                if (/*this.captureBlockStates*/ capturedBlockState) { //Paper - fix blockstate capture undoing
                    this.capturedBlockStates.remove(blockposition);
                }
                // CraftBukkit end
                return false;
            } else {
                IBlockData iblockdata2 = this.getType(blockposition);

                if (iblockdata2 != iblockdata1 && (iblockdata2.b((IBlockAccess) this, blockposition) != iblockdata1.b((IBlockAccess) this, blockposition) || iblockdata2.h() != iblockdata1.h() || iblockdata2.g() || iblockdata1.g())) {
                    this.methodProfiler.enter("queueCheckLight");
                    this.getChunkProvider().getLightEngine().a(blockposition);
                    this.methodProfiler.exit();
                }

                /*
                if (iblockdata2 == iblockdata) {
                    if (iblockdata1 != iblockdata2) {
                        this.b(blockposition, iblockdata1, iblockdata2);
                    }

                    if ((i & 2) != 0 && (!this.isClientSide || (i & 4) == 0) && (this.isClientSide || chunk.getState() != null && chunk.getState().isAtLeast(PlayerChunk.State.TICKING))) {
                        this.notify(blockposition, iblockdata1, iblockdata, i);
                    }

                    if (!this.isClientSide && (i & 1) != 0) {
                        this.update(blockposition, iblockdata1.getBlock());
                        if (iblockdata.isComplexRedstone()) {
                            this.updateAdjacentComparators(blockposition, block);
                        }
                    }

                    if ((i & 16) == 0) {
                        int j = i & -2;

                        iblockdata1.b(this, blockposition, j);
                        iblockdata.a(this, blockposition, j);
                        iblockdata.b(this, blockposition, j);
                    }

                    this.a(blockposition, iblockdata1, iblockdata2);
                }
                */

                // CraftBukkit start
                if (!this.captureBlockStates) { // Don't notify clients or update physics while capturing blockstates
                    // Modularize client and physic updates
                    // Spigot start
                    try {
                        notifyAndUpdatePhysics(blockposition, chunk, iblockdata1, iblockdata, iblockdata2, i);
                    } catch (StackOverflowError ex) {
                        lastPhysicsProblem = new BlockPosition(blockposition);
                    }
                    // Spigot end
                }
                // CraftBukkit end

                return true;
            }
        }
    }

    // CraftBukkit start - Split off from above in order to directly send client and physic updates
    public void notifyAndUpdatePhysics(BlockPosition blockposition, Chunk chunk, IBlockData oldBlock, IBlockData newBlock, IBlockData actualBlock, int i) {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async notify and update"); // Tuinity
        IBlockData iblockdata = newBlock;
        IBlockData iblockdata1 = oldBlock;
        IBlockData iblockdata2 = actualBlock;
        if (iblockdata2 == iblockdata) {
            if (iblockdata1 != iblockdata2) {
                this.b(blockposition, iblockdata1, iblockdata2);
            }

            if ((i & 2) != 0 && (!this.isClientSide || (i & 4) == 0) && (this.isClientSide || chunk == null || (chunk.getState() != null && chunk.getState().isAtLeast(PlayerChunk.State.TICKING)))) { // allow chunk to be null here as chunk.isReady() is false when we send our notification during block placement // Tuinity - diff on change, see below
                this.notify(blockposition, iblockdata1, iblockdata, i);
                // Tuinity start - per player view distance - allow block updates for non-ticking chunks in player view distance
                // if copied from above
            } else if ((i & 2) != 0 && (!this.isClientSide || (i & 4) == 0) && (this.isClientSide || chunk == null || ((WorldServer)this).getChunkProvider().playerChunkMap.playerViewDistanceBroadcastMap.getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(blockposition)) != null)) {
                ((WorldServer)this).getChunkProvider().flagDirty(blockposition);
                // Tuinity end - per player view distance
            }

            if (!this.isClientSide && (i & 1) != 0) {
                this.update(blockposition, iblockdata1.getBlock());
                if (iblockdata.isComplexRedstone()) {
                    this.updateAdjacentComparators(blockposition, newBlock.getBlock());
                }
            }

            if ((i & 16) == 0) {
                int j = i & -2;

                // CraftBukkit start
                iblockdata1.b(this, blockposition, j); // Don't call an event for the old block to limit event spam
                CraftWorld world = ((WorldServer) this).getWorld();
                if (world != null && ((WorldServer)this).hasPhysicsEvent) { // Paper
                    BlockPhysicsEvent event = new BlockPhysicsEvent(world.getBlockAt(blockposition.getX(), blockposition.getY(), blockposition.getZ()), CraftBlockData.fromData(iblockdata));
                    this.getServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return;
                    }
                }
                // CraftBukkit end
                iblockdata.a(this, blockposition, j);
                iblockdata.b(this, blockposition, j);
            }

            this.a(blockposition, iblockdata1, iblockdata2);
        }
    }
    // CraftBukkit end

    public void a(BlockPosition blockposition, IBlockData iblockdata, IBlockData iblockdata1) {}

    public boolean setAir(BlockPosition blockposition) { return this.a(blockposition, false); } // Paper - OBFHELPER
    public boolean setAir(BlockPosition blockposition, boolean moved) { return this.a(blockposition, moved); } // Paper - OBFHELPER
    @Override public boolean a(BlockPosition blockposition, boolean flag) { // Paper - OBFHELPER
        Fluid fluid = this.getFluid(blockposition);

        return this.setTypeAndData(blockposition, fluid.getBlockData(), 3 | (flag ? 64 : 0));
    }

    @Override
    public boolean a(BlockPosition blockposition, boolean flag, @Nullable Entity entity) {
        IBlockData iblockdata = this.getType(blockposition);

        if (iblockdata.isAir()) {
            return false;
        } else {
            Fluid fluid = this.getFluid(blockposition);
            // Paper start - while the above setAir method is named same and looks very similar
            // they are NOT used with same intent and the above should not fire this event. The above method is more of a BlockSetToAirEvent,
            // it doesn't imply destruction of a block that plays a sound effect / drops an item.
            boolean playEffect = true;
            if (com.destroystokyo.paper.event.block.BlockDestroyEvent.getHandlerList().getRegisteredListeners().length > 0) {
                com.destroystokyo.paper.event.block.BlockDestroyEvent event = new com.destroystokyo.paper.event.block.BlockDestroyEvent(MCUtil.toBukkitBlock(this, blockposition), fluid.getBlockData().createCraftBlockData(), flag);
                if (!event.callEvent()) {
                    return false;
                }
                playEffect = event.playEffect();
            }
            // Paper end

            if (playEffect) this.triggerEffect(2001, blockposition, Block.getCombinedId(iblockdata)); // Paper
            if (flag) {
                TileEntity tileentity = iblockdata.getBlock().isTileEntity() ? this.getTileEntity(blockposition) : null;

                Block.dropItems(iblockdata, this, blockposition, tileentity, entity, ItemStack.a);
            }

            return this.setTypeAndData(blockposition, fluid.getBlockData(), 3);
        }
    }

    public boolean setTypeUpdate(BlockPosition blockposition, IBlockData iblockdata) {
        return this.setTypeAndData(blockposition, iblockdata, 3);
    }

    public abstract void notify(BlockPosition blockposition, IBlockData iblockdata, IBlockData iblockdata1, int i);

    @Override
    public void update(BlockPosition blockposition, Block block) {
        if (this.worldData.getType() != WorldType.DEBUG_ALL_BLOCK_STATES) {
            // CraftBukkit start
            if (populating) {
                return;
            }
            // CraftBukkit end
            this.applyPhysics(blockposition, block);
        }

    }

    public void b(BlockPosition blockposition, IBlockData iblockdata, IBlockData iblockdata1) {}

    public void applyPhysics(BlockPosition blockposition, Block block) {
        if (captureBlockStates) { return; } // Paper - Cancel all physics during placement
        this.a(blockposition.west(), block, blockposition);
        this.a(blockposition.east(), block, blockposition);
        this.a(blockposition.down(), block, blockposition);
        this.a(blockposition.up(), block, blockposition);
        this.a(blockposition.north(), block, blockposition);
        this.a(blockposition.south(), block, blockposition);
    }

    public void a(BlockPosition blockposition, Block block, EnumDirection enumdirection) {
        if (enumdirection != EnumDirection.WEST) {
            this.a(blockposition.west(), block, blockposition);
        }

        if (enumdirection != EnumDirection.EAST) {
            this.a(blockposition.east(), block, blockposition);
        }

        if (enumdirection != EnumDirection.DOWN) {
            this.a(blockposition.down(), block, blockposition);
        }

        if (enumdirection != EnumDirection.UP) {
            this.a(blockposition.up(), block, blockposition);
        }

        if (enumdirection != EnumDirection.NORTH) {
            this.a(blockposition.north(), block, blockposition);
        }

        if (enumdirection != EnumDirection.SOUTH) {
            this.a(blockposition.south(), block, blockposition);
        }

    }

    public void neighborChanged(BlockPosition pos, Block blockIn, BlockPosition fromPos) { a(pos, blockIn, fromPos); } // Paper - OBFHELPER
    public void a(BlockPosition blockposition, Block block, BlockPosition blockposition1) {
        if (!this.isClientSide) {
            IBlockData iblockdata = this.getType(blockposition);

            try {
                // CraftBukkit start
                CraftWorld world = ((WorldServer) this).getWorld();
                if (world != null && ((WorldServer)this).hasPhysicsEvent) { // Paper
                    BlockPhysicsEvent event = new BlockPhysicsEvent(world.getBlockAt(blockposition.getX(), blockposition.getY(), blockposition.getZ()), CraftBlockData.fromData(iblockdata), world.getBlockAt(blockposition1.getX(), blockposition1.getY(), blockposition1.getZ()));
                    this.getServer().getPluginManager().callEvent(event);

                    if (event.isCancelled()) {
                        return;
                    }
                }
                // CraftBukkit end
                iblockdata.doPhysics(this, blockposition, block, blockposition1, false);
            // Spigot Start
            } catch (StackOverflowError ex) {
                lastPhysicsProblem = new BlockPosition(blockposition);
                // Spigot End
            } catch (Throwable throwable) {
                CrashReport crashreport = CrashReport.a(throwable, "Exception while updating neighbours");
                CrashReportSystemDetails crashreportsystemdetails = crashreport.a("Block being updated");

                crashreportsystemdetails.a("Source block type", () -> {
                    try {
                        return String.format("ID #%s (%s // %s)", IRegistry.BLOCK.getKey(block), block.k(), block.getClass().getCanonicalName());
                    } catch (Throwable throwable1) {
                        return "ID #" + IRegistry.BLOCK.getKey(block);
                    }
                });
                CrashReportSystemDetails.a(crashreportsystemdetails, blockposition, iblockdata);
                throw new ReportedException(crashreport);
            }
        }
    }

    public final int getHighestBlockY(final HeightMap.Type heightmap, final int x, final int z) { return this.a(heightmap, x, z); } // Paper - OBFHELPER
    @Override public int a(HeightMap.Type heightmap_type, int i, int j) { // Paper - OBFHELPER
        int k;

        if (i >= -30000000 && j >= -30000000 && i < 30000000 && j < 30000000) {
            if (this.isChunkLoaded(i >> 4, j >> 4)) {
                k = this.getChunkAt(i >> 4, j >> 4).a(heightmap_type, i & 15, j & 15) + 1;
            } else {
                k = 0;
            }
        } else {
            k = this.getSeaLevel() + 1;
        }

        return k;
    }

    @Override
    public LightEngine e() {
        return this.getChunkProvider().getLightEngine();
    }

    @Override
    public IBlockData getType(BlockPosition blockposition) {
        // CraftBukkit start - tree generation
        if (captureTreeGeneration) {
            CraftBlockState previous = capturedBlockStates.get(blockposition); // Paper
            if (previous != null) {
                return previous.getHandle();
            }
        }
        // CraftBukkit end
        if (isOutsideWorld(blockposition)) {
            return Blocks.VOID_AIR.getBlockData();
        } else {
            Chunk chunk = this.getChunkAt(blockposition.getX() >> 4, blockposition.getZ() >> 4);

            return chunk.getType(blockposition);
        }
    }

    @Override
    public Fluid getFluid(BlockPosition blockposition) {
        if (isOutsideWorld(blockposition)) {
            return FluidTypes.EMPTY.h();
        } else {
            Chunk chunk = this.getChunkAtWorldCoords(blockposition);

            return chunk.getFluid(blockposition);
        }
    }

    public boolean isDay() {
        return this.worldProvider.getDimensionManager().getType() == DimensionManager.OVERWORLD && this.c < 4; // CraftBukkit
    }

    public boolean isNight() {
        return this.worldProvider.getDimensionManager().getType() == DimensionManager.OVERWORLD && !this.isDay(); // CraftBukkit
    }

    @Override
    public void playSound(@Nullable EntityHuman entityhuman, BlockPosition blockposition, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1) {
        this.playSound(entityhuman, (double) blockposition.getX() + 0.5D, (double) blockposition.getY() + 0.5D, (double) blockposition.getZ() + 0.5D, soundeffect, soundcategory, f, f1);
    }

    // Paper start - OBFHELPER
    public final void sendSoundEffect(@Nullable EntityHuman fromEntity, double x, double y, double z, SoundEffect soundeffect, SoundCategory soundcategory, float volume, float pitch) {
        this.playSound(fromEntity, x, y, z, soundeffect, soundcategory, volume, pitch);
    }
    // Paper end
    public abstract void playSound(@Nullable EntityHuman entityhuman, double d0, double d1, double d2, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1);

    public abstract void playSound(@Nullable EntityHuman entityhuman, Entity entity, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1);

    public void a(double d0, double d1, double d2, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1, boolean flag) {}

    @Override
    public void addParticle(ParticleParam particleparam, double d0, double d1, double d2, double d3, double d4, double d5) {}

    public void b(ParticleParam particleparam, double d0, double d1, double d2, double d3, double d4, double d5) {}

    public void b(ParticleParam particleparam, boolean flag, double d0, double d1, double d2, double d3, double d4, double d5) {}

    public float a(float f) {
        float f1 = this.f(f);

        return f1 * 6.2831855F;
    }

    public boolean a(TileEntity tileentity) {
        if (this.tickingTileEntities) {
            World.LOGGER.error("Adding block entity while ticking: {} @ {}", new Supplier[]{() -> {
                        return IRegistry.BLOCK_ENTITY_TYPE.getKey(tileentity.getTileType());
                    }, tileentity::getPosition});
        }

        boolean flag = true; // Paper - remove unused list

        if (flag && tileentity instanceof ITickable && !this.tileEntityListTick.contains(tileentity)) { // Paper
            this.tileEntityListTick.add(tileentity);
        }

        if (this.isClientSide) {
            BlockPosition blockposition = tileentity.getPosition();
            IBlockData iblockdata = this.getType(blockposition);

            this.notify(blockposition, iblockdata, iblockdata, 2);
        }

        return flag;
    }

    public void a(Collection<TileEntity> collection) {
        if (this.tickingTileEntities) {
            this.tileEntityListPending.addAll(collection);
        } else {
            Iterator iterator = collection.iterator();

            while (iterator.hasNext()) {
                TileEntity tileentity = (TileEntity) iterator.next();

                this.a(tileentity);
            }
        }

    }

    public void tickBlockEntities() {
        GameProfilerFiller gameprofilerfiller = this.getMethodProfiler();

        gameprofilerfiller.enter("blockEntities");
        timings.tileEntityTick.startTiming(); // Spigot
        if (!this.tileEntityListUnload.isEmpty()) {
            // Paper start - Use alternate implementation with faster contains
            java.util.Set<TileEntity> toRemove = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            toRemove.addAll(tileEntityListUnload);
            this.tileEntityListTick.removeAll(toRemove);
            // Paper end
            //this.tileEntityList.removeAll(this.tileEntityListUnload); // Paper - remove unused list
            this.tileEntityListUnload.clear();
        }

        this.tickingTileEntities = true;
        // Spigot start
        // Iterator iterator = this.tileEntityListTick.iterator();
        int tilesThisCycle = 0;
        for (tileTickPosition = 0; tileTickPosition < tileEntityListTick.size(); tileTickPosition++) { // Paper - Disable tick limiters
            tileTickPosition = (tileTickPosition < tileEntityListTick.size()) ? tileTickPosition : 0;
            TileEntity tileentity = (TileEntity) this.tileEntityListTick.get(tileTickPosition);
            // Spigot start
            if (tileentity == null) {
                getServer().getLogger().severe("Spigot has detected a null entity and has removed it, preventing a crash");
                tilesThisCycle--;
                this.tileEntityListTick.remove(tileTickPosition--);
                continue;
            }
            // Spigot end

            if (!tileentity.isRemoved() && tileentity.hasWorld()) {
                BlockPosition blockposition = tileentity.getPosition();

                if (this.chunkProvider.a(blockposition) && this.getWorldBorder().a(blockposition)) {
                    try {
                        gameprofilerfiller.a(() -> {
                            return String.valueOf(TileEntityTypes.a(tileentity.getTileType()));
                        });
                        tileentity.tickTimer.startTiming(); // Spigot
                        if (tileentity.getTileType().isValidBlock(this.getType(blockposition).getBlock())) {
                            ((ITickable) tileentity).tick();
                        } else {
                            tileentity.v();
                        }

                        gameprofilerfiller.exit();
                    } catch (Throwable throwable) {
                        // Paper start - Prevent tile entity and entity crashes
                        String msg = "TileEntity threw exception at " + tileentity.world.getWorld().getName() + ":" + tileentity.position.getX() + "," + tileentity.position.getY() + "," + tileentity.position.getZ();
                        System.err.println(msg);
                        throwable.printStackTrace();
                        getServer().getPluginManager().callEvent(new ServerExceptionEvent(new ServerInternalException(msg, throwable)));
                        // Paper end
                        tilesThisCycle--;
                        this.tileEntityListTick.remove(tileTickPosition--);
                        continue;
                        // Paper end
                    }
                    // Spigot start
                    finally {
                        tileentity.tickTimer.stopTiming();
                    }
                    // Spigot end
                }
            }

            if (tileentity.isRemoved()) {
                // Spigot start
                tilesThisCycle--;
                this.tileEntityListTick.remove(tileTickPosition--);
                // Spigot end
                //this.tileEntityList.remove(tileentity); // Paper - remove unused list
                if (this.isLoaded(tileentity.getPosition())) {
                    this.getChunkAtWorldCoords(tileentity.getPosition()).removeTileEntity(tileentity.getPosition());
                }
            }
        }

        timings.tileEntityTick.stopTiming(); // Spigot
        timings.tileEntityPending.startTiming(); // Spigot
        this.tickingTileEntities = false;
        gameprofilerfiller.exitEnter("pendingBlockEntities");
        if (!this.tileEntityListPending.isEmpty()) {
            for (int i = 0; i < this.tileEntityListPending.size(); ++i) {
                TileEntity tileentity1 = (TileEntity) this.tileEntityListPending.get(i);

                if (!tileentity1.isRemoved()) {
                    /* CraftBukkit start - Order matters, moved down
                    if (!this.tileEntityList.contains(tileentity1)) {
                        this.a(tileentity1);
                    }
                    // CraftBukkit end */

                    if (this.isLoaded(tileentity1.getPosition())) {
                        Chunk chunk = this.getChunkAtWorldCoords(tileentity1.getPosition());
                        IBlockData iblockdata = chunk.getType(tileentity1.getPosition());

                        chunk.setTileEntity(tileentity1.getPosition(), tileentity1);
                        this.notify(tileentity1.getPosition(), iblockdata, iblockdata, 3);
                        // CraftBukkit start
                        // From above, don't screw this up - SPIGOT-1746
                        if (true) { // Paper - remove unused list
                            this.a(tileentity1);
                        }
                        // CraftBukkit end
                    }
                }
            }

            this.tileEntityListPending.clear();
        }

        timings.tileEntityPending.stopTiming(); // Spigot
        co.aikar.timings.TimingHistory.tileEntityTicks += this.tileEntityListTick.size(); // Paper
        gameprofilerfiller.exit();
        spigotConfig.currentPrimedTnt = 0; // Spigot
    }

    public void a(Consumer<Entity> consumer, Entity entity) {
        try {
            consumer.accept(entity);
        } catch (Throwable throwable) {
            // Paper start - Prevent tile entity and entity crashes
            String msg = "Entity threw exception at " + entity.world.getWorld().getName() + ":" + entity.locX() + "," + entity.locY() + "," + entity.locZ();
            System.err.println(msg);
            throwable.printStackTrace();
            getServer().getPluginManager().callEvent(new ServerExceptionEvent(new ServerInternalException(msg, throwable)));
            entity.dead = true;
            return;
            // Paper end
        }
    }

    // Paper start - Prevent armor stands from doing entity lookups
    @Override
    public boolean getCubes(@Nullable Entity entity, AxisAlignedBB axisAlignedBB) {
        if (entity instanceof EntityArmorStand && !entity.world.paperConfig.armorStandEntityLookups) return false;
        return GeneratorAccess.super.getCubes(entity, axisAlignedBB);
    }
    // Paper end

    public boolean b(AxisAlignedBB axisalignedbb) {
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.f(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.minY);
        int l = MathHelper.f(axisalignedbb.maxY);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.f(axisalignedbb.maxZ);
        BlockPosition.PooledBlockPosition blockposition_pooledblockposition = BlockPosition.PooledBlockPosition.r();
        Throwable throwable = null;

        try {
            for (int k1 = i; k1 < j; ++k1) {
                for (int l1 = k; l1 < l; ++l1) {
                    for (int i2 = i1; i2 < j1; ++i2) {
                        IBlockData iblockdata = this.getType(blockposition_pooledblockposition.d(k1, l1, i2));

                        if (!iblockdata.isAir()) {
                            boolean flag = true;

                            return flag;
                        }
                    }
                }
            }

            return false;
        } catch (Throwable throwable1) {
            throwable = throwable1;
            throw throwable1;
        } finally {
            if (blockposition_pooledblockposition != null) {
                if (throwable != null) {
                    try {
                        blockposition_pooledblockposition.close();
                    } catch (Throwable throwable2) {
                        throwable.addSuppressed(throwable2);
                    }
                } else {
                    blockposition_pooledblockposition.close();
                }
            }

        }
    }

    public boolean c(AxisAlignedBB axisalignedbb) {
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.f(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.minY);
        int l = MathHelper.f(axisalignedbb.maxY);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.f(axisalignedbb.maxZ);

        if (this.isAreaLoaded(i, k, i1, j, l, j1)) {
            BlockPosition.PooledBlockPosition blockposition_pooledblockposition = BlockPosition.PooledBlockPosition.r();
            Throwable throwable = null;

            try {
                for (int k1 = i; k1 < j; ++k1) {
                    for (int l1 = k; l1 < l; ++l1) {
                        for (int i2 = i1; i2 < j1; ++i2) {
                            Block block = this.getType(blockposition_pooledblockposition.d(k1, l1, i2)).getBlock();

                            if (block == Blocks.FIRE || block == Blocks.LAVA) {
                                boolean flag = true;

                                return flag;
                            }
                        }
                    }
                }

                return false;
            } catch (Throwable throwable1) {
                throwable = throwable1;
                throw throwable1;
            } finally {
                if (blockposition_pooledblockposition != null) {
                    if (throwable != null) {
                        try {
                            blockposition_pooledblockposition.close();
                        } catch (Throwable throwable2) {
                            throwable.addSuppressed(throwable2);
                        }
                    } else {
                        blockposition_pooledblockposition.close();
                    }
                }

            }
        } else {
            return false;
        }
    }

    public boolean a(AxisAlignedBB axisalignedbb, Material material) {
        int i = MathHelper.floor(axisalignedbb.minX);
        int j = MathHelper.f(axisalignedbb.maxX);
        int k = MathHelper.floor(axisalignedbb.minY);
        int l = MathHelper.f(axisalignedbb.maxY);
        int i1 = MathHelper.floor(axisalignedbb.minZ);
        int j1 = MathHelper.f(axisalignedbb.maxZ);
        MaterialPredicate materialpredicate = MaterialPredicate.a(material);

        return BlockPosition.a(i, k, i1, j - 1, l - 1, j1 - 1).anyMatch((blockposition) -> {
            return materialpredicate.test(this.getType(blockposition));
        });
    }

    public Explosion explode(@Nullable Entity entity, double d0, double d1, double d2, float f, Explosion.Effect explosion_effect) {
        return this.createExplosion(entity, (DamageSource) null, d0, d1, d2, f, false, explosion_effect);
    }

    public Explosion createExplosion(@Nullable Entity entity, double d0, double d1, double d2, float f, boolean flag, Explosion.Effect explosion_effect) {
        return this.createExplosion(entity, (DamageSource) null, d0, d1, d2, f, flag, explosion_effect);
    }

    public Explosion createExplosion(@Nullable Entity entity, @Nullable DamageSource damagesource, double d0, double d1, double d2, float f, boolean flag, Explosion.Effect explosion_effect) {
        Explosion explosion = new Explosion(this, entity, d0, d1, d2, f, flag, explosion_effect);

        if (damagesource != null) {
            explosion.a(damagesource);
        }

        explosion.a();
        explosion.a(true);
        return explosion;
    }

    public boolean douseFire(@Nullable EntityHuman entityhuman, BlockPosition blockposition, EnumDirection enumdirection) {
        blockposition = blockposition.shift(enumdirection);
        if (this.getType(blockposition).getBlock() == Blocks.FIRE) {
            this.a(entityhuman, 1009, blockposition, 0);
            this.a(blockposition, false);
            return true;
        } else {
            return false;
        }
    }

    @Nullable
    @Override
    // CraftBukkit start
    public TileEntity getTileEntity(BlockPosition blockposition) {
        return getTileEntity(blockposition, true);
    }

    @Nullable
    protected TileEntity getTileEntity(BlockPosition blockposition, boolean validate) {
        // CraftBukkit end
        if (isOutsideWorld(blockposition)) {
            return null;
        } else if (!this.isClientSide && Thread.currentThread() != this.serverThread) {
            return null;
        } else {
            // CraftBukkit start
            TileEntity tileentity = null; // Paper
            if (!capturedTileEntities.isEmpty() && (tileentity = capturedTileEntities.get(blockposition)) != null) { // Paper
                return tileentity; // Paper
            }
            // CraftBukkit end

            //TileEntity tileentity = null; // Paper - move up

            if (this.tickingTileEntities) {
                tileentity = this.e(blockposition);
            }

            if (tileentity == null) {
                tileentity = this.getChunkAtWorldCoords(blockposition).a(blockposition, Chunk.EnumTileEntityState.IMMEDIATE);
            }

            if (tileentity == null) {
                tileentity = this.e(blockposition);
            }

            return tileentity;
        }
    }

    @Nullable
    private TileEntity e(BlockPosition blockposition) {
        for (int i = 0; i < this.tileEntityListPending.size(); ++i) {
            TileEntity tileentity = (TileEntity) this.tileEntityListPending.get(i);

            if (!tileentity.isRemoved() && tileentity.getPosition().equals(blockposition)) {
                return tileentity;
            }
        }

        return null;
    }

    public void setTileEntity(BlockPosition blockposition, @Nullable TileEntity tileentity) {
        if (!isOutsideWorld(blockposition)) {
            if (tileentity != null && !tileentity.isRemoved()) {
                // CraftBukkit start
                if (captureBlockStates) {
                    tileentity.setLocation(this, blockposition);
                    capturedTileEntities.put(blockposition.immutableCopy(), tileentity);
                    return;
                }
                // CraftBukkit end
                if (this.tickingTileEntities) {
                    tileentity.setLocation(this, blockposition);
                    Iterator iterator = this.tileEntityListPending.iterator();

                    while (iterator.hasNext()) {
                        TileEntity tileentity1 = (TileEntity) iterator.next();

                        if (tileentity1.getPosition().equals(blockposition)) {
                            tileentity1.ab_();
                            iterator.remove();
                        }
                    }

                    this.tileEntityListPending.add(tileentity);
                } else {
                    this.getChunkAtWorldCoords(blockposition).setTileEntity(blockposition, tileentity);
                    this.a(tileentity);
                }
            }

        }
    }

    public void removeTileEntity(BlockPosition blockposition) {
        TileEntity tileentity = this.getTileEntity(blockposition, false); // CraftBukkit

        if (tileentity != null && this.tickingTileEntities) {
            tileentity.ab_();
            this.tileEntityListPending.remove(tileentity);
        } else {
            if (tileentity != null) {
                this.tileEntityListPending.remove(tileentity);
                //this.tileEntityList.remove(tileentity); // Paper - remove unused list
                this.tileEntityListTick.remove(tileentity);
            }

            this.getChunkAtWorldCoords(blockposition).removeTileEntity(blockposition);
        }

    }

    public boolean n(BlockPosition blockposition) {
        return isOutsideWorld(blockposition) ? false : isChunkLoaded(blockposition.getX() >> 4, blockposition.getZ() >> 4); // Paper
    }

    public boolean a(BlockPosition blockposition, Entity entity) {
        if (isOutsideWorld(blockposition)) {
            return false;
        } else {
            IChunkAccess ichunkaccess = this.getChunkIfLoadedImmediately(blockposition.getX() >> 4, blockposition.getZ() >> 4); // Paper

            return ichunkaccess == null ? false : ichunkaccess.getType(blockposition).a((IBlockAccess) this, blockposition, entity);
        }
    }

    public void N() {
        double d0 = 1.0D - (double) (this.d(1.0F) * 5.0F) / 16.0D;
        double d1 = 1.0D - (double) (this.b(1.0F) * 5.0F) / 16.0D;
        double d2 = 0.5D + 2.0D * MathHelper.a((double) MathHelper.cos(this.f(1.0F) * 6.2831855F), -0.25D, 0.25D);

        this.c = (int) ((1.0D - d2 * d0 * d1) * 11.0D);
    }

    public void setSpawnFlags(boolean flag, boolean flag1) {
        this.getChunkProvider().a(flag, flag1);
    }

    protected void O() {
        if (this.worldData.hasStorm()) {
            this.rainLevel = 1.0F;
            if (this.worldData.isThundering()) {
                this.thunderLevel = 1.0F;
            }
        }

    }

    public void close() throws IOException {
        this.chunkProvider.close();
    }

    @Nullable
    @Override
    public IBlockAccess c(int i, int j) {
        return this.getChunkAt(i, j, ChunkStatus.FULL, false);
    }

    @Override
    public List<Entity> getEntities(@Nullable Entity entity, AxisAlignedBB axisalignedbb, @Nullable Predicate<? super Entity> predicate) {
        this.getMethodProfiler().c("getEntities");
        List<Entity> list = Lists.newArrayList();
        int i = MathHelper.floor((axisalignedbb.minX - 2.0D) / 16.0D);
        int j = MathHelper.floor((axisalignedbb.maxX + 2.0D) / 16.0D);
        int k = MathHelper.floor((axisalignedbb.minZ - 2.0D) / 16.0D);
        int l = MathHelper.floor((axisalignedbb.maxZ + 2.0D) / 16.0D);

        ChunkProviderServer chunkProvider = ((ChunkProviderServer)this.chunkProvider); // Tuinity - optimize for loaded chunks

        for (int i1 = i; i1 <= j; ++i1) {
            for (int j1 = k; j1 <= l; ++j1) {
                Chunk chunk = chunkProvider.getChunkAtIfLoadedMainThread(i1, j1); // Paper // Tuinity - optimize for loaded chunks

                if (chunk != null) {
                    chunk.a(entity, axisalignedbb, list, predicate);
                }
            }
        }

        return list;
    }

    public <T extends Entity> List<T> a(@Nullable EntityTypes<T> entitytypes, AxisAlignedBB axisalignedbb, Predicate<? super T> predicate) {
        this.getMethodProfiler().c("getEntities");
        int i = MathHelper.floor((axisalignedbb.minX - 2.0D) / 16.0D);
        int j = MathHelper.f((axisalignedbb.maxX + 2.0D) / 16.0D);
        int k = MathHelper.floor((axisalignedbb.minZ - 2.0D) / 16.0D);
        int l = MathHelper.f((axisalignedbb.maxZ + 2.0D) / 16.0D);
        List<T> list = Lists.newArrayList();

        ChunkProviderServer chunkProvider = ((ChunkProviderServer)this.chunkProvider); // Tuinity - optimize for loaded chunks

        for (int i1 = i; i1 < j; ++i1) {
            for (int j1 = k; j1 < l; ++j1) {
                Chunk chunk = chunkProvider.getChunkAtIfLoadedMainThread(i1, j1); // Paper // Tuinity - optimize for loaded chunks

                if (chunk != null) {
                    chunk.a(entitytypes, axisalignedbb, list, predicate);
                }
            }
        }

        return list;
    }

    @Override
    public <T extends Entity> List<T> a(Class<? extends T> oclass, AxisAlignedBB axisalignedbb, @Nullable Predicate<? super T> predicate) {
        this.getMethodProfiler().c("getEntities");
        int i = MathHelper.floor((axisalignedbb.minX - 2.0D) / 16.0D);
        int j = MathHelper.f((axisalignedbb.maxX + 2.0D) / 16.0D);
        int k = MathHelper.floor((axisalignedbb.minZ - 2.0D) / 16.0D);
        int l = MathHelper.f((axisalignedbb.maxZ + 2.0D) / 16.0D);
        List<T> list = Lists.newArrayList();
        IChunkProvider ichunkprovider = this.getChunkProvider();
        ChunkProviderServer chunkProvider = ((ChunkProviderServer)this.chunkProvider); // Tuinity - optimize for loaded chunks

        for (int i1 = i; i1 < j; ++i1) {
            for (int j1 = k; j1 < l; ++j1) {
                Chunk chunk = chunkProvider.getChunkAtIfLoadedMainThread(i1, j1); // Paper // Tuinity - optimize for loaded chunks

                if (chunk != null) {
                    chunk.a(oclass, axisalignedbb, list, predicate);
                }
            }
        }

        return list;
    }

    @Override
    public <T extends Entity> List<T> b(Class<? extends T> oclass, AxisAlignedBB axisalignedbb, @Nullable Predicate<? super T> predicate) {
        this.getMethodProfiler().c("getLoadedEntities");
        int i = MathHelper.floor((axisalignedbb.minX - 2.0D) / 16.0D);
        int j = MathHelper.f((axisalignedbb.maxX + 2.0D) / 16.0D);
        int k = MathHelper.floor((axisalignedbb.minZ - 2.0D) / 16.0D);
        int l = MathHelper.f((axisalignedbb.maxZ + 2.0D) / 16.0D);
        List<T> list = Lists.newArrayList();
        IChunkProvider ichunkprovider = this.getChunkProvider();

        for (int i1 = i; i1 < j; ++i1) {
            for (int j1 = k; j1 < l; ++j1) {
                Chunk chunk = ichunkprovider.a(i1, j1);

                if (chunk != null) {
                    chunk.a(oclass, axisalignedbb, list, predicate);
                }
            }
        }

        return list;
    }

    @Nullable
    public abstract Entity getEntity(int i);

    public void b(BlockPosition blockposition, TileEntity tileentity) {
        if (this.isLoaded(blockposition)) {
            this.getChunkAtWorldCoords(blockposition).markDirty();
        }

    }

    @Override
    public int getSeaLevel() {
        return 63;
    }

    @Override
    public World getMinecraftWorld() {
        return this;
    }

    public WorldType P() {
        return this.worldData.getType();
    }

    public int getBlockPower(BlockPosition blockposition) {
        byte b0 = 0;
        int i = Math.max(b0, this.c(blockposition.down(), EnumDirection.DOWN));

        if (i >= 15) {
            return i;
        } else {
            i = Math.max(i, this.c(blockposition.up(), EnumDirection.UP));
            if (i >= 15) {
                return i;
            } else {
                i = Math.max(i, this.c(blockposition.north(), EnumDirection.NORTH));
                if (i >= 15) {
                    return i;
                } else {
                    i = Math.max(i, this.c(blockposition.south(), EnumDirection.SOUTH));
                    if (i >= 15) {
                        return i;
                    } else {
                        i = Math.max(i, this.c(blockposition.west(), EnumDirection.WEST));
                        if (i >= 15) {
                            return i;
                        } else {
                            i = Math.max(i, this.c(blockposition.east(), EnumDirection.EAST));
                            return i >= 15 ? i : i;
                        }
                    }
                }
            }
        }
    }

    public boolean isBlockFacePowered(BlockPosition blockposition, EnumDirection enumdirection) {
        return this.getBlockFacePower(blockposition, enumdirection) > 0;
    }

    public int getBlockFacePower(BlockPosition blockposition, EnumDirection enumdirection) {
        IBlockData iblockdata = this.getType(blockposition);

        return iblockdata.isOccluding(this, blockposition) ? this.getBlockPower(blockposition) : iblockdata.b((IBlockAccess) this, blockposition, enumdirection);
    }

    public boolean isBlockIndirectlyPowered(BlockPosition blockposition) {
        return this.getBlockFacePower(blockposition.down(), EnumDirection.DOWN) > 0 ? true : (this.getBlockFacePower(blockposition.up(), EnumDirection.UP) > 0 ? true : (this.getBlockFacePower(blockposition.north(), EnumDirection.NORTH) > 0 ? true : (this.getBlockFacePower(blockposition.south(), EnumDirection.SOUTH) > 0 ? true : (this.getBlockFacePower(blockposition.west(), EnumDirection.WEST) > 0 ? true : this.getBlockFacePower(blockposition.east(), EnumDirection.EAST) > 0))));
    }

    public int isBlockIndirectlyGettingPowered(BlockPosition pos) { return this.q(pos); } // Paper - OBFHELPER
    public int q(BlockPosition blockposition) {
        int i = 0;
        EnumDirection[] aenumdirection = World.a;
        int j = aenumdirection.length;

        for (int k = 0; k < j; ++k) {
            EnumDirection enumdirection = aenumdirection[k];
            int l = this.getBlockFacePower(blockposition.shift(enumdirection), enumdirection);

            if (l >= 15) {
                return 15;
            }

            if (l > i) {
                i = l;
            }
        }

        return i;
    }

    public void a(long i) {
        this.worldData.setTime(i);
    }

    @Override
    public long getSeed() {
        return this.worldData.getSeed();
    }

    public long getTime() {
        return this.worldData.getTime();
    }

    public long getDayTime() {
        return this.worldData.getDayTime();
    }

    public void setDayTime(long i) {
        this.worldData.setDayTime(i);
    }

    protected void a() {
        this.a(this.worldData.getTime() + 1L);
        if (this.worldData.v().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)) {
            this.setDayTime(this.worldData.getDayTime() + 1L);
        }

    }

    public BlockPosition getSpawn() {
        BlockPosition blockposition = new BlockPosition(this.worldData.b(), this.worldData.c(), this.worldData.d());

        if (!this.getWorldBorder().a(blockposition)) {
            blockposition = this.getHighestBlockYAt(HeightMap.Type.MOTION_BLOCKING, new BlockPosition(this.getWorldBorder().getCenterX(), 0.0D, this.getWorldBorder().getCenterZ()));
        }

        return blockposition;
    }

    public void a_(BlockPosition blockposition) {
        this.worldData.setSpawn(blockposition);
    }

    public boolean a(EntityHuman entityhuman, BlockPosition blockposition) {
        return true;
    }

    public void broadcastEntityEffect(Entity entity, byte b0) {}

    @Override
    public IChunkProvider getChunkProvider() {
        return this.chunkProvider;
    }

    public void playBlockAction(BlockPosition blockposition, Block block, int i, int j) {
        this.getType(blockposition).a(this, blockposition, i, j);
    }

    @Override
    public WorldData getWorldData() {
        return this.worldData;
    }

    public GameRules getGameRules() {
        return this.worldData.v();
    }

    public float b(float f) {
        return MathHelper.g(f, this.lastThunderLevel, this.thunderLevel) * this.d(f);
    }

    public float d(float f) {
        return MathHelper.g(f, this.lastRainLevel, this.rainLevel);
    }

    public boolean U() {
        return this.worldProvider.f() && !this.worldProvider.g() ? (double) this.b(1.0F) > 0.9D : false;
    }

    public boolean isRaining() {
        return (double) this.d(1.0F) > 0.2D;
    }

    public boolean isRainingAt(BlockPosition blockposition) {
        return !this.isRaining() ? false : (!this.f(blockposition) ? false : (this.getHighestBlockYAt(HeightMap.Type.MOTION_BLOCKING, blockposition).getY() > blockposition.getY() ? false : this.getBiome(blockposition).d() == BiomeBase.Precipitation.RAIN));
    }

    public boolean s(BlockPosition blockposition) {
        BiomeBase biomebase = this.getBiome(blockposition);

        return biomebase.e();
    }

    @Nullable
    public abstract WorldMap a(String s);

    public abstract void a(WorldMap worldmap);

    public abstract int getWorldMapCount();

    public void b(int i, BlockPosition blockposition, int j) {}

    public int getHeight() {
        return this.worldProvider.g() ? 128 : 256;
    }

    public CrashReportSystemDetails a(CrashReport crashreport) {
        CrashReportSystemDetails crashreportsystemdetails = crashreport.a("Affected level", 1);

        crashreportsystemdetails.a("All players", () -> {
            return this.getPlayers().size() + " total; " + this.getPlayers();
        });
        IChunkProvider ichunkprovider = this.chunkProvider;

        this.chunkProvider.getClass();
        crashreportsystemdetails.a("Chunk stats", ichunkprovider::getName);
        crashreportsystemdetails.a("Level dimension", () -> {
            return this.worldProvider.getDimensionManager().toString();
        });

        try {
            this.worldData.a(crashreportsystemdetails);
        } catch (Throwable throwable) {
            crashreportsystemdetails.a("Level Data Unobtainable", throwable);
        }

        return crashreportsystemdetails;
    }

    public abstract void a(int i, BlockPosition blockposition, int j);

    public abstract Scoreboard getScoreboard();

    public void updateAdjacentComparators(BlockPosition blockposition, Block block) {
        Iterator iterator = EnumDirection.EnumDirectionLimit.HORIZONTAL.iterator();

        while (iterator.hasNext()) {
            EnumDirection enumdirection = (EnumDirection) iterator.next();
            BlockPosition blockposition1 = blockposition.shift(enumdirection);

            if (this.isLoaded(blockposition1)) {
                IBlockData iblockdata = this.getType(blockposition1);

                if (iblockdata.getBlock() == Blocks.COMPARATOR) {
                    iblockdata.doPhysics(this, blockposition1, block, blockposition, false);
                } else if (iblockdata.isOccluding(this, blockposition1)) {
                    blockposition1 = blockposition1.shift(enumdirection);
                    iblockdata = this.getType(blockposition1);
                    if (iblockdata.getBlock() == Blocks.COMPARATOR) {
                        iblockdata.doPhysics(this, blockposition1, block, blockposition, false);
                    }
                }
            }
        }

    }

    @Override
    public DifficultyDamageScaler getDamageScaler(BlockPosition blockposition) {
        long i = 0L;
        float f = 0.0F;

        if (this.isLoaded(blockposition)) {
            f = this.Y();
            i = this.getChunkAtWorldCoords(blockposition).getInhabitedTime();
        }

        return new DifficultyDamageScaler(this.getDifficulty(), this.getDayTime(), i, f);
    }

    @Override
    public int c() {
        return this.c;
    }

    public void c(int i) {}

    @Override
    public WorldBorder getWorldBorder() {
        return this.worldBorder;
    }

    public void a(Packet<?> packet) {
        throw new UnsupportedOperationException("Can't send packets to server unless you're on the client.");
    }

    @Override
    public WorldProvider getWorldProvider() {
        return this.worldProvider;
    }

    @Override
    public Random getRandom() {
        return this.random;
    }

    @Override
    public boolean a(BlockPosition blockposition, Predicate<IBlockData> predicate) {
        return predicate.test(this.getType(blockposition));
    }

    public abstract CraftingManager getCraftingManager();

    public abstract TagRegistry t();

    public BlockPosition a(int i, int j, int k, int l) {
        // Paper start - allow use of mutable pos
        BlockPosition.MutableBlockPosition ret = new BlockPosition.MutableBlockPosition();
        this.getRandomBlockPosition(i, j, k, l, ret);
        return ret.immutableCopy();
    }

    public final BlockPosition.MutableBlockPosition getRandomBlockPosition(int i, int j, int k, int l, BlockPosition.MutableBlockPosition out) {
        // Paper end
        this.i = this.i * 3 + 1013904223;
        int i1 = this.i >> 2;

        out.setValues(i + (i1 & 15), j + (i1 >> 16 & l), k + (i1 >> 8 & 15)); // Paper - change to setValues call
        return out; // Paper
    }

    public boolean isSavingDisabled() {
        return false;
    }

    public GameProfilerFiller getMethodProfiler() {
        return this.methodProfiler;
    }

    public BiomeManager getBiomeManager() { return d(); } // Paper - OBFHELPER
    @Override public BiomeManager d() {
        return this.biomeManager;
    }
}
