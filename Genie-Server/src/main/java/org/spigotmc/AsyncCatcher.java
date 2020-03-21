package org.spigotmc;

import net.minecraft.server.MinecraftServer;

public class AsyncCatcher
{

    public static boolean enabled = true;
    public static boolean shuttingDown = false; // Paper

    public static void catchOp(String reason)
    {
        if ( ( enabled || com.tuinity.tuinity.util.TickThread.STRICT_THREAD_CHECKS ) && !org.bukkit.Bukkit.isPrimaryThread() ) // Tuinity
        {
            throw new IllegalStateException( "Asynchronous " + reason + "!" );
        }
    }
}
