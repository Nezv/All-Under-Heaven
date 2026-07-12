package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * All blocks of the mod. Register the corresponding {@code BlockItem} in
 * {@link ModItems} so the block is obtainable as an item.
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(AllUnderHeaven.MOD_ID);

    /** Decorative mineral block. Requires a pickaxe (see datagen mineable tag). */
    public static final DeferredBlock<Block> JADE_BLOCK = BLOCKS.registerSimpleBlock("jade_block",
            p -> p.mapColor(MapColor.EMERALD)
                    .strength(3.0f, 6.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.STONE));

    private ModBlocks() {
    }
}
