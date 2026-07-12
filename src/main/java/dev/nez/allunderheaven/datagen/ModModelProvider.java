package dev.nez.allunderheaven.datagen;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.registry.ModBlocks;
import dev.nez.allunderheaven.registry.ModItems;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.data.PackOutput;

/**
 * Generates blockstates, block/item models and client item definitions.
 * Textures are NOT generated — put them at
 * {@code assets/allunderheaven/textures/block|item/<id>.png}.
 */
public class ModModelProvider extends ModelProvider {
    public ModModelProvider(PackOutput output) {
        super(output, AllUnderHeaven.MOD_ID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        // Blocks: cube-all model + blockstate (+ the BlockItem model is derived automatically).
        blockModels.createTrivialCube(ModBlocks.JADE_BLOCK.get());

        // Items: flat "generated" models from their texture.
        itemModels.generateFlatItem(ModItems.JADE_SEAL.get(), ModelTemplates.FLAT_ITEM);
    }
}
