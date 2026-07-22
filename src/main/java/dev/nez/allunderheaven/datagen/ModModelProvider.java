package dev.nez.allunderheaven.datagen;

import dev.nez.allunderheaven.AllUnderHeaven;
import dev.nez.allunderheaven.registry.ModBlocks;
import dev.nez.allunderheaven.registry.ModItems;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.ModelProvider;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.minecraft.client.data.models.model.TexturedModel;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * Generates blockstates, block/item models and client item definitions.
 * Textures are NOT generated here — they live at
 * {@code assets/allunderheaven/textures/block|item/<id>.png} (procedurally
 * authored by {@code tools/items/build_items.py}).
 */
public class ModModelProvider extends ModelProvider {
    public ModModelProvider(PackOutput output) {
        super(output, AllUnderHeaven.MOD_ID);
    }

    @Override
    protected void registerModels(BlockModelGenerators blockModels, ItemModelGenerators itemModels) {
        blockModels.createTrivialCube(ModBlocks.JADE_BLOCK.get());
        blockModels.createTrivialCube(ModBlocks.STARDUST_ORE.get());
        // furnace-style: FACING + LIT blockstate, lit/unlit models + item model,
        // from _top/_side/_front/_front_on textures
        blockModels.createFurnace(ModBlocks.DRAGONLORD_FORGE.get(), TexturedModel.ORIENTABLE_ONLY_TOP);

        itemModels.generateFlatItem(ModItems.JADE_SEAL.get(), ModelTemplates.FLAT_ITEM);
        itemModels.generateFlatItem(ModItems.DRAGON_SPAWN_EGG.get(), ModelTemplates.FLAT_ITEM);
        flat(itemModels, ModItems.STAR_DUST);
        flat(itemModels, ModItems.STAR_FORGED_STEEL);
        flat(itemModels, ModItems.DRAGONLORD_STEEL);
        flat(itemModels, ModItems.DRAGON_BLOOD);

        for (DeferredItem<Item> tool : java.util.List.of(
                ModItems.STAR_FORGED_SWORD, ModItems.STAR_FORGED_PICKAXE, ModItems.STAR_FORGED_AXE,
                ModItems.STAR_FORGED_SHOVEL, ModItems.STAR_FORGED_HOE,
                ModItems.DRAGONLORD_SWORD, ModItems.DRAGONLORD_PICKAXE, ModItems.DRAGONLORD_AXE,
                ModItems.DRAGONLORD_SHOVEL, ModItems.DRAGONLORD_HOE)) {
            itemModels.generateFlatItem(tool.get(), ModelTemplates.FLAT_HANDHELD_ITEM);
        }
        for (DeferredItem<Item> armor : java.util.List.of(
                ModItems.STAR_FORGED_HELMET, ModItems.STAR_FORGED_CHESTPLATE,
                ModItems.STAR_FORGED_LEGGINGS, ModItems.STAR_FORGED_BOOTS,
                ModItems.DRAGONLORD_HELMET, ModItems.DRAGONLORD_CHESTPLATE,
                ModItems.DRAGONLORD_LEGGINGS, ModItems.DRAGONLORD_BOOTS)) {
            itemModels.generateFlatItem(armor.get(), ModelTemplates.FLAT_ITEM);
        }
    }

    private static void flat(ItemModelGenerators itemModels, DeferredItem<Item> item) {
        itemModels.generateFlatItem(item.get(), ModelTemplates.FLAT_ITEM);
    }
}
