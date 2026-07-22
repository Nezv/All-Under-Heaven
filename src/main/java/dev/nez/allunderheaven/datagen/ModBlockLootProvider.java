package dev.nez.allunderheaven.datagen;

import java.util.Set;

import dev.nez.allunderheaven.registry.ModBlocks;
import dev.nez.allunderheaven.registry.ModItems;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;

/**
 * Block loot tables. Every block registered in {@link ModBlocks} must get an
 * entry here (or datagen fails validation via {@link #getKnownBlocks()}).
 */
public class ModBlockLootProvider extends BlockLootSubProvider {
    public ModBlockLootProvider(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.DEFAULT_FLAGS, registries);
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ModBlocks.BLOCKS.getEntries().stream()
                .map(e -> (Block) e.value())
                .toList();
    }

    @Override
    protected void generate() {
        this.dropSelf(ModBlocks.JADE_BLOCK.get());
        this.dropSelf(ModBlocks.DRAGONLORD_FORGE.get());
        // stardust ore yields the dust (fortune-affected, correct-tool gated)
        this.add(ModBlocks.STARDUST_ORE.get(),
                this.createOreDrop(ModBlocks.STARDUST_ORE.get(), ModItems.STAR_DUST.get()));
    }
}
