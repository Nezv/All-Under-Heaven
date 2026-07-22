package dev.nez.allunderheaven.registry;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.feature.dragonforge.DragonlordForgeBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
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

    /** Star metal embedded in the fallen-star slag — drops star dust, and only
     *  a diamond-tier pickaxe releases it (mineable + needs-diamond tags in
     *  datagen). Faintly light-emitting, like a cooling meteor. */
    public static final DeferredBlock<Block> STARDUST_ORE = BLOCKS.registerSimpleBlock("stardust_ore",
            p -> p.mapColor(MapColor.COLOR_BLACK)
                    .strength(4.5f, 9.0f)
                    .requiresCorrectToolForDrops()
                    .lightLevel(s -> 4)
                    .sound(SoundType.DEEPSLATE));

    /** The Dragon-lord Forge — reworks star-forged steel on dragon blood.
     *  Glows while lit; the Dragon Keeper's job-site block. */
    public static final DeferredBlock<DragonlordForgeBlock> DRAGONLORD_FORGE =
            BLOCKS.registerBlock("dragonlord_forge", DragonlordForgeBlock::new,
                    () -> BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_BLACK)
                            .strength(4.0f, 6.0f)
                            .requiresCorrectToolForDrops()
                            .lightLevel(s -> s.getValue(DragonlordForgeBlock.LIT) ? 13 : 0)
                            .sound(SoundType.NETHERITE_BLOCK));

    private ModBlocks() {
    }
}
