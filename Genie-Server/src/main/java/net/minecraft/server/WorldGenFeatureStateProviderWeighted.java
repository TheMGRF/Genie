package net.minecraft.server;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.mojang.datafixers.Dynamic;
import com.mojang.datafixers.types.DynamicOps;
import java.util.Random;

public class WorldGenFeatureStateProviderWeighted extends WorldGenFeatureStateProvider {

    private final WeightedList<IBlockData> b;

    private WorldGenFeatureStateProviderWeighted(WeightedList<IBlockData> weightedlist) {
        super(WorldGenFeatureStateProviders.b);
        this.b = weightedlist;
    }

    public WorldGenFeatureStateProviderWeighted() {
        this(new WeightedList<>());
    }

    public <T> WorldGenFeatureStateProviderWeighted(Dynamic<T> dynamic) {
        this(new WeightedList<>(dynamic.get("entries").orElseEmptyList(), IBlockData::a));
    }

    public synchronized WorldGenFeatureStateProviderWeighted a(IBlockData iblockdata, int i) { // Paper
        this.b.a(iblockdata, i);
        return this;
    }

    @Override
    public synchronized IBlockData a(Random random, BlockPosition blockposition) { // Paper
        return (IBlockData) this.b.b(random);
    }

    @Override
    public synchronized <T> T a(DynamicOps<T> dynamicops) { // Paper
        Builder<T, T> builder = ImmutableMap.builder();

        builder.put(dynamicops.createString("type"), dynamicops.createString(IRegistry.t.getKey(this.a).toString())).put(dynamicops.createString("entries"), this.b.a(dynamicops, (iblockdata) -> {
            return IBlockData.a(dynamicops, iblockdata);
        }));
        return (new Dynamic<T>(dynamicops, dynamicops.createMap(builder.build()))).getValue(); // Paper - decompile fix
    }
}
