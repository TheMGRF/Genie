package net.minecraft.server;

import co.aikar.timings.Timing; // Paper
import com.destroystokyo.paper.PaperWorldConfig; // Paper
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.ComparisonChain; // Paper
import com.google.common.collect.Lists;
import com.google.common.collect.Queues;
import com.google.common.collect.Sets;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectBidirectionalIterator;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap; // Paper
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map; // Paper
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.UUID; // Paper
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.entity.Player; // CraftBukkit

public class PlayerChunkMap extends IChunkLoader implements PlayerChunk.d {

    private static final Logger LOGGER = LogManager.getLogger();
    public static final int GOLDEN_TICKET = 33 + ChunkStatus.b();
    //public final Long2ObjectLinkedOpenHashMap<PlayerChunk> updatingChunks = new Long2ObjectLinkedOpenHashMap(); // Tuinity - replace chunk map
    //public volatile Long2ObjectLinkedOpenHashMap<PlayerChunk> visibleChunks; // Tuinity - replace chunk map
    public final com.tuinity.tuinity.chunk.QueuedChangesMapLong2Object<PlayerChunk> chunkMap = new com.tuinity.tuinity.chunk.QueuedChangesMapLong2Object<>(8192, 0.7f); // Tuinity - replace chunk map
    private final Long2ObjectLinkedOpenHashMap<PlayerChunk> pendingUnload;
    final LongSet loadedChunks; // Paper - private -> package
    public final WorldServer world;
    private final LightEngineThreaded lightEngine;
    private final IAsyncTaskHandler<Runnable> executor;
    public final ChunkGenerator<?> chunkGenerator;
    private final Supplier<WorldPersistentData> l; public final Supplier<WorldPersistentData> getWorldPersistentDataSupplier() { return this.l; } // Paper - OBFHELPER
    private final VillagePlace m;
    public final LongSet unloadQueue;
    private boolean updatingChunksModified;
    private final ChunkTaskQueueSorter p;
    private final Mailbox<ChunkTaskQueueSorter.a<Runnable>> mailboxWorldGen;
    private final Mailbox<ChunkTaskQueueSorter.a<Runnable>> mailboxMain;
    public final WorldLoadListener worldLoadListener;
    public final PlayerChunkMap.a chunkDistanceManager; public final PlayerChunkMap.a getChunkMapDistanceManager() { return this.chunkDistanceManager; } // Paper - OBFHELPER
    private final AtomicInteger u;
    public final DefinedStructureManager definedStructureManager; // Paper - private -> public
    private final File w;
    private final PlayerMap playerMap;
    public final Int2ObjectMap<PlayerChunkMap.EntityTracker> trackedEntities;
    private final Queue<Runnable> z;
    int viewDistance; public final int getViewDistance() { return this.viewDistance; } // Tuinity - OBFHELPER // Paper - private -> package private
    //public final com.destroystokyo.paper.util.PlayerMobDistanceMap playerMobDistanceMap; // Paper // Tuinity - replaced by view distance map

    // CraftBukkit start - recursion-safe executor for Chunk loadCallback() and unloadCallback()
    public final CallbackExecutor callbackExecutor = new CallbackExecutor();
    public static final class CallbackExecutor implements java.util.concurrent.Executor, Runnable {

        private Runnable queued;

        @Override
        public void execute(Runnable runnable) {
            if (queued != null) {
                throw new IllegalStateException("Already queued");
            }
            queued = runnable;
        }

        @Override
        public void run() {
            Runnable task = queued;
            queued = null;
            if (task != null) {
                task.run();
            }
        }
    };
    // CraftBukkit end

    // Paper start - distance maps
    private final com.destroystokyo.paper.util.misc.PooledLinkedHashSets<EntityPlayer> pooledLinkedPlayerHashSets = new com.destroystokyo.paper.util.misc.PooledLinkedHashSets<>();

    // Tuinity start - per player view distance
    int noTickViewDistance;
    public final int getNoTickViewDistance() {
        return this.noTickViewDistance;
    }
    // we use this map to broadcast chunks to clients
    // they do not render chunks without having at least neighbours in a 1 chunk radius loaded
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerViewDistanceBroadcastMap;
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerViewDistanceTickMap;
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerViewDistanceNoTickMap;

    final ChunkSendThrottler chunkSendThrottler = new ChunkSendThrottler();

    public void updateViewDistance(EntityPlayer player, int viewDistance, int noTickViewDistance) {
        player.viewDistance = viewDistance;
        player.noTickViewDistance = noTickViewDistance;

        int chunkX = com.tuinity.tuinity.util.Util.getChunkCoordinate(player.locX());
        int chunkZ = com.tuinity.tuinity.util.Util.getChunkCoordinate(player.locZ());

        int effectiveViewDistance = viewDistance == -1 ? this.viewDistance : viewDistance;
        int effectiveNoTickViewDistance = Math.max(effectiveViewDistance, noTickViewDistance == -1 ? this.noTickViewDistance : noTickViewDistance);

        player.playerConnection.sendPacket(new PacketPlayOutViewDistance(effectiveNoTickViewDistance));

        if (!this.cannotLoadChunks(player)) {
            this.playerViewDistanceTickMap.update(player, chunkX, chunkZ, effectiveViewDistance);
            this.playerViewDistanceNoTickMap.update(player, chunkX, chunkZ, effectiveNoTickViewDistance + 2); // clients need chunk neighbours // add an extra one for antixray
        }
        this.playerViewDistanceMap.update(player, chunkX, chunkZ, effectiveViewDistance);
        player.needsChunkCenterUpdate = true;
        this.playerViewDistanceBroadcastMap.update(player, chunkX, chunkZ, effectiveNoTickViewDistance + 1); // clients need chunk neighbours
        player.needsChunkCenterUpdate = false;
        // Tuinity start - optimise PlayerChunkMap#isOutsideRange
        this.playerChunkTickRangeMap.update(player, chunkX, chunkZ, ChunkMapDistance.MOB_SPAWN_RANGE);
        // Tuinity end - optimise PlayerChunkMap#isOutsideRange

        // Tuinity start - use distance map to optimise entity tracker
        // force propagate tracker changes
        if (this.playerEntityTrackerTrackMap != null) {
            this.playerEntityTrackerTrackMap.update(player, chunkX, chunkZ, Math.min(this.entityTrackerTrackRange, effectiveViewDistance));
            this.playerEntityTrackerUntrackMap.update(player, chunkX, chunkZ, Math.min(this.entityTrackerUntrackRange, effectiveViewDistance));
        }
        // Tuinity end - use distance map to optimise entity tracker
    }

    final class ChunkSendThrottler {

        static final int ALREADY_QUEUED = 0;
        static final int QUEUED = 1;
        static final int FAILED = 2;

        protected final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap lastLoadedRadiusByPlayer = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(512, 0.5f);

        {
            this.lastLoadedRadiusByPlayer.defaultReturnValue(-1);
        }

        protected final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap lastChunkPositionByPlayer = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap(512, 0.5f);

        {
            this.lastChunkPositionByPlayer.defaultReturnValue(Long.MIN_VALUE);
        }

        protected final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap chunkSendCountPerPlayer = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(512, 0.5f);

        protected final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap lastChunkSendStartTimePerPlayer = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap(512, 0.5f);

        protected final java.util.List<EntityPlayer> players = new java.util.ArrayList<>(256);

        protected final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Packet[]> cachedChunkPackets = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        void addPlayer(EntityPlayer player) {
            this.players.add(player);
        }

        void removePlayer(EntityPlayer player) {
            this.players.remove(player);
            this.lastLoadedRadiusByPlayer.remove(player.getId());
            this.chunkSendCountPerPlayer.remove(player.getId());
            this.lastChunkPositionByPlayer.remove(player.getId());
            player.loadedChunks.clear();
        }

        int trySendChunk(int chunkX, int chunkZ, EntityPlayer player) {
            long coordinate = com.tuinity.tuinity.util.Util.getCoordinateKey(chunkX, chunkZ);
            PlayerChunk playerChunk = PlayerChunkMap.this.chunkMap.getUpdating(coordinate);

            if (playerChunk == null) {
                return FAILED;
            }
            Chunk chunk = playerChunk.getFullReadyChunk();
            if (chunk == null || !chunk.areNeighboursLoaded(1)) {
                return FAILED;
            }

            if (!player.loadedChunks.add(coordinate)) {
                return ALREADY_QUEUED;
            }

            Packet[] chunkPackets = this.cachedChunkPackets.computeIfAbsent(coordinate, (long keyInMap) -> new Packet[2]);
            PlayerChunkMap.this.sendChunk(player, chunkPackets, chunk);

            return QUEUED;
        }

        void tick() {
            int maxChunkSends = com.tuinity.tuinity.config.TuinityConfig.maxChunkSendsPerPlayerChoice[MinecraftServer.currentTick % com.tuinity.tuinity.config.TuinityConfig.maxChunkSendsPerPlayerChoice.length];
            for (EntityPlayer player : this.players) {
                int playerId = player.getId();
                int lastLoadedRadius = this.lastLoadedRadiusByPlayer.get(playerId);
                long lastChunkPos = this.lastChunkPositionByPlayer.get(playerId);
                long currentChunkPos = PlayerChunkMap.this.playerViewDistanceBroadcastMap.getLastCoordinate(player);

                if (currentChunkPos == Long.MIN_VALUE) {
                    // not tracking for whatever reason...
                    continue;
                }

                int newX = com.tuinity.tuinity.util.Util.getCoordinateX(currentChunkPos);
                int newZ = com.tuinity.tuinity.util.Util.getCoordinateZ(currentChunkPos);

                // handle movement
                if (currentChunkPos != lastChunkPos) {
                    this.lastChunkPositionByPlayer.put(playerId, currentChunkPos);
                    if (lastChunkPos != Long.MIN_VALUE) {
                        int oldX = com.tuinity.tuinity.util.Util.getCoordinateX(lastChunkPos);
                        int oldZ = com.tuinity.tuinity.util.Util.getCoordinateZ(lastChunkPos);

                        int radiusDiff = Math.max(Math.abs(newX - oldX), Math.abs(newZ - oldZ));
                        lastLoadedRadius = Math.max(-1, lastLoadedRadius - radiusDiff);
                        this.lastLoadedRadiusByPlayer.put(playerId, lastLoadedRadius);
                    }
                }

                int radius = lastLoadedRadius + 1;
                int viewDistance = PlayerChunkMap.this.playerViewDistanceBroadcastMap.getLastViewDistance(player);

                if (radius > viewDistance) {
                    // distance map will unload our chunks
                    this.lastLoadedRadiusByPlayer.put(playerId, viewDistance);
                    continue;
                }

                int totalChunkSends = 0;

                if (totalChunkSends >= maxChunkSends) {
                    continue;
                }

                radius_loop:
                for (; radius <= viewDistance; ++radius) {
                    for (int offset = 0; offset <= radius; ++offset) {
                        // try to load the chunks closest to the player by distance
                        // so instead of going left->right on the x axis, we start at the center of the view distance square
                        // and go left and right at the same time

                        // try top 2 chunks
                        // top left
                        int attempt = 0;
                        if ((attempt = this.trySendChunk(newX - offset, newZ + radius, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // top right
                        if ((attempt = this.trySendChunk(newX + offset, newZ + radius, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // try bottom 2 chunks

                        // bottom left
                        if ((attempt = this.trySendChunk(newX - offset, newZ - radius, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // bottom right
                        if ((attempt = this.trySendChunk(newX + offset, newZ - radius, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // try left 2 chunks

                        // left down
                        if ((attempt = this.trySendChunk(newX - radius, newZ - offset, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // left up
                        if ((attempt = this.trySendChunk(newX - radius, newZ + offset, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // try right 2 chunks

                        // right down
                        if ((attempt = this.trySendChunk(newX + radius, newZ - offset, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // right up
                        if ((attempt = this.trySendChunk(newX + radius, newZ + offset, player)) == QUEUED) {
                            if (++totalChunkSends >= maxChunkSends) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }
                    }
                }
                int newLoadedRadius = radius - 1;
                if (newLoadedRadius != lastLoadedRadius) {
                    this.lastLoadedRadiusByPlayer.put(playerId, newLoadedRadius);
                }
            }
            this.cachedChunkPackets.clear();
        }
    }

    // Tuinity end - per player view distance

    // Tuinity start - optimise PlayerChunkMap#isOutsideRange
    // A note about the naming used here:
    // Previously, mojang used a "spawn range" of 8 for controlling both ticking and
    // mob spawn range. However, spigot makes the spawn range configurable by
    // checking if the chunk is in the tick range (8) and the spawn range
    // obviously this means a spawn range > 8 cannot be implemented

    // these maps are named after spigot's uses
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerMobSpawnMap; // this map is absent from updateMaps since it's controlled at the start of a tick
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerChunkTickRangeMap;

    // Tuinity end - optimise PlayerChunkMap#isOutsideRange

    // Tuinity start - use distance map to optimise entity tracker
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerEntityTrackerTrackMap;
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerEntityTrackerUntrackMap;
    public final int entityTrackerTrackRange;
    public final int entityTrackerUntrackRange;

    //public final com.tuinity.tuinity.util.EntityList activelyTrackedEntities; // TODO not yet
    final com.tuinity.tuinity.util.EntityList activelyTrackedEntitiesLegacy;

    public static boolean isLegacyTrackingEntity(Entity entity) {
        return entity.isLegacyTrackingEntity;
    }

    private static int getEntityTrackingChunkRange(int blockRange) {
        int centerChunkRange = (blockRange - 8); // on average, players are in the middle of a chunk, so subtract 8
        return centerChunkRange >>> 4 + ((centerChunkRange & 15) != 0 ? 1 : 0);
    }
    // Tuinity end - use distance map to optimise entity tracker

    // Tuinity start - optimise getPlayersInRange type functions
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerGeneralAreaMap;
    public static final int PLAYER_GENERAL_AREA_MAP_DISTANCE = (32 + 3) + 1;
    public static final int PLAYER_GENERAL_AREA_MAP_DISTANCE_SQUARED_BLOCKS = (16 * PLAYER_GENERAL_AREA_MAP_DISTANCE) * (16 * PLAYER_GENERAL_AREA_MAP_DISTANCE);
    // Tuinity end - optimise getPlayersInRange type functions

    void addPlayerToDistanceMaps(EntityPlayer player) {
        this.updateMaps(player);



    }

    void removePlayerFromDistanceMaps(EntityPlayer player) {




    }

    void updateMaps(EntityPlayer player) {
        int chunkX = MCUtil.getChunkCoordinate(player.locX());
        int chunkZ = MCUtil.getChunkCoordinate(player.locZ());




    }


    // Paper end
    // Tuinity start - distance maps
    final com.tuinity.tuinity.util.map.PooledLinkedHashSets<EntityPlayer> pooledEntityPlayerSets = new com.tuinity.tuinity.util.map.PooledLinkedHashSets<>();
    public final com.tuinity.tuinity.util.map.PlayerAreaMap playerViewDistanceMap;

    void addPlayerToDistanceMapsTuinity(EntityPlayer player) {
        this.updateMapsTuinity(player);

        // Tuinity start - per player view distance
        this.getChunkMapDistanceManager().playerTickViewDistanceHandler.addPlayer(player);
        this.chunkSendThrottler.addPlayer(player);
        // Tuinity end - per player view distance
    }

    void removePlayerFromDistanceMapsTuinity(EntityPlayer player) {
        this.playerViewDistanceMap.remove(player);
        // Tuinity start - per player view distance
        this.playerViewDistanceBroadcastMap.remove(player);
        this.playerViewDistanceTickMap.remove(player);
        this.playerViewDistanceNoTickMap.remove(player);
        this.getChunkMapDistanceManager().playerTickViewDistanceHandler.removePlayer(player);
        this.chunkSendThrottler.removePlayer(player);
        // Tuinity end - per player view distance

        // Tuinity start - optimise PlayerChunkMap#isOutsideRange
        this.playerMobSpawnMap.remove(player);
        this.playerChunkTickRangeMap.remove(player);
        // Tuinity end - optimise PlayerChunkMap#isOutsideRange

        // Tuinity start - use distance map to optimise entity tracker
        if (this.playerEntityTrackerTrackMap != null) {
            this.playerEntityTrackerTrackMap.remove(player);
            this.playerEntityTrackerUntrackMap.remove(player);
        }
        // Tuinity end - use distance map to optimise entity tracker

        // Tuinity start - optimise getPlayersInRange type functions
        this.playerGeneralAreaMap.remove(player);
        // Tuinity end - optimise getPlayersInRange type functions
    }

    void updateDistanceMapsTuinity(EntityPlayer player) {
        this.updateMapsTuinity(player);
    }

    private void updateMapsTuinity(EntityPlayer player) {
        int chunkX = com.tuinity.tuinity.util.Util.getChunkCoordinate(player.locX());
        int chunkZ = com.tuinity.tuinity.util.Util.getChunkCoordinate(player.locZ());

        this.playerViewDistanceMap.update(player, chunkX, chunkZ, player.getEffectiveViewDistance(this)); // Tuinity - per player view distance

        // Tuinity start - per player view distance
        int effectiveViewDistance = player.getEffectiveViewDistance(this);
        int effectiveNoTickViewDistance = Math.max(effectiveViewDistance, player.getEffectiveNoTickViewDistance(this));

        if (!this.cannotLoadChunks(player)) {
            this.playerViewDistanceTickMap.update(player, chunkX, chunkZ, effectiveViewDistance);
            this.playerViewDistanceNoTickMap.update(player, chunkX, chunkZ, effectiveNoTickViewDistance + 2); // clients need chunk neighbours // add an extra one for antixray
        }
        player.needsChunkCenterUpdate = true;
        this.playerViewDistanceBroadcastMap.update(player, chunkX, chunkZ, effectiveNoTickViewDistance + 1); // clients need chunk neighbours
        player.needsChunkCenterUpdate = false;
        // Tuinity end - per player view distance

        // Tuinity start - optimise PlayerChunkMap#isOutsideRange
        this.playerChunkTickRangeMap.update(player, chunkX, chunkZ, ChunkMapDistance.MOB_SPAWN_RANGE);
        // Tuinity end - optimise PlayerChunkMap#isOutsideRange

        // Tuinity start - use distance map to optimise entity tracker
        if (this.playerEntityTrackerTrackMap != null) {
            this.playerEntityTrackerTrackMap.update(player, chunkX, chunkZ, Math.min(this.entityTrackerTrackRange, effectiveViewDistance));
            this.playerEntityTrackerUntrackMap.update(player, chunkX, chunkZ, Math.min(this.entityTrackerUntrackRange, effectiveViewDistance));
        }
        // Tuinity end - use distance map to optimise entity tracker

        // Tuinity start - optimise getPlayersInRange type functions
        this.playerGeneralAreaMap.update(player, chunkX, chunkZ, PLAYER_GENERAL_AREA_MAP_DISTANCE);
        // Tuinity end - optimise getPlayersInRange type functions
    }
    // Tuinity end

    public PlayerChunkMap(WorldServer worldserver, File file, DataFixer datafixer, DefinedStructureManager definedstructuremanager, Executor executor, IAsyncTaskHandler<Runnable> iasynctaskhandler, ILightAccess ilightaccess, ChunkGenerator<?> chunkgenerator, WorldLoadListener worldloadlistener, Supplier<WorldPersistentData> supplier, int i) {
        super(new File(worldserver.getWorldProvider().getDimensionManager().a(file), "region"), datafixer);
        //this.visibleChunks = this.updatingChunks.clone(); // Tuinity - replace chunk map
        this.pendingUnload = new Long2ObjectLinkedOpenHashMap();
        this.loadedChunks = new LongOpenHashSet();
        this.unloadQueue = new LongOpenHashSet();
        this.u = new AtomicInteger();
        this.playerMap = new PlayerMap();
        this.trackedEntities = new Int2ObjectOpenHashMap();
        this.z = new com.destroystokyo.paper.utils.CachedSizeConcurrentLinkedQueue<>(); // Paper
        this.definedStructureManager = definedstructuremanager;
        this.w = worldserver.getWorldProvider().getDimensionManager().a(file);
        this.world = worldserver;
        this.chunkGenerator = chunkgenerator;
        this.executor = iasynctaskhandler;
        ThreadedMailbox<Runnable> threadedmailbox = ThreadedMailbox.a(executor, "worldgen");

        iasynctaskhandler.getClass();
        Mailbox<Runnable> mailbox = Mailbox.a("main", iasynctaskhandler::a);

        this.worldLoadListener = worldloadlistener;
        ThreadedMailbox<Runnable> threadedmailbox1 = ThreadedMailbox.a(executor, "light");

        this.p = new ChunkTaskQueueSorter(ImmutableList.of(threadedmailbox, mailbox, threadedmailbox1), executor, Integer.MAX_VALUE);
        this.mailboxWorldGen = this.p.a(threadedmailbox, false);
        this.mailboxMain = this.p.a(mailbox, false);
        this.lightEngine = new LightEngineThreaded(ilightaccess, this, this.world.getWorldProvider().f(), threadedmailbox1, this.p.a(threadedmailbox1, false));
        this.chunkDistanceManager = new PlayerChunkMap.a(executor, iasynctaskhandler);
        this.l = supplier;
        this.m = new VillagePlace(new File(this.w, "poi"), datafixer, this.world); // Paper
        this.setViewDistance(i);
        // Tuinity start - distance maps
        //this.playerMobDistanceMap = this.world.paperConfig.perPlayerMobSpawns ? new com.destroystokyo.paper.util.PlayerMobDistanceMap() : null; // Paper
        com.tuinity.tuinity.util.map.PooledLinkedHashSets<EntityPlayer> sets = this.pooledEntityPlayerSets;
        this.playerViewDistanceMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets);
        // Tuinity end - distance maps
        // Tuinity start - per player view distance
        this.setNoTickViewDistance(this.world.tuinityConfig.noTickViewDistance < 0 ? this.viewDistance : this.world.tuinityConfig.noTickViewDistance);
        this.playerViewDistanceTickMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets,
                null,
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    if (newState != null) {
                        return;
                    }
                    PlayerChunkMap.this.chunkDistanceManager.playerTickViewDistanceHandler.playerMoveOutOfRange(rangeX, rangeZ);
                });
        this.chunkDistanceManager.playerTickViewDistanceHandler.areaMap = this.playerViewDistanceTickMap;
        this.playerViewDistanceNoTickMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets,
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    if (newState.size() != 1) {
                        return;
                    }
                    PlayerChunkMap.this.chunkDistanceManager.playerMoveInRange(rangeX, rangeZ, currPosX, currPosZ);
                },
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    if (newState != null) {
                        return;
                    }
                    PlayerChunkMap.this.chunkDistanceManager.playerMoveOutOfRange(rangeX, rangeZ, currPosX, currPosZ);
                });
        this.playerViewDistanceBroadcastMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets,
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    if (player.needsChunkCenterUpdate) {
                        player.needsChunkCenterUpdate = false;
                        player.playerConnection.sendPacket(new PacketPlayOutViewCentre(currPosX, currPosZ));
                    }
                },
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    PlayerChunkMap.this.sendChunk(player, rangeX, rangeZ, null, true, false); // unloaded, loaded
                    player.loadedChunks.remove(com.tuinity.tuinity.util.Util.getCoordinateKey(rangeX, rangeZ));
                });
        // Tuinity end - per player view distance
        
        // Tuinity start - optimise PlayerChunkMap#isOutsideRange
        this.playerChunkTickRangeMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets,
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    PlayerChunk playerChunk = PlayerChunkMap.this.getUpdatingChunk(com.tuinity.tuinity.util.Util.getCoordinateKey(rangeX, rangeZ));
                    if (playerChunk != null) {
                        playerChunk.playersInChunkTickRange = newState;
                    }
                },
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    PlayerChunk playerChunk = PlayerChunkMap.this.getUpdatingChunk(com.tuinity.tuinity.util.Util.getCoordinateKey(rangeX, rangeZ));
                    if (playerChunk != null) {
                        playerChunk.playersInChunkTickRange = newState;
                    }
                });
        this.playerMobSpawnMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets,
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    PlayerChunk playerChunk = PlayerChunkMap.this.getUpdatingChunk(com.tuinity.tuinity.util.Util.getCoordinateKey(rangeX, rangeZ));
                    if (playerChunk != null) {
                        playerChunk.playersInMobSpawnRange = newState;
                    }
                },
                (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                 com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                    PlayerChunk playerChunk = PlayerChunkMap.this.getUpdatingChunk(com.tuinity.tuinity.util.Util.getCoordinateKey(rangeX, rangeZ));
                    if (playerChunk != null) {
                        playerChunk.playersInMobSpawnRange = newState;
                    }
                });
        // Tuinity end

        // Tuinity start - use distance map to optimise entity tracker
        if (!this.world.tuinityConfig.useOptimizedTracker) {
            this.playerEntityTrackerTrackMap = null;
            this.playerEntityTrackerUntrackMap = null;
            this.entityTrackerTrackRange = -1;
            this.entityTrackerUntrackRange = -1;
            this.activelyTrackedEntitiesLegacy = null;
        } else {
            this.activelyTrackedEntitiesLegacy = new com.tuinity.tuinity.util.EntityList();

            // avoid player range, that's special-cased
            int maxEntityTrackRange = this.world.spigotConfig.animalTrackingRange;
            if (this.world.spigotConfig.monsterTrackingRange > maxEntityTrackRange) {
                maxEntityTrackRange = this.world.spigotConfig.monsterTrackingRange;
            }
            if (this.world.spigotConfig.miscTrackingRange > maxEntityTrackRange) {
                maxEntityTrackRange = this.world.spigotConfig.miscTrackingRange;
            }
            if (this.world.spigotConfig.otherTrackingRange > maxEntityTrackRange) {
                maxEntityTrackRange = this.world.spigotConfig.otherTrackingRange;
            }
            maxEntityTrackRange = (maxEntityTrackRange >> 4) + ((maxEntityTrackRange & 15) != 0 ? 2 : 1);

            if (this.world.tuinityConfig.optimizedTrackerTrackRange == -1) {
                this.entityTrackerTrackRange = Math.max(1, maxEntityTrackRange - 2);
                this.entityTrackerUntrackRange = Math.max(2, maxEntityTrackRange - 1);
            } else {
                this.entityTrackerTrackRange = this.world.tuinityConfig.optimizedTrackerTrackRange;
                this.entityTrackerUntrackRange = this.world.tuinityConfig.optimizedTrackerUntrackRange;
            }

            this.playerEntityTrackerTrackMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets,
                    (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                     com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                        Chunk chunk = PlayerChunkMap.this.world.getChunkProvider().getChunkAtIfCachedImmediately(rangeX, rangeZ);
                        if (chunk == null) {
                            return;
                        }
                        Entity[] entities = chunk.entities.getRawData();
                        for (int index = 0, len = chunk.entities.size(); index < len; ++index) {
                            Entity entity = entities[index];
                            if (entity.tracker == null) {
                                entity.addToTrackQueue(player);
                            } else {
                                entity.tracker.updateTrackingPlayer(player);
                                entity.clearTrackingQueues(player);
                            }
                        }
                    },
                    null);
            this.playerEntityTrackerUntrackMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets,
                    null,
                    (EntityPlayer player, int rangeX, int rangeZ, int currPosX, int currPosZ, int prevPosX, int prevPosZ,
                     com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> newState) -> {
                        Chunk chunk = PlayerChunkMap.this.world.getChunkProvider().getChunkAtIfCachedImmediately(rangeX, rangeZ);
                        if (chunk == null) {
                            return;
                        }
                        Entity[] entities = chunk.entities.getRawData();
                        for (int index = 0, len = chunk.entities.size(); index < len; ++index) {
                            Entity entity = entities[index];
                            if (entity.tracker == null) {
                                return; // not tracked by player for sure
                            }
                            entity.tracker.removeTrackingPlayer(player);
                            entity.clearTrackingQueues(player);
                        }
                    });
        }
        // Tuinity end - use distance map to optimise entity tracker
        // Tuinity start - optimise getPlayersInRange type functions
        this.playerGeneralAreaMap = new com.tuinity.tuinity.util.map.PlayerAreaMap(sets);
        // Tuinity end - optimise getPlayersInRange type functions
    }

    public void updatePlayerMobTypeMap(Entity entity) {
        if (!this.world.paperConfig.perPlayerMobSpawns) {
            return;
        }
        int chunkX = (int)Math.floor(entity.locX()) >> 4;
        int chunkZ = (int)Math.floor(entity.locZ()) >> 4;
        int index = entity.getEntityType().getEnumCreatureType().ordinal();

        // Tuinity start - use view distance map
        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> players = this.playerViewDistanceMap.getObjectsInRange(chunkX, chunkZ);
        if (players != null) {
            Object[] backingSet = players.getBackingSet();
        for (int i = 0, len = backingSet.length; i < len; ++i) {
            Object temp = backingSet[i];
            if (!(temp instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer)temp;
            if (player.isSpectator() || !player.affectsSpawning) {
                continue;
            }
            // Tuinity end - use view distance map
            ++player.mobCounts[index];
        }
        } // Tuinity - use view distance map
    }

    public int getMobCountNear(EntityPlayer entityPlayer, EnumCreatureType enumCreatureType) {
        return entityPlayer.mobCounts[enumCreatureType.ordinal()];
    }

    private static double getDistanceSquaredFromChunk(ChunkCoordIntPair chunkPos, Entity entity) { return a(chunkPos, entity); } // Tuinity - OBFHELPER
    private static double a(ChunkCoordIntPair chunkcoordintpair, Entity entity) {
        double d0 = (double) (chunkcoordintpair.x * 16 + 8);
        double d1 = (double) (chunkcoordintpair.z * 16 + 8);
        double d2 = d0 - entity.locX();
        double d3 = d1 - entity.locZ();

        return d2 * d2 + d3 * d3;
    }

    private static int b(ChunkCoordIntPair chunkcoordintpair, EntityPlayer entityplayer, boolean flag) {
        int i;
        int j;

        if (flag) {
            SectionPosition sectionposition = entityplayer.K();

            i = sectionposition.a();
            j = sectionposition.c();
        } else {
            i = MathHelper.floor(entityplayer.locX() / 16.0D);
            j = MathHelper.floor(entityplayer.locZ() / 16.0D);
        }

        return a(chunkcoordintpair, i, j);
    }

    private static int a(ChunkCoordIntPair chunkcoordintpair, int i, int j) {
        // Tuinity start - remove ChunkCoordIntPair allocation
        return getSquareRadiusDistance(chunkcoordintpair.x, chunkcoordintpair.z, i, j);
    }
    private static int getSquareRadiusDistance(int chunkX0, int chunkZ0, int i, int j) {
        int k = chunkX0 - i;
        int l = chunkZ0 - j;
        // Tuinity end

        return Math.max(Math.abs(k), Math.abs(l));
    }

    protected LightEngineThreaded a() {
        return this.lightEngine;
    }

    @Nullable
    protected PlayerChunk getUpdatingChunk(long i) {
        return (PlayerChunk) this.chunkMap.getUpdating(i); // Tuinity - replace chunk map
    }

    @Nullable
    public PlayerChunk getVisibleChunk(long i) { // Paper - protected -> public
        // Tuinity start - replace chunk map
        if (MinecraftServer.getServer().serverThread == Thread.currentThread()) {
            return this.chunkMap.getVisible(i);
        }
        return (PlayerChunk) this.chunkMap.getVisibleAsync(i);
        // Tuinity end - replace chunk map
    }

    protected IntSupplier c(long i) {
        return () -> {
            PlayerChunk playerchunk = this.getVisibleChunk(i);

            return playerchunk == null ? ChunkTaskQueue.a - 1 : Math.min(playerchunk.k(), ChunkTaskQueue.a - 1);
        };
    }

    private CompletableFuture<Either<List<IChunkAccess>, PlayerChunk.Failure>> a(ChunkCoordIntPair chunkcoordintpair, int i, IntFunction<ChunkStatus> intfunction) {
        List<CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>>> list = Lists.newArrayList();
        int j = chunkcoordintpair.x;
        int k = chunkcoordintpair.z;

        for (int l = -i; l <= i; ++l) {
            for (int i1 = -i; i1 <= i; ++i1) {
                int j1 = Math.max(Math.abs(i1), Math.abs(l));
                final ChunkCoordIntPair chunkcoordintpair1 = new ChunkCoordIntPair(j + i1, k + l);
                long k1 = chunkcoordintpair1.pair();
                PlayerChunk playerchunk = this.getUpdatingChunk(k1);

                if (playerchunk == null) {
                    return CompletableFuture.completedFuture(Either.right(new PlayerChunk.Failure() {
                        public String toString() {
                            return "Unloaded " + chunkcoordintpair1.toString();
                        }
                    }));
                }

                ChunkStatus chunkstatus = (ChunkStatus) intfunction.apply(j1);
                CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> completablefuture = playerchunk.a(chunkstatus, this);

                list.add(completablefuture);
            }
        }

        CompletableFuture<List<Either<IChunkAccess, PlayerChunk.Failure>>> completablefuture1 = SystemUtils.b(list);

        return completablefuture1.thenApply((list1) -> {
            List<IChunkAccess> list2 = Lists.newArrayList();
            // CraftBukkit start - decompile error
            int cnt = 0;

            for (Iterator iterator = list1.iterator(); iterator.hasNext(); ++cnt) {
                final int l1 = cnt;
                // CraftBukkit end
                final Either<IChunkAccess, PlayerChunk.Failure> either = (Either) iterator.next();
                Optional<IChunkAccess> optional = either.left();

                if (!optional.isPresent()) {
                    return Either.right(new PlayerChunk.Failure() {
                        public String toString() {
                            return "Unloaded " + new ChunkCoordIntPair(j + l1 % (i * 2 + 1), k + l1 / (i * 2 + 1)) + " " + ((PlayerChunk.Failure) either.right().get()).toString();
                        }
                    });
                }

                list2.add(optional.get());
            }

            return Either.left(list2);
        });
    }

    public CompletableFuture<Either<Chunk, PlayerChunk.Failure>> b(ChunkCoordIntPair chunkcoordintpair) {
        return this.a(chunkcoordintpair, 2, (i) -> {
            return ChunkStatus.FULL;
        }).thenApplyAsync((either) -> {
            return either.mapLeft((list) -> {
                return (Chunk) list.get(list.size() / 2);
            });
        }, this.executor);
    }

    @Nullable
    private PlayerChunk a(long i, int j, @Nullable PlayerChunk playerchunk, int k) {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Chunk holder update"); // Tuinity
        if (k > PlayerChunkMap.GOLDEN_TICKET && j > PlayerChunkMap.GOLDEN_TICKET) {
            return playerchunk;
        } else {
            if (playerchunk != null) {
                playerchunk.a(j);
            }

            if (playerchunk != null) {
                if (j > PlayerChunkMap.GOLDEN_TICKET) {
                    this.unloadQueue.add(i);
                } else {
                    this.unloadQueue.remove(i);
                }
            }

            if (j <= PlayerChunkMap.GOLDEN_TICKET && playerchunk == null) {
                playerchunk = (PlayerChunk) this.pendingUnload.remove(i);
                if (playerchunk != null) {
                    playerchunk.a(j);
                    playerchunk.updateRanges(); // Tuinity - optimise isOutsideOfRange
                } else {
                    playerchunk = new PlayerChunk(new ChunkCoordIntPair(i), j, this.lightEngine, this.p, this);
                }

                this.chunkMap.queueUpdate(i, playerchunk); // Tuinity - replace chunk map
                this.updatingChunksModified = true;
            }

            return playerchunk;
        }
    }

    @Override
    public void close() throws IOException {
        try {
            this.p.close();
            this.world.asyncChunkTaskManager.close(true); // Paper - Required since we're closing regionfiles in the next line
            this.m.close();
        } finally {
            super.close();
        }

    }

    // Paper start - incremental autosave
    final it.unimi.dsi.fastutil.objects.ObjectRBTreeSet<PlayerChunk> autoSaveQueue = new it.unimi.dsi.fastutil.objects.ObjectRBTreeSet<>((playerchunk1, playerchunk2) -> {
        int timeCompare =  Long.compare(playerchunk1.lastAutoSaveTime, playerchunk2.lastAutoSaveTime);
        if (timeCompare != 0) {
            return timeCompare;
        }

        return Long.compare(MCUtil.getCoordinateKey(playerchunk1.location), MCUtil.getCoordinateKey(playerchunk2.location));
    });

    protected void saveIncrementally() {
        int savedThisTick = 0;
        // optimized since we search far less chunks to hit ones that need to be saved
        List<PlayerChunk> reschedule = new ArrayList<>(this.world.paperConfig.maxAutoSaveChunksPerTick);
        long currentTick = this.world.getTime();
        long maxSaveTime = currentTick - this.world.paperConfig.autoSavePeriod;

        for (Iterator<PlayerChunk> iterator = this.autoSaveQueue.iterator(); iterator.hasNext();) {
            PlayerChunk playerchunk = iterator.next();
            if (playerchunk.lastAutoSaveTime > maxSaveTime) {
                break;
            }

            iterator.remove();

            IChunkAccess ichunkaccess = playerchunk.getChunkSave().getNow(null);
            if (ichunkaccess instanceof Chunk) {
                boolean shouldSave = ((Chunk)ichunkaccess).lastSaved <= maxSaveTime;

                if (shouldSave && this.saveChunk(ichunkaccess)) {
                    ++savedThisTick;

                    if (!playerchunk.setHasBeenLoaded()) {
                        // do not fall through to reschedule logic
                        playerchunk.inactiveTimeStart = currentTick;
                        if (savedThisTick >= this.world.paperConfig.maxAutoSaveChunksPerTick) {
                            break;
                        }
                        continue;
                    }
                }
            }

            reschedule.add(playerchunk);

            if (savedThisTick >= this.world.paperConfig.maxAutoSaveChunksPerTick) {
                break;
            }
        }

        for (int i = 0, len = reschedule.size(); i < len; ++i) {
            PlayerChunk playerchunk = reschedule.get(i);
            playerchunk.lastAutoSaveTime = this.world.getTime();
            this.autoSaveQueue.add(playerchunk);
        }
    }
    // Paper end

    protected void save(boolean flag) {
        if (flag) {
            List<PlayerChunk> list = (List) this.chunkMap.getVisibleValues().stream().filter(PlayerChunk::hasBeenLoaded).peek(PlayerChunk::m).collect(Collectors.toList()); // Tuinity - replace chunk map
            MutableBoolean mutableboolean = new MutableBoolean();

            do {
                mutableboolean.setFalse();
                list.stream().map((playerchunk) -> {
                    CompletableFuture completablefuture;

                    do {
                        completablefuture = playerchunk.getChunkSave();
                        this.executor.awaitTasks(completablefuture::isDone);
                    } while (completablefuture != playerchunk.getChunkSave());

                    return (IChunkAccess) completablefuture.join();
                }).filter((ichunkaccess) -> {
                    return ichunkaccess instanceof ProtoChunkExtension || ichunkaccess instanceof Chunk;
                }).filter(this::saveChunk).forEach((ichunkaccess) -> {
                    mutableboolean.setTrue();
                });
            } while (mutableboolean.isTrue());

            this.b(() -> {
                return true;
            });
            this.world.asyncChunkTaskManager.flush(); // Paper - flush to preserve behavior compat with pre-async behaviour
//            this.i(); // Paper - nuke IOWorker
            PlayerChunkMap.LOGGER.info("ThreadedAnvilChunkStorage ({}): All chunks are saved", this.w.getName());
        } else {
            this.chunkMap.getVisibleValues().stream().filter(PlayerChunk::hasBeenLoaded).forEach((playerchunk) -> { // Tuinity - replace chunk map
                IChunkAccess ichunkaccess = (IChunkAccess) playerchunk.getChunkSave().getNow(null); // CraftBukkit - decompile error

                if (ichunkaccess instanceof ProtoChunkExtension || ichunkaccess instanceof Chunk) {
                    this.saveChunk(ichunkaccess);
                    playerchunk.m();
                }

            });
            com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.flush(); // Paper - flush to preserve behavior compat with pre-async behaviour
        }

    }

    private static final double UNLOAD_QUEUE_RESIZE_FACTOR = 0.96; // Spigot

    protected void unloadChunks(BooleanSupplier booleansupplier) {
        GameProfilerFiller gameprofilerfiller = this.world.getMethodProfiler();

        try (Timing ignored = this.world.timings.poiUnload.startTiming()) { // Paper
        gameprofilerfiller.enter("poi");
        this.m.a(booleansupplier);
        } // Paper
        gameprofilerfiller.exitEnter("chunk_unload");
        if (!this.world.isSavingDisabled()) {
            try (Timing ignored = this.world.timings.chunkUnload.startTiming()) { // Paper
            this.b(booleansupplier);
            }// Paper
        }

        gameprofilerfiller.exit();
    }

    private void b(BooleanSupplier booleansupplier) {
        LongIterator longiterator = this.unloadQueue.iterator();
        // Spigot start
        org.spigotmc.SlackActivityAccountant activityAccountant = this.world.getMinecraftServer().slackActivityAccountant;
        activityAccountant.startActivity(0.5);
        int targetSize = Math.min(this.unloadQueue.size() - 100,  (int) (this.unloadQueue.size() * UNLOAD_QUEUE_RESIZE_FACTOR)); // Paper - Make more aggressive
        // Spigot end
        while (longiterator.hasNext()) { // Spigot
            long j = longiterator.nextLong();
            longiterator.remove(); // Spigot
            PlayerChunk playerchunk = (PlayerChunk) this.chunkMap.queueRemove(j); // Tuinity - replace chunk map

            if (playerchunk != null) {
                this.pendingUnload.put(j, playerchunk);
                this.updatingChunksModified = true;
                // Spigot start
                if (!booleansupplier.getAsBoolean() && this.unloadQueue.size() <= targetSize && activityAccountant.activityTimeIsExhausted()) {
                    break;
                }
                // Spigot end
                this.a(j, playerchunk);
            }
        }
        activityAccountant.endActivity(); // Spigot

        Runnable runnable;

        int queueTarget = Math.min(this.z.size() - 100, (int) (this.z.size() * UNLOAD_QUEUE_RESIZE_FACTOR)); // Paper - Target this queue as well
        while ((booleansupplier.getAsBoolean() || this.z.size() > queueTarget) && (runnable = (Runnable) this.z.poll()) != null) { // Paper - Target this queue as well
            runnable.run();
        }

    }

    // Paper start - async chunk save for unload
    // Note: This is very unsafe to call if the chunk is still in use.
    // This is also modeled after PlayerChunkMap#saveChunk(IChunkAccess, boolean), with the intentional difference being
    // serializing the chunk is left to a worker thread.
    private void asyncSave(IChunkAccess chunk) {
        ChunkCoordIntPair chunkPos = chunk.getPos();
        NBTTagCompound poiData;
        try (Timing ignored = this.world.timings.chunkUnloadPOISerialization.startTiming()) {
            poiData = this.getVillagePlace().getData(chunk.getPos());
        }

        com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.scheduleSave(this.world, chunkPos.x, chunkPos.z,
            poiData, null, com.destroystokyo.paper.io.PrioritizedTaskQueue.LOW_PRIORITY);

        if (!chunk.isNeedsSaving()) {
            return;
        }

        ChunkStatus chunkstatus = chunk.getChunkStatus();

        // Copied from PlayerChunkMap#saveChunk(IChunkAccess, boolean)
        if (chunkstatus.getType() != ChunkStatus.Type.LEVELCHUNK) {
            try (co.aikar.timings.Timing ignored1 = this.world.timings.chunkSaveOverwriteCheck.startTiming()) { // Paper
                // Paper start - Optimize save by using status cache
                try {
                    ChunkStatus statusOnDisk = this.getChunkStatusOnDisk(chunkPos);
                    if (statusOnDisk != null && statusOnDisk.getType() == ChunkStatus.Type.LEVELCHUNK) {
                        // Paper end
                        return;
                    }

                    if (chunkstatus == ChunkStatus.EMPTY && chunk.h().values().stream().noneMatch(StructureStart::e)) {
                        return;
                    }
                } catch (IOException ex) {
                    ex.printStackTrace();
                    return;
                }
            }
        }

        ChunkRegionLoader.AsyncSaveData asyncSaveData;
        try (Timing ignored = this.world.timings.chunkUnloadPrepareSave.startTiming()) {
            asyncSaveData = ChunkRegionLoader.getAsyncSaveData(this.world, chunk);
        }

        this.world.asyncChunkTaskManager.scheduleChunkSave(chunkPos.x, chunkPos.z, com.destroystokyo.paper.io.PrioritizedTaskQueue.LOW_PRIORITY,
            asyncSaveData, chunk);

        chunk.setLastSaved(this.world.getTime());
        chunk.setNeedsSaving(false);
    }
    // Paper end

    private void a(long i, PlayerChunk playerchunk) {
        CompletableFuture<IChunkAccess> completablefuture = playerchunk.getChunkSave();
        Consumer<IChunkAccess> consumer = (ichunkaccess) -> { // CraftBukkit - decompile error
            CompletableFuture<IChunkAccess> completablefuture1 = playerchunk.getChunkSave();

            if (completablefuture1 != completablefuture) {
                this.a(i, playerchunk);
            } else {
                if (this.pendingUnload.remove(i, playerchunk) && ichunkaccess != null) {
                    if (ichunkaccess instanceof Chunk) {
                        ((Chunk) ichunkaccess).setLoaded(false);
                    }

                    //this.saveChunk(ichunkaccess);// Paper - delay
                    if (this.loadedChunks.remove(i) && ichunkaccess instanceof Chunk) {
                        Chunk chunk = (Chunk) ichunkaccess;

                        this.world.unloadChunk(chunk);
                    }
                    this.autoSaveQueue.remove(playerchunk); // Paper

                    try {
                        this.asyncSave(ichunkaccess); // Paper - async chunk saving
                    } catch (Throwable ex) {
                        LOGGER.fatal("Failed to prepare async save, attempting synchronous save", ex);
                        this.saveChunk(ichunkaccess);
                    }

                    this.lightEngine.a(ichunkaccess.getPos());
                    this.lightEngine.queueUpdate();
                    this.worldLoadListener.a(ichunkaccess.getPos(), (ChunkStatus) null);
                }

            }
        };
        Queue queue = this.z;

        this.z.getClass();
        completablefuture.thenAcceptAsync(consumer, queue::add).whenComplete((ovoid, throwable) -> {
            if (throwable != null) {
                PlayerChunkMap.LOGGER.error("Failed to save chunk " + playerchunk.i(), throwable);
            }

        });
    }

    protected boolean b() {
        if (!this.updatingChunksModified) {
            return false;
        } else {
            this.chunkMap.performUpdates(); // Tuinity - replace chunk map
            this.updatingChunksModified = false;
            return true;
        }
    }

    public CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> a(PlayerChunk playerchunk, ChunkStatus chunkstatus) {
        ChunkCoordIntPair chunkcoordintpair = playerchunk.i();

        if (chunkstatus == ChunkStatus.EMPTY) {
            return this.f(chunkcoordintpair);
        } else {
            CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> completablefuture = playerchunk.a(chunkstatus.e(), this);

            return completablefuture.thenComposeAsync((either) -> {
                Optional<IChunkAccess> optional = either.left();

                if (!optional.isPresent()) {
                    return CompletableFuture.completedFuture(either);
                } else {
                    if (chunkstatus == ChunkStatus.LIGHT) {
                        this.chunkDistanceManager.a(TicketType.LIGHT, chunkcoordintpair, 33 + ChunkStatus.a(ChunkStatus.FEATURES), chunkcoordintpair);
                    }

                    IChunkAccess ichunkaccess = (IChunkAccess) optional.get();

                    if (ichunkaccess.getChunkStatus().b(chunkstatus)) {
                        CompletableFuture completablefuture1;

                        if (chunkstatus == ChunkStatus.LIGHT) {
                            completablefuture1 = this.b(playerchunk, chunkstatus);
                        } else {
                            completablefuture1 = chunkstatus.a(this.world, this.definedStructureManager, this.lightEngine, (ichunkaccess1) -> {
                                return this.c(playerchunk);
                            }, ichunkaccess);
                        }

                        this.worldLoadListener.a(chunkcoordintpair, chunkstatus);
                        return completablefuture1;
                    } else {
                        return this.b(playerchunk, chunkstatus);
                    }
                }
            }, this.executor);
        }
    }

    // Paper start - Async chunk io
    public NBTTagCompound completeChunkData(NBTTagCompound compound, ChunkCoordIntPair chunkcoordintpair) throws IOException {
        return compound == null ? null : this.getChunkData(this.world.getWorldProvider().getDimensionManager(), this.getWorldPersistentDataSupplier(), compound, chunkcoordintpair, this.world);
    }
    // Paper end

    private CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> f(ChunkCoordIntPair chunkcoordintpair) {
        // Paper start - Async chunk io
        final java.util.function.BiFunction<ChunkRegionLoader.InProgressChunkHolder, Throwable, Either<IChunkAccess, PlayerChunk.Failure>> syncLoadComplete = (chunkHolder, ioThrowable) -> {
            try (Timing ignored = this.world.timings.syncChunkLoadTimer.startTimingIfSync()) { // Paper
                this.world.getMethodProfiler().c("chunkLoad");
                if (ioThrowable != null) {
                    com.destroystokyo.paper.io.IOUtil.rethrow(ioThrowable);
                }

                this.getVillagePlace().loadInData(chunkcoordintpair, chunkHolder.poiData);
                chunkHolder.tasks.forEach(Runnable::run);
                // Paper - async load completes this
                // Paper end

                // Paper start - This is done async
                if (chunkHolder.protoChunk != null) {
                    chunkHolder.protoChunk.setLastSaved(this.world.getTime());
                    return Either.left(chunkHolder.protoChunk);
                }
                // Paper end
            } catch (ReportedException reportedexception) {
                Throwable throwable = reportedexception.getCause();

                if (!(throwable instanceof IOException)) {
                    throw reportedexception;
                }

                PlayerChunkMap.LOGGER.error("Couldn't load chunk {}", chunkcoordintpair, throwable);
            } catch (Exception exception) {
                PlayerChunkMap.LOGGER.error("Couldn't load chunk {}", chunkcoordintpair, exception);
            }

            return Either.left(new ProtoChunk(chunkcoordintpair, ChunkConverter.a, this.world)); // Paper - Anti-Xray
            // Paper start - Async chunk io
        };
        CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> ret = new CompletableFuture<>();

        Consumer<ChunkRegionLoader.InProgressChunkHolder> chunkHolderConsumer = (ChunkRegionLoader.InProgressChunkHolder holder) -> {
            PlayerChunkMap.this.executor.addTask(() -> {
                ret.complete(syncLoadComplete.apply(holder, null));
            });
        };

        CompletableFuture<NBTTagCompound> chunkSaveFuture = this.world.asyncChunkTaskManager.getChunkSaveFuture(chunkcoordintpair.x, chunkcoordintpair.z);
        if (chunkSaveFuture != null) {
            this.world.asyncChunkTaskManager.scheduleChunkLoad(chunkcoordintpair.x, chunkcoordintpair.z,
                com.destroystokyo.paper.io.PrioritizedTaskQueue.HIGH_PRIORITY, chunkHolderConsumer, false, chunkSaveFuture);
            this.world.asyncChunkTaskManager.raisePriority(chunkcoordintpair.x, chunkcoordintpair.z, com.destroystokyo.paper.io.PrioritizedTaskQueue.HIGH_PRIORITY);
        } else {
            this.world.asyncChunkTaskManager.scheduleChunkLoad(chunkcoordintpair.x, chunkcoordintpair.z,
                com.destroystokyo.paper.io.PrioritizedTaskQueue.NORMAL_PRIORITY, chunkHolderConsumer, false);
        }
        return ret;
        // Paper end
    }

    private CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> b(PlayerChunk playerchunk, ChunkStatus chunkstatus) {
        ChunkCoordIntPair chunkcoordintpair = playerchunk.i();
        CompletableFuture<Either<List<IChunkAccess>, PlayerChunk.Failure>> completablefuture = this.a(chunkcoordintpair, chunkstatus.f(), (i) -> {
            return this.a(chunkstatus, i);
        });

        this.world.getMethodProfiler().c(() -> {
            return "chunkGenerate " + chunkstatus.d();
        });
        return completablefuture.thenComposeAsync((either) -> {
            return either.map((list) -> { // Paper - Shut up.
                try {
                    CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> completablefuture1 = chunkstatus.a(this.world, this.chunkGenerator, this.definedStructureManager, this.lightEngine, (ichunkaccess) -> {
                        return this.c(playerchunk);
                    }, list);

                    this.worldLoadListener.a(chunkcoordintpair, chunkstatus);
                    return completablefuture1;
                } catch (Exception exception) {
                    CrashReport crashreport = CrashReport.a(exception, "Exception generating new chunk");
                    CrashReportSystemDetails crashreportsystemdetails = crashreport.a("Chunk to be generated");

                    crashreportsystemdetails.a("Location", (Object) String.format("%d,%d", chunkcoordintpair.x, chunkcoordintpair.z));
                    crashreportsystemdetails.a("Position hash", (Object) ChunkCoordIntPair.pair(chunkcoordintpair.x, chunkcoordintpair.z));
                    crashreportsystemdetails.a("Generator", (Object) this.chunkGenerator);
                    throw new ReportedException(crashreport);
                }
            }, (playerchunk_failure) -> {
                this.c(chunkcoordintpair);
                return CompletableFuture.completedFuture(Either.right(playerchunk_failure));
            });
        }, (runnable) -> {
            this.mailboxWorldGen.a(ChunkTaskQueueSorter.a(playerchunk, runnable)); // CraftBukkit - decompile error
        });
    }

    protected void c(ChunkCoordIntPair chunkcoordintpair) {
        this.executor.a(SystemUtils.a(() -> {
            this.chunkDistanceManager.b(TicketType.LIGHT, chunkcoordintpair, 33 + ChunkStatus.a(ChunkStatus.FEATURES), chunkcoordintpair);
        }, () -> {
            return "release light ticket " + chunkcoordintpair;
        }));
    }

    private ChunkStatus a(ChunkStatus chunkstatus, int i) {
        ChunkStatus chunkstatus1;

        if (i == 0) {
            chunkstatus1 = chunkstatus.e();
        } else {
            chunkstatus1 = ChunkStatus.a(ChunkStatus.a(chunkstatus) + i);
        }

        return chunkstatus1;
    }

    private CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> c(PlayerChunk playerchunk) {
        CompletableFuture<Either<IChunkAccess, PlayerChunk.Failure>> completablefuture = playerchunk.getStatusFutureUnchecked(ChunkStatus.FULL.e());

        return completablefuture.thenApplyAsync((either) -> {
            ChunkStatus chunkstatus = PlayerChunk.getChunkStatus(playerchunk.getTicketLevel());

            return !chunkstatus.b(ChunkStatus.FULL) ? PlayerChunk.UNLOADED_CHUNK_ACCESS : either.mapLeft((ichunkaccess) -> {
            try (Timing ignored = world.timings.chunkIOStage2.startTimingIfSync()) { // Paper
                ChunkCoordIntPair chunkcoordintpair = playerchunk.i();
                Chunk chunk;

                if (ichunkaccess instanceof ProtoChunkExtension) {
                    chunk = ((ProtoChunkExtension) ichunkaccess).u();
                } else {
                    chunk = new Chunk(this.world, (ProtoChunk) ichunkaccess);
                    playerchunk.a(new ProtoChunkExtension(chunk));
                }

                chunk.setLastSaved(this.world.getTime() - 1); // Paper - avoid autosaving newly generated/loaded chunks

                chunk.a(() -> {
                    return PlayerChunk.getChunkState(playerchunk.getTicketLevel());
                });
                chunk.addEntities();
                if (this.loadedChunks.add(chunkcoordintpair.pair())) {
                    chunk.setLoaded(true);
                    this.world.a(chunk.getTileEntities().values());
                    List<Entity> list = null;
                    List<Entity>[] aentityslice = chunk.getEntitySlices(); // Spigot
                    int i = aentityslice.length;

                    for (int j = 0; j < i; ++j) {
                        List<Entity> entityslice = aentityslice[j]; // Spigot

                        // Paper start
                        PaperWorldConfig.DuplicateUUIDMode mode = world.paperConfig.duplicateUUIDMode;
                        if (mode == PaperWorldConfig.DuplicateUUIDMode.WARN || mode == PaperWorldConfig.DuplicateUUIDMode.DELETE || mode == PaperWorldConfig.DuplicateUUIDMode.SAFE_REGEN) {
                            Map<UUID, Entity> thisChunk = new HashMap<>();
                            for (Iterator<Entity> iterator = ((List<Entity>) entityslice).iterator(); iterator.hasNext(); ) {
                                Entity entity = iterator.next();

                                // CraftBukkit start - these are spawned serialized (DefinedStructure) and we don't call an add event below at the moment due to ordering complexities
                                if (chunk.needsDecoration && !this.world.getServer().getServer().getSpawnNPCs() && entity instanceof NPC) {
                                    entity.die();
                                }
                                // CraftBukkit end

                                if (entity.dead || entity.valid) continue;
                                Entity other = ((WorldServer) world).getEntity(entity.uniqueID);
                                if (other == null || other.dead) {
                                    other = thisChunk.get(entity.uniqueID);
                                }

                                if (mode == PaperWorldConfig.DuplicateUUIDMode.SAFE_REGEN && other != null && !other.dead
                                        && java.util.Objects.equals(other.getSaveID(), entity.getSaveID())
                                        && entity.getBukkitEntity().getLocation().distance(other.getBukkitEntity().getLocation()) < world.paperConfig.duplicateUUIDDeleteRange
                                ) {
                                    if (World.DEBUG_ENTITIES) LOGGER.warn("[DUPE-UUID] Duplicate UUID found used by " + other + ", deleted entity " + entity + " because it was near the duplicate and likely an actual duplicate. See https://github.com/PaperMC/Paper/issues/1223 for discussion on what this is about.");
                                    entity.dead = true;
                                    iterator.remove();
                                    continue;
                                }
                                if (other != null && !other.dead) {
                                    switch (mode) {
                                        case SAFE_REGEN: {
                                            entity.setUUID(UUID.randomUUID());
                                            if (World.DEBUG_ENTITIES) LOGGER.warn("[DUPE-UUID] Duplicate UUID found used by " + other + ", regenerated UUID for " + entity + ". See https://github.com/PaperMC/Paper/issues/1223 for discussion on what this is about.");
                                            break;
                                        }
                                        case DELETE: {
                                            if (World.DEBUG_ENTITIES) LOGGER.warn("[DUPE-UUID] Duplicate UUID found used by " + other + ", deleted entity " + entity + ". See https://github.com/PaperMC/Paper/issues/1223 for discussion on what this is about.");
                                            entity.dead = true;
                                            iterator.remove();
                                            break;
                                        }
                                        default:
                                            if (World.DEBUG_ENTITIES) LOGGER.warn("[DUPE-UUID] Duplicate UUID found used by " + other + ", doing nothing to " + entity + ". See https://github.com/PaperMC/Paper/issues/1223 for discussion on what this is about.");
                                            break;
                                    }
                                }
                                // Paper end
                            if (!(entity instanceof EntityHuman) && (entity.dead || !this.world.addEntityChunk(entity))) { // Paper
                                if (list == null) {
                                    list = Lists.newArrayList(new Entity[]{entity});
                                } else {
                                    list.add(entity);
                                }
                            }
                        }
                        } // Paper
                    }

                    if (list != null) {
                        list.forEach(chunk::b);
                    }
                }

                return chunk;
                } // Paper
            });
        }, (runnable) -> {
            Mailbox mailbox = this.mailboxMain;
            long i = playerchunk.i().pair();

            playerchunk.getClass();
            mailbox.a(ChunkTaskQueueSorter.a(runnable, i, playerchunk::getTicketLevel)); // CraftBukkit - decompile error
        });
    }

    public CompletableFuture<Either<Chunk, PlayerChunk.Failure>> a(PlayerChunk playerchunk) {
        ChunkCoordIntPair chunkcoordintpair = playerchunk.i();
        CompletableFuture<Either<List<IChunkAccess>, PlayerChunk.Failure>> completablefuture = this.a(chunkcoordintpair, 1, (i) -> {
            return ChunkStatus.FULL;
        });
        CompletableFuture<Either<Chunk, PlayerChunk.Failure>> completablefuture1 = completablefuture.thenApplyAsync((either) -> {
            return either.flatMap((list) -> {
                Chunk chunk = (Chunk) list.get(list.size() / 2);

                chunk.A();
                return Either.left(chunk);
            });
        }, (runnable) -> {
            this.mailboxMain.a(ChunkTaskQueueSorter.a(playerchunk, runnable)); // CraftBukkit - decompile error
        });

        completablefuture1.thenAcceptAsync((either) -> {
            either.mapLeft((chunk) -> {
                this.u.getAndIncrement();
                // Tuinity - per player view distance - moved to full chunk load, instead of ticking load
                return Either.left(chunk);
            });
        }, (runnable) -> {
            this.mailboxMain.a(ChunkTaskQueueSorter.a(playerchunk, runnable)); // CraftBukkit - decompile error
        });
        return completablefuture1;
    }

    public CompletableFuture<Either<Chunk, PlayerChunk.Failure>> b(PlayerChunk playerchunk) {
        return playerchunk.a(ChunkStatus.FULL, this).thenApplyAsync((either) -> {
            return either.mapLeft((ichunkaccess) -> {
                Chunk chunk = (Chunk) ichunkaccess;

                chunk.B();
                return chunk;
            });
        }, (runnable) -> {
            this.mailboxMain.a(ChunkTaskQueueSorter.a(playerchunk, runnable)); // CraftBukkit - decompile error
        });
    }

    public int c() {
        return this.u.get();
    }

    // Paper start - async chunk io
    private boolean writeDataAsync(ChunkCoordIntPair chunkPos, NBTTagCompound poiData, NBTTagCompound chunkData, boolean async) {
        com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.scheduleSave(this.world, chunkPos.x, chunkPos.z,
            poiData, chunkData, !async ? com.destroystokyo.paper.io.PrioritizedTaskQueue.HIGHEST_PRIORITY : com.destroystokyo.paper.io.PrioritizedTaskQueue.LOW_PRIORITY);

        if (async) {
            return true;
        }

        try (co.aikar.timings.Timing ignored = this.world.timings.chunkSaveIOWait.startTiming()) { // Paper
        Boolean successPoi = com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.waitForIOToComplete(this.world, chunkPos.x, chunkPos.z, true, true);
        Boolean successChunk = com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.waitForIOToComplete(this.world, chunkPos.x, chunkPos.z, true, false);

        if (successPoi == Boolean.FALSE || successChunk == Boolean.FALSE) {
            return false;
        }

        // null indicates no task existed, which means our write completed before we waited on it

        return true;
        } // Paper
    }
    // Paper end

    public boolean saveChunk(IChunkAccess ichunkaccess) {
        // Paper start - async param
        return this.saveChunk(ichunkaccess, true);
    }
    public boolean saveChunk(IChunkAccess ichunkaccess, boolean async) {
        try (co.aikar.timings.Timing ignored = this.world.timings.chunkSave.startTiming()) {
        NBTTagCompound poiData = this.getVillagePlace().getData(ichunkaccess.getPos()); // Paper
        //this.m.a(ichunkaccess.getPos()); // Delay
        // Paper end
        if (!ichunkaccess.isNeedsSaving()) {
            return false;
        } else {
            // Paper - The save session check is performed on the IO thread

            ichunkaccess.setLastSaved(this.world.getTime());
            ichunkaccess.setNeedsSaving(false);
            ChunkCoordIntPair chunkcoordintpair = ichunkaccess.getPos();

            try {
                ChunkStatus chunkstatus = ichunkaccess.getChunkStatus();
                NBTTagCompound nbttagcompound;

                if (chunkstatus.getType() != ChunkStatus.Type.LEVELCHUNK) {
                    try (co.aikar.timings.Timing ignored1 = this.world.timings.chunkSaveOverwriteCheck.startTiming()) { // Paper
                    // Paper start - Optimize save by using status cache
                    ChunkStatus statusOnDisk = this.getChunkStatusOnDisk(chunkcoordintpair);
                    if (statusOnDisk != null && statusOnDisk.getType() == ChunkStatus.Type.LEVELCHUNK) {
                        // Paper end
                        this.writeDataAsync(ichunkaccess.getPos(), poiData, null, async); // Paper - Async chunk io
                        return false;
                    }

                    if (chunkstatus == ChunkStatus.EMPTY && ichunkaccess.h().values().stream().noneMatch(StructureStart::e)) {
                        this.writeDataAsync(ichunkaccess.getPos(), poiData, null, async); // Paper - Async chunk io
                        return false;
                    }
                }

                this.world.getMethodProfiler().c("chunkSave");
                } // Paper
                try (co.aikar.timings.Timing ignored1 = this.world.timings.chunkSaveDataSerialization.startTiming()) { // Paper
                nbttagcompound = ChunkRegionLoader.saveChunk(this.world, ichunkaccess);
                } // Paper
                return this.writeDataAsync(ichunkaccess.getPos(), poiData, nbttagcompound, async); // Paper - Async chunk io
                //return true; // Paper
            } catch (Exception exception) {
                PlayerChunkMap.LOGGER.error("Failed to save chunk {},{}", chunkcoordintpair.x, chunkcoordintpair.z, exception);
                com.destroystokyo.paper.exception.ServerInternalException.reportInternalException(exception); // Paper
                return false;
            }
        }
        } // Paper
    }

    public void setViewDistance(int i) { // Tuinity - make public
        int j = MathHelper.clamp(i + 1, 3, 33) - 1; // Tuinity - we correctly handle view distance, no need to add 1

        if (j != this.viewDistance) {
            int k = this.viewDistance;

            this.viewDistance = j;
            this.chunkDistanceManager.setGlobalViewDistance(this.viewDistance, this); // Tuinity - per player view distance
            // Tuinity start - view distance map handles this
            if (this.world != null && this.world.players != null) { // ... called inside constructor, where these may not be initialized
                for (EntityPlayer player : this.world.players) {
                    this.updateViewDistance(player, player.getRawViewDistance(), player.getRawNoTickViewDistance());
                }
            }
            // Tuinity end - view distance map handles this
        }

    }

    // Tuinity start - no ticket view distance
    public void setNoTickViewDistance(int noTickViewDistance) {
        // modeled after the above
        noTickViewDistance = MathHelper.clamp(noTickViewDistance, 2, 32);
        if (this.noTickViewDistance != noTickViewDistance) {
            this.noTickViewDistance = noTickViewDistance;
            if (this.world != null && this.world.players != null) { // ... called inside constructor, where these may not be initialized
                for (EntityPlayer player : this.world.players) {
                    this.updateViewDistance(player, player.getRawViewDistance(), player.getRawNoTickViewDistance());
                }
            }
        }
    }
    // Tuinity end

    protected void sendChunk(EntityPlayer entityplayer, ChunkCoordIntPair chunkcoordintpair, Packet<?>[] apacket, boolean flag, boolean flag1) {
        // Tuinity start - remove ChunkCoordIntPair allocation, use two ints instead of ChunkCoordIntPair
        this.sendChunk(entityplayer, chunkcoordintpair.x, chunkcoordintpair.z, apacket, flag, flag1);
    }
    protected void sendChunk(EntityPlayer entityplayer, int chunkX, int chunkZ, Packet<?>[] apacket, boolean flag, boolean flag1) {
        // Tuinity end
        if (entityplayer.world == this.world) {
            if (flag1 && !flag) {
                PlayerChunk playerchunk = this.getVisibleChunk(ChunkCoordIntPair.pair(chunkX, chunkZ)); // Tuinity - remove ChunkCoordIntPair allocation

                if (playerchunk != null) {
                    Chunk chunk = playerchunk.getFullReadyChunk(); // Tuinity - per player view distance

                    if (chunk != null) {
                        this.a(entityplayer, apacket, chunk);
                    }

                    //PacketDebug.a(this.world, chunkcoordintpair); // Tuinity - remove ChunkCoordIntPair allocation (this function is a no-op)
                }
            }

            if (!flag1 && flag) {
                entityplayer.sendChunkUnload(chunkX, chunkZ); // Tuinity - remove ChunkCoordIntPair allocation
            }

        }
    }

    public int d() {
        return this.chunkMap.getVisibleSizeAsync(); // Tuinity - replace chunk map
    }

    protected PlayerChunkMap.a e() {
        return this.chunkDistanceManager;
    }

    protected Iterable<PlayerChunk> f() {
        return Iterables.unmodifiableIterable(this.chunkMap.getUpdatingValuesCopy()); // Tuinity - replace chunk map
    }

    void a(Writer writer) throws IOException {
        CSVWriter csvwriter = CSVWriter.a().a("x").a("z").a("level").a("in_memory").a("status").a("full_status").a("accessible_ready").a("ticking_ready").a("entity_ticking_ready").a("ticket").a("spawning").a("entity_count").a("block_entity_count").a(writer);
        ObjectBidirectionalIterator objectbidirectionaliterator = this.chunkMap.getVisibleMap().long2ObjectEntrySet().iterator(); // Tuinity - replace chunk map

        while (objectbidirectionaliterator.hasNext()) {
            Entry<PlayerChunk> entry = (Entry) objectbidirectionaliterator.next();
            ChunkCoordIntPair chunkcoordintpair = new ChunkCoordIntPair(entry.getLongKey());
            PlayerChunk playerchunk = (PlayerChunk) entry.getValue();
            Optional<IChunkAccess> optional = Optional.ofNullable(playerchunk.f());
            Optional<Chunk> optional1 = optional.flatMap((ichunkaccess) -> {
                return ichunkaccess instanceof Chunk ? Optional.of((Chunk) ichunkaccess) : Optional.empty();
            });

            // CraftBukkit - decompile error
            csvwriter.a(chunkcoordintpair.x, chunkcoordintpair.z, playerchunk.getTicketLevel(), optional.isPresent(), optional.map(IChunkAccess::getChunkStatus).orElse(null), optional1.map(Chunk::getState).orElse(null), a(playerchunk.c()), a(playerchunk.a()), a(playerchunk.b()), this.chunkDistanceManager.c(entry.getLongKey()), !this.isOutsideOfRange(chunkcoordintpair), optional1.map((chunk) -> {
                return Stream.of(chunk.getEntitySlices()).mapToInt(List::size).sum(); // Spigot
            }).orElse(0), optional1.map((chunk) -> {
                return chunk.getTileEntities().size();
            }).orElse(0));
        }

    }

    private static String a(CompletableFuture<Either<Chunk, PlayerChunk.Failure>> completablefuture) {
        try {
            Either<Chunk, PlayerChunk.Failure> either = (Either) completablefuture.getNow(null); // CraftBukkit - decompile error

            return either != null ? (String) either.map((chunk) -> {
                return "done";
            }, (playerchunk_failure) -> {
                return "unloaded";
            }) : "not completed";
        } catch (CompletionException completionexception) {
            return "failed " + completionexception.getCause().getMessage();
        } catch (CancellationException cancellationexception) {
            return "cancelled";
        }
    }

    // Paper start - Asynchronous chunk io
    @Nullable
    @Override
    public NBTTagCompound read(ChunkCoordIntPair chunkcoordintpair) throws IOException {
        if (Thread.currentThread() != com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE) {
            NBTTagCompound ret = com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE
                .loadChunkDataAsyncFuture(this.world, chunkcoordintpair.x, chunkcoordintpair.z, com.destroystokyo.paper.io.IOUtil.getPriorityForCurrentThread(),
                    false, true, true).join().chunkData;

            if (ret == com.destroystokyo.paper.io.PaperFileIOThread.FAILURE_VALUE) {
                throw new IOException("See logs for further detail");
            }
            return ret;
        }
        return super.read(chunkcoordintpair);
    }

    @Override
    public void write(ChunkCoordIntPair chunkcoordintpair, NBTTagCompound nbttagcompound) throws IOException {
        if (Thread.currentThread() != com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE) {
            com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.scheduleSave(
                this.world, chunkcoordintpair.x, chunkcoordintpair.z, null, nbttagcompound,
                com.destroystokyo.paper.io.IOUtil.getPriorityForCurrentThread());

            Boolean ret = com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE.waitForIOToComplete(this.world,
                chunkcoordintpair.x, chunkcoordintpair.z, true, false);

            if (ret == Boolean.FALSE) {
                throw new IOException("See logs for further detail");
            }
            return;
        }
        super.write(chunkcoordintpair, nbttagcompound);
    }
    // Paper end

    @Nullable
    public NBTTagCompound readChunkData(ChunkCoordIntPair chunkcoordintpair) throws IOException { // Paper - private -> public
        NBTTagCompound nbttagcompound = this.read(chunkcoordintpair);

        // Paper start - Cache chunk status on disk
        if (nbttagcompound == null) {
            return null;
        }

        nbttagcompound = this.getChunkData(this.world.getWorldProvider().getDimensionManager(), this.l, nbttagcompound, chunkcoordintpair, world); // CraftBukkit
        if (nbttagcompound == null) {
            return null;
        }

        this.updateChunkStatusOnDisk(chunkcoordintpair, nbttagcompound);

        return nbttagcompound;
        // Paper end
    }

    // Paper start - chunk status cache "api"
    public ChunkStatus getChunkStatusOnDiskIfCached(ChunkCoordIntPair chunkPos) {
        synchronized (this) { // Paper
        RegionFile regionFile = this.getRegionFileIfLoaded(chunkPos);

        return regionFile == null ? null : regionFile.getStatusIfCached(chunkPos.x, chunkPos.z);
        } // Paper
    }

    public ChunkStatus getChunkStatusOnDisk(ChunkCoordIntPair chunkPos) throws IOException {
        // Paper start - async chunk save for unload
        IChunkAccess unloadingChunk = this.world.asyncChunkTaskManager.getChunkInSaveProgress(chunkPos.x, chunkPos.z);
        if (unloadingChunk != null) {
            return unloadingChunk.getChunkStatus();
        }
        // Paper end
        // Paper start - async io
        NBTTagCompound inProgressWrite = com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE
                                             .getPendingWrite(this.world, chunkPos.x, chunkPos.z, false);

        if (inProgressWrite != null) {
            return ChunkRegionLoader.getStatus(inProgressWrite);
        }
        // Paper end
        synchronized (this) { // Paper - async io
            RegionFile regionFile = this.getFile(chunkPos, false);

            if (!regionFile.chunkExists(chunkPos)) {
                return null;
            }

            ChunkStatus status = regionFile.getStatusIfCached(chunkPos.x, chunkPos.z);

            if (status != null) {
                return status;
            }
            // Paper start - async io
        }

        NBTTagCompound compound = this.readChunkData(chunkPos);

        return ChunkRegionLoader.getStatus(compound);
        // Paper end
    }

    public void updateChunkStatusOnDisk(ChunkCoordIntPair chunkPos, @Nullable NBTTagCompound compound) throws IOException {
        synchronized (this) {
            RegionFile regionFile = this.getFile(chunkPos, false);

            regionFile.setStatus(chunkPos.x, chunkPos.z, ChunkRegionLoader.getStatus(compound));
        }
    }

    public IChunkAccess getUnloadingChunk(int chunkX, int chunkZ) {
        PlayerChunk chunkHolder = this.pendingUnload.get(ChunkCoordIntPair.pair(chunkX, chunkZ));
        return chunkHolder == null ? null : chunkHolder.getAvailableChunkNow();
    }
    // Paper end


    // Paper start - async io
    // this function will not load chunk data off disk to check for status
    // ret null for unknown, empty for empty status on disk or absent from disk
    public ChunkStatus getStatusOnDiskNoLoad(int x, int z) {
        // Paper start - async chunk save for unload
        IChunkAccess unloadingChunk = this.world.asyncChunkTaskManager.getChunkInSaveProgress(x, z);
        if (unloadingChunk != null) {
            return unloadingChunk.getChunkStatus();
        }
        // Paper end
        // Paper start - async io
        net.minecraft.server.NBTTagCompound inProgressWrite = com.destroystokyo.paper.io.PaperFileIOThread.Holder.INSTANCE
            .getPendingWrite(this.world, x, z, false);

        if (inProgressWrite != null) {
            return net.minecraft.server.ChunkRegionLoader.getStatus(inProgressWrite);
        }
        // Paper end
        // variant of PlayerChunkMap#getChunkStatusOnDisk that does not load data off disk, but loads the region file
        ChunkCoordIntPair chunkPos = new ChunkCoordIntPair(x, z);
        synchronized (world.getChunkProvider().playerChunkMap) {
            net.minecraft.server.RegionFile file;
            try {
                file = world.getChunkProvider().playerChunkMap.getFile(chunkPos, false);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }

            return !file.chunkExists(chunkPos) ? ChunkStatus.EMPTY : file.getStatusIfCached(x, z);
        }
    }

    boolean isOutsideOfRange(ChunkCoordIntPair chunkcoordintpair) {
        // Spigot start
        return isOutsideOfRange(chunkcoordintpair, false);
    }

    // Tuinity start
    final boolean isOutsideOfRange(ChunkCoordIntPair chunkcoordintpair, boolean reducedRange) {
        return this.isOutsideOfRange(this.getUpdatingChunk(chunkcoordintpair.pair()), chunkcoordintpair, reducedRange);
    }

    final boolean isOutsideOfRange(PlayerChunk playerchunk, ChunkCoordIntPair chunkcoordintpair, boolean reducedRange) {
        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> playersInRange = reducedRange ? playerchunk.playersInMobSpawnRange : playerchunk.playersInChunkTickRange;

        if (playersInRange == null) {
            return true;
        }

        Object[] backingSet = playersInRange.getBackingSet();

        if (reducedRange) {
            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer player = (EntityPlayer) raw;
                // don't check spectator and whatnot, already handled by mob spawn map update
                if (player.lastEntitySpawnRadiusSquared > getDistanceSquaredFromChunk(chunkcoordintpair, player)) {
                    return false; // in range
                }
            }
        } else {
            final double range = (ChunkMapDistance.MOB_SPAWN_RANGE * 16) * (ChunkMapDistance.MOB_SPAWN_RANGE * 16);
            // before spigot, mob spawn range was actually mob spawn range + tick range, but it was split
            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object raw = backingSet[i];
                if (!(raw instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer player = (EntityPlayer) raw;
                // don't check spectator and whatnot, already handled by mob spawn map update
                if (range > getDistanceSquaredFromChunk(chunkcoordintpair, player)) {
                    return false; // in range
                }
            }
        }
        // no players in range
        return true;
    }
    // Tuinity end

    private boolean cannotLoadChunks(EntityPlayer entityplayer) { return this.b(entityplayer); } // Tuinity - OBFHELPER
    private boolean b(EntityPlayer entityplayer) {
        return entityplayer.isSpectator() && !this.world.getGameRules().getBoolean(GameRules.SPECTATORS_GENERATE_CHUNKS);
    }

    void a(EntityPlayer entityplayer, boolean flag) {
        boolean flag1 = this.b(entityplayer);
        boolean flag2 = this.playerMap.c(entityplayer);
        int i = MathHelper.floor(entityplayer.locX()) >> 4;
        int j = MathHelper.floor(entityplayer.locZ()) >> 4;

        if (flag) {
            this.playerMap.a(ChunkCoordIntPair.pair(i, j), entityplayer, flag1);
            this.c(entityplayer);
            if (!flag1) {
                this.chunkDistanceManager.a(SectionPosition.a((Entity) entityplayer), entityplayer);
            }
        } else {
            SectionPosition sectionposition = entityplayer.K();

            this.playerMap.a(sectionposition.u().pair(), entityplayer);
            if (!flag2) {
                this.chunkDistanceManager.b(sectionposition, entityplayer);
            }
        }

        // Tuinity start - view distance map handles this
        if (flag) {
            this.updateMaps(entityplayer);
        }
        // Tuinity end - view distance map handles this

    }

    private SectionPosition c(EntityPlayer entityplayer) {
        SectionPosition sectionposition = SectionPosition.a((Entity) entityplayer);

        entityplayer.a(sectionposition);
        //entityplayer.playerConnection.sendPacket(new PacketPlayOutViewCentre(sectionposition.a(), sectionposition.c())); // Tuinity - distance map handles this now
        return sectionposition;
    }

    public void movePlayer(EntityPlayer entityplayer) {
        if (this.playerEntityTrackerTrackMap == null) { // Tuinity - optimized tracker
        ObjectIterator objectiterator = this.trackedEntities.values().iterator();

        while (objectiterator.hasNext()) {
            PlayerChunkMap.EntityTracker playerchunkmap_entitytracker = (PlayerChunkMap.EntityTracker) objectiterator.next();

            if (playerchunkmap_entitytracker.tracker == entityplayer) {
                playerchunkmap_entitytracker.track(this.world.getPlayers());
            } else {
                playerchunkmap_entitytracker.updatePlayer(entityplayer);
            }
        }
        } // Tuinity - optimized tracker

        int i = MathHelper.floor(entityplayer.locX()) >> 4;
        int j = MathHelper.floor(entityplayer.locZ()) >> 4;
        SectionPosition sectionposition = entityplayer.K();
        SectionPosition sectionposition1 = SectionPosition.a((Entity) entityplayer);
        long k = sectionposition.u().pair();
        long l = sectionposition1.u().pair();
        boolean flag = this.playerMap.d(entityplayer);
        boolean flag1 = this.b(entityplayer);
        boolean flag2 = sectionposition.v() != sectionposition1.v();

        if (flag2 || flag != flag1) {
            this.c(entityplayer);
            if (!flag) {
                this.chunkDistanceManager.b(sectionposition, entityplayer);
            }

            if (!flag1) {
                this.chunkDistanceManager.a(sectionposition1, entityplayer);
            }

            if (!flag && flag1) {
                this.playerMap.a(entityplayer);
            }

            if (flag && !flag1) {
                this.playerMap.b(entityplayer);
            }

            if (k != l) {
                this.playerMap.a(k, l, entityplayer);
            }
        }

        int i1 = sectionposition.a();
        int j1 = sectionposition.c();
        int k1;
        int l1;

        this.updateMaps(entityplayer); // Paper - distance maps
        this.updateDistanceMapsTuinity(entityplayer); // Tuinity - distance maps
    }

    @Override
    public Stream<EntityPlayer> a(ChunkCoordIntPair chunkcoordintpair, boolean flag) {
        // Tuinity start - per player view distance
        // there can be potential desync with player's last mapped section and the view distance map, so use the
        // view distance map here.
        com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> inRange = this.playerViewDistanceBroadcastMap.getObjectsInRange(chunkcoordintpair);

        if (inRange == null) {
            return Stream.empty();
        }
        // all current cases are inlined so we wont hit this code, it's just in case plugins or future updates use it
        List<EntityPlayer> players = new ArrayList<>();
        Object[] backingSet = inRange.getBackingSet();

        if (flag) { // flag -> border only
            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object temp = backingSet[i];
                if (!(temp instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer player = (EntityPlayer)temp;
                int viewDistance = this.playerViewDistanceBroadcastMap.getLastViewDistance(player);
                long lastPosition = this.playerViewDistanceBroadcastMap.getLastCoordinate(player);

                int distX = Math.abs(com.tuinity.tuinity.util.Util.getCoordinateX(lastPosition) - chunkcoordintpair.x);
                int distZ = Math.abs(com.tuinity.tuinity.util.Util.getCoordinateZ(lastPosition) - chunkcoordintpair.z);

                if (Math.max(distX, distZ) == viewDistance) {
                    players.add(player);
                }
            }
        } else {
            for (int i = 0, len = backingSet.length; i < len; ++i) {
                Object temp = backingSet[i];
                if (!(temp instanceof EntityPlayer)) {
                    continue;
                }
                EntityPlayer player = (EntityPlayer)temp;
                players.add(player);
            }
        }

        return players.stream();
    }

    protected void addEntity(Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity track"); // Spigot
        if (!(entity instanceof EntityComplexPart)) {
            if (!(entity instanceof EntityLightning)) {
                EntityTypes<?> entitytypes = entity.getEntityType();
                int i = entitytypes.getChunkRange() * 16;
                i = org.spigotmc.TrackingRange.getEntityTrackingRange(entity, i); // Spigot
                int j = entitytypes.getUpdateInterval();

                if (this.trackedEntities.containsKey(entity.getId())) {
                    throw (IllegalStateException) SystemUtils.c(new IllegalStateException("Entity is already tracked!"));
                } else {
                    PlayerChunkMap.EntityTracker playerchunkmap_entitytracker = new PlayerChunkMap.EntityTracker(entity, i, j, entitytypes.isDeltaTracking());

                    entity.tracker = playerchunkmap_entitytracker; // Paper - Fast access to tracker
                    this.trackedEntities.put(entity.getId(), playerchunkmap_entitytracker);
                    if (this.playerEntityTrackerTrackMap == null) { // Tuinity - implement optimized tracker
                    playerchunkmap_entitytracker.track(this.world.getPlayers());
                        // Tuinity start - implement optimized tracker
                    } else {
                        if (PlayerChunkMap.isLegacyTrackingEntity(entity)) {
                            this.activelyTrackedEntitiesLegacy.add(entity);
                            // tracker tick will propagate updates
                        } else {
                            int chunkX = com.tuinity.tuinity.util.Util.getChunkCoordinate(entity.locX());
                            int chunkZ = com.tuinity.tuinity.util.Util.getChunkCoordinate(entity.locZ());
                            com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> playersTracking = this.playerEntityTrackerTrackMap.getObjectsInRange(chunkX, chunkZ);
                            if (playersTracking != null) {
                                Object[] backingSet = playersTracking.getBackingSet();
                                for (int index = 0, len = backingSet.length; index < len; ++index) {
                                    Object temp = backingSet[index];
                                    if (!(temp instanceof EntityPlayer)) {
                                        continue;
                                    }
                                    EntityPlayer trackingPlayer = (EntityPlayer) temp;
                                    playerchunkmap_entitytracker.updateTrackingPlayer(trackingPlayer);
                                }
                            }
                        }
                    }
                    // Tuinity end - implement optimized tracker
                    if (entity instanceof EntityPlayer) {
                        EntityPlayer entityplayer = (EntityPlayer) entity;

                        this.a(entityplayer, true);
                        if (this.playerEntityTrackerTrackMap == null) { // Tuinity - implement optimized tracker
                        ObjectIterator objectiterator = this.trackedEntities.values().iterator();

                        while (objectiterator.hasNext()) {
                            PlayerChunkMap.EntityTracker playerchunkmap_entitytracker1 = (PlayerChunkMap.EntityTracker) objectiterator.next();

                            if (playerchunkmap_entitytracker1.tracker != entityplayer) {
                                playerchunkmap_entitytracker1.updatePlayer(entityplayer);
                            }
                        }
                        } // Tuinity - implement optimized tracker
                    }

                }
            }
        }
    }

    protected void removeEntity(Entity entity) {
        org.spigotmc.AsyncCatcher.catchOp("entity untrack"); // Spigot
        if (entity instanceof EntityPlayer) {
            EntityPlayer entityplayer = (EntityPlayer) entity;

            this.a(entityplayer, false);
            ObjectIterator objectiterator = this.trackedEntities.values().iterator();

            while (objectiterator.hasNext()) {
                PlayerChunkMap.EntityTracker playerchunkmap_entitytracker = (PlayerChunkMap.EntityTracker) objectiterator.next();

                playerchunkmap_entitytracker.clear(entityplayer);
            }
        }

        PlayerChunkMap.EntityTracker playerchunkmap_entitytracker1 = (PlayerChunkMap.EntityTracker) this.trackedEntities.remove(entity.getId());

        if (playerchunkmap_entitytracker1 != null) {
            playerchunkmap_entitytracker1.a();
        }
        entity.tracker = null; // Paper - We're no longer tracked
        // Tuinity start - optimise entity tracking - we're no longer tracked
        if (this.activelyTrackedEntitiesLegacy != null) {
            this.activelyTrackedEntitiesLegacy.remove(entity);
        }
        // Tuinity end - optimise entity tracking - we're no longer tracked
    }

    // Tuinity start - optimized tracker
    private void processTrackQueue() {
        // handle queued changes

        this.world.timings.tracker1.startTiming();
        for (Entity tracked : this.world.trackingUpdateQueue) {
            EntityTracker tracker = tracked.tracker;
            if (tracker == null) {
                continue;
            }
            // queued tracks
            for (it.unimi.dsi.fastutil.ints.IntIterator iterator = tracked.trackQueue.iterator(); iterator.hasNext();) {
                int id = iterator.nextInt();
                Entity player = this.world.entitiesById.get(id);

                if (!(player instanceof EntityPlayer)) {
                    continue;
                }

                // double-check to make sure we're in range...
                int chunkX = com.tuinity.tuinity.util.Util.getChunkCoordinate(player.locX());
                int chunkZ = com.tuinity.tuinity.util.Util.getChunkCoordinate(player.locZ());

                com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> inRange =
                        this.playerEntityTrackerUntrackMap.getObjectsInRange(chunkX, chunkZ);

                if (inRange != null && inRange.contains(player)) {
                    tracker.updateTrackingPlayer((EntityPlayer)player);
                } else {
                    tracker.removeTrackingPlayer((EntityPlayer)player);
                }
            }
            tracked.trackQueue.clear();

            // queued untracks
            for (it.unimi.dsi.fastutil.ints.IntIterator iterator = tracked.unTrackQueue.iterator(); iterator.hasNext();) {
                int id = iterator.nextInt();
                Entity player = this.world.entitiesById.get(id);

                if (!(player instanceof EntityPlayer)) {
                    continue;
                }

                tracker.removeTrackingPlayer((EntityPlayer)player);
            }
            tracked.unTrackQueue.clear();
        }
        this.world.trackingUpdateQueue.clear();
        this.world.timings.tracker1.stopTiming();

        // broadcast updates

        this.world.timings.tracker2.startTiming();
        for (Entity tracked : this.world.loadedEntities) {
            EntityTracker tracker = tracked.tracker;
            if (tracker != null) {
                tracker.trackerEntry.tick();
            }
        }
        this.world.timings.tracker2.stopTiming();

        // legacy tracker

        Entity[] legacyEntities = this.activelyTrackedEntitiesLegacy.getRawData();
        for (int i = 0, size = this.activelyTrackedEntitiesLegacy.size(); i < size; ++i) {
            Entity entity = legacyEntities[i];
            EntityTracker tracker = this.trackedEntities.get(entity.getId());
            if (tracker == null) {
                MinecraftServer.LOGGER.error("Legacy tracking entity has no tracker! No longer tracking entity " + entity);
                this.activelyTrackedEntitiesLegacy.remove(entity);
                --i;
                --size;
                continue;
            }

            EntityTrackerEntry entry = tracker.trackerEntry;
            tracker.track(this.world.getPlayers());
            entry.tick(); // always tick the entry, even if no player is tracking
        }
    }
    // Tuinity end - optimized tracker

    protected void g() {
        // Tuinity start - optimized tracker
        if (this.playerEntityTrackerTrackMap != null) {
            this.processTrackQueue();
            return;
        }
        // Tuinity end - optimized tracker
        List<EntityPlayer> list = Lists.newArrayList();
        List<EntityPlayer> list1 = this.world.getPlayers();

        PlayerChunkMap.EntityTracker playerchunkmap_entitytracker;
        ObjectIterator objectiterator;
        world.timings.tracker1.startTiming(); // Paper

        for (objectiterator = this.trackedEntities.values().iterator(); objectiterator.hasNext(); playerchunkmap_entitytracker.trackerEntry.a()) {
            playerchunkmap_entitytracker = (PlayerChunkMap.EntityTracker) objectiterator.next();
            SectionPosition sectionposition = playerchunkmap_entitytracker.e;
            SectionPosition sectionposition1 = SectionPosition.a(playerchunkmap_entitytracker.tracker);

            if (!Objects.equals(sectionposition, sectionposition1)) {
                playerchunkmap_entitytracker.track(list1);
                Entity entity = playerchunkmap_entitytracker.tracker;

                if (entity instanceof EntityPlayer) {
                    list.add((EntityPlayer) entity);
                }

                playerchunkmap_entitytracker.e = sectionposition1;
            }
        }
        world.timings.tracker1.stopTiming(); // Paper

        if (!list.isEmpty()) {
            objectiterator = this.trackedEntities.values().iterator();

            world.timings.tracker2.startTiming(); // Paper
            while (objectiterator.hasNext()) {
                playerchunkmap_entitytracker = (PlayerChunkMap.EntityTracker) objectiterator.next();
                playerchunkmap_entitytracker.track(list);
            }
            world.timings.tracker2.stopTiming(); // Paper
        }


    }

    protected void broadcast(Entity entity, Packet<?> packet) {
        PlayerChunkMap.EntityTracker playerchunkmap_entitytracker = (PlayerChunkMap.EntityTracker) this.trackedEntities.get(entity.getId());

        if (playerchunkmap_entitytracker != null) {
            playerchunkmap_entitytracker.broadcast(packet);
        }

    }

    protected void broadcastIncludingSelf(Entity entity, Packet<?> packet) {
        PlayerChunkMap.EntityTracker playerchunkmap_entitytracker = (PlayerChunkMap.EntityTracker) this.trackedEntities.get(entity.getId());

        if (playerchunkmap_entitytracker != null) {
            playerchunkmap_entitytracker.broadcastIncludingSelf(packet);
        }

    }

    final void sendChunk(EntityPlayer entityplayer, Packet<?>[] apacket, Chunk chunk) { this.a(entityplayer, apacket, chunk); } // Tuinity - OBFHELPER
    private void a(EntityPlayer entityplayer, Packet<?>[] apacket, Chunk chunk) {
        if (apacket[0] == null) {
            apacket[0] = new PacketPlayOutMapChunk(chunk, 65535, true); // Paper - Anti-Xray
            apacket[1] = new PacketPlayOutLightUpdate(chunk.getPos(), this.lightEngine);
        }

        entityplayer.a(chunk.getPos(), apacket[0], apacket[1]);
        PacketDebug.a(this.world, chunk.getPos());
        List<Entity> list = Lists.newArrayList();
        List<Entity> list1 = Lists.newArrayList();
        if (this.playerEntityTrackerTrackMap == null) { // Tuinity - implement optimized tracker
        ObjectIterator objectiterator = this.trackedEntities.values().iterator();

        while (objectiterator.hasNext()) {
            PlayerChunkMap.EntityTracker playerchunkmap_entitytracker = (PlayerChunkMap.EntityTracker) objectiterator.next();
            Entity entity = playerchunkmap_entitytracker.tracker;

            if (entity != entityplayer && entity.chunkX == chunk.getPos().x && entity.chunkZ == chunk.getPos().z) {
                playerchunkmap_entitytracker.updatePlayer(entityplayer);
                if (entity instanceof EntityInsentient && ((EntityInsentient) entity).getLeashHolder() != null) {
                    list.add(entity);
                }

                if (!entity.getPassengers().isEmpty()) {
                    list1.add(entity);
                }
            }
        }
            // Tuinity  start- implement optimized tracker
        } else {
            // Tuinity - implement optimized tracker
            // Tuinity start - implement optimized tracker
            // It's important to note that this is ONLY called when the chunk is at ticking level.
            // At this point, the entities should be added in the chunk.
            com.tuinity.tuinity.util.map.PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> playersInRange
                    = this.playerEntityTrackerTrackMap.getObjectsInRange(chunk.getPos());
            // only send entities when they're in tracking range...
            if (playersInRange != null && playersInRange.contains(entityplayer)) {
                chunk.forEachEntity((Entity entityInChunk) -> {
                    PlayerChunkMap.EntityTracker tracker = entityInChunk.tracker;
                    if (tracker == null) {
                        return; // when added to tracker, this entity will propagate to players
                    }

                    if (entityInChunk == entityplayer) {
                        return; // can't track himself
                    }

                    // Note: We don't add to the lists because the track logic will handle it
                    tracker.updateTrackingPlayer(entityplayer);
                });
            }
        }
        // Tuinity end - implement optimized tracker

        Iterator iterator;
        Entity entity1;

        if (!list.isEmpty()) {
            iterator = list.iterator();

            while (iterator.hasNext()) {
                entity1 = (Entity) iterator.next();
                entityplayer.playerConnection.sendPacket(new PacketPlayOutAttachEntity(entity1, ((EntityInsentient) entity1).getLeashHolder()));
            }
        }

        if (!list1.isEmpty()) {
            iterator = list1.iterator();

            while (iterator.hasNext()) {
                entity1 = (Entity) iterator.next();
                entityplayer.playerConnection.sendPacket(new PacketPlayOutMount(entity1));
            }
        }

    }

    public VillagePlace getVillagePlace() { return this.h(); } // Paper - OBFHELPER
    protected VillagePlace h() {
        return this.m;
    }

    public CompletableFuture<Void> a(Chunk chunk) {
        return this.executor.f(() -> {
            chunk.a(this.world);
        });
    }

    public class EntityTracker {

        final EntityTrackerEntry trackerEntry; // Tuinity - private -> package private
        private final Entity tracker;
        private final int trackingDistance;
        private SectionPosition e;
        // Paper start
        // Replace trackedPlayers Set with a Map. The value is true until the player receives
        // their first update (which is forced to have absolute coordinates), false afterward.
        public java.util.Map<EntityPlayer, Boolean> trackedPlayerMap = new java.util.HashMap<>();
        public Set<EntityPlayer> trackedPlayers = trackedPlayerMap.keySet();

        public EntityTracker(Entity entity, int i, int j, boolean flag) {
            this.trackerEntry = new EntityTrackerEntry(PlayerChunkMap.this.world, entity, j, flag, this::broadcast, trackedPlayerMap); // CraftBukkit // Paper
            this.tracker = entity;
            this.trackingDistance = i;
            this.e = SectionPosition.a(entity);
        }

        public boolean equals(Object object) {
            return object instanceof PlayerChunkMap.EntityTracker ? ((PlayerChunkMap.EntityTracker) object).tracker.getId() == this.tracker.getId() : false;
        }

        public int hashCode() {
            return this.tracker.getId();
        }

        public void broadcast(Packet<?> packet) {
            Iterator iterator = this.trackedPlayers.iterator();

            while (iterator.hasNext()) {
                EntityPlayer entityplayer = (EntityPlayer) iterator.next();

                entityplayer.playerConnection.sendPacket(packet);
            }

        }

        public void broadcastIncludingSelf(Packet<?> packet) {
            this.broadcast(packet);
            if (this.tracker instanceof EntityPlayer) {
                ((EntityPlayer) this.tracker).playerConnection.sendPacket(packet);
            }

        }

        public void a() {
            Iterator iterator = this.trackedPlayers.iterator();

            while (iterator.hasNext()) {
                EntityPlayer entityplayer = (EntityPlayer) iterator.next();

                this.trackerEntry.a(entityplayer);
            }

        }

        public void clear(EntityPlayer entityplayer) {
            org.spigotmc.AsyncCatcher.catchOp("player tracker clear"); // Spigot
            if (this.trackedPlayers.remove(entityplayer)) {
                this.trackerEntry.a(entityplayer);
            }

        }

        public void updatePlayer(EntityPlayer entityplayer) {
            org.spigotmc.AsyncCatcher.catchOp("player tracker update"); // Spigot
            if (entityplayer != this.tracker) {
                // Tuinity start - remove allocation of Vec3d here
                double vec3d_dx = entityplayer.locX() - this.tracker.locX();
                double vec3d_dy = entityplayer.locY() - this.tracker.locY();
                double vec3d_dz = entityplayer.locZ() - this.tracker.locZ();
                // Tuinity end - remove allocation of Vec3d here
                int i = Math.min(this.b(), (entityplayer.getEffectiveViewDistance(PlayerChunkMap.this)) * 16);  // Tuinity - per player view distance
                boolean flag = vec3d_dx >= (double) (-i) && vec3d_dx <= (double) i && vec3d_dz >= (double) (-i) && vec3d_dz <= (double) i && this.tracker.a(entityplayer); // Tuinity start - remove allocation of Vec3d here
                if (flag) {
                    boolean flag1 = this.tracker.attachedToPlayer;

                    if (!flag1) {
                        ChunkCoordIntPair chunkcoordintpair = new ChunkCoordIntPair(this.tracker.chunkX, this.tracker.chunkZ);
                        PlayerChunk playerchunk = PlayerChunkMap.this.getVisibleChunk(chunkcoordintpair.pair());

                        if (playerchunk != null && playerchunk.getChunk() != null) {
                            flag1 = PlayerChunkMap.b(chunkcoordintpair, entityplayer, false) <= (1 + PlayerChunkMap.this.playerViewDistanceTickMap.getLastViewDistance(entityplayer)) && entityplayer.loadedChunks.contains(com.tuinity.tuinity.util.Util.getCoordinateKey(this.tracker)); // Tuinity - per player view distance
                        }
                    }

                    // CraftBukkit start - respect vanish API
                    if (this.tracker instanceof EntityPlayer) {
                        Player player = ((EntityPlayer) this.tracker).getBukkitEntity();
                        if (!entityplayer.getBukkitEntity().canSee(player)) {
                            flag1 = false;
                        }
                    }

                    entityplayer.removeQueue.remove(Integer.valueOf(this.tracker.getId()));
                    // CraftBukkit end

                    if (flag1 && this.trackedPlayerMap.putIfAbsent(entityplayer, true) == null) { // Paper
                        this.trackerEntry.b(entityplayer);
                    }
                } else if (this.trackedPlayers.remove(entityplayer)) {
                    this.trackerEntry.a(entityplayer);
                }

            }
        }

        private int b() {
            Collection<Entity> collection = this.tracker.getAllPassengers();
            int i = this.trackingDistance;
            Iterator iterator = collection.iterator();

            while (iterator.hasNext()) {
                Entity entity = (Entity) iterator.next();
                int j = entity.getEntityType().getChunkRange() * 16;

                if (j > i) {
                    i = j;
                }
            }

            return i;
        }

        // Tuinity start - optimized tracker
        public final void updateTrackingPlayer(EntityPlayer entityplayer) {
            if (entityplayer == this.tracker) {
                return;
            }
            com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Tracker update"); // Tuinity
            // the same as updatePlayer except without a distance check
            // we also add a world check since we queue tracking changes
            // TODO check on update
            // CraftBukkit start - respect vanish API
            boolean shouldTrack = entityplayer.world == tracker.world;
            if (this.tracker instanceof EntityPlayer) {
                Player player = ((EntityPlayer)this.tracker).getBukkitEntity();
                if (!entityplayer.getBukkitEntity().canSee(player)) {
                    shouldTrack = false;
                }
            }

            entityplayer.removeQueue.remove(Integer.valueOf(this.tracker.getId()));
            // CraftBukkit end

            if (shouldTrack) {
                if (this.trackedPlayerMap.putIfAbsent(entityplayer, true) == null) { // Paper
                    this.trackerEntry.onTrack(entityplayer);
                }
            } else {
                this.removeTrackingPlayer(entityplayer);
            }
        }

        public final void removeTrackingPlayer(EntityPlayer player) {
            com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Tracker update"); // Tuinity
            if (this.trackedPlayers.remove(player)) {
                this.trackerEntry.onUntrack(player);
            }
        }
        // Tuinity end - optimized tracker

        public void track(List<EntityPlayer> list) {
            Iterator iterator = list.iterator();

            while (iterator.hasNext()) {
                EntityPlayer entityplayer = (EntityPlayer) iterator.next();

                this.updatePlayer(entityplayer);
            }

        }
    }

    class a extends ChunkMapDistance {

        protected a(Executor executor, Executor executor1) {
            super(executor, executor1);
        }

        @Override
        protected boolean a(long i) {
            return PlayerChunkMap.this.unloadQueue.contains(i);
        }

        @Nullable
        @Override
        protected PlayerChunk b(long i) {
            return PlayerChunkMap.this.getUpdatingChunk(i);
        }

        @Nullable
        @Override
        protected PlayerChunk a(long i, int j, @Nullable PlayerChunk playerchunk, int k) {
            return PlayerChunkMap.this.a(i, j, playerchunk, k);
        }
    }
}
