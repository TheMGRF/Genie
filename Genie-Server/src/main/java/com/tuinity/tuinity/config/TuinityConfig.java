package com.tuinity.tuinity.config;

import ca.spottedleaf.concurrentutil.util.Throw;
import net.minecraft.server.TicketType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.logging.Level;

public final class TuinityConfig {

    public static final String CONFIG_HEADER = "Configuration file for Tuinity.";
    public static final int CURRENT_CONFIG_VERSION = 0;

    private static final Object[] EMPTY = new Object[0];

    private static File configFile;
    private static YamlConfiguration config;
    private static int configVersion;

    public static void init(final File file) {
        // TODO remove this in the future...
        final File tuinityConfig = new File(file.getParent(), "tuinity.yml");
        if (!tuinityConfig.exists()) {
            final File oldConfig = new File(file.getParent(), "concrete.yml");
            oldConfig.renameTo(tuinityConfig);
        }
        TuinityConfig.configFile = file;
        final YamlConfiguration config = new YamlConfiguration();
        config.options().header(CONFIG_HEADER);
        config.options().copyDefaults(true);

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (final Exception ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Failure to create tuinity config", ex);
            }
        } else {
            try {
                config.load(file);
            } catch (final Exception ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Failure to load tuinity config", ex);
                Throw.rethrow(ex); /* Rethrow, this is critical */
                throw new RuntimeException(ex); // unreachable
            }
        }

        TuinityConfig.load(config);
    }

    public static void load(final YamlConfiguration config) {
        TuinityConfig.config = config;
        TuinityConfig.configVersion = TuinityConfig.getInt("config-version-please-do-not-modify-me", CURRENT_CONFIG_VERSION);

        for (final Method method : TuinityConfig.class.getDeclaredMethods()) {
            if (method.getReturnType() != void.class || method.getParameterCount() != 0 ||
                    !Modifier.isPrivate(method.getModifiers()) || !Modifier.isStatic(method.getModifiers())) {
                continue;
            }

            try {
                method.setAccessible(true);
                method.invoke(null, EMPTY);
            } catch (final Exception ex) {
                Throw.rethrow(ex);
                throw new RuntimeException(ex); // unreachable
            }
        }

        /* We re-save to add new options */
        try {
            config.save(TuinityConfig.configFile);
        } catch (final Exception ex) {
            Bukkit.getLogger().log(Level.SEVERE, "Unable to save tuinity config", ex);
        }
    }

    private static boolean getBoolean(final String path, final boolean dfl) {
        TuinityConfig.config.addDefault(path, Boolean.valueOf(dfl));
        return TuinityConfig.config.getBoolean(path, dfl);
    }

    private static int getInt(final String path, final int dfl) {
        TuinityConfig.config.addDefault(path, Integer.valueOf(dfl));
        return TuinityConfig.config.getInt(path, dfl);
    }

    private static double getDouble(final String path, final double dfl) {
        TuinityConfig.config.addDefault(path, Double.valueOf(dfl));
        return TuinityConfig.config.getDouble(path, dfl);
    }

    public static boolean tickWorldsInParallel;

    /**
     * if tickWorldsInParallel == true, then this value is used as a default only for worlds
     */
    public static int tickThreads;

    /*
    private static void worldticking() {
        tickWorldsInParallel = TuinityConfig.getBoolean("tick-worlds-in-parallel", false);
        tickThreads = TuinityConfig.getInt("server-tick-threads", 1); // will be 4 in the future
    }*/

    public static double maxChunkSendsPerPlayer; // per second

    public static int[] maxChunkSendsPerPlayerChoice = new int[100];

    private static void maxChunkLoadsPerPlayer() {
        maxChunkSendsPerPlayer = TuinityConfig.getDouble("target-chunk-sends-per-player-per-second", 40.0);
        if (maxChunkSendsPerPlayer <= -1.0) {
            maxChunkSendsPerPlayer = Integer.MAX_VALUE;
        } else if (maxChunkSendsPerPlayer <= 1.0) {
            maxChunkSendsPerPlayer = 1.0;
        } else if (maxChunkSendsPerPlayer > Integer.MAX_VALUE) {
            maxChunkSendsPerPlayer = Integer.MAX_VALUE;
        }

        double rateTick = maxChunkSendsPerPlayer / 20.0;
        double a = Math.floor(rateTick);
        double b = Math.ceil(rateTick);

        // we want to spread out a and b over the interval so it's smooth

        int aInt = (int)a;
        int bInt = (int)b;
        double total = b;
        maxChunkSendsPerPlayerChoice[0] = bInt;

        for (int i = 1, len = maxChunkSendsPerPlayerChoice.length; i < len; ++i) {
            if (total / (double)i >= rateTick) {
                total += a;
                maxChunkSendsPerPlayerChoice[i] = aInt;
            } else {
                total += b;
                maxChunkSendsPerPlayerChoice[i] = bInt;
            }
        }
    }

    public static int delayChunkUnloadsBy;

    private static void delayChunkUnloadsBy() {
        delayChunkUnloadsBy = TuinityConfig.getInt("delay-chunkunloads-by", 10) * 20;
        if (delayChunkUnloadsBy >= 0) {
            TicketType.DELAYED_UNLOAD.loadPeriod = delayChunkUnloadsBy;
        }
    }


    public static final class WorldConnfig {

        public final String worldName;
        public ConfigurationSection config;

        public WorldConnfig(final String worldName) {
            this.worldName = worldName;
            this.init();
        }

        public void init() {
            ConfigurationSection section = TuinityConfig.config.getConfigurationSection(this.worldName);
            if (section == null) {
                section = TuinityConfig.config.createSection(this.worldName);
            }
            TuinityConfig.config.set(this.worldName, section);

            this.load(section);
        }

        public void load(final ConfigurationSection config) {
            this.config = config;

            for (final Method method : TuinityConfig.WorldConnfig.class.getDeclaredMethods()) {
                if (method.getReturnType() != void.class || method.getParameterCount() != 0 ||
                        !Modifier.isPrivate(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    method.invoke(this, EMPTY);
                } catch (final Exception ex) {
                    Throw.rethrow(ex);
                    throw new RuntimeException(ex); // unreachable
                }
            }

            /* We re-save to add new options */
            try {
                TuinityConfig.config.save(TuinityConfig.configFile);
            } catch (final Exception ex) {
                Bukkit.getLogger().log(Level.SEVERE, "Unable to save tuinity config", ex);
            }
        }

        private boolean getBoolean(final String path, final boolean dfl) {
            this.config.addDefault(path, Boolean.valueOf(dfl));
            return this.config.getBoolean(path, dfl);
        }

        private int getInt(final String path, final int dfl) {
            this.config.addDefault(path, Integer.valueOf(dfl));
            return this.config.getInt(path, dfl);
        }

        private double getDouble(final String path, final double dfl) {
            this.config.addDefault(path, Double.valueOf(dfl));
            return this.config.getDouble(path, dfl);
        }

        /** ignored if {@link TuinityConfig#tickWorldsInParallel} == false */
        public int threads;

        /*
        private void worldthreading() {
            final int threads = this.getInt("tick-threads", -1);
            this.threads = threads == -1 ? TuinityConfig.tickThreads : threads;
        }*/

        public int noTickViewDistance;
        private void noTickViewDistance() {
            this.noTickViewDistance = this.getInt("no-tick-view-distance", -1);
        }

        public boolean useOptimizedTracker;
        public int optimizedTrackerTrackRange;
        public int optimizedTrackerUntrackRange;

        private void optimizetracker() {
            this.useOptimizedTracker = this.getBoolean("optimized-tracker", true);
            this.optimizedTrackerTrackRange = this.getInt("optimized-tracker-track-range", -1);
            this.optimizedTrackerUntrackRange = this.getInt("optimized-tracker-untrack-range", -1);
            if (!this.useOptimizedTracker) {
                this.optimizedTrackerTrackRange = -1;
                this.optimizedTrackerUntrackRange = -1;
                return;
            }
            if (this.optimizedTrackerTrackRange != this.optimizedTrackerUntrackRange && (this.optimizedTrackerTrackRange | this.optimizedTrackerUntrackRange) == -1) {
                // TODO error here
                this.optimizedTrackerTrackRange = -1;
                this.optimizedTrackerUntrackRange = -1;
            }
        }
    }

}