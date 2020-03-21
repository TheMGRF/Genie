package org.spigotmc;

import java.util.Collection;
import java.util.List;
import net.minecraft.server.AxisAlignedBB;
import net.minecraft.server.Chunk;
import net.minecraft.server.ChunkProviderServer; // Tuinity
import net.minecraft.server.Entity;
import net.minecraft.server.EntityAmbient;
import net.minecraft.server.EntityAnimal;
import net.minecraft.server.EntityArrow;
import net.minecraft.server.EntityComplexPart;
import net.minecraft.server.EntityCreature;
import net.minecraft.server.EntityCreeper;
import net.minecraft.server.EntityEnderCrystal;
import net.minecraft.server.EntityEnderDragon;
import net.minecraft.server.EntityFallingBlock; // Paper
import net.minecraft.server.EntityFireball;
import net.minecraft.server.EntityFireworks;
import net.minecraft.server.EntityHuman;
import net.minecraft.server.EntityLightning;
import net.minecraft.server.EntityLiving;
import net.minecraft.server.EntityMonster;
import net.minecraft.server.EntityProjectile;
import net.minecraft.server.EntityRaider;
import net.minecraft.server.EntitySheep;
import net.minecraft.server.EntitySlice;
import net.minecraft.server.EntitySlime;
import net.minecraft.server.EntityTNTPrimed;
import net.minecraft.server.EntityThrownTrident;
import net.minecraft.server.EntityVillager;
import net.minecraft.server.EntityWither;
import net.minecraft.server.MathHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.World;
import co.aikar.timings.MinecraftTimings;
// Paper start
import net.minecraft.server.EntityInsentient;
import net.minecraft.server.EntityLlama;
import net.minecraft.server.EntityWaterAnimal;
// Paper end
import net.minecraft.server.WorldServer; // Tuinity

public class ActivationRange
{

    public enum ActivationType
    {
        WATER, // Paper
        MONSTER,
        ANIMAL,
        RAIDER,
        MISC;

        AxisAlignedBB boundingBox = new AxisAlignedBB( 0, 0, 0, 0, 0, 0 );
    }

    static AxisAlignedBB maxBB = new AxisAlignedBB( 0, 0, 0, 0, 0, 0 );

    /**
     * Initializes an entities type on construction to specify what group this
     * entity is in for activation ranges.
     *
     * @param entity
     * @return group id
     */
    public static ActivationType initializeEntityActivationType(Entity entity)
    {
        if (entity instanceof EntityWaterAnimal) { return ActivationType.WATER; } // Paper
        if ( entity instanceof EntityRaider )
        {
            return ActivationType.RAIDER;
        } else if ( entity instanceof EntityMonster || entity instanceof EntitySlime )
        {
            return ActivationType.MONSTER;
        } else if ( entity instanceof EntityCreature || entity instanceof EntityAmbient )
        {
            return ActivationType.ANIMAL;
        } else
        {
            return ActivationType.MISC;
        }
    }

    /**
     * These entities are excluded from Activation range checks.
     *
     * @param entity Entity to initialize
     * @param config Spigot config to determine ranges
     * @return boolean If it should always tick.
     */
    public static boolean initializeEntityActivationState(Entity entity, SpigotWorldConfig config)
    {
        if ( ( entity.activationType == ActivationType.MISC && config.miscActivationRange == 0 )
                || ( entity.activationType == ActivationType.RAIDER && config.raiderActivationRange == 0 )
                || ( entity.activationType == ActivationType.ANIMAL && config.animalActivationRange == 0 )
                || ( entity.activationType == ActivationType.MONSTER && config.monsterActivationRange == 0 )
                || ( entity.activationType == ActivationType.WATER && config.waterActivationRange == 0 ) // Paper
                || entity instanceof EntityHuman
                || entity instanceof EntityProjectile
                || entity instanceof EntityEnderDragon
                || entity instanceof EntityComplexPart
                || entity instanceof EntityWither
                || entity instanceof EntityFireball
                || entity instanceof EntityLightning
                || entity instanceof EntityTNTPrimed
                || entity instanceof EntityFallingBlock // Paper - Always tick falling blocks
                || entity instanceof EntityEnderCrystal
                || entity instanceof EntityFireworks
                || entity instanceof EntityThrownTrident )
        {
            return true;
        }

        return false;
    }

    /**
     * Find what entities are in range of the players in the world and set
     * active if in range.
     *
     * @param world
     */
    public static void activateEntities(World world)
    {
        MinecraftTimings.entityActivationCheckTimer.startTiming();
        final int miscActivationRange = world.spigotConfig.miscActivationRange;
        final int raiderActivationRange = world.spigotConfig.raiderActivationRange;
        final int animalActivationRange = world.spigotConfig.animalActivationRange;
        final int monsterActivationRange = world.spigotConfig.monsterActivationRange;
        final int waterActivationRange = world.spigotConfig.waterActivationRange; // Paper

        // Tuinity start - per player view distance
        int maxRangeTemp = Math.max( monsterActivationRange, animalActivationRange );
        maxRangeTemp = Math.max( maxRangeTemp, raiderActivationRange );
        maxRangeTemp = Math.max( maxRangeTemp, miscActivationRange );

        ChunkProviderServer chunkProviderServer = (ChunkProviderServer)world.getChunkProvider();

        for ( EntityHuman player : world.getPlayers() )
        {
            final int maxRange = Math.min( ( ( player instanceof net.minecraft.server.EntityPlayer ? ((net.minecraft.server.EntityPlayer)player).getEffectiveViewDistance(((WorldServer)world).getChunkProvider().playerChunkMap) : world.spigotConfig.viewDistance ) << 4 ) - 8, maxRangeTemp );
            // Tuinity end - per player view distance
            player.activatedTick = MinecraftServer.currentTick;
            maxBB = player.getBoundingBox().grow( maxRange, 256, maxRange );
            ActivationType.MISC.boundingBox = player.getBoundingBox().grow( miscActivationRange, 256, miscActivationRange );
            ActivationType.RAIDER.boundingBox = player.getBoundingBox().grow( raiderActivationRange, 256, raiderActivationRange );
            ActivationType.ANIMAL.boundingBox = player.getBoundingBox().grow( animalActivationRange, 256, animalActivationRange );
            ActivationType.MONSTER.boundingBox = player.getBoundingBox().grow( monsterActivationRange, 256, monsterActivationRange );
            ActivationType.WATER.boundingBox = player.getBoundingBox().grow( waterActivationRange, 256, waterActivationRange ); // Paper


            int i = MathHelper.floor( maxBB.minX / 16.0D );
            int j = MathHelper.floor( maxBB.maxX / 16.0D );
            int k = MathHelper.floor( maxBB.minZ / 16.0D );
            int l = MathHelper.floor( maxBB.maxZ / 16.0D );

            for ( int i1 = i; i1 <= j; ++i1 )
            {
                for ( int j1 = k; j1 <= l; ++j1 )
                {
                    Chunk chunk = chunkProviderServer.getChunkAtIfLoadedMainThreadNoCache( i1, j1 ); // Tuinity
                    if ( chunk != null )
                    {
                        activateChunkEntities( chunk );
                    }
                }
            }
        }
        MinecraftTimings.entityActivationCheckTimer.stopTiming();
    }

    /**
     * Checks for the activation state of all entities in this chunk.
     *
     * @param chunk
     */
    private static void activateChunkEntities(Chunk chunk)
    {
        // Tuinity start - optimise this call
        com.destroystokyo.paper.util.maplist.EntityList entityList = chunk.entities;
        Entity[] rawData = entityList.getRawData();
        for (int i = 0, len = entityList.size(); i < len; ++i) {
            Entity entity = rawData[i];
            if ( MinecraftServer.currentTick > entity.activatedTick )
            {
                if ( entity.defaultActivationState || entity.activationType.boundingBox.intersects(entity.getBoundingBox())) // Tuinity - optimise this call
                {
                    entity.activatedTick = MinecraftServer.currentTick;
                } // Tuinity - optimise this call
            }
        }
        // Tuinity end - optimise this call
    }

    /**
     * If an entity is not in range, do some more checks to see if we should
     * give it a shot.
     *
     * @param entity
     * @return
     */
    public static boolean checkEntityImmunities(Entity entity)
    {
        // quick checks.
        if ( entity.inWater || entity.fireTicks > 0 )
        {
            return true;
        }
        if ( !( entity instanceof EntityArrow ) )
        {
            if ( !entity.onGround || !entity.passengers.isEmpty() || entity.isPassenger() )
            {
                return true;
            }
        } else if ( !( (EntityArrow) entity ).inGround )
        {
            return true;
        }
        // special cases.
        if ( entity instanceof EntityLiving )
        {
            EntityLiving living = (EntityLiving) entity;
            if ( /*TODO: Missed mapping? living.attackTicks > 0 || */ living.hurtTicks > 0 || living.effects.size() > 0 )
            {
                return true;
            }
            if ( entity instanceof EntityCreature && (( (EntityCreature) entity ).getGoalTarget() != null || ( (EntityCreature) entity ).getMovingTarget() != null)) // Paper
            {
                return true;
            }
            if ( entity instanceof EntityVillager && ( (EntityVillager) entity ).canBreed() )
            {
                return true;
            }
            // Paper start
            if ( entity instanceof EntityLlama && ( (EntityLlama ) entity ).inCaravan() )
            {
                return true;
            }
            // Paper end
            if ( entity instanceof EntityAnimal )
            {
                EntityAnimal animal = (EntityAnimal) entity;
                if ( animal.isBaby() || animal.isInLove() )
                {
                    return true;
                }
                if ( entity instanceof EntitySheep && ( (EntitySheep) entity ).isSheared() )
                {
                    return true;
                }
            }
            if (entity instanceof EntityCreeper && ((EntityCreeper) entity).isIgnited()) { // isExplosive
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the entity is active for this tick.
     *
     * @param entity
     * @return
     */
    public static boolean checkIfActive(Entity entity)
    {
        // Never safe to skip fireworks or entities not yet added to chunk
        if ( !entity.inChunk || entity instanceof EntityFireworks ) {
            return true;
        }

        boolean isActive = entity.activatedTick >= MinecraftServer.currentTick || entity.defaultActivationState;

        // Should this entity tick?
        if ( !isActive )
        {
            if ( ( MinecraftServer.currentTick - entity.activatedTick - 1 ) % 20 == 0 )
            {
                // Check immunities every 20 ticks.
                if ( checkEntityImmunities( entity ) )
                {
                    // Triggered some sort of immunity, give 20 full ticks before we check again.
                    entity.activatedTick = MinecraftServer.currentTick + 20;
                }
                isActive = true;
            // Paper start
            } else if (entity instanceof EntityInsentient && ((EntityInsentient) entity).targetSelector.hasTasks()) {
                isActive = true;
            }
            // Paper end
            // Add a little performance juice to active entities. Skip 1/4 if not immune.
        } else if ( !entity.defaultActivationState && entity.ticksLived % 4 == 0 && !(entity instanceof EntityInsentient && ((EntityInsentient) entity).targetSelector.hasTasks()) && !checkEntityImmunities( entity ) ) // Paper - add targetSelector.hasTasks
        {
            isActive = false;
        }
        return isActive;
    }
}
