package net.minecraft.server;

import com.google.common.annotations.VisibleForTesting;
import co.aikar.timings.TimingHistory; // Paper
import co.aikar.timings.Timings; // Paper

import com.destroystokyo.paper.PaperWorldConfig; // Paper
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

// CraftBukkit start
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.WeatherType;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.event.world.TimeSkipEvent;
// CraftBukkit end

public class WorldServer extends World {

    private static final Logger LOGGER = LogManager.getLogger();
    private final List<Entity> globalEntityList = Lists.newArrayList();
    public final Int2ObjectMap<Entity> entitiesById = new Int2ObjectLinkedOpenHashMap(); // Tuinity - diff on change, we expect Int2ObjectLinkedOpenHashMap
    private final Map<UUID, Entity> entitiesByUUID = Maps.newHashMap();
    private final Queue<Entity> entitiesToAdd = Queues.newArrayDeque();
    public final List<EntityPlayer> players = Lists.newArrayList(); // Paper - private -> public
    boolean tickingEntities;
    private final MinecraftServer server;
    private final WorldNBTStorage dataManager;
    public boolean savingDisabled;
    private boolean everyoneSleeping;
    private int emptyTime;
    private final PortalTravelAgent portalTravelAgent;
    private final TickListServer<Block> nextTickListBlock;
    private final TickListServer<FluidType> nextTickListFluid;
    private final Set<NavigationAbstract> navigators;
    protected final PersistentRaid persistentRaid;
    private final ObjectLinkedOpenHashSet<BlockActionData> I;
    private boolean ticking;
    @Nullable
    private final MobSpawnerTrader mobSpawnerTrader;

    // CraftBukkit start
    private int tickPosition;
    boolean hasPhysicsEvent = true; // Paper
    private static Throwable getAddToWorldStackTrace(Entity entity) {
        return new Throwable(entity + " Added to world at " + new java.util.Date());
    }

    final com.tuinity.tuinity.util.EntityList trackingUpdateQueue = new com.tuinity.tuinity.util.EntityList(); // Tuinity - optimise tracker

    // Paper start - optimise getPlayerByUUID
    @Nullable
    @Override
    public EntityHuman getPlayerByUUID(UUID uuid) {
        Entity player = this.entitiesByUUID.get(uuid);
        return (player instanceof EntityHuman) ? (EntityHuman)player : null;
    }
    // Paper end

    // Paper start - Asynchronous IO
    public final com.destroystokyo.paper.io.PaperFileIOThread.ChunkDataController poiDataController = new com.destroystokyo.paper.io.PaperFileIOThread.ChunkDataController() {
        @Override
        public void writeData(int x, int z, NBTTagCompound compound) throws java.io.IOException {
            WorldServer.this.getChunkProvider().playerChunkMap.getVillagePlace().write(new ChunkCoordIntPair(x, z), compound);
        }

        @Override
        public NBTTagCompound readData(int x, int z) throws java.io.IOException {
            return WorldServer.this.getChunkProvider().playerChunkMap.getVillagePlace().read(new ChunkCoordIntPair(x, z));
        }

        @Override
        public <T> T computeForRegionFile(int chunkX, int chunkZ, java.util.function.Function<RegionFile, T> function) {
            synchronized (WorldServer.this.getChunkProvider().playerChunkMap.getVillagePlace()) {
                RegionFile file;

                try {
                    file = WorldServer.this.getChunkProvider().playerChunkMap.getVillagePlace().getFile(new ChunkCoordIntPair(chunkX, chunkZ), false);
                } catch (java.io.IOException ex) {
                    throw new RuntimeException(ex);
                }

                return function.apply(file);
            }
        }

        @Override
        public <T> T computeForRegionFileIfLoaded(int chunkX, int chunkZ, java.util.function.Function<RegionFile, T> function) {
            synchronized (WorldServer.this.getChunkProvider().playerChunkMap.getVillagePlace()) {
                RegionFile file = WorldServer.this.getChunkProvider().playerChunkMap.getVillagePlace().getRegionFileIfLoaded(new ChunkCoordIntPair(chunkX, chunkZ));
                return function.apply(file);
            }
        }
    };

    public final com.destroystokyo.paper.io.PaperFileIOThread.ChunkDataController chunkDataController = new com.destroystokyo.paper.io.PaperFileIOThread.ChunkDataController() {
        @Override
        public void writeData(int x, int z, NBTTagCompound compound) throws java.io.IOException {
            WorldServer.this.getChunkProvider().playerChunkMap.write(new ChunkCoordIntPair(x, z), compound);
        }

        @Override
        public NBTTagCompound readData(int x, int z) throws java.io.IOException {
            return WorldServer.this.getChunkProvider().playerChunkMap.read(new ChunkCoordIntPair(x, z));
        }

        @Override
        public <T> T computeForRegionFile(int chunkX, int chunkZ, java.util.function.Function<RegionFile, T> function) {
            synchronized (WorldServer.this.getChunkProvider().playerChunkMap) {
                RegionFile file;

                try {
                    file = WorldServer.this.getChunkProvider().playerChunkMap.getFile(new ChunkCoordIntPair(chunkX, chunkZ), false);
                } catch (java.io.IOException ex) {
                    throw new RuntimeException(ex);
                }

                return function.apply(file);
            }
        }

        @Override
        public <T> T computeForRegionFileIfLoaded(int chunkX, int chunkZ, java.util.function.Function<RegionFile, T> function) {
            synchronized (WorldServer.this.getChunkProvider().playerChunkMap) {
                RegionFile file = WorldServer.this.getChunkProvider().playerChunkMap.getRegionFileIfLoaded(new ChunkCoordIntPair(chunkX, chunkZ));
                return function.apply(file);
            }
        }
    };
    public final com.destroystokyo.paper.io.chunk.ChunkTaskManager asyncChunkTaskManager;
    // Paper end
    // Paper start
    @Override
    public boolean isChunkLoaded(int x, int z) {
        return this.getChunkProvider().getChunkAtIfLoadedImmediately(x, z) != null;
    }
    // Paper end


    // Tuinity start
    public final com.tuinity.tuinity.util.EntityList loadedEntities = new com.tuinity.tuinity.util.EntityList();
    void onChunkLoad(final Chunk chunk) {
        final com.destroystokyo.paper.util.maplist.EntityList list = chunk.entities;
        final Entity[] entities = list.getRawData();
        for (int i = 0, size = list.size(); i < size; ++i) {
            this.loadedEntities.add(entities[i]);
        }
    }

    void onChunkUnload(final Chunk chunk) {
        final com.destroystokyo.paper.util.maplist.EntityList list = chunk.entities;
        final Entity[] entities = list.getRawData();
        for (int i = 0, size = list.size(); i < size; ++i) {
            this.loadedEntities.remove(entities[i]);
        }
    }

    void onChunkSetTicking(int chunkX, int chunkZ) {
        ((com.tuinity.tuinity.server.TuinityTickList)this.nextTickListBlock).onChunkSetTicking(chunkX, chunkZ);
        ((com.tuinity.tuinity.server.TuinityTickList)this.nextTickListFluid).onChunkSetTicking(chunkX, chunkZ);
    }
    // Tuinity end

    // Tuinity start
    // TODO the general area map is too large, use a smaller one depending on distance
    @Nullable
    public EntityPlayer findClosestPlayer(double fromX, double fromY, double fromZ, double distance) {
        double maximumDistanceSquared;
        if (distance < 0) {
            maximumDistanceSquared = Double.MAX_VALUE;
        } else {
            maximumDistanceSquared = distance * distance;
        }

        EntityPlayer closestPlayer = null;
        double closestDistanceSquared = maximumDistanceSquared;

        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> nearestPlayers =
                this.getChunkProvider().playerChunkMap.playerGeneralAreaMap
                        .getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(com.tuinity.tuinity.util.Util.getChunkCoordinate(fromX), com.tuinity.tuinity.util.Util.getChunkCoordinate(fromZ)));

        if (nearestPlayers != null) {
            Object[] backingSet = nearestPlayers.getBackingSet();

            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer currPlayer = (EntityPlayer)raw;
                double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

                if (currDistanceSquared <= closestDistanceSquared && currDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
                    closestPlayer = currPlayer;
                    closestDistanceSquared = currDistanceSquared;
                }
            }
        }

        if (closestPlayer != null) {
            return closestPlayer;
        } else if (maximumDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
            return null;
        }

        // fall back to iteration over the world players

        for (int i = 0, len = this.players.size(); i < len; ++i) {
            EntityPlayer currPlayer = this.players.get(i);
            double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

            if (currDistanceSquared <= closestDistanceSquared) {
                closestPlayer = currPlayer;
                closestDistanceSquared = currDistanceSquared;
            }
        }

        return closestPlayer;
    }

    @Nullable
    public EntityPlayer findClosestPlayer(double fromX, double fromY, double fromZ, double distance, @Nullable Predicate<Entity> predicate) {
        if (predicate == null) {
            return this.findClosestPlayer(fromX, fromY, fromZ, distance);
        }

        double maximumDistanceSquared;
        if (distance < 0) {
            maximumDistanceSquared = Double.MAX_VALUE;
        } else {
            maximumDistanceSquared = distance * distance;
        }

        EntityPlayer closestPlayer = null;
        double closestDistanceSquared = maximumDistanceSquared;

        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> nearestPlayers =
                this.getChunkProvider().playerChunkMap.playerGeneralAreaMap
                        .getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(com.tuinity.tuinity.util.Util.getChunkCoordinate(fromX), com.tuinity.tuinity.util.Util.getChunkCoordinate(fromZ)));

        if (nearestPlayers != null) {
            Object[] backingSet = nearestPlayers.getBackingSet();

            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer currPlayer = (EntityPlayer)raw;
                double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

                if (predicate.test(currPlayer) && currDistanceSquared <= closestDistanceSquared && currDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
                    closestPlayer = currPlayer;
                    closestDistanceSquared = currDistanceSquared;
                }
            }
        }

        if (closestPlayer != null) {
            return closestPlayer;
        } else if (maximumDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
            return null;
        }

        // fall back to iteration over the world players

        for (int i = 0, len = this.players.size(); i < len; ++i) {
            EntityPlayer currPlayer = this.players.get(i);
            double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

            if (predicate.test(currPlayer) && currDistanceSquared <= closestDistanceSquared) {
                closestPlayer = currPlayer;
                closestDistanceSquared = currDistanceSquared;
            }
        }

        return closestPlayer;
    }

    @Nullable
    public List<EntityPlayer> findPlayersInRange(double fromX, double fromY, double fromZ, double distance) {
        return this.findPlayersInRange(fromX, fromY, fromZ, distance, new java.util.ArrayList<>(), null);
    }

    @Nullable
    public List<EntityPlayer> findPlayersInRange(double fromX, double fromY, double fromZ, double distance, @Nonnull List<EntityPlayer> players, @Nullable Predicate<Entity> predicate) {
        double maximumDistanceSquared;
        if (distance < 0) {
            maximumDistanceSquared = Double.MAX_VALUE;
        } else {
            maximumDistanceSquared = distance * distance;
        }

        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> nearestPlayers =
                this.getChunkProvider().playerChunkMap.playerGeneralAreaMap
                        .getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(com.tuinity.tuinity.util.Util.getChunkCoordinate(fromX), com.tuinity.tuinity.util.Util.getChunkCoordinate(fromZ)));

        if (nearestPlayers != null) {
            Object[] backingSet = nearestPlayers.getBackingSet();

            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer currPlayer = (EntityPlayer)raw;
                double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

                if ((predicate == null || predicate.test(currPlayer)) && currDistanceSquared <= maximumDistanceSquared && currDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
                    players.add(currPlayer);
                }
            }
        }

        if (maximumDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
            return players;
        }

        // fall back to iteration over the world players

        for (int i = 0, len = this.players.size(); i < len; ++i) {
            EntityPlayer currPlayer = this.players.get(i);
            double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

            if ((predicate == null || predicate.test(currPlayer)) && currDistanceSquared <= maximumDistanceSquared) {
                players.add(currPlayer);
            }
        }

        return players;
    }

    @Override
    public boolean isPlayerNearby(double fromX, double fromY, double fromZ, double distance) {
        double maximumDistanceSquared;
        if (distance < 0) {
            maximumDistanceSquared = Double.MAX_VALUE;
        } else {
            maximumDistanceSquared = distance * distance;
        }

        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> nearestPlayers =
                this.getChunkProvider().playerChunkMap.playerGeneralAreaMap
                        .getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(com.tuinity.tuinity.util.Util.getChunkCoordinate(fromX), com.tuinity.tuinity.util.Util.getChunkCoordinate(fromZ)));

        if (nearestPlayers != null) {
            Object[] backingSet = nearestPlayers.getBackingSet();

            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer currPlayer = (EntityPlayer)raw;
                double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

                // TODO check on update
                if ((currPlayer.isAlive() && !currPlayer.isSpectator()) && currDistanceSquared <= maximumDistanceSquared && currDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
                    return true;
                }
            }
        }

        if (maximumDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
            return false;
        }

        // fall back to iteration over the world players

        for (int i = 0, len = this.players.size(); i < len; ++i) {
            EntityPlayer currPlayer = this.players.get(i);
            double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

            // TODO check on update
            if ((currPlayer.isAlive() && !currPlayer.isSpectator()) && currDistanceSquared <= maximumDistanceSquared) {
                return true;
            }
        }

        return false;
    }

    @Override
    public EntityPlayer findClosestPlayerXZ(double fromX, double fromZ, double distanceXZ, @Nullable Predicate<Entity> predicate) {
        double maximumDistanceSquared;
        if (distanceXZ < 0) {
            maximumDistanceSquared = Double.MAX_VALUE;
        } else {
            maximumDistanceSquared = distanceXZ * distanceXZ;
        }

        EntityPlayer closestPlayer = null;
        double closestDistanceSquared = maximumDistanceSquared;

        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> nearestPlayers =
                this.getChunkProvider().playerChunkMap.playerGeneralAreaMap
                        .getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(com.tuinity.tuinity.util.Util.getChunkCoordinate(fromX), com.tuinity.tuinity.util.Util.getChunkCoordinate(fromZ)));

        if (nearestPlayers != null) {
            Object[] backingSet = nearestPlayers.getBackingSet();

            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer currPlayer = (EntityPlayer)raw;
                double currDistanceSquared = currPlayer.getDistanceXZSquared(fromX, fromZ);

                if ((predicate == null || predicate.test(currPlayer)) && currDistanceSquared <= closestDistanceSquared && currDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
                    closestPlayer = currPlayer;
                    closestDistanceSquared = currDistanceSquared;
                }
            }
        }

        if (closestPlayer != null) {
            return closestPlayer;
        } else if (maximumDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
            return null;
        }

        // fall back to iteration over the world players

        for (int i = 0, len = this.players.size(); i < len; ++i) {
            EntityPlayer currPlayer = this.players.get(i);
            double currDistanceSquared = currPlayer.getDistanceXZSquared(fromX, fromZ);

            if ((predicate == null || predicate.test(currPlayer)) && currDistanceSquared <= closestDistanceSquared) {
                closestPlayer = currPlayer;
                closestDistanceSquared = currDistanceSquared;
            }
        }

        return closestPlayer;
    }

    @Nullable
    @Override
    public EntityPlayer getNearestPlayerForPathFinding(PathfinderTargetCondition pathfindertargetcondition, @Nullable EntityLiving entityliving, double fromX, double fromY, double fromZ) {
        double maximumDistanceSquared = PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS;
        // it isn't mojang behaviour to straight up limit to 576 blocks or less, but there should be nothing
        // that tracks outside that range. Besides, not hard limiting at some point is really stupid.

        EntityPlayer closestPlayer = null;
        double closestDistanceSquared = maximumDistanceSquared;

        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> nearestPlayers =
                this.getChunkProvider().playerChunkMap.playerGeneralAreaMap
                        .getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(com.tuinity.tuinity.util.Util.getChunkCoordinate(fromX), com.tuinity.tuinity.util.Util.getChunkCoordinate(fromZ)));

        if (nearestPlayers != null) {
            Object[] backingSet = nearestPlayers.getBackingSet();

            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer currPlayer = (EntityPlayer)raw;
                double currDistanceSquared = currPlayer.getDistanceSquared(fromX, fromY, fromZ);

                if (pathfindertargetcondition.test(entityliving, currPlayer) && currDistanceSquared <= closestDistanceSquared && currDistanceSquared <= PlayerChunkMap.PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS) {
                    closestPlayer = currPlayer;
                    closestDistanceSquared = currDistanceSquared;
                }
            }
        }

        return closestPlayer;
    }

    @Override
    public List<EntityHuman> getNearestPlayersForPathFinding(PathfinderTargetCondition pathfindertargetcondition, EntityLiving entityliving, AxisAlignedBB axisalignedbb) {
        List<EntityHuman> ret = new java.util.ArrayList<>();

        double centerX = (axisalignedbb.minX + axisalignedbb.maxX) / 2.0;
        double centerZ = (axisalignedbb.minZ + axisalignedbb.maxZ) / 2.0;

        // we make the assumption that the bounding box isn't greater-than the general area map's chunk radius (~576 blocks)

        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> nearestPlayers =
                this.getChunkProvider().playerChunkMap.playerGeneralAreaMap
                        .getObjectsInRange(com.tuinity.tuinity.util.Util.getCoordinateKey(com.tuinity.tuinity.util.Util.getChunkCoordinate(centerX), com.tuinity.tuinity.util.Util.getChunkCoordinate(centerZ)));

        if (nearestPlayers != null) {
            Object[] backingSet = nearestPlayers.getBackingSet();

            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer currPlayer = (EntityPlayer)raw;

                if (axisalignedbb.contains(currPlayer.locX(), currPlayer.locY(), currPlayer.locZ()) && pathfindertargetcondition.test(entityliving, currPlayer)) {
                    ret.add(currPlayer);
                }
            }
        }

        return ret;
    }
    // Tuinity end

    // Add env and gen to constructor
    public WorldServer(MinecraftServer minecraftserver, Executor executor, WorldNBTStorage worldnbtstorage, WorldData worlddata, DimensionManager dimensionmanager, GameProfilerFiller gameprofilerfiller, WorldLoadListener worldloadlistener, org.bukkit.World.Environment env, org.bukkit.generator.ChunkGenerator gen) {
        super(worlddata, dimensionmanager, (world, worldprovider) -> {
            // CraftBukkit start
            ChunkGenerator<?> chunkGenerator;

            if (gen != null) {
                chunkGenerator = new org.bukkit.craftbukkit.generator.CustomChunkGenerator(world, gen);
            } else {
                chunkGenerator = worldprovider.getChunkGenerator();
            }

            return new ChunkProviderServer((WorldServer) world, worldnbtstorage.getDirectory(), worldnbtstorage.getDataFixer(), worldnbtstorage.f(), executor, chunkGenerator, world.spigotConfig.viewDistance, worldloadlistener, () -> { // Spigot
                return minecraftserver.getWorldServer(DimensionManager.OVERWORLD).getWorldPersistentData();
            });
            // CraftBukkit end
        }, gameprofilerfiller, false, gen, env);
        this.pvpMode = minecraftserver.getPVP();
        worlddata.world = this;
        // CraftBukkit end
        this.nextTickListBlock = new com.tuinity.tuinity.server.TuinityTickList<>(this, (block) -> { // Tuinity - optimise TickListServer
            return block == null || block.getBlockData().isAir();
        }, IRegistry.BLOCK::getKey, IRegistry.BLOCK::get, this::b, "Blocks"); // Paper - Timings
        this.nextTickListFluid = new com.tuinity.tuinity.server.TuinityTickList<>(this, (fluidtype) -> { // Tuinity - optimise TickListServer
            return fluidtype == null || fluidtype == FluidTypes.EMPTY;
        }, IRegistry.FLUID::getKey, IRegistry.FLUID::get, this::a, "Fluids"); // Paper - Timings
        this.navigators = Sets.newHashSet();
        this.I = new ObjectLinkedOpenHashSet();
        this.dataManager = worldnbtstorage;
        this.server = minecraftserver;
        this.portalTravelAgent = new PortalTravelAgent(this);
        this.N();
        this.O();
        this.getWorldBorder().a(minecraftserver.ax());
        this.persistentRaid = (PersistentRaid) this.getWorldPersistentData().a(() -> {
            return new PersistentRaid(this);
        }, PersistentRaid.a(this.worldProvider));
        if (!minecraftserver.isEmbeddedServer()) {
            this.getWorldData().setGameType(minecraftserver.getGamemode());
        }

        this.mobSpawnerTrader = this.worldProvider.getDimensionManager().getType() == DimensionManager.OVERWORLD ? new MobSpawnerTrader(this) : null; // CraftBukkit - getType()
        this.getServer().addWorld(this.getWorld()); // CraftBukkit

        this.asyncChunkTaskManager = new com.destroystokyo.paper.io.chunk.ChunkTaskManager(this); // Paper
    }

    // CraftBukkit start
    @Override
    protected TileEntity getTileEntity(BlockPosition pos, boolean validate) {
        TileEntity result = super.getTileEntity(pos, validate);
        if (!validate || Thread.currentThread() != this.serverThread) {
            // SPIGOT-5378: avoid deadlock, this can be called in loading logic (i.e lighting) but getType() will block on chunk load
            return result;
        }
        Block type = getType(pos).getBlock();

        if (result != null && type != Blocks.AIR) {
            if (!result.getTileType().isValidBlock(type)) {
                result = fixTileEntity(pos, type, result);
            }
        }

        return result;
    }

    private TileEntity fixTileEntity(BlockPosition pos, Block type, TileEntity found) {
        this.getServer().getLogger().log(Level.SEVERE, "Block at {0}, {1}, {2} is {3} but has {4}" + ". "
                + "Bukkit will attempt to fix this, but there may be additional damage that we cannot recover.", new Object[]{pos.getX(), pos.getY(), pos.getZ(), type, found});

        if (type instanceof ITileEntity) {
            TileEntity replacement = ((ITileEntity) type).createTile(this);
            replacement.world = this;
            this.setTileEntity(pos, replacement);
            return replacement;
        } else {
            return found;
        }
    }
    // CraftBukkit end

    @Override
    public BiomeBase a(int i, int j, int k) {
        return this.getChunkProvider().getChunkGenerator().getWorldChunkManager().getBiome(i, j, k);
    }

    public void doTick(BooleanSupplier booleansupplier) {
        GameProfilerFiller gameprofilerfiller = this.getMethodProfiler();

        this.ticking = true;
        gameprofilerfiller.enter("world border");
        this.getWorldBorder().s();
        gameprofilerfiller.exitEnter("weather");
        boolean flag = this.isRaining();
        int i;

        if (this.worldProvider.f()) {
            if (this.getGameRules().getBoolean(GameRules.DO_WEATHER_CYCLE)) {
                int j = this.worldData.z();

                i = this.worldData.getThunderDuration();
                int k = this.worldData.getWeatherDuration();
                boolean flag1 = this.worldData.isThundering();
                boolean flag2 = this.worldData.hasStorm();

                if (j > 0) {
                    --j;
                    i = flag1 ? 0 : 1;
                    k = flag2 ? 0 : 1;
                    flag1 = false;
                    flag2 = false;
                } else {
                    if (i > 0) {
                        --i;
                        if (i == 0) {
                            flag1 = !flag1;
                        }
                    } else if (flag1) {
                        i = this.random.nextInt(12000) + 3600;
                    } else {
                        i = this.random.nextInt(168000) + 12000;
                    }

                    if (k > 0) {
                        --k;
                        if (k == 0) {
                            flag2 = !flag2;
                        }
                    } else if (flag2) {
                        k = this.random.nextInt(12000) + 12000;
                    } else {
                        k = this.random.nextInt(168000) + 12000;
                    }
                }

                this.worldData.setThunderDuration(i);
                this.worldData.setWeatherDuration(k);
                this.worldData.g(j);
                this.worldData.setThundering(flag1);
                this.worldData.setStorm(flag2);
            }

            this.lastThunderLevel = this.thunderLevel;
            if (this.worldData.isThundering()) {
                this.thunderLevel = (float) ((double) this.thunderLevel + 0.01D);
            } else {
                this.thunderLevel = (float) ((double) this.thunderLevel - 0.01D);
            }

            this.thunderLevel = MathHelper.a(this.thunderLevel, 0.0F, 1.0F);
            this.lastRainLevel = this.rainLevel;
            if (this.worldData.hasStorm()) {
                this.rainLevel = (float) ((double) this.rainLevel + 0.01D);
            } else {
                this.rainLevel = (float) ((double) this.rainLevel - 0.01D);
            }

            this.rainLevel = MathHelper.a(this.rainLevel, 0.0F, 1.0F);
        }

        /* CraftBukkit start
        if (this.lastRainLevel != this.rainLevel) {
            this.server.getPlayerList().a((Packet) (new PacketPlayOutGameStateChange(7, this.rainLevel)), this.worldProvider.getDimensionManager());
        }

        if (this.lastThunderLevel != this.thunderLevel) {
            this.server.getPlayerList().a((Packet) (new PacketPlayOutGameStateChange(8, this.thunderLevel)), this.worldProvider.getDimensionManager());
        }

        if (flag != this.isRaining()) {
            if (flag) {
                this.server.getPlayerList().sendAll(new PacketPlayOutGameStateChange(2, 0.0F));
            } else {
                this.server.getPlayerList().sendAll(new PacketPlayOutGameStateChange(1, 0.0F));
            }

            this.server.getPlayerList().sendAll(new PacketPlayOutGameStateChange(7, this.rainLevel));
            this.server.getPlayerList().sendAll(new PacketPlayOutGameStateChange(8, this.thunderLevel));
        }
        // */
        for (int idx = 0; idx < this.players.size(); ++idx) {
            if (((EntityPlayer) this.players.get(idx)).world == this) {
                ((EntityPlayer) this.players.get(idx)).tickWeather();
            }
        }

        if (flag != this.isRaining()) {
            // Only send weather packets to those affected
            for (int idx = 0; idx < this.players.size(); ++idx) {
                if (((EntityPlayer) this.players.get(idx)).world == this) {
                    ((EntityPlayer) this.players.get(idx)).setPlayerWeather((!flag ? WeatherType.DOWNFALL : WeatherType.CLEAR), false);
                }
            }
        }
        for (int idx = 0; idx < this.players.size(); ++idx) {
            if (((EntityPlayer) this.players.get(idx)).world == this) {
                ((EntityPlayer) this.players.get(idx)).updateWeather(this.lastRainLevel, this.rainLevel, this.lastThunderLevel, this.thunderLevel);
            }
        }
        // CraftBukkit end

        if (this.getWorldData().isHardcore() && this.getDifficulty() != EnumDifficulty.HARD) {
            this.getWorldData().setDifficulty(EnumDifficulty.HARD);
        }

        if (this.everyoneSleeping && this.players.stream().noneMatch((entityplayer) -> {
            return !entityplayer.isSpectator() && !entityplayer.isDeeplySleeping() && !entityplayer.fauxSleeping; // CraftBukkit
        })) {
            // CraftBukkit start
            long l = this.worldData.getDayTime() + 24000L;
            TimeSkipEvent event = new TimeSkipEvent(this.getWorld(), TimeSkipEvent.SkipReason.NIGHT_SKIP, (l - l % 24000L) - this.getDayTime());
            if (this.getGameRules().getBoolean(GameRules.DO_DAYLIGHT_CYCLE)) {
                getServer().getPluginManager().callEvent(event);
                if (!event.isCancelled()) {
                    this.setDayTime(this.getDayTime() + event.getSkipAmount());
                }

            }

            if (!event.isCancelled()) {
                this.everyoneSleeping = false;
                this.wakeupPlayers();
            }
            // CraftBukkit end
            if (this.getGameRules().getBoolean(GameRules.DO_WEATHER_CYCLE)) {
                this.clearWeather();
            }
        }

        this.N();
        this.a();
        gameprofilerfiller.exitEnter("chunkSource");
        this.timings.chunkProviderTick.startTiming(); // Paper - timings
        this.getChunkProvider().tick(booleansupplier);
        this.timings.chunkProviderTick.stopTiming(); // Paper - timings
        gameprofilerfiller.exitEnter("tickPending");
        timings.scheduledBlocks.startTiming(); // Spigot
        if (this.worldData.getType() != WorldType.DEBUG_ALL_BLOCK_STATES) {
            this.nextTickListBlock.b();
            this.nextTickListFluid.b();
        }
        timings.scheduledBlocks.stopTiming(); // Spigot

        gameprofilerfiller.exitEnter("raid");
        this.timings.raids.startTiming(); // Paper - timings
        this.persistentRaid.a();
        if (this.mobSpawnerTrader != null) {
            this.mobSpawnerTrader.a();
        }
        this.timings.raids.stopTiming(); // Paper - timings

        gameprofilerfiller.exitEnter("blockEvents");
        timings.doSounds.startTiming(); // Spigot
        this.ad();
        timings.doSounds.stopTiming(); // Spigot
        this.ticking = false;
        gameprofilerfiller.exitEnter("entities");
        boolean flag3 = true || !this.players.isEmpty() || !this.getForceLoadedChunks().isEmpty(); // CraftBukkit - this prevents entity cleanup, other issues on servers with no players

        if (flag3) {
            this.resetEmptyTime();
        }

        if (flag3 || this.emptyTime++ < 300) {
            timings.tickEntities.startTiming(); // Spigot
            this.worldProvider.j();
            gameprofilerfiller.enter("global");

            Entity entity;

            for (i = 0; i < this.globalEntityList.size(); ++i) {
                entity = (Entity) this.globalEntityList.get(i);
                // CraftBukkit start - Fixed an NPE
                if (entity == null) {
                    continue;
                }
                // CraftBukkit end
                this.a((entity1) -> {
                    ++entity1.ticksLived;
                    entity1.tick();
                }, entity);
                if (entity.dead) {
                    this.globalEntityList.remove(i--);
                }
            }

            gameprofilerfiller.exitEnter("regular");
            this.tickingEntities = true;
            Iterator<Entity> objectiterator = this.loadedEntities.iterator(); // Tuinity - use fast iterator to reduce entry creation // Tuinity - use loaded entity list

            org.spigotmc.ActivationRange.activateEntities(this); // Spigot
            timings.entityTick.startTiming(); // Spigot
            TimingHistory.entityTicks += this.globalEntityList.size(); // Paper
            while (objectiterator.hasNext()) {
                Entity entity1 = (Entity) objectiterator.next(); // Tuinity - use loaded entity list
                Entity entity2 = entity1.getVehicle();

                /* CraftBukkit start - We prevent spawning in general, so this butchering is not needed
                if (!this.server.getSpawnAnimals() && (entity1 instanceof EntityAnimal || entity1 instanceof EntityWaterAnimal)) {
                    entity1.die();
                }

                if (!this.server.getSpawnNPCs() && entity1 instanceof NPC) {
                    entity1.die();
                }
                // CraftBukkit end */

                gameprofilerfiller.enter("checkDespawn");
                if (!entity1.dead) {
                    entity1.checkDespawn();
                }

                gameprofilerfiller.exit();
                if (entity2 != null) {
                    if (!entity2.dead && entity2.w(entity1)) {
                        continue;
                    }

                    entity1.stopRiding();
                }

                gameprofilerfiller.enter("tick");
                if (!entity1.dead && !(entity1 instanceof EntityComplexPart)) {
                    this.a(this::entityJoinedWorld, entity1);
                    ++TimingHistory.entityTicks; // Paper
                }

                gameprofilerfiller.exit();
                gameprofilerfiller.enter("remove");
                if (entity1.dead) {
                    this.removeEntityFromChunk(entity1);
                    objectiterator.remove(); this.entitiesById.remove(entity1.getId()); // Tuinity - use loaded entity list
                    this.unregisterEntity(entity1);
                }

                gameprofilerfiller.exit();
            }
            timings.entityTick.stopTiming(); // Spigot

            this.tickingEntities = false;

            try (co.aikar.timings.Timing ignored = this.timings.newEntities.startTiming()) { // Paper - timings
            while ((entity = (Entity) this.entitiesToAdd.poll()) != null) {
                this.registerEntity(entity);
            }
            } // Paper - timings

            gameprofilerfiller.exit();
            timings.tickEntities.stopTiming(); // Spigot
            this.tickBlockEntities();
        }

        gameprofilerfiller.exit();
    }

    private void wakeupPlayers() {
        (this.players.stream().filter(EntityLiving::isSleeping).collect(Collectors.toList())).forEach((entityplayer) -> { // CraftBukkit - decompile error
            entityplayer.wakeup(false, false);
        });
    }

    // Paper start - optimise random block ticking
    private final BlockPosition.MutableBlockPosition chunkTickMutablePosition = new BlockPosition.MutableBlockPosition();
    private final com.destroystokyo.paper.util.math.ThreadUnsafeRandom randomTickRandom = new com.destroystokyo.paper.util.math.ThreadUnsafeRandom();
    // Paper end

    public void a(Chunk chunk, int i) {
        ChunkCoordIntPair chunkcoordintpair = chunk.getPos();
        boolean flag = this.isRaining();
        int j = chunkcoordintpair.d();
        int k = chunkcoordintpair.e();
        GameProfilerFiller gameprofilerfiller = this.getMethodProfiler();

        gameprofilerfiller.enter("thunder");
        final BlockPosition.MutableBlockPosition blockposition = this.chunkTickMutablePosition; // Paper - use mutable to reduce allocation rate, final to force compile fail on change

        if (!this.paperConfig.disableThunder && flag && this.U() && this.randomTickRandom.nextInt(100000) == 0) { // Paper - Disable thunder // Paper - optimise random ticking
            blockposition.setValues(this.a(this.getRandomBlockPosition(j, 0, k, 15, blockposition))); // Paper
            if (this.isRainingAt(blockposition)) {
                DifficultyDamageScaler difficultydamagescaler = this.getDamageScaler(blockposition);
                boolean flag1 = this.getGameRules().getBoolean(GameRules.DO_MOB_SPAWNING) && this.random.nextDouble() < (double) difficultydamagescaler.b() * paperConfig.skeleHorseSpawnChance; // Paper

                if (flag1) {
                    EntityHorseSkeleton entityhorseskeleton = (EntityHorseSkeleton) EntityTypes.SKELETON_HORSE.a((World) this);

                    entityhorseskeleton.r(true);
                    entityhorseskeleton.setAgeRaw(0);
                    entityhorseskeleton.setPosition((double) blockposition.getX(), (double) blockposition.getY(), (double) blockposition.getZ());
                    this.addEntity(entityhorseskeleton, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.LIGHTNING); // CraftBukkit
                }

                this.strikeLightning(new EntityLightning(this, (double) blockposition.getX() + 0.5D, (double) blockposition.getY(), (double) blockposition.getZ() + 0.5D, flag1), org.bukkit.event.weather.LightningStrikeEvent.Cause.WEATHER); // CraftBukkit
            }
        }

        gameprofilerfiller.exitEnter("iceandsnow");
        if (!this.paperConfig.disableIceAndSnow && this.randomTickRandom.nextInt(16) == 0) { // Paper - Disable ice and snow // Paper - optimise random ticking
            // Paper start - optimise chunk ticking
            this.getRandomBlockPosition(j, 0, k, 15, blockposition);
            int normalY = chunk.getHighestBlockY(HeightMap.Type.MOTION_BLOCKING, blockposition.getX() & 15, blockposition.getZ() & 15);
            int downY = normalY - 1;
            blockposition.setY(normalY);
            // Paper end
            BiomeBase biomebase = this.getBiome(blockposition);

            // Paper start - optimise chunk ticking
            blockposition.setY(downY);
            if (biomebase.a((IWorldReader) this, blockposition)) {
                org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this, blockposition, Blocks.ICE.getBlockData(), null); // CraftBukkit
                // Paper end
            }

            blockposition.setY(normalY); // Paper
            if (flag && biomebase.b(this, blockposition)) {
                org.bukkit.craftbukkit.event.CraftEventFactory.handleBlockFormEvent(this, blockposition, Blocks.SNOW.getBlockData(), null); // CraftBukkit
            }

            // Paper start - optimise chunk ticking
            blockposition.setY(downY);
            if (flag && this.getBiome(blockposition).d() == BiomeBase.Precipitation.RAIN) {
                chunk.getType(blockposition).getBlock().c((World) this, blockposition);
                // Paper end
            }
        }

        // Paper start - optimise random block ticking
        gameprofilerfiller.exit();
        int blocks = chunk.tickingList.size();
        if (i > 0 && blocks > 0) {
            if ((this.randomTickRandom.nextInt() & (16 * 16 * 256 - 1)) > blocks) {
                // we optimise random block ticking by realising that most of the blocks we will try to tick
                // are not tickable. Instead we only tick tickable blocks, but only if the above
                // statement is true
                // Note: The number of blocks that get ticked per tick still REMAIN the same.
                return;
            }
            gameprofilerfiller.enter("tickBlocks");
            timings.chunkTicksBlocks.startTiming(); // Paper

            int toTick = i << 4; // i * 16

            gameprofilerfiller.enter("randomTick");
            for (int tick = 0; tick < toTick; ++tick) {
                int tickingSize = chunk.tickingList.size();
                if (tickingSize == 0) {
                    break;
                }

                int index = this.randomTickRandom.nextInt(tickingSize);
                long raw = chunk.tickingList.getRaw(index);
                int location = com.destroystokyo.paper.util.maplist.IBlockDataList.getLocationFromRaw(raw);
                int randomX = location & 15;
                int randomY = (location >>> (4 + 4)) & 255;
                int randomZ = (location >>> 4) & 15;

                BlockPosition blockposition2 = blockposition.setValues(j + randomX, randomY, k + randomZ);
                IBlockData iblockdata = com.destroystokyo.paper.util.maplist.IBlockDataList.getBlockDataFromRaw(raw);

                iblockdata.getBlock().randomTick = true; // Paper - fix MC-113809
                iblockdata.b(this, blockposition2, this.randomTickRandom);
                iblockdata.getBlock().randomTick = false; // Paper - fix MC-113809

                // We drop the fluid tick since LAVA is ALREADY TICKED by the above method.
                // TODO CHECK ON UPDATE
            }

            gameprofilerfiller.exit();
            timings.chunkTicksBlocks.stopTiming(); // Paper
            gameprofilerfiller.exit();
            // Paper end
        }
    }

    protected BlockPosition a(BlockPosition blockposition) {
        BlockPosition blockposition1 = this.getHighestBlockYAt(HeightMap.Type.MOTION_BLOCKING, blockposition);
        AxisAlignedBB axisalignedbb = (new AxisAlignedBB(blockposition1, new BlockPosition(blockposition1.getX(), this.getBuildHeight(), blockposition1.getZ()))).g(3.0D);
        List<EntityLiving> list = this.a(EntityLiving.class, axisalignedbb, (java.util.function.Predicate<EntityLiving>) (entityliving) -> { // CraftBukkit - decompile error
            return entityliving != null && entityliving.isAlive() && this.f(entityliving.getChunkCoordinates());
        });

        if (!list.isEmpty()) {
            return ((EntityLiving) list.get(this.random.nextInt(list.size()))).getChunkCoordinates();
        } else {
            if (blockposition1.getY() == -1) {
                blockposition1 = blockposition1.up(2);
            }

            return blockposition1;
        }
    }

    public boolean b() {
        return this.ticking;
    }

    public void everyoneSleeping() {
        this.everyoneSleeping = false;
        if (!this.players.isEmpty()) {
            int i = 0;
            int j = 0;
            Iterator iterator = this.players.iterator();

            while (iterator.hasNext()) {
                EntityPlayer entityplayer = (EntityPlayer) iterator.next();

                if (entityplayer.isSpectator() || (entityplayer.fauxSleeping && !entityplayer.isSleeping())) { // CraftBukkit
                    ++i;
                } else if (entityplayer.isSleeping()) {
                    ++j;
                }
            }

            this.everyoneSleeping = j > 0 && j >= this.players.size() - i;
        }

    }

    @Override
    public ScoreboardServer getScoreboard() {
        return this.server.getScoreboard();
    }

    private void clearWeather() {
        // CraftBukkit start
        this.worldData.setStorm(false);
        // If we stop due to everyone sleeping we should reset the weather duration to some other random value.
        // Not that everyone ever manages to get the whole server to sleep at the same time....
        if (!this.worldData.hasStorm()) {
            this.worldData.setWeatherDuration(0);
        }
        // CraftBukkit end
        this.worldData.setThundering(false);
        // CraftBukkit start
        // If we stop due to everyone sleeping we should reset the weather duration to some other random value.
        // Not that everyone ever manages to get the whole server to sleep at the same time....
        if (!this.worldData.isThundering()) {
            this.worldData.setThunderDuration(0);
        }
        // CraftBukkit end
    }

    public void resetEmptyTime() {
        this.emptyTime = 0;
    }

    private void a(NextTickListEntry<FluidType> nextticklistentry) {
        Fluid fluid = this.getFluid(nextticklistentry.a);

        if (fluid.getType() == nextticklistentry.b()) {
            fluid.a((World) this, nextticklistentry.a);
        }

    }

    private void b(NextTickListEntry<Block> nextticklistentry) {
        IBlockData iblockdata = this.getType(nextticklistentry.a);

        if (iblockdata.getBlock() == nextticklistentry.b()) {
            iblockdata.a(this, nextticklistentry.a, this.random);
        }

    }

    public void entityJoinedWorld(Entity entity) {
        if (entity instanceof EntityHuman || this.getChunkProvider().a(entity)) {
            // Spigot start
            if (!org.spigotmc.ActivationRange.checkIfActive(entity)) {
                entity.ticksLived++;
                entity.inactiveTick();
                return;
            }
            // Spigot end

            entity.tickTimer.startTiming(); // Spigot
            entity.f(entity.locX(), entity.locY(), entity.locZ());
            entity.lastYaw = entity.yaw;
            entity.lastPitch = entity.pitch;
            if (entity.inChunk) {
                ++entity.ticksLived;
                GameProfilerFiller gameprofilerfiller = this.getMethodProfiler();

                gameprofilerfiller.a(() -> {
                    return IRegistry.ENTITY_TYPE.getKey(entity.getEntityType()).toString();
                });
                gameprofilerfiller.c("tickNonPassenger");
                entity.tick();
                entity.postTick(); // CraftBukkit
                gameprofilerfiller.exit();
            }

            this.chunkCheck(entity);
            if (entity.inChunk) {
                Iterator iterator = entity.getPassengers().iterator();

                while (iterator.hasNext()) {
                    Entity entity1 = (Entity) iterator.next();

                    this.a(entity, entity1);
                }
            }
            entity.tickTimer.stopTiming(); // Spigot

        }
    }

    public void a(Entity entity, Entity entity1) {
        if (!entity1.dead && entity1.getVehicle() == entity) {
            if (entity1 instanceof EntityHuman || this.getChunkProvider().a(entity1)) {
                entity1.f(entity1.locX(), entity1.locY(), entity1.locZ());
                entity1.lastYaw = entity1.yaw;
                entity1.lastPitch = entity1.pitch;
                if (entity1.inChunk) {
                    ++entity1.ticksLived;
                    GameProfilerFiller gameprofilerfiller = this.getMethodProfiler();

                    gameprofilerfiller.a(() -> {
                        return IRegistry.ENTITY_TYPE.getKey(entity1.getEntityType()).toString();
                    });
                    gameprofilerfiller.c("tickPassenger");
                    entity1.passengerTick();
                    gameprofilerfiller.exit();
                }

                this.chunkCheck(entity1);
                if (entity1.inChunk) {
                    Iterator iterator = entity1.getPassengers().iterator();

                    while (iterator.hasNext()) {
                        Entity entity2 = (Entity) iterator.next();

                        this.a(entity1, entity2);
                    }
                }

            }
        } else {
            entity1.stopRiding();
        }
    }

    public void chunkCheck(Entity entity) {
        this.getMethodProfiler().enter("chunkCheck");
        int i = MathHelper.floor(entity.locX() / 16.0D);
        int j =  Math.min(15, Math.max(0, MathHelper.floor(entity.locY() / 16.0D))); // Paper - stay consistent with chunk add/remove behavior;
        int k = MathHelper.floor(entity.locZ() / 16.0D);

        if (!entity.inChunk || entity.chunkX != i || entity.chunkY != j || entity.chunkZ != k) {
            // Tuinity start - optimized tracker
            int prevChunkX = entity.chunkX;
            int prevChunkZ = entity.chunkZ;
            // Tuinity end - optimized tracker
            if (entity.inChunk && this.isChunkLoaded(entity.chunkX, entity.chunkZ)) {
                this.getChunkAt(entity.chunkX, entity.chunkZ).a(entity, entity.chunkY);
            }

            if (!entity.valid && !entity.cc() && !this.isChunkLoaded(i, k)) { // Paper - always load chunks to register valid entities location
                entity.inChunk = false;
            } else {
                this.getChunkAt(i, k).a(entity);
            }
            // Tuinity start - optimized tracker
            int newChunkX = entity.chunkX;
            int newChunkZ = entity.chunkZ;
            if (entity.valid && (prevChunkX != newChunkX || prevChunkZ != newChunkZ)) {
                PlayerChunkMap chunkMap = this.getChunkProvider().playerChunkMap;
                if (chunkMap.playerEntityTrackerTrackMap != null) {
                    if (!PlayerChunkMap.isLegacyTrackingEntity(entity)) {
                        // handle tracking
                        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> oldRange =
                                chunkMap.playerEntityTrackerTrackMap.getObjectsInRange(prevChunkX, prevChunkZ);
                        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newRange =
                                chunkMap.playerEntityTrackerTrackMap.getObjectsInRange(newChunkX, newChunkZ);

                        if (oldRange != newRange) { // optimization: equal pooled sets are identity equal
                            if (newRange != null) {
                                if (oldRange == null) {
                                    Object[] backingSet = newRange.getBackingSet();
                                    for (int index = 0, len = backingSet.length; index < len; ++index) {
                                        Object temp = backingSet[index];
                                        if (!(temp instanceof EntityPlayer)) {
                                            continue;
                                        }
                                        EntityPlayer player = (EntityPlayer) temp;
                                        entity.addToTrackQueue(player);
                                    }
                                } else {
                                    Object[] backingSet = newRange.getBackingSet();
                                    for (int index = 0, len = backingSet.length; index < len; ++index) {
                                        Object temp = backingSet[index];
                                        if (!(temp instanceof EntityPlayer)) {
                                            continue;
                                        }
                                        EntityPlayer player = (EntityPlayer) temp;
                                        if (oldRange.contains(player)) {
                                            continue;
                                        }
                                        entity.addToTrackQueue(player);
                                    }
                                }
                            }
                        }

                        // handle untracking
                        oldRange = chunkMap.playerEntityTrackerUntrackMap.getObjectsInRange(prevChunkX, prevChunkZ);
                        newRange = chunkMap.playerEntityTrackerUntrackMap.getObjectsInRange(newChunkX, newChunkZ);
                        if (oldRange != newRange) { // optimization: equal pooled sets are identity equal
                            if (oldRange != null) {
                                if (newRange == null) {
                                    Object[] backingSet = oldRange.getBackingSet();
                                    for (int index = 0, len = backingSet.length; index < len; ++index) {
                                        Object temp = backingSet[index];
                                        if (!(temp instanceof EntityPlayer)) {
                                            continue;
                                        }
                                        EntityPlayer player = (EntityPlayer) temp;
                                        entity.addToUntrackQueue(player);
                                    }
                                } else {
                                    Object[] backingSet = oldRange.getBackingSet();
                                    for (int index = 0, len = backingSet.length; index < len; ++index) {
                                        Object temp = backingSet[index];
                                        if (!(temp instanceof EntityPlayer)) {
                                            continue;
                                        }
                                        EntityPlayer player = (EntityPlayer) temp;
                                        if (newRange.contains(player)) {
                                            continue;
                                        }
                                        entity.addToUntrackQueue(player);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Tuinity end - optimized tracker
        }

        this.getMethodProfiler().exit();
    }

    @Override
    public boolean a(EntityHuman entityhuman, BlockPosition blockposition) {
        return !this.server.a(this, blockposition, entityhuman) && this.getWorldBorder().a(blockposition);
    }

    public void a(WorldSettings worldsettings) {
        if (!this.worldProvider.canRespawn()) {
            this.worldData.setSpawn(BlockPosition.ZERO.up(this.getChunkProvider().getChunkGenerator().getSpawnHeight()));
        } else if (this.worldData.getType() == WorldType.DEBUG_ALL_BLOCK_STATES) {
            this.worldData.setSpawn(BlockPosition.ZERO.up());
        } else {
//            Paper start - moved down
//            WorldChunkManager worldchunkmanager = this.getChunkProvider().getChunkGenerator().getWorldChunkManager();
//            List<BiomeBase> list = worldchunkmanager.a();
//            Random random = new Random(this.getSeed());
//            BlockPosition blockposition = worldchunkmanager.a(0, this.getSeaLevel(), 0, 256, list, random);
//            ChunkCoordIntPair chunkcoordintpair = blockposition == null ? new ChunkCoordIntPair(0, 0) : new ChunkCoordIntPair(blockposition);
//            Paper end
            // CraftBukkit start
            if (this.generator != null) {
                Random rand = new Random(this.getSeed());
                org.bukkit.Location spawn = this.generator.getFixedSpawnLocation(((WorldServer) this).getWorld(), rand);

                if (spawn != null) {
                    if (spawn.getWorld() != ((WorldServer) this).getWorld()) {
                        throw new IllegalStateException("Cannot set spawn point for " + this.worldData.getName() + " to be in another world (" + spawn.getWorld().getName() + ")");
                    } else {
                        this.worldData.setSpawn(new BlockPosition(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()));
                        return;
                    }
                }
            }
            // CraftBukkit end

            // Paper start - this is useless if craftbukkit returns early
            WorldChunkManager worldchunkmanager = this.getChunkProvider().getChunkGenerator().getWorldChunkManager();
            List<BiomeBase> list = worldchunkmanager.a();
            Random random = new Random(this.getSeed());
            BlockPosition blockposition = worldchunkmanager.a(0, this.getSeaLevel(), 0, 256, list, random);
            ChunkCoordIntPair chunkcoordintpair = blockposition == null ? new ChunkCoordIntPair(0, 0) : new ChunkCoordIntPair(blockposition);
            // Paper end
            if (blockposition == null) {
                WorldServer.LOGGER.warn("Unable to find spawn biome");
            }

            boolean flag = false;
            Iterator iterator = TagsBlock.VALID_SPAWN.a().iterator();

            while (iterator.hasNext()) {
                Block block = (Block) iterator.next();

                if (worldchunkmanager.b().contains(block.getBlockData())) {
                    flag = true;
                    break;
                }
            }

            this.worldData.setSpawn(chunkcoordintpair.l().b(8, this.getChunkProvider().getChunkGenerator().getSpawnHeight(), 8));
            int i = 0;
            int j = 0;
            int k = 0;
            int l = -1;
            boolean flag1 = true;

            for (int i1 = 0; i1 < 1024; ++i1) {
                if (i > -16 && i <= 16 && j > -16 && j <= 16) {
                    BlockPosition blockposition1 = this.worldProvider.a(new ChunkCoordIntPair(chunkcoordintpair.x + i, chunkcoordintpair.z + j), flag);

                    if (blockposition1 != null) {
                        this.worldData.setSpawn(blockposition1);
                        break;
                    }
                }

                if (i == j || i < 0 && i == -j || i > 0 && i == 1 - j) {
                    int j1 = k;

                    k = -l;
                    l = j1;
                }

                i += k;
                j += l;
            }

            if (worldsettings.c()) {
                this.g();
            }

        }
    }

    protected void g() {
        WorldGenFeatureConfigured<?, ?> worldgenfeatureconfigured = WorldGenerator.BONUS_CHEST.b(WorldGenFeatureConfiguration.e); // CraftBukkit - decompile error

        worldgenfeatureconfigured.a(this, this.getChunkProvider().getChunkGenerator(), this.random, new BlockPosition(this.worldData.b(), this.worldData.c(), this.worldData.d()));
    }

    @Nullable
    public BlockPosition getDimensionSpawn() {
        return this.worldProvider.c();
    }

    // Paper start - derived from below
    public void saveIncrementally(boolean doFull) throws ExceptionWorldConflict {
        ChunkProviderServer chunkproviderserver = this.getChunkProvider();

        if (doFull) {
            org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.WorldSaveEvent(getWorld()));
        }

        try (co.aikar.timings.Timing ignored = timings.worldSave.startTiming()) {
            if (doFull) {
                this.saveData();
            }

            timings.worldSaveChunks.startTiming(); // Paper
            if (!this.isSavingDisabled()) chunkproviderserver.saveIncrementally();
            timings.worldSaveChunks.stopTiming(); // Paper


            // CraftBukkit start - moved from MinecraftServer.saveChunks
            // PAIL - rename
            if (doFull) {
                WorldServer worldserver1 = this;
                WorldData worlddata = worldserver1.getWorldData();

                worldserver1.getWorldBorder().save(worlddata);
                worlddata.setCustomBossEvents(this.server.getBossBattleCustomData().save());
                worldserver1.getDataManager().saveWorldData(worlddata, this.server.getPlayerList().save());
                // CraftBukkit end
            }
        }
    }
    // Paper end

    public void save(@Nullable IProgressUpdate iprogressupdate, boolean flag, boolean flag1) throws ExceptionWorldConflict {
        ChunkProviderServer chunkproviderserver = this.getChunkProvider();

        if (!flag1) {
            if (flag) org.bukkit.Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.WorldSaveEvent(getWorld())); // CraftBukkit
            try (co.aikar.timings.Timing ignored = timings.worldSave.startTiming()) { // Paper
            if (iprogressupdate != null) {
                iprogressupdate.a(new ChatMessage("menu.savingLevel", new Object[0]));
            }

            this.m_();
            if (iprogressupdate != null) {
                iprogressupdate.c(new ChatMessage("menu.savingChunks", new Object[0]));
            }

            timings.worldSaveChunks.startTiming(); // Paper
            chunkproviderserver.save(flag);
            timings.worldSaveChunks.stopTiming(); // Paper
            } // Paper
        }

        // CraftBukkit start - moved from MinecraftServer.saveChunks
        WorldServer worldserver1 = this;
        WorldData worlddata = worldserver1.getWorldData();

        worldserver1.getWorldBorder().save(worlddata);
        worlddata.setCustomBossEvents(this.server.getBossBattleCustomData().save());
        worldserver1.getDataManager().saveWorldData(worlddata, this.server.getPlayerList().save());
        // CraftBukkit end
    }

    protected void saveData() throws ExceptionWorldConflict { this.m_(); } // Paper - OBFHELPER
    protected void m_() throws ExceptionWorldConflict {
        this.checkSession();
        this.worldProvider.i();
        this.getChunkProvider().getWorldPersistentData().a();
    }

    public List<Entity> a(@Nullable EntityTypes<?> entitytypes, Predicate<? super Entity> predicate) {
        List<Entity> list = Lists.newArrayList();
        ChunkProviderServer chunkproviderserver = this.getChunkProvider();
        ObjectIterator objectiterator = this.entitiesById.values().iterator();

        while (objectiterator.hasNext()) {
            Entity entity = (Entity) objectiterator.next();

            if ((entitytypes == null || entity.getEntityType() == entitytypes) && chunkproviderserver.isLoaded(MathHelper.floor(entity.locX()) >> 4, MathHelper.floor(entity.locZ()) >> 4) && predicate.test(entity)) {
                list.add(entity);
            }
        }

        return list;
    }

    public List<EntityEnderDragon> j() {
        List<EntityEnderDragon> list = Lists.newArrayList();
        ObjectIterator objectiterator = this.entitiesById.values().iterator();

        while (objectiterator.hasNext()) {
            Entity entity = (Entity) objectiterator.next();

            if (entity instanceof EntityEnderDragon && entity.isAlive()) {
                list.add((EntityEnderDragon) entity);
            }
        }

        return list;
    }

    public List<EntityPlayer> a(Predicate<? super EntityPlayer> predicate) {
        List<EntityPlayer> list = Lists.newArrayList();
        Iterator iterator = this.players.iterator();

        while (iterator.hasNext()) {
            EntityPlayer entityplayer = (EntityPlayer) iterator.next();

            if (predicate.test(entityplayer)) {
                list.add(entityplayer);
            }
        }

        return list;
    }

    @Nullable
    public EntityPlayer k() {
        List<EntityPlayer> list = this.a(EntityLiving::isAlive);

        return list.isEmpty() ? null : (EntityPlayer) list.get(this.random.nextInt(list.size()));
    }

    public Object2IntMap<EnumCreatureType> l() {
        // Paper start
        int[] values = this.countMobs(false);
        EnumCreatureType[] byId = EnumCreatureType.values();
        Object2IntMap<EnumCreatureType> ret = new Object2IntOpenHashMap<>();

        for (int i = 0, len = values.length; i < len; ++i) {
            ret.put(byId[i], values[i]);
        }

        return ret;
    }
    public int[] countMobs(boolean updatePlayerCounts) {
        int[] ret = new int[EntityPlayer.ENUMCREATURETYPE_TOTAL_ENUMS];
        // Paper end
        ObjectIterator objectiterator = this.entitiesById.values().iterator();

        while (objectiterator.hasNext()) {
            Entity entity = (Entity) objectiterator.next();
            if (entity.shouldBeRemoved) continue; // Paper
            if (entity instanceof EntityInsentient) {
                EntityInsentient entityinsentient = (EntityInsentient) entity;

                // CraftBukkit - Split out persistent check, don't apply it to special persistent mobs
                if (entityinsentient.isTypeNotPersistent(0) && entityinsentient.isPersistent()) {
                    continue;
                }
            }

            EnumCreatureType enumcreaturetype = entity.getEntityType().e();

            if (enumcreaturetype != EnumCreatureType.MISC && this.getChunkProvider().b(entity)) {
                // Paper start - Only count natural spawns
                if (!this.paperConfig.countAllMobsForSpawning &&
                    !(entity.spawnReason == CreatureSpawnEvent.SpawnReason.NATURAL ||
                        entity.spawnReason == CreatureSpawnEvent.SpawnReason.CHUNK_GEN)) {
                    continue;
                }
                // Paper end
                // Paper start - rework mob spawning
                if (updatePlayerCounts) {
                    this.getChunkProvider().playerChunkMap.updatePlayerMobTypeMap(entity);
                }
                ++ret[enumcreaturetype.ordinal()];
                // Paper end
            }
        }

        return ret;
    }

    @Override
    public boolean addEntity(Entity entity) {
        // CraftBukkit start
        return this.addEntity0(entity, CreatureSpawnEvent.SpawnReason.DEFAULT);
    }

    @Override
    public boolean addEntity(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        return this.addEntity0(entity, reason);
        // CraftBukkit end
    }

    public boolean addEntitySerialized(Entity entity) {
        // CraftBukkit start
        return this.addEntitySerialized(entity, CreatureSpawnEvent.SpawnReason.DEFAULT);
    }

    public boolean addEntitySerialized(Entity entity, CreatureSpawnEvent.SpawnReason reason) {
        return this.addEntity0(entity, reason);
        // CraftBukkit end
    }

    public void addEntityTeleport(Entity entity) {
        boolean flag = entity.attachedToPlayer;

        entity.attachedToPlayer = true;
        this.addEntitySerialized(entity);
        entity.attachedToPlayer = flag;
        this.chunkCheck(entity);
    }

    public void addPlayerCommand(EntityPlayer entityplayer) {
        this.addPlayer0(entityplayer);
        this.chunkCheck(entityplayer);
    }

    public void addPlayerPortal(EntityPlayer entityplayer) {
        this.addPlayer0(entityplayer);
        this.chunkCheck(entityplayer);
    }

    public void addPlayerJoin(EntityPlayer entityplayer) {
        this.addPlayer0(entityplayer);
    }

    public void addPlayerRespawn(EntityPlayer entityplayer) {
        this.addPlayer0(entityplayer);
    }

    private void addPlayer0(EntityPlayer entityplayer) {
        Entity entity = (Entity) this.entitiesByUUID.get(entityplayer.getUniqueID());

        if (entity != null) {
            WorldServer.LOGGER.warn("Force-added player with duplicate UUID {}", entityplayer.getUniqueID().toString());
            entity.decouple();
            this.removePlayer((EntityPlayer) entity);
        }

        this.players.add(entityplayer);
        this.everyoneSleeping();
        IChunkAccess ichunkaccess = this.getChunkAt(MathHelper.floor(entityplayer.locX() / 16.0D), MathHelper.floor(entityplayer.locZ() / 16.0D), ChunkStatus.FULL, true);

        if (ichunkaccess instanceof Chunk) {
            ichunkaccess.a((Entity) entityplayer);
        }

        this.registerEntity(entityplayer);
        this.getChunkProvider().playerChunkMap.addPlayerToDistanceMaps(entityplayer); // Paper - distance maps
        this.getChunkProvider().playerChunkMap.addPlayerToDistanceMapsTuinity(entityplayer); // Tuinity - distance maps
    }

    // CraftBukkit start
    private boolean addEntity0(Entity entity, CreatureSpawnEvent.SpawnReason spawnReason) {
        org.spigotmc.AsyncCatcher.catchOp("entity add"); // Spigot
        if (entity.spawnReason == null) entity.spawnReason = spawnReason; // Paper
        // Paper start
        if (entity.valid) {
            MinecraftServer.LOGGER.error("Attempted Double World add on " + entity, new Throwable());

            if (DEBUG_ENTITIES) {
                Throwable thr = entity.addedToWorldStack;
                if (thr == null) {
                    MinecraftServer.LOGGER.error("Double add entity has no add stacktrace");
                } else {
                    MinecraftServer.LOGGER.error("Double add stacktrace: ", thr);
                }
            }
            return true;
        }
        // Paper end
        if (entity.dead) {
            // Paper start
            if (DEBUG_ENTITIES) {
                new Throwable("Tried to add entity " + entity + " but it was marked as removed already").printStackTrace(); // CraftBukkit
                getAddToWorldStackTrace(entity).printStackTrace();
            }
            // Paper end
            // WorldServer.LOGGER.warn("Tried to add entity {} but it was marked as removed already", EntityTypes.getName(entity.getEntityType())); // CraftBukkit
            return false;
        } else if (this.isUUIDTaken(entity)) {
            return false;
        } else {
            if (!CraftEventFactory.doEntityAddEventCalling(this, entity, spawnReason)) {
                return false;
            }
            // CraftBukkit end
            IChunkAccess ichunkaccess = this.getChunkAt(MathHelper.floor(entity.locX() / 16.0D), MathHelper.floor(entity.locZ() / 16.0D), ChunkStatus.FULL, true); // Paper - always load chunks for entity adds

            if (!(ichunkaccess instanceof Chunk)) {
                return false;
            } else {
                ichunkaccess.a(entity);
                this.registerEntity(entity);
                return true;
            }
        }
    }

    public boolean addEntityChunk(Entity entity) {
        if (this.isUUIDTaken(entity)) {
            return false;
        } else {
            this.registerEntity(entity);
            return true;
        }
    }

    private boolean isUUIDTaken(Entity entity) {
        Entity entity1 = (Entity) this.entitiesByUUID.get(entity.getUniqueID());

        if (entity1 == null) {
            return false;
        } else {
            // Paper start
            if (entity1.dead) {
                unregisterEntity(entity1); // remove the existing entity
                return false;
            }

            if (DEBUG_ENTITIES && entity.world.paperConfig.duplicateUUIDMode != PaperWorldConfig.DuplicateUUIDMode.NOTHING) {
                WorldServer.LOGGER.error("Keeping entity {} that already exists with UUID {}", EntityTypes.getName(entity1.getEntityType()), entity.getUniqueID().toString()); // CraftBukkit // paper
                WorldServer.LOGGER.error("Deleting duplicate entity {}", entity); // CraftBukkit // paper

                if (entity1.addedToWorldStack != null) {
                    entity1.addedToWorldStack.printStackTrace();
                }

                getAddToWorldStackTrace(entity).printStackTrace();
            }
            // Paper end
            return true;
        }
    }

    public void unloadChunk(Chunk chunk) {
        // Spigot Start
        for (TileEntity tileentity : chunk.getTileEntities().values())
        {
            if ( tileentity instanceof IInventory )
            {
                for ( org.bukkit.entity.HumanEntity h : Lists.<org.bukkit.entity.HumanEntity>newArrayList((List<org.bukkit.entity.HumanEntity>) ( (IInventory) tileentity ).getViewers() ) )
                {
                    if ( h instanceof org.bukkit.craftbukkit.entity.CraftHumanEntity )
                    {
                       ( (org.bukkit.craftbukkit.entity.CraftHumanEntity) h).getHandle().closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED); // Paper
                    }
                }
            }
        }
        // Spigot End
        this.tileEntityListUnload.addAll(chunk.getTileEntities().values());
        List[] aentityslice = chunk.getEntitySlices(); // Spigot
        int i = aentityslice.length;

        for (int j = 0; j < i; ++j) {
            List<Entity> entityslice = aentityslice[j]; // Spigot
            Iterator iterator = entityslice.iterator();

            while (iterator.hasNext()) {
                Entity entity = (Entity) iterator.next();
                // Spigot Start
                if ( entity instanceof IInventory )
                {
                    for ( org.bukkit.entity.HumanEntity h : Lists.<org.bukkit.entity.HumanEntity>newArrayList( (List<org.bukkit.entity.HumanEntity>) ( (IInventory) entity ).getViewers() ) )
                    {
                        if ( h instanceof org.bukkit.craftbukkit.entity.CraftHumanEntity )
                        {
                           ( (org.bukkit.craftbukkit.entity.CraftHumanEntity) h).getHandle().closeInventory(org.bukkit.event.inventory.InventoryCloseEvent.Reason.UNLOADED); // Paper
                        }
                    }
                }
                // Spigot End

                if (!(entity instanceof EntityPlayer)) {
                    if (this.tickingEntities) {
                        throw (IllegalStateException) SystemUtils.c(new IllegalStateException("Removing entity while ticking!"));
                    }

                    this.entitiesById.remove(entity.getId());
                    this.unregisterEntity(entity);
                }
            }
        }

    }

    public void unregisterEntity(Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity unregister"); // Spigot
        // Spigot start
        if ( entity instanceof EntityHuman )
        {
            this.getMinecraftServer().worldServer.values().stream().map( WorldServer::getWorldPersistentData ).forEach( (worldData) ->
            {
                for (Object o : worldData.data.values() )
                {
                    if ( o instanceof WorldMap )
                    {
                        WorldMap map = (WorldMap) o;
                        map.humans.remove( (EntityHuman) entity );
                        for ( Iterator<WorldMap.WorldMapHumanTracker> iter = (Iterator<WorldMap.WorldMapHumanTracker>) map.i.iterator(); iter.hasNext(); )
                        {
                            if ( iter.next().trackee == entity )
                            {
                                map.decorations.remove(entity.getDisplayName().getString()); // Paper
                                iter.remove();
                            }
                        }
                    }
                }
            } );
        }
        // Spigot end

        if (entity instanceof EntityEnderDragon) {
            EntityComplexPart[] aentitycomplexpart = ((EntityEnderDragon) entity).eo();
            int i = aentitycomplexpart.length;

            for (int j = 0; j < i; ++j) {
                EntityComplexPart entitycomplexpart = aentitycomplexpart[j];

                entitycomplexpart.die();
            }
        }

        this.entitiesByUUID.remove(entity.getUniqueID());
        this.getChunkProvider().removeEntity(entity);
        if (entity instanceof EntityPlayer) {
            EntityPlayer entityplayer = (EntityPlayer) entity;

            this.players.remove(entityplayer);
            this.getChunkProvider().playerChunkMap.removePlayerFromDistanceMaps(entityplayer); // Paper - distance maps
            this.getChunkProvider().playerChunkMap.removePlayerFromDistanceMapsTuinity(entityplayer); // Tuinity - distance maps
        }

        this.getScoreboard().a(entity);
        // CraftBukkit start - SPIGOT-5278
        if (entity instanceof EntityDrowned) {
            this.navigators.remove(((EntityDrowned) entity).navigationWater);
            this.navigators.remove(((EntityDrowned) entity).navigationLand);
        } else
        // CraftBukkit end
        if (entity instanceof EntityInsentient) {
            this.navigators.remove(((EntityInsentient) entity).getNavigation());
        }
        this.loadedEntities.remove(entity); // Tuinity - loaded entity list
        new com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent(entity.getBukkitEntity()).callEvent(); // Paper - fire while valid
        entity.valid = false; // CraftBukkit
    }

    private void registerEntity(Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity register"); // Spigot
        if (this.tickingEntities) {
            this.entitiesToAdd.add(entity);
        } else {
            this.entitiesById.put(entity.getId(), entity);
            if (entity instanceof EntityEnderDragon) {
                EntityComplexPart[] aentitycomplexpart = ((EntityEnderDragon) entity).eo();
                int i = aentitycomplexpart.length;

                for (int j = 0; j < i; ++j) {
                    EntityComplexPart entitycomplexpart = aentitycomplexpart[j];

                    this.entitiesById.put(entitycomplexpart.getId(), entitycomplexpart);
                }
            }

            if (DEBUG_ENTITIES) {
                entity.addedToWorldStack = getAddToWorldStackTrace(entity);
            }

            Entity old = this.entitiesByUUID.put(entity.getUniqueID(), entity);
            if (old != null && old.getId() != entity.getId() && old.valid && entity.world.paperConfig.duplicateUUIDMode != com.destroystokyo.paper.PaperWorldConfig.DuplicateUUIDMode.NOTHING) { // Paper
                Logger logger = LogManager.getLogger();
                logger.error("Overwrote an existing entity " + old + " with " + entity);
                if (DEBUG_ENTITIES) {
                    if (old.addedToWorldStack != null) {
                        old.addedToWorldStack.printStackTrace();
                    } else {
                        logger.error("Oddly, the old entity was not added to the world in the normal way. Plugins?");
                    }
                    entity.addedToWorldStack.printStackTrace();
                }
            }

            this.getChunkProvider().addEntity(entity);
            // CraftBukkit start - SPIGOT-5278
            if (entity instanceof EntityDrowned) {
                this.navigators.add(((EntityDrowned) entity).navigationWater);
                this.navigators.add(((EntityDrowned) entity).navigationLand);
            } else
            // CraftBukkit end
            if (entity instanceof EntityInsentient) {
                this.navigators.add(((EntityInsentient) entity).getNavigation());
            }
            entity.valid = true; // CraftBukkit
            // Paper start - Set origin location when the entity is being added to the world
            if (entity.origin == null) {
                entity.origin = entity.getBukkitEntity().getLocation();
            }
            // Paper end
            entity.shouldBeRemoved = false; // Paper - shouldn't be removed after being re-added
            // Tuinity start - loaded entity list
            if (this.isChunkLoaded(com.tuinity.tuinity.util.Util.getChunkCoordinate(entity.locX()), com.tuinity.tuinity.util.Util.getChunkCoordinate(entity.locZ()))) {
                this.loadedEntities.add(entity);
            }
            // Tuinity end - loaded entity list
            new com.destroystokyo.paper.event.entity.EntityAddToWorldEvent(entity.getBukkitEntity()).callEvent(); // Paper - fire while valid
        }

    }

    public void removeEntity(Entity entity) {
        if (this.tickingEntities) {
            throw (IllegalStateException) SystemUtils.c(new IllegalStateException("Removing entity while ticking!"));
        } else {
            this.removeEntityFromChunk(entity);
            this.entitiesById.remove(entity.getId());
            this.unregisterEntity(entity);
            entity.shouldBeRemoved = true; // Paper
        }
    }

    private void removeEntityFromChunk(Entity entity) {
        IChunkAccess ichunkaccess = this.getChunkAt(entity.chunkX, entity.chunkZ, ChunkStatus.FULL, false);

        if (ichunkaccess instanceof Chunk) {
            ((Chunk) ichunkaccess).b(entity);
        }

    }

    public void removePlayer(EntityPlayer entityplayer) {
        entityplayer.die();
        this.removeEntity(entityplayer);
        this.everyoneSleeping();
    }

    public void strikeLightning(EntityLightning entitylightning) {
        // CraftBukkit start
        this.strikeLightning(entitylightning, LightningStrikeEvent.Cause.UNKNOWN);
    }

    public void strikeLightning(EntityLightning entitylightning, LightningStrikeEvent.Cause cause) {
        LightningStrikeEvent lightning = new LightningStrikeEvent(this.getWorld(), (org.bukkit.entity.LightningStrike) entitylightning.getBukkitEntity(), cause);
        this.getServer().getPluginManager().callEvent(lightning);

        if (lightning.isCancelled()) {
            return;
        }
        // CraftBukkit end
        this.globalEntityList.add(entitylightning);
        this.server.getPlayerList().sendPacketNearby((EntityHuman) null, entitylightning.locX(), entitylightning.locY(), entitylightning.locZ(), paperConfig.maxLightningFlashDistance, this, new PacketPlayOutSpawnEntityWeather(entitylightning)); // Paper - use world instead of dimension, limit lightning strike effect distance
    }

    @Override
    public void a(int i, BlockPosition blockposition, int j) {
        Iterator iterator = this.server.getPlayerList().getPlayers().iterator();

        // CraftBukkit start
        EntityHuman entityhuman = null;
        Entity entity = this.getEntity(i);
        if (entity instanceof EntityHuman) entityhuman = (EntityHuman) entity;
        // CraftBukkit end

        while (iterator.hasNext()) {
            EntityPlayer entityplayer = (EntityPlayer) iterator.next();

            if (entityplayer != null && entityplayer.world == this && entityplayer.getId() != i) {
                double d0 = (double) blockposition.getX() - entityplayer.locX();
                double d1 = (double) blockposition.getY() - entityplayer.locY();
                double d2 = (double) blockposition.getZ() - entityplayer.locZ();

                // CraftBukkit start
                if (entityhuman != null && entityhuman instanceof EntityPlayer && !entityplayer.getBukkitEntity().canSee(((EntityPlayer) entityhuman).getBukkitEntity())) {
                    continue;
                }
                // CraftBukkit end

                if (d0 * d0 + d1 * d1 + d2 * d2 < 1024.0D) {
                    entityplayer.playerConnection.sendPacket(new PacketPlayOutBlockBreakAnimation(i, blockposition, j));
                }
            }
        }

    }

    @Override
    public void playSound(@Nullable EntityHuman entityhuman, double d0, double d1, double d2, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1) {
        this.server.getPlayerList().sendPacketNearby(entityhuman, d0, d1, d2, f > 1.0F ? (double) (16.0F * f) : 16.0D, this.worldProvider.getDimensionManager(), new PacketPlayOutNamedSoundEffect(soundeffect, soundcategory, d0, d1, d2, f, f1));
    }

    @Override
    public void playSound(@Nullable EntityHuman entityhuman, Entity entity, SoundEffect soundeffect, SoundCategory soundcategory, float f, float f1) {
        this.server.getPlayerList().sendPacketNearby(entityhuman, entity.locX(), entity.locY(), entity.locZ(), f > 1.0F ? (double) (16.0F * f) : 16.0D, this.worldProvider.getDimensionManager(), new PacketPlayOutEntitySound(soundeffect, soundcategory, entity, f, f1));
    }

    @Override
    public void b(int i, BlockPosition blockposition, int j) {
        this.server.getPlayerList().sendAll(new PacketPlayOutWorldEvent(i, blockposition, j, true));
    }

    @Override
    public void a(@Nullable EntityHuman entityhuman, int i, BlockPosition blockposition, int j) {
        this.server.getPlayerList().sendPacketNearby(entityhuman, (double) blockposition.getX(), (double) blockposition.getY(), (double) blockposition.getZ(), 64.0D, this.worldProvider.getDimensionManager(), new PacketPlayOutWorldEvent(i, blockposition, j, false));
    }

    @Override
    public void notify(BlockPosition blockposition, IBlockData iblockdata, IBlockData iblockdata1, int i) {
        this.getChunkProvider().flagDirty(blockposition);
        VoxelShape voxelshape = iblockdata.getCollisionShape(this, blockposition);
        VoxelShape voxelshape1 = iblockdata1.getCollisionShape(this, blockposition);

        if (VoxelShapes.c(voxelshape, voxelshape1, OperatorBoolean.NOT_SAME)) {
            boolean wasTicking = this.tickingEntities; this.tickingEntities = true; // Paper
            Iterator iterator = this.navigators.iterator();

            while (iterator.hasNext()) {
                NavigationAbstract navigationabstract = (NavigationAbstract) iterator.next();

                if (!navigationabstract.i()) {
                    navigationabstract.b(blockposition);
                }
            }

            this.tickingEntities = wasTicking; // Paper
        }
    }

    @Override
    public void broadcastEntityEffect(Entity entity, byte b0) {
        this.getChunkProvider().broadcastIncludingSelf(entity, new PacketPlayOutEntityStatus(entity, b0));
    }

    @Override
    public ChunkProviderServer getChunkProvider() {
        return (ChunkProviderServer) super.getChunkProvider();
    }

    @Override
    public Explosion createExplosion(@Nullable Entity entity, @Nullable DamageSource damagesource, double d0, double d1, double d2, float f, boolean flag, Explosion.Effect explosion_effect) {
        // CraftBukkit start
        Explosion explosion = super.createExplosion(entity, damagesource, d0, d1, d2, f, flag, explosion_effect);

        if (explosion.wasCanceled) {
            return explosion;
        }

        /* Remove
        Explosion explosion = new Explosion(this, entity, d0, d1, d2, f, flag, explosion_effect);

        if (damagesource != null) {
            explosion.a(damagesource);
        }

        explosion.a();
        explosion.a(false);
        */
        // CraftBukkit end - TODO: Check if explosions are still properly implemented
        if (explosion_effect == Explosion.Effect.NONE) {
            explosion.clearBlocks();
        }

        Iterator iterator = this.players.iterator();

        while (iterator.hasNext()) {
            EntityPlayer entityplayer = (EntityPlayer) iterator.next();

            if (entityplayer.g(d0, d1, d2) < 4096.0D) {
                entityplayer.playerConnection.sendPacket(new PacketPlayOutExplosion(d0, d1, d2, f, explosion.getBlocks(), (Vec3D) explosion.c().get(entityplayer)));
            }
        }

        return explosion;
    }

    @Override
    public void playBlockAction(BlockPosition blockposition, Block block, int i, int j) {
        this.I.add(new BlockActionData(blockposition, block, i, j));
    }

    private void ad() {
        while (!this.I.isEmpty()) {
            BlockActionData blockactiondata = (BlockActionData) this.I.removeFirst();

            if (this.a(blockactiondata)) {
                this.server.getPlayerList().sendPacketNearby((EntityHuman) null, (double) blockactiondata.a().getX(), (double) blockactiondata.a().getY(), (double) blockactiondata.a().getZ(), 64.0D, this, new PacketPlayOutBlockAction(blockactiondata.a(), blockactiondata.b(), blockactiondata.c(), blockactiondata.d()));
            }
        }

    }

    private boolean a(BlockActionData blockactiondata) {
        IBlockData iblockdata = this.getType(blockactiondata.a());

        return iblockdata.getBlock() == blockactiondata.b() ? iblockdata.a(this, blockactiondata.a(), blockactiondata.c(), blockactiondata.d()) : false;
    }

    @Override
    public TickListServer<Block> getBlockTickList() {
        return this.nextTickListBlock;
    }

    @Override
    public TickListServer<FluidType> getFluidTickList() {
        return this.nextTickListFluid;
    }

    @Nonnull
    @Override
    public MinecraftServer getMinecraftServer() {
        return this.server;
    }

    public PortalTravelAgent getTravelAgent() {
        return this.portalTravelAgent;
    }

    public DefinedStructureManager r() {
        return this.dataManager.f();
    }

    public <T extends ParticleParam> int a(T t0, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6) {
        // CraftBukkit - visibility api support
        return sendParticles(null, t0, d0, d1, d2, i, d3, d4, d5, d6, false);
    }

    public <T extends ParticleParam> int sendParticles(EntityPlayer sender, T t0, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, boolean force) {
        // Paper start - Particle API Expansion
        return sendParticles(players, sender, t0, d0, d1, d2, i, d3, d4, d5, d6, force);
    }
    public <T extends ParticleParam> int sendParticles(List<EntityPlayer> receivers, EntityPlayer sender, T t0, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6, boolean force) {
        // Paper end
        PacketPlayOutWorldParticles packetplayoutworldparticles = new PacketPlayOutWorldParticles(t0, force, d0, d1, d2, (float) d3, (float) d4, (float) d5, (float) d6, i);
        // CraftBukkit end
        int j = 0;

        for (EntityHuman entityhuman : receivers) { // Paper - Particle API Expansion
            EntityPlayer entityplayer = (EntityPlayer) entityhuman; // Paper - Particle API Expansion
            if (sender != null && !entityplayer.getBukkitEntity().canSee(sender.getBukkitEntity())) continue; // CraftBukkit

            if (this.a(entityplayer, force, d0, d1, d2, packetplayoutworldparticles)) { // CraftBukkit
                ++j;
            }
        }

        return j;
    }

    public <T extends ParticleParam> boolean a(EntityPlayer entityplayer, T t0, boolean flag, double d0, double d1, double d2, int i, double d3, double d4, double d5, double d6) {
        Packet<?> packet = new PacketPlayOutWorldParticles(t0, flag, d0, d1, d2, (float) d3, (float) d4, (float) d5, (float) d6, i);

        return this.a(entityplayer, flag, d0, d1, d2, packet);
    }

    private boolean a(EntityPlayer entityplayer, boolean flag, double d0, double d1, double d2, Packet<?> packet) {
        if (entityplayer.getWorldServer() != this) {
            return false;
        } else {
            BlockPosition blockposition = entityplayer.getChunkCoordinates();

            if (blockposition.a((IPosition) (new Vec3D(d0, d1, d2)), flag ? 512.0D : 32.0D)) {
                entityplayer.playerConnection.sendPacket(packet);
                return true;
            } else {
                return false;
            }
        }
    }

    @Nullable
    @Override
    public Entity getEntity(int i) {
        return (Entity) this.entitiesById.get(i);
    }

    @Nullable
    public Entity getEntity(UUID uuid) {
        return (Entity) this.entitiesByUUID.get(uuid);
    }

    @Nullable
    public BlockPosition a(String s, BlockPosition blockposition, int i, boolean flag) {
        return this.getChunkProvider().getChunkGenerator().findNearestMapFeature(this, s, blockposition, i, flag);
    }

    @Override
    public CraftingManager getCraftingManager() {
        return this.server.getCraftingManager();
    }

    @Override
    public TagRegistry t() {
        return this.server.getTagRegistry();
    }

    @Override
    public void a(long i) {
        super.a(i);
        this.worldData.y().a(this.server, i);
    }

    @Override
    public boolean isSavingDisabled() {
        return this.savingDisabled;
    }

    public void checkSession() throws ExceptionWorldConflict {
        this.dataManager.checkSession();
    }

    public WorldNBTStorage getDataManager() {
        return this.dataManager;
    }

    public WorldPersistentData getWorldPersistentData() {
        return this.getChunkProvider().getWorldPersistentData();
    }

    @Nullable
    @Override
    public WorldMap a(String s) {
        return (WorldMap) this.getMinecraftServer().getWorldServer(DimensionManager.OVERWORLD).getWorldPersistentData().b(() -> {
            // CraftBukkit start
            // We only get here when the data file exists, but is not a valid map
            WorldMap newMap = new WorldMap(s);
            MapInitializeEvent event = new MapInitializeEvent(newMap.mapView);
            Bukkit.getServer().getPluginManager().callEvent(event);
            return newMap;
            // CraftBukkit end
        }, s);
    }

    @Override
    public void a(WorldMap worldmap) {
        this.getMinecraftServer().getWorldServer(DimensionManager.OVERWORLD).getWorldPersistentData().a((PersistentBase) worldmap);
    }

    @Override
    public int getWorldMapCount() {
        return ((PersistentIdCounts) this.getMinecraftServer().getWorldServer(DimensionManager.OVERWORLD).getWorldPersistentData().a(PersistentIdCounts::new, "idcounts")).a();
    }

    // Paper start - helper function for configurable spawn radius
    public void addTicketsForSpawn(int radiusInBlocks, BlockPosition spawn) {
        // In order to respect vanilla behavior, which is ensuring everything but the spawn border can tick, we add tickets
        // with level 31 for the non-border spawn chunks
        ChunkProviderServer chunkproviderserver = this.getChunkProvider();
        int tickRadius = radiusInBlocks - 16;

        // add ticking chunks
        for (int x = -tickRadius; x <= tickRadius; x += 16) {
            for (int z = -tickRadius; z <= tickRadius; z += 16) {
                // radius of 2 will have the current chunk be level 31
                chunkproviderserver.addTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(x, 0, z)), 2, Unit.INSTANCE);
            }
        }

        // add border chunks

        // add border along x axis (including corner chunks)
        for (int x = -radiusInBlocks; x <= radiusInBlocks; x += 16) {
            // top
            chunkproviderserver.addTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(x, 0, radiusInBlocks)), 1, Unit.INSTANCE); // level 32
            // bottom
            chunkproviderserver.addTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(x, 0, -radiusInBlocks)), 1, Unit.INSTANCE); // level 32
        }

        // add border along z axis (excluding corner chunks)
        for (int z = -radiusInBlocks + 16; z < radiusInBlocks; z += 16) {
            // right
            chunkproviderserver.addTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(radiusInBlocks, 0, z)), 1, Unit.INSTANCE); // level 32
            // left
            chunkproviderserver.addTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(-radiusInBlocks, 0, z)), 1, Unit.INSTANCE); // level 32
        }
    }
    public void removeTicketsForSpawn(int radiusInBlocks, BlockPosition spawn) {
        // In order to respect vanilla behavior, which is ensuring everything but the spawn border can tick, we added tickets
        // with level 31 for the non-border spawn chunks
        ChunkProviderServer chunkproviderserver = this.getChunkProvider();
        int tickRadius = radiusInBlocks - 16;

        // remove ticking chunks
        for (int x = -tickRadius; x <= tickRadius; x += 16) {
            for (int z = -tickRadius; z <= tickRadius; z += 16) {
                // radius of 2 will have the current chunk be level 31
                chunkproviderserver.removeTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(x, 0, z)), 2, Unit.INSTANCE);
            }
        }

        // remove border chunks

        // remove border along x axis (including corner chunks)
        for (int x = -radiusInBlocks; x <= radiusInBlocks; x += 16) {
            // top
            chunkproviderserver.removeTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(x, 0, radiusInBlocks)), 1, Unit.INSTANCE); // level 32
            // bottom
            chunkproviderserver.removeTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(x, 0, -radiusInBlocks)), 1, Unit.INSTANCE); // level 32
        }

        // remove border along z axis (excluding corner chunks)
        for (int z = -radiusInBlocks + 16; z < radiusInBlocks; z += 16) {
            // right
            chunkproviderserver.removeTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(radiusInBlocks, 0, z)), 1, Unit.INSTANCE); // level 32
            // left
            chunkproviderserver.removeTicket(TicketType.START, new ChunkCoordIntPair(spawn.add(-radiusInBlocks, 0, z)), 1, Unit.INSTANCE); // level 32
        }
    }
    // Paper end

    @Override
    public void a_(BlockPosition blockposition) {
        // Paper - configurable spawn radius
        BlockPosition prevSpawn = this.getSpawn();

        super.a_(blockposition);
        if (this.keepSpawnInMemory) {
            // if this keepSpawnInMemory is false a plugin has already removed our tickets, do not re-add
            this.removeTicketsForSpawn(this.paperConfig.keepLoadedRange, prevSpawn);
            this.addTicketsForSpawn(this.paperConfig.keepLoadedRange, blockposition);
        }
        // Paper end
    }

    public LongSet getForceLoadedChunks() {
        ForcedChunk forcedchunk = (ForcedChunk) this.getWorldPersistentData().b(ForcedChunk::new, "chunks");

        return (LongSet) (forcedchunk != null ? LongSets.unmodifiable(forcedchunk.a()) : LongSets.EMPTY_SET);
    }

    public boolean setForceLoaded(int i, int j, boolean flag) {
        ForcedChunk forcedchunk = (ForcedChunk) this.getWorldPersistentData().a(ForcedChunk::new, "chunks");
        ChunkCoordIntPair chunkcoordintpair = new ChunkCoordIntPair(i, j);
        long k = chunkcoordintpair.pair();
        boolean flag1;

        if (flag) {
            flag1 = forcedchunk.a().add(k);
            if (flag1) {
                this.getChunkAt(i, j);
            }
        } else {
            flag1 = forcedchunk.a().remove(k);
        }

        forcedchunk.a(flag1);
        if (flag1) {
            this.getChunkProvider().a(chunkcoordintpair, flag);
        }

        return flag1;
    }

    @Override
    public List<EntityPlayer> getPlayers() {
        return this.players;
    }

    @Override
    public void a(BlockPosition blockposition, IBlockData iblockdata, IBlockData iblockdata1) {
        Optional<VillagePlaceType> optional = VillagePlaceType.b(iblockdata);
        Optional<VillagePlaceType> optional1 = VillagePlaceType.b(iblockdata1);

        if (!Objects.equals(optional, optional1)) {
            BlockPosition blockposition1 = blockposition.immutableCopy();

            optional.ifPresent((villageplacetype) -> {
                this.getMinecraftServer().execute(() -> {
                    this.B().a(blockposition1);
                    PacketDebug.b(this, blockposition1);
                });
            });
            optional1.ifPresent((villageplacetype) -> {
                this.getMinecraftServer().execute(() -> {
                    this.B().a(blockposition1, villageplacetype);
                    PacketDebug.a(this, blockposition1);
                });
            });
        }
    }

    public VillagePlace B() {
        return this.getChunkProvider().j();
    }

    public boolean b_(BlockPosition blockposition) {
        return this.a(blockposition, 1);
    }

    public boolean a(SectionPosition sectionposition) {
        return this.b_(sectionposition.t());
    }

    public boolean a(BlockPosition blockposition, int i) {
        return i > 6 ? false : this.b(SectionPosition.a(blockposition)) <= i;
    }

    public int b(SectionPosition sectionposition) {
        return this.B().a(sectionposition);
    }

    public PersistentRaid getPersistentRaid() {
        return this.persistentRaid;
    }

    @Nullable
    public Raid c_(BlockPosition blockposition) {
        return this.persistentRaid.getNearbyRaid(blockposition, 9216);
    }

    public boolean e(BlockPosition blockposition) {
        return this.c_(blockposition) != null;
    }

    public void a(ReputationEvent reputationevent, Entity entity, ReputationHandler reputationhandler) {
        reputationhandler.a(reputationevent, entity);
    }

    public void a(java.nio.file.Path java_nio_file_path) throws IOException {
        PlayerChunkMap playerchunkmap = this.getChunkProvider().playerChunkMap;
        BufferedWriter bufferedwriter = Files.newBufferedWriter(java_nio_file_path.resolve("stats.txt"));
        Throwable throwable = null;

        try {
            bufferedwriter.write(String.format("spawning_chunks: %d\n", playerchunkmap.e().b()));
            ObjectIterator objectiterator = this.l().object2IntEntrySet().iterator();

            while (objectiterator.hasNext()) {
                it.unimi.dsi.fastutil.objects.Object2IntMap.Entry<EnumCreatureType> it_unimi_dsi_fastutil_objects_object2intmap_entry = (it.unimi.dsi.fastutil.objects.Object2IntMap.Entry) objectiterator.next();

                bufferedwriter.write(String.format("spawn_count.%s: %d\n", ((EnumCreatureType) it_unimi_dsi_fastutil_objects_object2intmap_entry.getKey()).a(), it_unimi_dsi_fastutil_objects_object2intmap_entry.getIntValue()));
            }

            bufferedwriter.write(String.format("entities: %d\n", this.entitiesById.size()));
            bufferedwriter.write(String.format("block_entities: %d\n", this.tileEntityListTick.size())); // Paper - remove unused list
            bufferedwriter.write(String.format("block_ticks: %d\n", this.getBlockTickList().a()));
            bufferedwriter.write(String.format("fluid_ticks: %d\n", this.getFluidTickList().a()));
            bufferedwriter.write("distance_manager: " + playerchunkmap.e().c() + "\n");
            bufferedwriter.write(String.format("pending_tasks: %d\n", this.getChunkProvider().f()));
        } catch (Throwable throwable1) {
            throwable = throwable1;
            throw throwable1;
        } finally {
            if (bufferedwriter != null) {
                if (throwable != null) {
                    try {
                        bufferedwriter.close();
                    } catch (Throwable throwable2) {
                        throwable.addSuppressed(throwable2);
                    }
                } else {
                    bufferedwriter.close();
                }
            }

        }

        CrashReport crashreport = new CrashReport("Level dump", new Exception("dummy"));

        this.a(crashreport);
        BufferedWriter bufferedwriter1 = Files.newBufferedWriter(java_nio_file_path.resolve("example_crash.txt"));
        Throwable throwable3 = null;

        try {
            bufferedwriter1.write(crashreport.e());
        } catch (Throwable throwable4) {
            throwable3 = throwable4;
            throw throwable4;
        } finally {
            if (bufferedwriter1 != null) {
                if (throwable3 != null) {
                    try {
                        bufferedwriter1.close();
                    } catch (Throwable throwable5) {
                        throwable3.addSuppressed(throwable5);
                    }
                } else {
                    bufferedwriter1.close();
                }
            }

        }

        java.nio.file.Path java_nio_file_path1 = java_nio_file_path.resolve("chunks.csv");
        BufferedWriter bufferedwriter2 = Files.newBufferedWriter(java_nio_file_path1);
        Throwable throwable6 = null;

        try {
            playerchunkmap.a((Writer) bufferedwriter2);
        } catch (Throwable throwable7) {
            throwable6 = throwable7;
            throw throwable7;
        } finally {
            if (bufferedwriter2 != null) {
                if (throwable6 != null) {
                    try {
                        bufferedwriter2.close();
                    } catch (Throwable throwable8) {
                        throwable6.addSuppressed(throwable8);
                    }
                } else {
                    bufferedwriter2.close();
                }
            }

        }

        java.nio.file.Path java_nio_file_path2 = java_nio_file_path.resolve("entities.csv");
        BufferedWriter bufferedwriter3 = Files.newBufferedWriter(java_nio_file_path2);
        Throwable throwable9 = null;

        try {
            a((Writer) bufferedwriter3, (Iterable) this.entitiesById.values());
        } catch (Throwable throwable10) {
            throwable9 = throwable10;
            throw throwable10;
        } finally {
            if (bufferedwriter3 != null) {
                if (throwable9 != null) {
                    try {
                        bufferedwriter3.close();
                    } catch (Throwable throwable11) {
                        throwable9.addSuppressed(throwable11);
                    }
                } else {
                    bufferedwriter3.close();
                }
            }

        }

        java.nio.file.Path java_nio_file_path3 = java_nio_file_path.resolve("global_entities.csv");
        BufferedWriter bufferedwriter4 = Files.newBufferedWriter(java_nio_file_path3);
        Throwable throwable12 = null;

        try {
            a((Writer) bufferedwriter4, (Iterable) this.globalEntityList);
        } catch (Throwable throwable13) {
            throwable12 = throwable13;
            throw throwable13;
        } finally {
            if (bufferedwriter4 != null) {
                if (throwable12 != null) {
                    try {
                        bufferedwriter4.close();
                    } catch (Throwable throwable14) {
                        throwable12.addSuppressed(throwable14);
                    }
                } else {
                    bufferedwriter4.close();
                }
            }

        }

        java.nio.file.Path java_nio_file_path4 = java_nio_file_path.resolve("block_entities.csv");
        BufferedWriter bufferedwriter5 = Files.newBufferedWriter(java_nio_file_path4);
        Throwable throwable15 = null;

        try {
            this.a((Writer) bufferedwriter5);
        } catch (Throwable throwable16) {
            throwable15 = throwable16;
            throw throwable16;
        } finally {
            if (bufferedwriter5 != null) {
                if (throwable15 != null) {
                    try {
                        bufferedwriter5.close();
                    } catch (Throwable throwable17) {
                        throwable15.addSuppressed(throwable17);
                    }
                } else {
                    bufferedwriter5.close();
                }
            }

        }

    }

    private static void a(Writer writer, Iterable<Entity> iterable) throws IOException {
        CSVWriter csvwriter = CSVWriter.a().a("x").a("y").a("z").a("uuid").a("type").a("alive").a("display_name").a("custom_name").a(writer);
        Iterator iterator = iterable.iterator();

        while (iterator.hasNext()) {
            Entity entity = (Entity) iterator.next();
            IChatBaseComponent ichatbasecomponent = entity.getCustomName();
            IChatBaseComponent ichatbasecomponent1 = entity.getScoreboardDisplayName();

            csvwriter.a(entity.locX(), entity.locY(), entity.locZ(), entity.getUniqueID(), IRegistry.ENTITY_TYPE.getKey(entity.getEntityType()), entity.isAlive(), ichatbasecomponent1.getString(), ichatbasecomponent != null ? ichatbasecomponent.getString() : null);
        }

    }

    private void a(Writer writer) throws IOException {
        CSVWriter csvwriter = CSVWriter.a().a("x").a("y").a("z").a("type").a(writer);
        Iterator iterator = this.tileEntityListTick.iterator(); // Paper - remove unused list

        while (iterator.hasNext()) {
            TileEntity tileentity = (TileEntity) iterator.next();
            BlockPosition blockposition = tileentity.getPosition();

            csvwriter.a(blockposition.getX(), blockposition.getY(), blockposition.getZ(), IRegistry.BLOCK_ENTITY_TYPE.getKey(tileentity.getTileType()));
        }

    }

    @VisibleForTesting
    public void a(StructureBoundingBox structureboundingbox) {
        this.I.removeIf((blockactiondata) -> {
            return structureboundingbox.b((BaseBlockPosition) blockactiondata.a());
        });
    }
}
