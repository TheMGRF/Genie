package com.tuinity.tuinity.util.pool;

import net.minecraft.server.BlockPosition;
import net.minecraft.server.MinecraftServer;

public class PooledBlockPositions {

    private static final int BLOCK_POOL_SIZE = 8192;

    private static final BlockPosition.MutableBlockPosition[] POOL = new BlockPosition.MutableBlockPosition[BLOCK_POOL_SIZE];
    private static int used = 0; // exclusive index of used positions

    static {
        for (int i = 0; i < BLOCK_POOL_SIZE; ++i) {
            POOL[i] = new BlockPosition.MutableBlockPosition();
        }
    }

    public static BlockPosition.MutableBlockPosition get(final int x, final int y, final int z) {
        final int currentUsed = used;
        if (Thread.currentThread() != MinecraftServer.getServer().serverThread || currentUsed >= POOL.length) {
            return new BlockPosition.MutableBlockPosition(x, y, z);
        }
        used = currentUsed + 1;

        final BlockPosition.MutableBlockPosition ret = POOL[currentUsed];
        POOL[currentUsed] = null;

        return ret.setValues(x, y, z);
    }

    public static void ret(final BlockPosition.MutableBlockPosition position) {
        final int currentUsed = used;
        if (Thread.currentThread() != MinecraftServer.getServer().serverThread || currentUsed == 0) {
            return;
        }

        POOL[used = currentUsed - 1] = position;
    }
}
