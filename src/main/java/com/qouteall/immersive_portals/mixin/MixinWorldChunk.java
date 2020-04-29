package com.qouteall.immersive_portals.mixin;

import com.qouteall.immersive_portals.ducks.IEWorldChunk;
import net.minecraft.entity.Entity;
import net.minecraft.util.TypeFilterableList;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(WorldChunk.class)
public abstract class MixinWorldChunk implements IEWorldChunk {
    @Final
    @Shadow
    private TypeFilterableList<Entity>[] entitySections;

    @Override
    public TypeFilterableList<Entity>[] getEntitySections() {
        return entitySections;
    }
}
