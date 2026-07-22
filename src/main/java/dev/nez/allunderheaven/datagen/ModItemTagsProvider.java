package dev.nez.allunderheaven.datagen;

import java.util.concurrent.CompletableFuture;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.registry.ModItems;
import dev.nez.allunderheaven.registry.ModMaterials;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.BlockTagCopyingItemTagProvider;

/**
 * Item tags. Use {@code this.copy(blockTag, itemTag)} to mirror a block tag
 * onto the corresponding block items, or {@code this.tag(...)} for plain item tags.
 */
public class ModItemTagsProvider extends BlockTagCopyingItemTagProvider {
    public ModItemTagsProvider(PackOutput output,
            CompletableFuture<HolderLookup.Provider> lookupProvider,
            CompletableFuture<TagsProvider.TagLookup<Block>> blockTags) {
        super(output, lookupProvider, blockTags, AllUnderHeaven.MOD_ID);
    }

    @Override
    protected void addTags(HolderLookup.Provider lookupProvider) {
        // repair-ingredient tags for the two mod materials
        this.tag(ModMaterials.STAR_FORGED_INGOTS).add(ModItems.STAR_FORGED_STEEL.getKey());
        this.tag(ModMaterials.DRAGONLORD_INGOTS).add(ModItems.DRAGONLORD_STEEL.getKey());
    }
}
