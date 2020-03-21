package com.tuinity.tuinity.util.map;


import net.minecraft.server.EntityPlayer;

public final class PlayerAreaMap extends AreaMap<EntityPlayer> {

    public PlayerAreaMap() {
        super();
    }

    public PlayerAreaMap(final PooledLinkedHashSets<EntityPlayer> pooledHashSets) {
        super(pooledHashSets);
    }

    public PlayerAreaMap(final PooledLinkedHashSets<EntityPlayer> pooledHashSets, final ChangeCallback<EntityPlayer> addCallback,
                         final ChangeCallback<EntityPlayer> removeCallback) {
        super(pooledHashSets, addCallback, removeCallback);
    }

    @Override
    protected PooledLinkedHashSets.PooledObjectLinkedOpenHashSet<EntityPlayer> getEmptySetFor(final EntityPlayer player) {
        return player.cachedSingleHashSetTuinity;
    }
}