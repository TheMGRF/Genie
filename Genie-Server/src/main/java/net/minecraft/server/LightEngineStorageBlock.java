package net.minecraft.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public class LightEngineStorageBlock extends LightEngineStorage<LightEngineStorageBlock.a> {

    protected LightEngineStorageBlock(ILightAccess ilightaccess) {
        super(EnumSkyBlock.BLOCK, ilightaccess, new LightEngineStorageBlock.a(new com.tuinity.tuinity.chunk.QueuedChangesMapLong2Object<>(), false)); // Tuinity - avoid copying light data
    }

    @Override
    protected int d(long i) {
        long j = SectionPosition.e(i);
        NibbleArray nibblearray = this.a(j, false);

        return nibblearray == null ? 0 : nibblearray.a(SectionPosition.b(BlockPosition.b(i)), SectionPosition.b(BlockPosition.c(i)), SectionPosition.b(BlockPosition.d(i)));
    }

    public static final class a extends LightEngineStorageArray<LightEngineStorageBlock.a> {

        public a(com.tuinity.tuinity.chunk.QueuedChangesMapLong2Object<NibbleArray> long2objectopenhashmap, boolean isVisible) { // Tuinity - avoid copying light data
            super(long2objectopenhashmap, isVisible); // Tuinity - avoid copying light data
        }

        @Override
        public LightEngineStorageBlock.a b() {
            return new a(this.data, true); // Tuinity - avoid copying light data
        }
    }
}
