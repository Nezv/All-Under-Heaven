package dev.nez.allunderheaven.datagen;

import java.util.concurrent.CompletableFuture;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.registry.ModBlocks;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;

/**
 * Block tags — mining tool/level, plus any custom {@code allunderheaven:} tags.
 */
public class ModBlockTagsProvider extends BlockTagsProvider {
    public ModBlockTagsProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> lookupProvider) {
        super(output, lookupProvider, AllUnderHeaven.MOD_ID);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        this.tag(BlockTags.MINEABLE_WITH_PICKAXE)
                .add(ModBlocks.JADE_BLOCK.getKey())
                .add(ModBlocks.STARDUST_ORE.getKey())
                .add(ModBlocks.DRAGONLORD_FORGE.getKey());
        this.tag(BlockTags.NEEDS_IRON_TOOL)
                .add(ModBlocks.JADE_BLOCK.getKey())
                .add(ModBlocks.DRAGONLORD_FORGE.getKey());
        this.tag(BlockTags.NEEDS_DIAMOND_TOOL)
                .add(ModBlocks.STARDUST_ORE.getKey());
    }
}
