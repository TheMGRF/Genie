package net.minecraft.server;

import com.tuinity.tuinity.util.Util; // Tuinity
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.annotation.Nullable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public abstract class ChunkMapDistance {

    private static final Logger LOGGER = LogManager.getLogger();
    private static final int b = 33 + ChunkStatus.a(ChunkStatus.FULL) - 2; public static int getPlayerTicketLevel() { return ChunkMapDistance.b; } // Tuinity - OBFHELPER
    private final Long2ObjectMap<ObjectSet<EntityPlayer>> c = new Long2ObjectOpenHashMap();
    public final Long2ObjectOpenHashMap<ArraySetSorted<Ticket<?>>> tickets = new Long2ObjectOpenHashMap();
    private final ChunkMapDistance.a e = new ChunkMapDistance.a(); final ChunkMapDistance.a getTicketTracker() { return this.e; } // Tuinity - OBFHELPER
    public static final int MOB_SPAWN_RANGE = 8; //private final ChunkMapDistance.b f = new ChunkMapDistance.b(8); // Tuinity - no longer used
    //private final ChunkMapDistance.c g = new ChunkMapDistance.c(33); // Tuinity - no longer used
    private final java.util.Queue<PlayerChunk> pendingChunkUpdates = new java.util.ArrayDeque<>(); // PAIL pendingChunkUpdates // Paper - use a queue // Tuinity - use a better queue
    private final ChunkTaskQueueSorter i;
    private final Mailbox<ChunkTaskQueueSorter.a<Runnable>> j;
    private final Mailbox<ChunkTaskQueueSorter.b> k;
    private final LongSet l = new LongOpenHashSet();
    private final Executor m;
    private long currentTick;

    // Tuinity start
    protected PlayerChunkMap chunkMap;
    protected final ChunkMapDistance.TicketTracker playerTickViewDistanceHandler = new TicketTracker(ChunkMapDistance.getPlayerTicketLevel()) {
        @Override
        protected int tryQueueChunk(int chunkX, int chunkZ, EntityPlayer player) {
            long coordinate = Util.getCoordinateKey(chunkX, chunkZ);
            PlayerChunk currentChunk = ChunkMapDistance.this.chunkMap.chunkMap.getUpdating(coordinate);
            if (currentChunk != null) {
                Chunk fullChunk = currentChunk.getFullReadyChunk();
                if (fullChunk != null && fullChunk.areNeighboursLoaded(2)) {
                    this.chunkReferenceMap.putIfAbsent(coordinate, LOADED_PLAYER_REFERENCE);
                    ChunkMapDistance.this.addTicket(coordinate, new Ticket<>(TicketType.PLAYER, this.ticketLevel, new ChunkCoordIntPair(chunkX, chunkZ)));
                    return QUEUED;
                }
            }

            return FAILED;
        }

        @Override
        protected int getMaxChunkLoads(EntityPlayer player) {
            return Integer.MAX_VALUE;
        }
    };

    // this is copied from ChunkMapDistance.a(long, int, boolean, boolean), TODO check on update
    // this is invoked if and only if there are no other players in range of the chunk.
    public void playerMoveInRange(final int chunkX, final int chunkZ, final int fromX, final int fromZ) {
        final long coordinate = Util.getCoordinateKey(chunkX, chunkZ);

        final int dist = Math.max(Math.abs(chunkX - fromX), Math.abs(chunkZ - fromZ));
        Ticket<?> ticket = new Ticket<>(TicketType.PLAYER, 33, new ChunkCoordIntPair(chunkX, chunkZ));

        ChunkMapDistance.this.j.a(ChunkTaskQueueSorter.a(() -> { // Craftbukkit - decompile error
            ChunkMapDistance.this.m.execute(() -> {
                if (ChunkMapDistance.this.chunkMap.playerViewDistanceNoTickMap.getObjectsInRange(coordinate) != null) {
                    ChunkMapDistance.this.addTicket(coordinate, ticket);
                    ChunkMapDistance.this.l.add(coordinate);
                } else {
                    ChunkMapDistance.this.k.a(ChunkTaskQueueSorter.a(() -> { // Craftbukkit - decompile error
                    }, coordinate, false));
                }
            });
        }, coordinate, () -> {
            return dist;
        }));
    }

    // this is copied from ChunkMapDistance.a(long, int, boolean, boolean), TODO check on update
    // this is invoked if and only if there are no other players in range of the chunk.
    public void playerMoveOutOfRange(final int chunkX, final int chunkZ, final int fromX, final int fromZ) {
        final long coordinate = Util.getCoordinateKey(chunkX, chunkZ);

        Ticket<?> ticket = new Ticket<>(TicketType.PLAYER, 33, new ChunkCoordIntPair(chunkX, chunkZ));

        ChunkMapDistance.this.k.a(ChunkTaskQueueSorter.a(() -> { // Craftbukkit - decompile error
            ChunkMapDistance.this.m.execute(() -> {
                ChunkMapDistance.this.removeTicket(coordinate, ticket);
            });
        }, coordinate, true));
    }
    // Tuinity end

    // Tuinity start - delay chunk unloads
    private long nextUnloadId; // delay chunk unloads
    private final Long2ObjectOpenHashMap<Ticket<Long>> delayedChunks = new Long2ObjectOpenHashMap<>();
    public final void removeTickets(long chunk, TicketType<?> type) {
        ArraySetSorted<Ticket<?>> tickets = this.tickets.get(chunk);
        if (tickets == null) {
            return;
        }
        if (type == TicketType.DELAYED_UNLOAD) {
            this.delayedChunks.remove(chunk);
        }
        boolean changed = tickets.removeIf((Ticket<?> ticket) -> {
            return ticket.getTicketType() == type;
        });
        if (changed) {
            this.getTicketTracker().update(chunk, getLowestTicketLevel(tickets), false);
        }
    }

    private final java.util.function.LongFunction<Ticket<Long>> computeFuntion = (long key) -> {
        Ticket<Long> ret = new Ticket<>(TicketType.DELAYED_UNLOAD, -1, ++ChunkMapDistance.this.nextUnloadId);
        ret.isCached = true;
        return ret;
    };

    private void computeDelayedTicketFor(long chunk, int removedLevel, ArraySetSorted<Ticket<?>> tickets) {
        int lowestLevel = getLowestTicketLevel(tickets);
        if (removedLevel > lowestLevel) {
            return;
        }
        final Ticket<Long> ticket = this.delayedChunks.computeIfAbsent(chunk, this.computeFuntion);
        if (ticket.getTicketLevel() != -1) {
            // since we modify data used in sorting, we need to remove before
            tickets.remove(ticket);
        }
        ticket.setCreationTick(this.currentTick);
        ticket.setTicketLevel(removedLevel);
        tickets.add(ticket); // re-add with new expire time and ticket level
    }
    // Tuinity end - delay chunk unloads

    protected ChunkMapDistance(Executor executor, Executor executor1) {
        executor1.getClass();
        Mailbox<Runnable> mailbox = Mailbox.a("player ticket throttler", executor1::execute);
        ChunkTaskQueueSorter chunktaskqueuesorter = new ChunkTaskQueueSorter(ImmutableList.of(mailbox), executor, 4);

        this.i = chunktaskqueuesorter;
        this.j = chunktaskqueuesorter.a(mailbox, true);
        this.k = chunktaskqueuesorter.a(mailbox);
        this.m = executor1;
    }

    protected void purgeTickets() {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async purge tickets"); // Tuinity
        ++this.currentTick;
        ObjectIterator objectiterator = this.tickets.long2ObjectEntrySet().fastIterator();

        int[] tempLevel = new int[] { PlayerChunkMap.GOLDEN_TICKET + 1 }; // Tuinity - delay chunk unloads
        while (objectiterator.hasNext()) {
            Entry<ArraySetSorted<Ticket<?>>> entry = (Entry) objectiterator.next();

            if ((entry.getValue()).removeIf((ticket) -> { // CraftBukkit - decompile error
                // Tuinity start - delay chunk unloads
                boolean ret = ticket.isExpired(this.currentTick);
                if (com.tuinity.tuinity.config.TuinityConfig.delayChunkUnloadsBy <= 0) {
                    return ret;
                }
                if (ret && ticket.getTicketType() != TicketType.DELAYED_UNLOAD && ticket.getTicketLevel() < tempLevel[0]) {
                    tempLevel[0] = ticket.getTicketLevel();
                }
                if (ticket.getTicketType() == TicketType.DELAYED_UNLOAD && ticket.isCached) {
                    this.delayedChunks.remove(entry.getLongKey(), ticket); // clean up ticket...
                }
                return ret;
                // Tuinity end - delay chunk unloads
            })) {
                // Tuinity start - delay chunk unloads
                if (tempLevel[0] < (PlayerChunkMap.GOLDEN_TICKET + 1)) {
                    this.computeDelayedTicketFor(entry.getLongKey(), tempLevel[0], entry.getValue());
                }
                // Tuinity end - delay chunk unloads
                this.e.b(entry.getLongKey(), a((ArraySetSorted) entry.getValue()), false);
            }

            if (((ArraySetSorted) entry.getValue()).isEmpty()) {
                objectiterator.remove();
            }
        }

    }

    private static int getLowestTicketLevel(ArraySetSorted<Ticket<?>> arraysetsorted) { return a(arraysetsorted); } // Tuinity - OBFHELPER
    private static int a(ArraySetSorted<Ticket<?>> arraysetsorted) {
        return !arraysetsorted.isEmpty() ? ((Ticket) arraysetsorted.b()).b() : PlayerChunkMap.GOLDEN_TICKET + 1;
    }

    protected abstract boolean a(long i);

    @Nullable
    protected abstract PlayerChunk b(long i);

    @Nullable
    protected abstract PlayerChunk a(long i, int j, @Nullable PlayerChunk playerchunk, int k);

    public boolean a(PlayerChunkMap playerchunkmap) {
        //this.f.a(); // Tuinity - no longer used
        //this.g.a(); // Tuinity - no longer used
        int i = Integer.MAX_VALUE - this.e.a(Integer.MAX_VALUE);
        boolean flag = i != 0;

        if (flag) {
            ;
        }

        // Paper start
        if (!this.pendingChunkUpdates.isEmpty()) {
            while(!this.pendingChunkUpdates.isEmpty()) {
                this.pendingChunkUpdates.remove().a(playerchunkmap);
            }
            // Paper end
            return true;
        } else {
            if (!this.l.isEmpty()) {
                LongIterator longiterator = this.l.iterator();

                while (longiterator.hasNext()) {
                    long j = longiterator.nextLong();

                    if (this.e(j).stream().anyMatch((ticket) -> {
                        return ticket.getTicketType() == TicketType.PLAYER;
                    })) {
                        PlayerChunk playerchunk = playerchunkmap.getUpdatingChunk(j);

                        if (playerchunk == null) {
                            throw new IllegalStateException();
                        }

                        CompletableFuture<Either<Chunk, PlayerChunk.Failure>> completablefuture = playerchunk.b();

                        completablefuture.thenAccept((either) -> {
                            this.m.execute(() -> {
                                this.k.a(ChunkTaskQueueSorter.a(() -> { // CraftBukkit - decompile error
                                }, j, false));
                            });
                        });
                    }
                }

                this.l.clear();
            }

            return flag;
        }
    }

    private boolean addTicket(long i, Ticket<?> ticket) { // CraftBukkit - void -> boolean
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async ticket add"); // Tuinity
        ArraySetSorted<Ticket<?>> arraysetsorted = this.e(i);
        int j = a(arraysetsorted);
        Ticket<?> ticket1 = (Ticket) arraysetsorted.a(ticket); // CraftBukkit - decompile error

        ticket1.a(this.currentTick);
        if (ticket.b() < j) {
            this.e.b(i, ticket.b(), true);
        }

        return ticket == ticket1; // CraftBukkit
    }

    private boolean removeTicket(long i, Ticket<?> ticket) { // CraftBukkit - void -> boolean
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async ticket remove"); // Tuinity
        ArraySetSorted<Ticket<?>> arraysetsorted = this.e(i);

        boolean removed = false; // CraftBukkit
        if (arraysetsorted.remove(ticket)) {
            removed = true; // CraftBukkit
            // Tuinity start - delay chunk unloads
            if (com.tuinity.tuinity.config.TuinityConfig.delayChunkUnloadsBy > 0 && ticket.getTicketType() != TicketType.DELAYED_UNLOAD) {
                this.computeDelayedTicketFor(i, ticket.getTicketLevel(), arraysetsorted);
            }
            // Tuinity end - delay chunk unloads
        }

        if (arraysetsorted.isEmpty()) {
            this.tickets.remove(i);
        }

        this.e.b(i, a(arraysetsorted), false);
        return removed; // CraftBukkit
    }

    public <T> void a(TicketType<T> tickettype, ChunkCoordIntPair chunkcoordintpair, int i, T t0) {
        // CraftBukkit start
        this.addTicketAtLevel(tickettype, chunkcoordintpair, i, t0);
    }

    public <T> boolean addTicketAtLevel(TicketType<T> ticketType, ChunkCoordIntPair chunkcoordintpair, int level, T identifier) {
        return this.addTicket(chunkcoordintpair.pair(), new Ticket<>(ticketType, level, identifier));
        // CraftBukkit end
    }

    public <T> void b(TicketType<T> tickettype, ChunkCoordIntPair chunkcoordintpair, int i, T t0) {
        // CraftBukkit start
        this.removeTicketAtLevel(tickettype, chunkcoordintpair, i, t0);
    }

    public <T> boolean removeTicketAtLevel(TicketType<T> ticketType, ChunkCoordIntPair chunkcoordintpair, int level, T identifier) {
        Ticket<T> ticket = new Ticket<>(ticketType, level, identifier);

        return this.removeTicket(chunkcoordintpair.pair(), ticket);
        // CraftBukkit end
    }

    public <T> void addTicket(TicketType<T> tickettype, ChunkCoordIntPair chunkcoordintpair, int i, T t0) {
        this.addTicket(chunkcoordintpair.pair(), new Ticket<>(tickettype, 33 - i, t0));
    }

    public <T> void removeTicket(TicketType<T> tickettype, ChunkCoordIntPair chunkcoordintpair, int i, T t0) {
        Ticket<T> ticket = new Ticket<>(tickettype, 33 - i, t0);

        this.removeTicket(chunkcoordintpair.pair(), ticket);
    }

    private ArraySetSorted<Ticket<?>> e(long i) {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async tickets compute"); // Tuinity
        return (ArraySetSorted) this.tickets.computeIfAbsent(i, (j) -> {
            return ArraySetSorted.a(4);
        });
    }

    protected void a(ChunkCoordIntPair chunkcoordintpair, boolean flag) {
        Ticket<ChunkCoordIntPair> ticket = new Ticket<>(TicketType.FORCED, 31, chunkcoordintpair);

        if (flag) {
            this.addTicket(chunkcoordintpair.pair(), ticket);
        } else {
            this.removeTicket(chunkcoordintpair.pair(), ticket);
        }

    }

    public void a(SectionPosition sectionposition, EntityPlayer entityplayer) {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async player add"); // Tuinity
        long i = sectionposition.u().pair();

        ((ObjectSet) this.c.computeIfAbsent(i, (j) -> {
            return new ObjectOpenHashSet();
        })).add(entityplayer);
        //this.f.b(i, 0, true); // Tuinity - no longer used
        //this.g.b(i, 0, true); // Tuinity - no longer used
    }

    public void b(SectionPosition sectionposition, EntityPlayer entityplayer) {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async player remove"); // Tuinity
        long i = sectionposition.u().pair();
        ObjectSet<EntityPlayer> objectset = (ObjectSet) this.c.get(i);

        objectset.remove(entityplayer);
        if (objectset.isEmpty()) {
            this.c.remove(i);
            //this.f.b(i, Integer.MAX_VALUE, false); // Tuinity - no longer used
            //this.g.b(i, Integer.MAX_VALUE, false); // Tuinity - no longer used
        }

    }

    protected String c(long i) {
        ArraySetSorted<Ticket<?>> arraysetsorted = (ArraySetSorted) this.tickets.get(i);
        String s;

        if (arraysetsorted != null && !arraysetsorted.isEmpty()) {
            s = ((Ticket) arraysetsorted.b()).toString();
        } else {
            s = "no_ticket";
        }

        return s;
    }

    protected void setViewDistance(int viewDistance) { this.a(viewDistance); } // Tuinity - OBFHELPER
    protected void a(int i) {
        //this.g.a(i); // Tuinity - no longer used
    }
    // Tuinity start - per player view distance
    protected void setGlobalViewDistance(int viewDistance, PlayerChunkMap chunkMap) {
        this.chunkMap = chunkMap;
        this.setViewDistance(viewDistance);
    }
    // Tuinity end

    public int b() {
        // Tuinity start - use distance map to implement
        // note: this is the spawn chunk count
        return this.chunkMap.playerChunkTickRangeMap.size();
        // Tuinity end - use distance map to implement
    }

    public boolean d(long i) {
        // Tuinity start - use distance map to implement
        // note: this is the is spawn chunk method
        return this.chunkMap.playerChunkTickRangeMap.getObjectsInRange(i) != null;
        // Tuinity end - use distance map to implement
    }

    public String c() {
        return this.i.a();
    }

    // CraftBukkit start
    public <T> void removeAllTicketsFor(TicketType<T> ticketType, int ticketLevel, T ticketIdentifier) {
        com.tuinity.tuinity.util.TickThread.softEnsureTickThread("Async ticket remove"); // Tuinity
        Ticket<T> target = new Ticket<>(ticketType, ticketLevel, ticketIdentifier);

        for (java.util.Iterator<ArraySetSorted<Ticket<?>>> iterator = this.tickets.values().iterator(); iterator.hasNext();) {
            ArraySetSorted<Ticket<?>> tickets = iterator.next();
            tickets.remove(target);

            if (tickets.isEmpty()) {
                iterator.remove();
            }
        }
    }
    // CraftBukkit end

    class a extends ChunkMap {

        public a() {
            super(PlayerChunkMap.GOLDEN_TICKET + 2, 16, 256);
        }

        @Override
        protected int b(long i) {
            ArraySetSorted<Ticket<?>> arraysetsorted = (ArraySetSorted) ChunkMapDistance.this.tickets.get(i);

            return arraysetsorted == null ? Integer.MAX_VALUE : (arraysetsorted.isEmpty() ? Integer.MAX_VALUE : ((Ticket) arraysetsorted.b()).b());
        }

        @Override
        protected int c(long i) {
            if (!ChunkMapDistance.this.a(i)) {
                PlayerChunk playerchunk = ChunkMapDistance.this.b(i);

                if (playerchunk != null) {
                    return playerchunk.getTicketLevel();
                }
            }

            return PlayerChunkMap.GOLDEN_TICKET + 1;
        }

        @Override
        protected void a(long i, int j) {
            PlayerChunk playerchunk = ChunkMapDistance.this.b(i);
            int k = playerchunk == null ? PlayerChunkMap.GOLDEN_TICKET + 1 : playerchunk.getTicketLevel();

            if (k != j) {
                playerchunk = ChunkMapDistance.this.a(i, j, playerchunk, k);
                if (playerchunk != null) {
                    ChunkMapDistance.this.pendingChunkUpdates.add(playerchunk);
                }

            }
        }

        public int a(int i) {
            return this.b(i);
        }
    }

    // Tuinity start - Per player view distance
    abstract class TicketTracker {

        static final int LOADED_PLAYER_REFERENCE = -2;

        protected final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap chunkReferenceMap = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(8192, 0.25f);
        {
            this.chunkReferenceMap.defaultReturnValue(-1);
        }
        protected final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap lastLoadedRadiusByPlayer = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(512, 0.5f);
        {
            this.lastLoadedRadiusByPlayer.defaultReturnValue(-1);
        }

        protected final it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap pendingChunkLoadsByPlayer = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap(512, 0.5f);
        protected final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap lastChunkPositionByPlayer = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap(512, 0.5f);
        {
            this.lastChunkPositionByPlayer.defaultReturnValue(Long.MIN_VALUE);
        }

        protected final int ticketLevel;

        public TicketTracker(int ticketLevel) {
            this.ticketLevel = ticketLevel;
        }

        protected final java.util.List<EntityPlayer> players = new java.util.ArrayList<>(256);

        protected com.tuinity.tuinity.util.map.PlayerAreaMap areaMap;

        static final int ALREADY_QUEUED = 0;
        static final int QUEUED = 1;
        static final int FAILED = 2;

        protected abstract int tryQueueChunk(int chunkX, int chunkZ, EntityPlayer player);

        protected abstract int getMaxChunkLoads(EntityPlayer player);

        public void tick() {
            for (EntityPlayer player : this.players) {
                int playerId = player.getId();
                int lastLoadedRadius = this.lastLoadedRadiusByPlayer.get(playerId);
                int pendingChunkLoads = this.pendingChunkLoadsByPlayer.get(playerId);
                long lastChunkPos = this.lastChunkPositionByPlayer.get(playerId);
                long currentChunkPos = this.areaMap.getLastCoordinate(player);

                if (currentChunkPos == Long.MIN_VALUE) {
                    // not tracking for whatever reason...
                    continue;
                }

                int newX = Util.getCoordinateX(currentChunkPos);
                int newZ = Util.getCoordinateZ(currentChunkPos);

                // handle movement
                if (currentChunkPos != lastChunkPos) {
                    this.lastChunkPositionByPlayer.put(playerId, currentChunkPos);
                    if (lastChunkPos != Long.MIN_VALUE) {
                        int oldX = Util.getCoordinateX(lastChunkPos);
                        int oldZ = Util.getCoordinateZ(lastChunkPos);

                        int radiusDiff = Math.max(Math.abs(newX - oldX), Math.abs(newZ - oldZ));
                        lastLoadedRadius = Math.max(-1, lastLoadedRadius - radiusDiff);
                        this.lastLoadedRadiusByPlayer.put(playerId, lastLoadedRadius);
                    }
                }

                int maxChunkLoads = this.getMaxChunkLoads(player);

                int radius = lastLoadedRadius + 1;
                int viewDistance = this.areaMap.getLastViewDistance(player);

                if (radius > viewDistance) {
                    // distance map will unload our chunks
                    this.lastLoadedRadiusByPlayer.put(playerId, viewDistance);
                    continue;
                }

                if (pendingChunkLoads >= maxChunkLoads) {
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
                        if ((attempt = this.tryQueueChunk(newX - offset, newZ + radius, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // top right
                        if ((attempt = this.tryQueueChunk(newX + offset, newZ + radius, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // try bottom 2 chunks

                        // bottom left
                        if ((attempt = this.tryQueueChunk(newX - offset, newZ - radius, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // bottom right
                        if ((attempt = this.tryQueueChunk(newX + offset, newZ - radius, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // try left 2 chunks

                        // left down
                        if ((attempt = this.tryQueueChunk(newX - radius, newZ - offset, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // left up
                        if ((attempt = this.tryQueueChunk(newX - radius, newZ + offset, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // try right 2 chunks

                        // right down
                        if ((attempt = this.tryQueueChunk(newX + radius, newZ - offset, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
                                break radius_loop;
                            }
                        } else if (attempt == FAILED) {
                            break radius_loop;
                        }

                        // right up
                        if ((attempt = this.tryQueueChunk(newX + radius, newZ + offset, player)) == QUEUED) {
                            if (++pendingChunkLoads >= maxChunkLoads) {
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
                this.pendingChunkLoadsByPlayer.put(playerId, pendingChunkLoads);
            }
        }

        public void addPlayer(EntityPlayer player) {
            this.players.add(player);
        }

        public void removePlayer(EntityPlayer player) {
            this.players.remove(player);
            this.lastLoadedRadiusByPlayer.remove(player.getId());
            this.pendingChunkLoadsByPlayer.remove(player.getId());
            this.lastChunkPositionByPlayer.remove(player.getId());
        }

        public void onChunkLoad(int chunkX, int chunkZ) {
            long coordinate = Util.getCoordinateKey(chunkX, chunkZ);
            int playerReference = this.chunkReferenceMap.replace(coordinate, LOADED_PLAYER_REFERENCE);
            if (playerReference != -1) {
                this.pendingChunkLoadsByPlayer.computeIfPresent(playerReference, (Integer keyInMap, Integer valueInMap) -> {
                    return valueInMap - 1;
                });
            }
        }

        // this is invoked if and only if there are no other players in range of the chunk.
        public void playerMoveOutOfRange(int chunkX, int chunkZ) {
            long coordinate = Util.getCoordinateKey(chunkX, chunkZ);
            int playerReference = this.chunkReferenceMap.remove(coordinate);
            if (playerReference != -1) {
                if (playerReference != LOADED_PLAYER_REFERENCE) {
                    this.pendingChunkLoadsByPlayer.computeIfPresent(playerReference, (Integer keyInMap, Integer valueInMap) -> {
                        return valueInMap - 1;
                    });
                }
                ChunkMapDistance.this.removeTicket(coordinate, new Ticket<>(TicketType.PLAYER, this.ticketLevel, new ChunkCoordIntPair(chunkX, chunkZ)));
            }
        }
    }
    // Tuinity end - per player view distance

    class c extends ChunkMapDistance.b {

        private int e = 0;
        private final Long2IntMap f = Long2IntMaps.synchronize(new Long2IntOpenHashMap());
        private final LongSet g = new LongOpenHashSet();

        protected c(int i) {
            super(i);
            this.f.defaultReturnValue(i + 2);
        }

        @Override
        protected void a(long i, int j, int k) {
            this.g.add(i);
        }

        public void a(int i) {
            ObjectIterator objectiterator = this.a.long2ByteEntrySet().fastIterator(); // Tuinity - use fast iterator (reduces entry creation)

            while (objectiterator.hasNext()) {
                it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry it_unimi_dsi_fastutil_longs_long2bytemap_entry = (it.unimi.dsi.fastutil.longs.Long2ByteMap.Entry) objectiterator.next();
                byte b0 = it_unimi_dsi_fastutil_longs_long2bytemap_entry.getByteValue();
                long j = it_unimi_dsi_fastutil_longs_long2bytemap_entry.getLongKey();

                this.a(j, b0, this.c(b0), b0 <= i - 2);
            }

            this.e = i;
        }

        private void a(long i, int j, boolean flag, boolean flag1) {
            if (flag != flag1) {
                Ticket<?> ticket = new Ticket<>(TicketType.PLAYER, ChunkMapDistance.b, new ChunkCoordIntPair(i));

                if (flag1) {
                    ChunkMapDistance.this.j.a(ChunkTaskQueueSorter.a(() -> { // CraftBukkit - decompile error
                        ChunkMapDistance.this.m.execute(() -> {
                            if (this.c(this.c(i))) {
                                ChunkMapDistance.this.addTicket(i, ticket);
                                ChunkMapDistance.this.l.add(i);
                            } else {
                                ChunkMapDistance.this.k.a(ChunkTaskQueueSorter.a(() -> { // CraftBukkit - decompile error
                                }, i, false));
                            }

                        });
                    }, i, () -> {
                        return j;
                    }));
                } else {
                    ChunkMapDistance.this.k.a(ChunkTaskQueueSorter.a(() -> { // CraftBukkit - decompile error
                        ChunkMapDistance.this.m.execute(() -> {
                            ChunkMapDistance.this.removeTicket(i, ticket);
                        });
                    }, i, true));
                }
            }

        }

        @Override
        public void a() {
            super.a();
            if (!this.g.isEmpty()) {
                LongIterator longiterator = this.g.iterator();

                while (longiterator.hasNext()) {
                    long i = longiterator.nextLong();
                    int j = this.f.get(i);
                    int k = this.c(i);

                    if (j != k) {
                        ChunkMapDistance.this.i.a(new ChunkCoordIntPair(i), () -> {
                            return this.f.get(i);
                        }, k, (l) -> {
                            if (l >= this.f.defaultReturnValue()) {
                                this.f.remove(i);
                            } else {
                                this.f.put(i, l);
                            }

                        });
                        this.a(i, k, this.c(j), this.c(k));
                    }
                }

                this.g.clear();
            }

        }

        private boolean c(int i) {
            return i <= this.e - 2;
        }
    }

    class b extends ChunkMap {

        protected final Long2ByteOpenHashMap a = new Long2ByteOpenHashMap(); // Tuinity - change type for fast iterator
        protected final int b;

        protected b(int i) {
            super(i + 2, 16, 256);
            this.b = i;
            this.a.defaultReturnValue((byte) (i + 2));
        }

        @Override
        protected int c(long i) {
            return this.a.get(i);
        }

        @Override
        protected void a(long i, int j) {
            byte b0;

            if (j > this.b) {
                b0 = this.a.remove(i);
            } else {
                b0 = this.a.put(i, (byte) j);
            }

            this.a(i, b0, j);
        }

        protected void a(long i, int j, int k) {}

        @Override
        protected int b(long i) {
            return this.d(i) ? 0 : Integer.MAX_VALUE;
        }

        private boolean d(long i) {
            ObjectSet<EntityPlayer> objectset = (ObjectSet) ChunkMapDistance.this.c.get(i);

            return objectset != null && !objectset.isEmpty();
        }

        public void a() {
            this.b(Integer.MAX_VALUE);
        }
    }
}
